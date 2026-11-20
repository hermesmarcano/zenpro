import json
import time
import unittest

from zen_brain.config import ConfigError, check_hermes_url
from zen_brain.hermes import HermesClient, HermesError

from .support import EMPTY, FakeHermesServer


class HermesClientTest(unittest.TestCase):
    def setUp(self) -> None:
        self.server = FakeHermesServer()
        self.addCleanup(self.server.close)
        self.sleeps: list[float] = []

    def client(self, **kw) -> HermesClient:
        kw.setdefault("sleep", self.sleeps.append)
        return HermesClient(self.server.url, "k" * 40, "zen-brain", **kw)

    def ask(self, **kw):
        return self.client(**kw).complete("sistema", "usuario", idempotency_key="zen-phone-e1", session_id="zen-phone-e1")

    def test_request_carries_the_documented_headers_and_body(self):
        self.server.responses = [(200, '{"commands": []}')]
        reply = self.ask()
        (req,) = self.server.requests
        self.assertEqual(req["headers"]["authorization"], "Bearer " + "k" * 40)
        self.assertEqual(req["headers"]["idempotency-key"], "zen-phone-e1")
        self.assertEqual(req["headers"]["x-hermes-session-id"], "zen-phone-e1")
        self.assertEqual(req["body"]["model"], "zen-brain")
        self.assertIs(req["body"]["stream"], False)
        self.assertEqual(req["body"]["response_format"], {"type": "json_object"})
        self.assertEqual([m["role"] for m in req["body"]["messages"]], ["system", "user"])
        self.assertEqual(reply.content, '{"commands": []}')
        self.assertEqual(reply.total_tokens, 42)

    def test_reasoning_content_is_never_exposed(self):
        reply = self.ask()
        self.assertNotIn("PENSAMIENTO", repr(reply))

    def test_429_and_5xx_are_retried_with_backoff_and_then_succeed(self):
        self.server.responses = [(429, b"{}"), (503, b"{}"), (200, EMPTY)]
        self.assertEqual(self.ask().content, EMPTY)
        self.assertEqual(len(self.server.requests), 3)
        self.assertEqual(len(self.sleeps), 2)
        self.assertLess(self.sleeps[0], self.sleeps[1] + 0.5)

    def test_retries_are_bounded(self):
        self.server.responses = [(500, b"{}")] * 5
        with self.assertRaises(HermesError) as ctx:
            self.ask()
        self.assertEqual(ctx.exception.code, "server")
        self.assertEqual(len(self.server.requests), 3)

    def test_auth_conflict_and_other_errors_are_not_retried(self):
        for status, code in ((401, "auth"), (403, "auth"), (409, "conflict"), (400, "http_error"), (404, "http_error")):
            self.server.requests.clear()
            self.server.responses = [(status, b"{}")]
            with self.assertRaises(HermesError) as ctx:
                self.ask()
            self.assertEqual((ctx.exception.code, len(self.server.requests)), (code, 1), status)

    def test_redirects_are_not_followed(self):
        self.server.responses = [(302, b"", {"Location": "http://127.0.0.1:1/robar-la-clave"})]
        with self.assertRaises(HermesError) as ctx:
            self.ask()
        self.assertEqual((ctx.exception.code, ctx.exception.status), ("http_error", 302))
        self.assertEqual(len(self.server.requests), 1)

    def test_a_timeout_is_not_retried(self):
        self.server.responses = [lambda: (time.sleep(1.0), (200, EMPTY))[1]]
        with self.assertRaises(HermesError) as ctx:
            self.ask(timeout_s=0.3)
        self.assertEqual(ctx.exception.code, "timeout")
        self.assertEqual(len(self.server.requests), 1)

    def test_an_unreachable_server_is_retried_then_reported(self):
        client = HermesClient("http://127.0.0.1:1/v1/chat/completions", "k" * 40, "zen-brain", timeout_s=1, sleep=self.sleeps.append)
        with self.assertRaises(HermesError) as ctx:
            client.complete("s", "u", idempotency_key="k", session_id="s")
        self.assertEqual(ctx.exception.code, "unreachable")
        self.assertEqual(len(self.sleeps), 2)

    def test_garbage_and_oversized_responses_are_rejected(self):
        for body, code in (
            (b"no json", "bad_response"), (b'{"choices": []}', "bad_response"), (b"[]", "bad_response"),
            (json.dumps({"choices": [{"message": {"content": 5}}]}).encode(), "bad_response"),
            (json.dumps({"choices": [{"message": {}}]}).encode(), "bad_response"),
            (b"x" * 1_000_001, "too_large"),
        ):
            self.server.responses = [(200, body)]
            with self.assertRaises(HermesError) as ctx:
                self.ask()
            self.assertEqual(ctx.exception.code, code, body[:20])

    def test_bogus_token_counts_are_ignored(self):
        self.server.responses = [(200, json.dumps({"choices": [{"message": {"content": EMPTY}}], "usage": {"total_tokens": True}}).encode())]
        self.assertIsNone(self.ask().total_tokens)


class HermesUrlTest(unittest.TestCase):
    def test_only_loopback_urls_are_accepted(self):
        for url in ("http://127.0.0.1:8642/v1/chat/completions", "http://localhost:8642/x", "http://[::1]:8642/x"):
            check_hermes_url(url)
        for url in ("http://10.0.0.5:8642/x", "http://hermes.local/x", "http://localhost.evil.com/x", "ftp://127.0.0.1/x",
                    "http://user:pw@127.0.0.1/x", "no es url", "http://0.0.0.0:8642/x"):
            with self.assertRaises(ConfigError, msg=url):
                check_hermes_url(url)


if __name__ == "__main__":
    unittest.main()
