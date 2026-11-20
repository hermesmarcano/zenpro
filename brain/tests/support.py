"""Ayudas de las pruebas: Hermes simulado (por script y por HTTP real) y un arnés con el servidor de verdad."""

from __future__ import annotations

import http.client
import io
import json
import shutil
import struct
import tempfile
import threading
import time
import unittest
import wave
from dataclasses import replace
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from zen_brain.audit import Audit
from zen_brain.brain import Brain
from zen_brain.config import Config
from zen_brain.hermes import HermesClient, HermesError, HermesReply
from zen_brain.monitor import Monitor
from zen_brain.server import App, make_server
from zen_brain.store import Store
from zen_brain.stt import NullTranscriber
from zen_brain.tokens import TokenStore

EMPTY = json.dumps({"commands": []})
CONV = "com.whatsapp|0|marta-conv-key"


def make_config(tmp: Path, **over) -> Config:
    base = Config(
        data_dir=tmp / "data", audit_path=tmp / "audit.jsonl", tokens_file=tmp / "tokens.json",
        hermes_key="h" * 40, workers=1, block_allowlist=frozenset({"com.zhiliaoapp.musically"}),
    )
    return replace(base, **over)


class ScriptedHermes:
    """Sustituto directo de HermesClient: devuelve lo que se le programa y apunta cada llamada."""

    def __init__(self, *replies: object) -> None:
        self.replies = list(replies)
        self.calls: list[dict] = []
        self.gate: threading.Event | None = None

    def complete(self, system: str, user: str, *, idempotency_key: str, session_id: str) -> HermesReply:
        self.calls.append({"system": system, "user": user, "key": idempotency_key, "session": session_id})
        if self.gate is not None:
            self.gate.wait(10)
        item = self.replies.pop(0) if self.replies else EMPTY
        if isinstance(item, Exception):
            raise item
        return HermesReply(content=item, total_tokens=100, elapsed_ms=12)


class FakeHermesServer:
    """Hermes por HTTP real en loopback, para probar HermesClient y el recorrido completo."""

    def __init__(self, responses: list | None = None) -> None:
        self.responses = list(responses or [])
        self.requests: list[dict] = []
        outer = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args) -> None:
                pass

            def do_POST(self) -> None:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length)
                outer.requests.append({"headers": {k.lower(): v for k, v in self.headers.items()}, "body": json.loads(raw), "path": self.path})
                item = outer.responses.pop(0) if outer.responses else (200, EMPTY)
                if callable(item):
                    item = item()
                status, content = item[0], item[1]
                extra = item[2] if len(item) > 2 else {}
                body = content if isinstance(content, bytes) else json.dumps(
                    {"choices": [{"message": {"content": content, "reasoning_content": "PENSAMIENTO-INTERNO"}}], "usage": {"total_tokens": 42}}
                ).encode()
                self.send_response(status)
                for key, value in extra.items():
                    self.send_header(key, value)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                try:
                    self.wfile.write(body)
                except OSError:
                    pass  # el cliente ya cortó (prueba de timeout)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    @property
    def url(self) -> str:
        return f"http://127.0.0.1:{self.server.server_address[1]}/v1/chat/completions"

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()


def wav(seconds: float = 1.0, *, channels: int = 1, width: int = 2, rate: int = 16000) -> bytes:
    out = io.BytesIO()
    with wave.open(out, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(width)
        w.setframerate(rate)
        w.writeframes(b"\x01\x00" * channels * int(rate * seconds) if width == 2 else b"\x01" * channels * int(rate * seconds))
    return out.getvalue()


def request(port: int, method: str, path: str, *, token: str | None = None, body: bytes | None = None,
            headers: dict | None = None, raw_headers: bool = False) -> tuple[int, dict | bytes, dict]:
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    hdrs = dict(headers or {})
    if token is not None:
        hdrs["Authorization"] = f"Bearer {token}"
    try:
        conn.request(method, path, body=body, headers=hdrs)
        response = conn.getresponse()
        data = response.read()
        parsed: dict | bytes
        try:
            parsed = json.loads(data)
        except ValueError:
            parsed = data
        return response.status, parsed, {k.lower(): v for k, v in response.getheaders()}
    finally:
        conn.close()


def event(event_id: str = "e1", *, kind: str = "notification", package: str = "com.whatsapp", title: str = "Marta",
          text: str = "¿cenamos hoy?", conv: str | None = CONV, at: int | None = None) -> dict:
    extra = {"conv": conv} if conv else {}
    return {"id": event_id, "kind": kind, "package": package, "title": title, "text": text,
            "at": at if at is not None else int(time.time() * 1000), "extra": extra}


def batch(*events: dict) -> bytes:
    return json.dumps({"device": "6f1c0000-0000-0000-0000-000000000000", "events": list(events)}).encode()


class Harness:
    """El servidor real (en un puerto efímero) con un Hermes por script y un token de dispositivo."""

    def __init__(self, hermes=None, transcriber=None, tokens: tuple[str, ...] = ("phone",), **config_over) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-brain-test-"))
        self.cfg = make_config(self.tmp, **config_over)
        self.audit = Audit(self.cfg.audit_path)
        self.store = Store(self.cfg.data_dir / "state.db")
        self.hermes = hermes or ScriptedHermes()
        self.tokens = TokenStore(self.cfg.tokens_file)
        self.token = {label: self.tokens.add(label) for label in tokens}
        self.brain = Brain(self.cfg, self.store, self.audit, self.hermes, transcriber or NullTranscriber(), Monitor(self.audit))
        self.app = App(self.cfg, TokenStore(self.cfg.tokens_file), self.brain, self.store, self.audit)
        self.server = make_server(self.app, 0)
        self.port = self.server.server_address[1]
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def post_events(self, *events: dict, label: str = "phone") -> tuple[int, dict | bytes, dict]:
        return request(self.port, "POST", "/v1/events", token=self.token[label], body=batch(*events))

    def commands(self, cursor: str = "", label: str = "phone") -> tuple[int, dict | bytes, dict]:
        return request(self.port, "GET", f"/v1/commands?device=x&cursor={cursor}", token=self.token[label])

    def audit_lines(self) -> list[dict]:
        self.brain.drain()
        return [json.loads(line) for line in self.cfg.audit_path.read_text().splitlines()]

    def close(self) -> None:
        self.server.shutdown()
        self.server.server_close()
        self.brain.drain(5)
        self.brain.stop()
        self.audit.close()
        self.store.close()
        shutil.rmtree(self.tmp, ignore_errors=True)


class HarnessCase(unittest.TestCase):
    def make(self, **kw) -> Harness:
        h = Harness(**kw)
        self.addCleanup(h.close)
        return h
