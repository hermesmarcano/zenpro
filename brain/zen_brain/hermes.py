"""Cliente de la API de Hermes (compatible con OpenAI) en loopback.

- Solo se lee `choices[0].message.content` y `usage.total_tokens`. `reasoning_content` NO se toca: ni se parsea, ni
  se guarda ni se muestra.
- No se siguen redirecciones (la clave no debe viajar a otro sitio) ni se acepta una respuesta enorme.
- Se reintenta solo 429/5xx/conexión rechazada, con espera exponencial. Un tiempo agotado NO se reintenta.
"""

from __future__ import annotations

import http.client
import json
import random
import socket
import time
from dataclasses import dataclass
from typing import Callable
from urllib.parse import urlsplit

MAX_RESPONSE_BYTES = 1_000_000


class HermesError(Exception):
    """`code`: auth, conflict, rate_limited, server, timeout, unreachable, bad_response, too_large, http_error."""

    def __init__(self, code: str, status: int | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


@dataclass(frozen=True)
class HermesReply:
    content: str
    total_tokens: int | None
    elapsed_ms: int


class HermesClient:
    def __init__(
        self,
        url: str,
        api_key: str,
        model: str,
        timeout_s: float = 60.0,
        retries: int = 2,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        parts = urlsplit(url)
        self._host = parts.hostname or "127.0.0.1"
        self._port = parts.port or (443 if parts.scheme == "https" else 80)
        self._tls = parts.scheme == "https"
        self._path = parts.path or "/"
        self._key = api_key
        self._model = model
        self._timeout = timeout_s
        self._retries = retries
        self._sleep = sleep

    def complete(self, system: str, user: str, *, idempotency_key: str, session_id: str) -> HermesReply:
        body = json.dumps(
            {
                "model": self._model,
                "stream": False,
                "response_format": {"type": "json_object"},
                "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            },
            ensure_ascii=False,
        ).encode("utf-8")
        headers = {
            "Authorization": f"Bearer {self._key}",
            "Content-Type": "application/json",
            "Accept": "application/json",
            "Idempotency-Key": idempotency_key,
            "X-Hermes-Session-Id": session_id,
        }
        started = time.monotonic()
        last: HermesError | None = None
        for attempt in range(self._retries + 1):
            if attempt:
                self._sleep(min(8.0, 2.0 ** (attempt - 1)) + random.random() * 0.25)
            try:
                status, data = self._once(body, headers)
            except (socket.timeout, TimeoutError):
                raise HermesError("timeout") from None
            except (ConnectionError, OSError, http.client.HTTPException):
                last = HermesError("unreachable")
                continue
            if status == 200:
                return self._parse(data, started)
            if status in (401, 403):
                raise HermesError("auth", status)
            if status == 409:
                raise HermesError("conflict", status)
            if status == 429:
                last = HermesError("rate_limited", status)
                continue
            if 500 <= status < 600:
                last = HermesError("server", status)
                continue
            raise HermesError("http_error", status)
        assert last is not None
        raise last

    def _once(self, body: bytes, headers: dict[str, str]) -> tuple[int, bytes]:
        factory = http.client.HTTPSConnection if self._tls else http.client.HTTPConnection
        conn = factory(self._host, self._port, timeout=self._timeout)
        try:
            conn.request("POST", self._path, body=body, headers=headers)
            response = conn.getresponse()
            data = response.read(MAX_RESPONSE_BYTES + 1)
            if len(data) > MAX_RESPONSE_BYTES:
                raise HermesError("too_large")
            return response.status, data
        finally:
            conn.close()

    @staticmethod
    def _parse(data: bytes, started: float) -> HermesReply:
        try:
            payload = json.loads(data.decode("utf-8"))
            content = payload["choices"][0]["message"]["content"]
            usage = payload.get("usage")
            tokens = usage.get("total_tokens") if isinstance(usage, dict) else None
        except (UnicodeDecodeError, ValueError, KeyError, IndexError, TypeError, AttributeError, RecursionError):
            raise HermesError("bad_response") from None
        if not isinstance(content, str):
            raise HermesError("bad_response")
        return HermesReply(
            content=content,
            total_tokens=tokens if isinstance(tokens, int) and not isinstance(tokens, bool) else None,
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )
