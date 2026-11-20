import contextlib
import io
import json
import socket
import time
import unittest

from zen_brain import server

from .support import CONV, EMPTY, HarnessCase, ScriptedHermes, batch, event, request, wav


def raw_exchange(port: int, payload: bytes, timeout: float = 5.0, half_close: bool = False) -> bytes:
    with socket.create_connection(("127.0.0.1", port), timeout=timeout) as sock:
        sock.sendall(payload)
        if half_close:
            sock.shutdown(socket.SHUT_WR)  # «no voy a mandar más»: el servidor ve el final del cuerpo
        chunks = []
        try:
            while True:
                data = sock.recv(65536)
                if not data:
                    break
                chunks.append(data)
        except socket.timeout:
            pass
        return b"".join(chunks)


class AuthTest(HarnessCase):
    def test_healthz_needs_no_token_and_reveals_nothing(self):
        h = self.make()
        status, body, _ = request(h.port, "GET", "/healthz")
        self.assertEqual((status, body), (200, {"status": "ok"}))

    def test_every_kind_of_bad_credential_gets_the_same_401(self):
        h = self.make()
        good = h.token["phone"]
        cases = [
            {},
            {"Authorization": "Basic " + good},
            {"Authorization": "Bearer"},
            {"Authorization": "Bearer corto"},
            {"Authorization": "Bearer " + "x" * 43},
            {"Authorization": "Bearer " + good[:-1] + ("A" if good[-1] != "A" else "B")},
            {"Authorization": good},
            {"X-Api-Key": good},
        ]
        seen = set()
        for headers in cases:
            status, body, _ = request(h.port, "POST", "/v1/events", body=batch(event()), headers=headers)
            self.assertEqual(status, 401, headers)
            seen.add(json.dumps(body))
        self.assertEqual(seen, {'{"error": "unauthorized"}'})
        # El token en la URL no sirve de nada.
        self.assertEqual(request(h.port, "GET", f"/v1/commands?token={good}")[0], 401)
        self.assertEqual(h.brain.hermes.calls, [])

    def test_failed_attempts_are_counted_in_the_audit_without_their_content(self):
        h = self.make()
        for _ in range(3):
            request(h.port, "GET", "/v1/commands", token="x" * 43)
        h.app.flush_auth_failures()
        lines = h.audit_lines()
        self.assertEqual([l["count"] for l in lines if l["type"] == "auth_failures"], [3])
        self.assertNotIn("x" * 43, h.cfg.audit_path.read_text())

    def test_revoking_a_token_takes_effect_without_restart(self):
        h = self.make(tokens=("phone", "tablet"))
        self.assertEqual(h.commands(label="tablet")[0], 200)
        h.tokens.revoke("tablet")
        time.sleep(1.2)  # el servidor relee el archivo como mucho una vez por segundo
        self.assertEqual(request(h.port, "GET", "/v1/commands", token=h.token["tablet"])[0], 401)
        self.assertEqual(h.commands(label="phone")[0], 200)

    def test_rotating_a_token_kills_the_old_one(self):
        h = self.make()
        new = h.tokens.add("phone", replace=True)
        time.sleep(1.2)
        self.assertEqual(request(h.port, "GET", "/v1/commands", token=h.token["phone"])[0], 401)
        self.assertEqual(request(h.port, "GET", "/v1/commands", token=new)[0], 200)


class RoutingAndLimitsTest(HarnessCase):
    def test_unknown_paths_and_wrong_methods(self):
        h = self.make()
        t = h.token["phone"]
        self.assertEqual(request(h.port, "GET", "/", token=t)[0], 404)
        self.assertEqual(request(h.port, "GET", "/v1/admin", token=t)[0], 404)
        self.assertEqual(request(h.port, "GET", "/v1/events", token=t)[0], 405)
        self.assertEqual(request(h.port, "POST", "/v1/commands", token=t, body=b"{}")[0], 405)
        for method in ("PUT", "DELETE", "PATCH", "OPTIONS"):
            self.assertEqual(request(h.port, method, "/v1/events", token=t)[0], 405, method)

    def test_responses_have_safe_headers_and_no_python_banner(self):
        h = self.make()
        _, _, headers = request(h.port, "GET", "/healthz")
        self.assertEqual(headers["cache-control"], "no-store")
        self.assertEqual(headers["x-content-type-options"], "nosniff")
        self.assertNotIn("python", headers.get("server", "").lower())
        self.assertEqual(headers["connection"], "close")

    def test_body_limits_and_framing(self):
        h = self.make()
        t = h.token["phone"]
        # Sin Content-Length / con Transfer-Encoding: se exige longitud conocida.
        nolen = f"POST /v1/events HTTP/1.0\r\nAuthorization: Bearer {t}\r\n\r\n".encode()
        self.assertIn(b" 411 ", raw_exchange(h.port, nolen).split(b"\r\n")[0])
        chunked = f"POST /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n".encode()
        self.assertIn(b" 411 ", raw_exchange(h.port, chunked).split(b"\r\n")[0])
        # Declara más de lo permitido: se rechaza sin leerlo.
        huge = f"POST /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\nContent-Length: {3 * 1024 * 1024}\r\n\r\n".encode()
        self.assertIn(b" 413 ", raw_exchange(h.port, huge).split(b"\r\n")[0])
        junk = f"POST /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\nContent-Length: -5\r\n\r\n".encode()
        self.assertIn(b" 411 ", raw_exchange(h.port, junk).split(b"\r\n")[0])
        # Cuerpo más corto de lo declarado.
        short = f"POST /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\nContent-Length: 500\r\n\r\n{{}}".encode()
        self.assertIn(b" 400 ", raw_exchange(h.port, short, half_close=True).split(b"\r\n")[0])

    def test_malformed_event_bodies(self):
        h = self.make()
        t = h.token["phone"]
        self.assertEqual(request(h.port, "POST", "/v1/events", token=t, body=b"no json")[0], 400)
        self.assertEqual(request(h.port, "POST", "/v1/events", token=t, body=batch(*[event(f"e{i}") for i in range(101)]))[0], 413)

    def test_no_access_log_reaches_stderr(self):
        h = self.make()
        buffer = io.StringIO()
        with contextlib.redirect_stderr(buffer):
            request(h.port, "GET", f"/v1/commands?cursor=secreto", token=h.token["phone"])
            request(h.port, "GET", "/v1/commands", token="x" * 43)
            time.sleep(0.2)
        self.assertEqual(buffer.getvalue(), "")

    def test_an_unauthenticated_slow_client_gets_only_a_short_window(self):
        original = server.HEADER_DEADLINE_S
        server.HEADER_DEADLINE_S = 0.5
        self.addCleanup(setattr, server, "HEADER_DEADLINE_S", original)
        h = self.make()
        started = time.monotonic()
        with socket.create_connection(("127.0.0.1", h.port), timeout=5) as sock:
            sock.sendall(b"POST /v1/events HTTP/1.1\r\nHost: x\r\n")  # nunca termina la cabecera
            self.assertEqual(sock.recv(1024), b"")  # el servidor corta la conexión
        self.assertLess(time.monotonic() - started, 3)

    def test_a_trickle_of_header_lines_cannot_hold_a_slot_past_the_header_window(self):
        original = server.HEADER_DEADLINE_S
        server.HEADER_DEADLINE_S = 0.6
        self.addCleanup(setattr, server, "HEADER_DEADLINE_S", original)
        h = self.make()
        started = time.monotonic()
        with socket.create_connection(("127.0.0.1", h.port), timeout=5) as sock:
            sock.sendall(b"GET /healthz HTTP/1.1\r\n")
            try:
                for _ in range(20):  # una cabecera cada 0,25 s: el timeout «por lectura» nunca saltaría
                    time.sleep(0.25)
                    sock.sendall(b"X-Lento: 1\r\n")
            except OSError:
                pass  # el servidor ya cortó
        self.assertLess(time.monotonic() - started, 3.5)

    def test_after_authenticating_a_slow_body_is_cut_by_the_longer_deadline(self):
        original = server.REQUEST_DEADLINE_S
        server.REQUEST_DEADLINE_S = 0.6
        self.addCleanup(setattr, server, "REQUEST_DEADLINE_S", original)
        h = self.make()
        started = time.monotonic()
        with socket.create_connection(("127.0.0.1", h.port), timeout=5) as sock:
            sock.sendall(f"POST /v1/events HTTP/1.1\r\nAuthorization: Bearer {h.token['phone']}\r\nContent-Length: 1000\r\n\r\n{{".encode())
            self.assertEqual(sock.recv(1024), b"")  # autenticado, pero el cuerpo no llega: se corta
        self.assertLess(time.monotonic() - started, 3)

    def test_a_valid_client_is_not_hurt_by_the_short_window(self):
        h = self.make()
        self.assertEqual(h.post_events(event("e1"))[0], 200)

    def test_non_ascii_digits_in_numeric_headers_get_a_clean_4xx(self):
        h = self.make()
        t = h.token["phone"]
        for bad in ("²", "¹", "٣"):
            payload = f"POST /v1/events HTTP/1.1\r\nHost: x\r\nAuthorization: Bearer {t}\r\nContent-Length: {bad}\r\n\r\n".encode("utf-8")
            reply = raw_exchange(h.port, payload).split(b"\r\n")[0]
            self.assertIn(b" 411 ", reply, bad)
        status, _, _ = request(h.port, "GET", "/v1/commands?cursor=abc.%C2%B2", token=t)
        self.assertEqual(status, 200)

    def test_a_failure_to_start_the_connection_thread_does_not_leak_the_slot(self):
        from http.server import ThreadingHTTPServer
        from unittest import mock

        h = self.make()
        slots = h.server._slots
        taken = []
        with mock.patch.object(ThreadingHTTPServer, "process_request", side_effect=RuntimeError("can't start new thread")):
            for _ in range(server.MAX_CONNECTIONS * 2):  # más fallos que ranuras: antes, tras 64 el servidor quedaba muerto
                client, peer = socket.socketpair()
                self.addCleanup(client.close)
                with self.assertRaises(RuntimeError):
                    h.server.process_request(peer, ("127.0.0.1", 0))
                peer.close()
        for _ in range(server.MAX_CONNECTIONS):
            self.assertTrue(slots.acquire(blocking=False))
            taken.append(1)
        for _ in taken:
            slots.release()
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 200)

    def test_idle_connections_cost_one_thread_each_not_two(self):
        import threading

        h = self.make()
        before = threading.active_count()
        held = [socket.create_connection(("127.0.0.1", h.port), timeout=5) for _ in range(20)]
        self.addCleanup(lambda: [s.close() for s in held])
        time.sleep(0.5)
        self.assertLessEqual(threading.active_count() - before, 22)  # 20 manejadores (el vigilante es uno solo y ya existía)

    def test_oversized_headers_are_cut_before_authenticating(self):
        h = self.make()
        junk = "".join(f"X-Basura-{i}: {'a' * 6000}\r\n" for i in range(6))  # ~36 KB de cabeceras, sin credenciales
        reply = raw_exchange(h.port, f"GET /healthz HTTP/1.1\r\nHost: x\r\n{junk}\r\n".encode()).split(b"\r\n")[0]
        self.assertIn(b" 431 ", reply)
        # Una petición normal (con cabeceras de proxy de tamaño razonable) sigue pasando.
        extra = "".join(f"X-Forwarded-{i}: {'b' * 200}\r\n" for i in range(5))
        ok = raw_exchange(h.port, f"GET /healthz HTTP/1.1\r\nHost: x\r\n{extra}\r\n".encode()).split(b"\r\n")[0]
        self.assertIn(b" 200 ", ok)

    def test_a_giant_request_line_is_cut_without_a_crash(self):
        h = self.make()
        raw_exchange(h.port, b"GET /" + b"a" * 40_000 + b" HTTP/1.1\r\n\r\n")
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 200)

    def test_a_malformed_ipv6_request_target_gets_a_clean_400(self):
        h = self.make()
        self.assertIn(b" 400 ", raw_exchange(h.port, b"GET http://[::1/x HTTP/1.0\r\n\r\n").split(b"\r\n")[0])

    def test_healthz_turns_503_when_the_adapter_cannot_act_safely(self):
        h = self.make()
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 200)
        h.tokens._path.write_text("{ roto")  # tokens.json ilegible: nadie puede entrar
        time.sleep(1.2)
        status, body, _ = request(h.port, "GET", "/healthz")
        self.assertEqual((status, body), (503, {"status": "degraded"}))
        h.tokens._path.write_text("{}")
        h.tokens._path.write_text(json.dumps({"devices": {}}))
        time.sleep(1.2)
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 200)
        h.audit.ok = False  # la auditoría dejó de poder escribir
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 503)

    def test_rejected_requests_cannot_flood_the_audit_log(self):
        h = self.make()
        for n in range(80):
            request(h.port, "POST", "/v1/audio", token=h.token["phone"], body=b"basura" * 20, headers={"X-Zen-Segment-Id": f"s{n}"})
        h.app.flush_auth_failures()
        lines = [json.loads(l) for l in h.cfg.audit_path.read_text().splitlines()]
        self.assertLessEqual(sum(1 for l in lines if l["type"] == "audio_rejected"), server.AUDIT_LINES_PER_MINUTE)
        self.assertEqual([l["count"] for l in lines if l["type"] == "suppressed"], [60])

    def test_the_connection_limit_answers_503_instead_of_piling_up(self):
        original = server.MAX_CONNECTIONS
        server.MAX_CONNECTIONS = 2
        self.addCleanup(setattr, server, "MAX_CONNECTIONS", original)
        h = self.make()
        held = [socket.create_connection(("127.0.0.1", h.port), timeout=5) for _ in range(2)]
        self.addCleanup(lambda: [s.close() for s in held])
        time.sleep(0.3)
        self.assertIn(b" 503 ", raw_exchange(h.port, b"GET /healthz HTTP/1.0\r\n\r\n").split(b"\r\n")[0])
        for s in held:
            s.close()
        time.sleep(0.3)
        self.assertEqual(request(h.port, "GET", "/healthz")[0], 200)


class FlowTest(HarnessCase):
    def test_events_in_commands_out(self):
        h = self.make(hermes=ScriptedHermes(json.dumps({"commands": [{"type": "draft_reply", "text": "Dale"}]})))
        status, body, _ = h.post_events(event("e1"), event("u1", kind="usage"), {"nada": 1})
        self.assertEqual((status, body), (200, {"accepted": 1, "duplicates": 0, "dropped": 2}))
        h.brain.drain()
        status, body, _ = h.commands()
        self.assertEqual(status, 200)
        (cmd,) = body["commands"]
        self.assertEqual({k: cmd[k] for k in ("type", "conversation", "text")}, {"type": "draft_reply", "conversation": CONV, "text": "Dale"})
        status, again, _ = h.commands(body["cursor"])
        self.assertEqual(again["commands"], [])

    def test_a_device_cannot_read_anothers_commands_even_by_naming_it(self):
        h = self.make(tokens=("uno", "dos"), hermes=ScriptedHermes(json.dumps({"commands": [{"type": "notify", "text": "para uno"}]})))
        h.post_events(event("e1"), label="uno")
        h.brain.drain()
        status, body, _ = request(h.port, "GET", "/v1/commands?device=uno&cursor=", token=h.token["dos"])
        self.assertEqual(body["commands"], [])
        self.assertEqual(len(request(h.port, "GET", "/v1/commands?device=dos", token=h.token["uno"])[1]["commands"]), 1)

    def test_the_wire_format_matches_what_the_app_parses(self):
        # EventJson.parseCommands (Kotlin) lee: id, type, conversation|package|title|text|minutes.
        reply = json.dumps({"commands": [
            {"type": "draft_reply", "text": "Dale"}, {"type": "notify", "text": "Aviso"}, {"type": "block", "app": "com.zhiliaoapp.musically", "minutes": 30},
        ]})
        h = self.make(hermes=ScriptedHermes(reply, json.dumps({"commands": [{"type": "pause", "minutes": 15}]})), max_pauses_per_hour=5)
        h.post_events(event("e1"), event("e2"))
        h.brain.drain()
        by_type = {c["type"]: c for c in h.commands()[1]["commands"]}
        self.assertEqual(set(by_type["draft_reply"]), {"id", "type", "conversation", "text"})
        self.assertEqual(set(by_type["notify"]), {"id", "type", "title", "text"})
        self.assertEqual(set(by_type["block"]), {"id", "type", "package", "minutes"})
        self.assertEqual(set(by_type["pause"]), {"id", "type", "minutes"})
        self.assertEqual(len({c["id"] for c in by_type.values()}), 4)

    def test_the_token_never_appears_in_the_audit_or_the_state_database(self):
        h = self.make()
        h.post_events(event("e1"))
        h.commands()
        h.brain.drain()
        token = h.token["phone"]
        self.assertNotIn(token, h.cfg.audit_path.read_text())
        self.assertNotIn(token.encode(), (h.cfg.data_dir / "state.db").read_bytes())


class AudioRouteTest(HarnessCase):
    def post(self, h, data: bytes, **headers):
        headers.setdefault("X-Zen-Segment-Id", "seg-1")
        return request(h.port, "POST", "/v1/audio", token=h.token["phone"], body=data, headers=headers)

    def test_invalid_audio_is_rejected_with_a_reason(self):
        h = self.make()
        self.assertEqual(self.post(h, b"basura" * 20)[:2], (422, {"error": "not_wav"}))
        self.assertEqual(self.post(h, wav(1.0, channels=2))[:2], (422, {"error": "not_pcm16_mono"}))
        self.assertEqual(self.post(h, wav(1.0, rate=44100))[:2], (422, {"error": "bad_rate"}))
        self.assertEqual(self.post(h, wav(40.0))[0], 413)
        self.assertEqual(self.post(h, wav(1.0), **{"X-Zen-Segment-Id": "../x"})[0], 400)
        rejected = [l for l in h.audit_lines() if l["type"] == "audio_rejected"]
        self.assertEqual([l["reason"] for l in rejected], ["not_wav", "not_pcm16_mono", "bad_rate"])

    def test_content_type_is_not_trusted(self):
        h = self.make()
        self.assertEqual(self.post(h, wav(1.0), **{"Content-Type": "text/html"})[0], 200)
        self.assertEqual(self.post(h, b"<html>" + b"x" * 100, **{"Content-Type": "audio/wav", "X-Zen-Segment-Id": "seg-2"})[0], 422)


if __name__ == "__main__":
    unittest.main()
