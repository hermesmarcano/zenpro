"""Servidor HTTP del adaptador (biblioteca estándar).

Solo escucha en 127.0.0.1: el TLS y el acceso desde el teléfono los pone `tailscale serve` (o un proxy local).
Limita tamaño, tiempo y conexiones; no registra rutas ni cabeceras; responde sin detalles ante un token malo.
"""

from __future__ import annotations

import http.client
import json
import socket
import sys
import threading
import time
from dataclasses import dataclass
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Callable
from urllib.parse import parse_qs, urlsplit

from . import protocol, stt
from .audit import Audit
from .brain import Brain
from .config import Config
from .store import Store
from .tokens import TokenStore

LISTEN_HOST = "127.0.0.1"
MAX_EVENTS_BODY = 2 * 1024 * 1024
# Antes de autenticar solo se da una ventana corta (cabeceras + token) y un tope de bytes: un cliente sin credenciales no
# debe poder retener una ranura mucho tiempo ni hacer que se reserve memoria. Ya autenticado, hay margen para el cuerpo.
HEADER_DEADLINE_S = 4.0
REQUEST_DEADLINE_S = 30.0
HEADER_BYTES_MAX = 16 * 1024
MAX_CONNECTIONS = 64
# Líneas de auditoría de bajo valor (peticiones rechazadas): como mucho tantas por minuto y tipo; el resto se cuenta.
AUDIT_LINES_PER_MINUTE = 20
_ERRORS = {400: "bad_request", 401: "unauthorized", 404: "not_found", 405: "method_not_allowed", 411: "length_required", 413: "too_large",
           414: "bad_request", 422: "unprocessable", 429: "too_many_events", 431: "bad_request", 503: "overloaded"}
_SILENT_ERRORS = (BrokenPipeError, ConnectionResetError, socket.timeout, TimeoutError, http.client.HTTPException)


@dataclass
class App:
    cfg: Config
    tokens: TokenStore
    brain: Brain
    store: Store
    audit: Audit
    clock: Callable[[], float] = time.time

    def __post_init__(self) -> None:
        self._failures = 0
        self._failures_lock = threading.Lock()
        self._throttle: dict[str, list] = {}  # tipo → [inicio de la ventana, escritas, suprimidas]

    def note_auth_failure(self) -> None:
        with self._failures_lock:
            self._failures += 1

    def audit_limited(self, kind: str, **fields: object) -> None:
        """Una línea de auditoría de una petición rechazada, con tope por minuto: quien tenga un token válido no puede
        empujar fuera del historial las alertas y la evidencia anteriores a base de peticiones inútiles."""
        now = self.clock()
        with self._failures_lock:
            window = self._throttle.setdefault(kind, [now, 0, 0])
            if now - window[0] >= 60:
                window[0], window[1] = now, 0
            if window[1] >= AUDIT_LINES_PER_MINUTE:
                window[2] += 1
                return
            window[1] += 1
        self.audit.log(type=kind, **fields)

    def flush_auth_failures(self) -> None:
        """Vuelca los contadores acumulados (intentos de autenticación fallidos y líneas suprimidas): solo totales."""
        with self._failures_lock:
            count, self._failures = self._failures, 0
            suppressed = {kind: w[2] for kind, w in self._throttle.items() if w[2]}
            for window in self._throttle.values():
                window[2] = 0
        if count:
            self.audit.log(type="auth_failures", count=count)  # nada del contenido de los intentos
        for kind, total in suppressed.items():
            self.audit.log(type="suppressed", kind=kind, count=total)

    def healthy(self) -> bool:
        """Sin auditoría o sin tokens legibles el adaptador no actúa (falla cerrado): que se note desde fuera."""
        return self.audit.ok and self.tokens.check()


class _Watchdog:
    """UN hilo vigila los plazos de TODAS las conexiones (antes, un `Timer` —un hilo más— por conexión: con el límite de
    tareas de systemd, unas pocas decenas de conexiones ociosas agotaban los hilos). Un socket cerrado ya no es un
    descriptor: apagarlo tarde no puede tocar uno que otro archivo haya reutilizado."""

    def __init__(self, tick: float = 0.25) -> None:
        self._deadlines: dict[socket.socket, float] = {}
        self._lock = threading.Lock()
        self._stop = threading.Event()
        self._tick = tick
        threading.Thread(target=self._run, name="watchdog", daemon=True).start()

    def arm(self, sock: socket.socket, seconds: float) -> None:
        with self._lock:
            self._deadlines[sock] = time.monotonic() + seconds

    def disarm(self, sock: socket.socket) -> None:
        with self._lock:
            self._deadlines.pop(sock, None)

    def stop(self) -> None:
        self._stop.set()

    def _run(self) -> None:
        while not self._stop.wait(self._tick):
            now = time.monotonic()
            with self._lock:
                expired = [s for s, deadline in self._deadlines.items() if deadline <= now]
                for sock in expired:
                    del self._deadlines[sock]
            for sock in expired:
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass


class _Capped:
    """Envuelve la lectura de la petición: hasta autenticar solo deja leer `limit` bytes en total (línea de petición +
    cabeceras). Sin esto, 64 conexiones con 99 cabeceras de 64 KB cada una reservan gigas antes de pedir ningún token."""

    def __init__(self, raw, limit: int) -> None:
        self._raw = raw
        self._left: int | None = limit

    def lift(self) -> None:
        self._left = None

    def readline(self, size: int | None = -1) -> bytes:
        if self._left is not None and (size is None or size < 0 or size > self._left + 1):
            size = self._left + 1
        line = self._raw.readline(size)
        if self._left is not None:
            self._left -= len(line)
            if self._left < 0:
                raise http.client.LineTooLong("cabeceras")
        return line

    def read(self, size: int = -1) -> bytes:
        data = self._raw.read(size)
        if self._left is not None:
            self._left -= len(data)
            if self._left < 0:
                raise http.client.LineTooLong("cabeceras")
        return data

    def close(self) -> None:
        self._raw.close()


class _Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True
    request_queue_size = 64

    def __init__(self, address: tuple[str, int], handler: type[BaseHTTPRequestHandler]) -> None:
        self._slots = threading.BoundedSemaphore(MAX_CONNECTIONS)
        self.watchdog = _Watchdog()
        super().__init__(address, handler)

    def server_close(self) -> None:
        self.watchdog.stop()
        super().server_close()

    def handle_error(self, request, client_address) -> None:  # type: ignore[override]
        # Sin trazas por stderr (un cliente que corta no es un fallo); solo el tipo, para poder diagnosticar.
        exc = sys.exc_info()[1]
        if not isinstance(exc, _SILENT_ERRORS):
            try:
                print(f"zen-brain: error en una conexión ({type(exc).__name__})", file=sys.stderr, flush=True)
            except Exception:  # noqa: BLE001 — ni siquiera stderr roto debe propagarse
                pass

    def process_request(self, request, client_address) -> None:  # type: ignore[override]
        if not self._slots.acquire(blocking=False):
            try:
                request.sendall(b"HTTP/1.0 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            except OSError:
                pass
            self.shutdown_request(request)
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            # Si no se pudo crear el hilo de la conexión (sin hilos disponibles), nadie liberaría la ranura: se perdería
            # para siempre y, tras 64 fallos, el servidor daría 503 a todos hasta reiniciarlo.
            self._slots.release()
            raise

    def process_request_thread(self, request, client_address) -> None:  # type: ignore[override]
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._slots.release()


def make_handler(app: App) -> type[BaseHTTPRequestHandler]:
    def _log(**fields: object) -> bool:
        try:
            return bool(app.audit.log(**fields))
        except Exception:  # noqa: BLE001 — la auditoría no debe romper una respuesta
            return False

    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.0"  # una petición por conexión: nada de conexiones persistentes que retener
        timeout = 10

        def version_string(self) -> str:
            return "zen-brain"

        def setup(self) -> None:
            super().setup()
            # Tope TOTAL de vida de la conexión (el timeout normal es por lectura y un cliente lento lo esquiva).
            self.server.watchdog.arm(self.connection, HEADER_DEADLINE_S)  # type: ignore[attr-defined]
            self.rfile = _Capped(self.rfile, HEADER_BYTES_MAX)  # type: ignore[assignment]

        def _arm(self, seconds: float) -> None:
            self.server.watchdog.arm(self.connection, seconds)  # type: ignore[attr-defined]

        def finish(self) -> None:
            self.server.watchdog.disarm(self.connection)  # type: ignore[attr-defined]
            super().finish()

        # Sin registro de acceso: las rutas llevan el cursor y las cabeceras el token.
        def log_message(self, *args: object) -> None:
            pass

        def log_error(self, *args: object) -> None:
            pass

        def send_error(self, code: int, message: str | None = None, explain: str | None = None) -> None:  # type: ignore[override]
            self._json(code, {"error": _ERRORS.get(code, "error")})

        def _json(self, status: int, body: dict, headers: dict[str, str] | None = None) -> None:
            payload = json.dumps(body, separators=(",", ":")).encode("utf-8")
            try:
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-store")
                self.send_header("X-Content-Type-Options", "nosniff")
                self.send_header("Connection", "close")
                for key, value in (headers or {}).items():
                    self.send_header(key, value)
                self.end_headers()
                if self.command != "HEAD":
                    self.wfile.write(payload)
            except (BrokenPipeError, ConnectionResetError):
                pass  # el cliente ya no está

        # ---- ruteo ----------------------------------------------------------------------------------------------

        _consumed = False

        def do_GET(self) -> None:
            self._route("GET")

        def do_POST(self) -> None:
            try:
                self._route("POST")
            finally:
                self._drain()

        def _drain(self) -> None:
            """Si se respondió (401, 413...) sin leer el cuerpo, se descarta un poco antes de cerrar: cerrar con datos
            pendientes provoca un reset y el teléfono vería un fallo de red en vez del código de error."""
            if self._consumed:
                return
            try:
                self.rfile.lift()  # type: ignore[attr-defined]
                pending = min(int(self.headers.get("Content-Length", "0") or 0), 256 * 1024)
                while pending > 0:
                    chunk = self.rfile.read(min(65536, pending))
                    if not chunk:
                        break
                    pending -= len(chunk)
            except (OSError, ValueError, http.client.HTTPException):
                pass

        def do_PUT(self) -> None:
            self._json(405, {"error": "method_not_allowed"})

        do_DELETE = do_PATCH = do_OPTIONS = do_HEAD = do_PUT

        def _route(self, method: str) -> None:
            try:
                parts = urlsplit(self.path)
            except ValueError:  # p. ej. «GET http://[::1/x»
                return self._json(400, {"error": "bad_request"})
            path = parts.path
            if method == "GET" and path == "/healthz":
                # Sin detalles. 503 si el adaptador no puede actuar con seguridad (auditoría o tokens rotos).
                return self._json(200, {"status": "ok"}) if app.healthy() else self._json(503, {"status": "degraded"})
            routes = {"/v1/events": "POST", "/v1/audio": "POST", "/v1/commands": "GET"}
            if path not in routes:
                return self._json(404, {"error": "not_found"})
            if routes[path] != method:
                return self._json(405, {"error": "method_not_allowed"})
            device = self._authenticate()
            if device is None:
                return
            if path == "/v1/events":
                self._events(device)
            elif path == "/v1/audio":
                self._audio(device)
            else:
                self._commands(device, parts.query)

        def _authenticate(self) -> str | None:
            scheme, _, token = self.headers.get("Authorization", "").partition(" ")
            device = app.tokens.authenticate(token.strip()) if scheme.lower() == "bearer" else None
            if device is None:
                app.note_auth_failure()
                self._json(401, {"error": "unauthorized"})  # idéntico para ausente, corto o incorrecto
            else:
                self._arm(REQUEST_DEADLINE_S)  # autenticado: ahora sí, tiempo para el cuerpo
                self.rfile.lift()  # type: ignore[attr-defined]
            return device

        def _body(self, limit: int) -> bytes | None:
            if self.headers.get("Transfer-Encoding"):
                self._json(411, {"error": "length_required"})
                return None
            raw = self.headers.get("Content-Length", "")
            if not (raw.isascii() and raw.isdigit()) or len(raw) > 10:
                self._json(411, {"error": "length_required"})
                return None
            length = int(raw)
            if length > limit:
                self._json(413, {"error": "too_large"})
                return None
            self._consumed = True
            data = self.rfile.read(length)
            if len(data) != length:
                self._json(400, {"error": "bad_request"})
                return None
            return data

        # ---- rutas ----------------------------------------------------------------------------------------------

        def _events(self, device: str) -> None:
            body = self._body(MAX_EVENTS_BODY)
            if body is None:
                return
            try:
                parsed = protocol.parse_events(body, int(app.clock() * 1000))
            except protocol.ProtocolError as exc:
                return self._json(exc.status, {"error": exc.code})
            result = app.brain.submit_events(device, parsed.events)
            if result.limited:
                app.audit_limited("batch_limited", device=device, events=len(parsed.events))
                return self._json(429, {"error": "too_many_events"}, {"Retry-After": "300"})
            _log(type="batch", device=device, received=len(parsed.events) + sum(parsed.dropped.values()),
                 accepted=result.accepted, duplicates=result.duplicates, dropped=parsed.dropped)
            if result.overloaded:
                return self._json(503, {"error": "overloaded"}, {"Retry-After": "30"})
            self._json(200, {"accepted": result.accepted, "duplicates": result.duplicates, "dropped": sum(parsed.dropped.values())})

        def _audio(self, device: str) -> None:
            try:
                meta = protocol.parse_audio_headers(self.headers, int(app.clock() * 1000))
            except protocol.ProtocolError as exc:
                return self._json(exc.status, {"error": exc.code})
            body = self._body(stt.MAX_WAV_BYTES)
            if body is None:
                return
            try:
                pcm = stt.parse_wav(body)  # no se confía en Content-Type: se valida la cabecera del WAV
            except stt.WavError as exc:
                app.audit_limited("audio_rejected", device=device, reason=exc.code, bytes=len(body))
                return self._json(422, {"error": exc.code})
            result = app.brain.submit_audio(device, meta.segment_id, meta.started_ms, pcm)
            if result.limited:
                app.audit_limited("batch_limited", device=device, events=1)
                return self._json(429, {"error": "too_many_events"}, {"Retry-After": "300"})
            if result.overloaded:
                return self._json(503, {"error": "overloaded"}, {"Retry-After": "30"})
            self._json(200, {"accepted": result.accepted, "duplicates": result.duplicates})

        def _commands(self, device: str, query: str) -> None:
            # `device` de la URL se ignora a propósito: cada token solo ve las órdenes de SU dispositivo.
            cursor = parse_qs(query).get("cursor", [""])[0][:64]
            next_cursor, commands = app.store.fetch_commands(device, cursor)
            if commands:
                _log(type="delivery", device=device, commands=len(commands), kinds=sorted({c["type"] for c in commands}))
            self._json(200, {"cursor": next_cursor, "commands": commands})

    return Handler


def make_server(app: App, port: int | None = None) -> _Server:
    return _Server((LISTEN_HOST, app.cfg.port if port is None else port), make_handler(app))
