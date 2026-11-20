"""Tokens del teléfono: uno por dispositivo, guardados solo como hash, con rotación y revocación en caliente.

El archivo (tokens.json, permisos 600) se vuelve a leer solo cuando cambia: revocar un token es borrar su línea
(o `python -m zen_brain tokens revoke <nombre>`), sin reiniciar el adaptador. Si el archivo se corrompe, el
adaptador deja de aceptar tokens (falla cerrado) en lugar de aceptar los que había, y `broken` queda a True para
que el mantenimiento lo avise en la auditoría.

No hay token por variable de entorno: no se podría revocar ni rotar sin reiniciar.
"""

from __future__ import annotations

import fcntl
import hashlib
import hmac
import json
import os
import re
import secrets
import tempfile
import threading
import time
from contextlib import contextmanager
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable, Iterator

from .config import LABEL_RE, MIN_SECRET_CHARS

MAX_TOKEN_CHARS = 256
_RECHECK_S = 1.0
_SHA256_HEX = re.compile(r"[0-9a-f]{64}")


def hash_token(token: str) -> str:
    # Los tokens son aleatorios de 256 bits: un SHA-256 simple basta (no hay contraseñas débiles que proteger).
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def new_token() -> str:
    return secrets.token_urlsafe(32)  # 43 caracteres, 256 bits


class TokenStore:
    def __init__(self, path: Path, clock: Callable[[], float] = time.monotonic) -> None:
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        self._file_hashes: dict[str, str] = {}
        self._signature: tuple[int, int, int] | None = None
        self._checked_at = -_RECHECK_S
        self.broken = False

    def authenticate(self, presented: str) -> str | None:
        """Devuelve el nombre del dispositivo dueño del token, o None. Tiempo constante respecto al contenido."""
        if not MIN_SECRET_CHARS <= len(presented) <= MAX_TOKEN_CHARS or not presented.isascii():
            return None
        digest = hash_token(presented)
        self._reload()
        found: str | None = None
        # Se recorren todos (sin cortar al encontrar) para no revelar por tiempo cuál coincidió.
        for label, stored in tuple(self._file_hashes.items()):
            if hmac.compare_digest(digest, stored):
                found = label
        return found

    def check(self) -> bool:
        """¿El archivo de tokens se lee bien? (Para /healthz: roto = nadie puede entrar y hay que enterarse.)"""
        self._reload()
        return not self.broken

    def _reload(self) -> None:
        now = self._clock()
        with self._lock:
            if now - self._checked_at < _RECHECK_S:
                return
            self._checked_at = now
            try:
                st = self._path.stat()
                signature = (st.st_ino, st.st_mtime_ns, st.st_size)
            except FileNotFoundError:
                self._file_hashes, self._signature, self.broken = {}, None, False
                return
            except OSError:
                self._file_hashes, self._signature, self.broken = {}, None, True
                return
            if signature == self._signature:
                return
            try:
                data = json.loads(self._path.read_text(encoding="utf-8"))
                hashes: dict[str, str] = {}
                for label, entry in data["devices"].items():
                    stored = entry["sha256"]
                    if not LABEL_RE.fullmatch(label) or not isinstance(stored, str) or not _SHA256_HEX.fullmatch(stored):
                        raise ValueError("entrada inválida")
                    hashes[label] = stored
                self._file_hashes, self._signature, self.broken = hashes, signature, False
            except Exception:  # noqa: BLE001 — cualquier cosa rara (incluido JSON anidado) falla CERRADO, sin dejar tokens viejos
                self._file_hashes, self._signature, self.broken = {}, signature, True

    # ---- administración (CLI) -------------------------------------------------------------------------------

    @contextmanager
    def _exclusive(self) -> Iterator[None]:
        """Un solo administrador a la vez: leer-modificar-escribir sin pisarse entre procesos."""
        self._path.parent.mkdir(parents=True, exist_ok=True)
        fd = os.open(self._path.with_name(self._path.name + ".lock"), os.O_RDWR | os.O_CREAT, 0o600)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX)
            yield
        finally:
            os.close(fd)  # al cerrar se libera el candado

    def _read_raw(self) -> dict:
        try:
            return json.loads(self._path.read_text(encoding="utf-8"))
        except FileNotFoundError:
            return {"version": 1, "devices": {}}

    def _write_raw(self, data: dict) -> None:
        fd, tmp = tempfile.mkstemp(dir=self._path.parent, prefix=".tokens-")
        try:
            os.fchmod(fd, 0o600)
            with os.fdopen(fd, "w", encoding="utf-8") as handle:
                json.dump(data, handle, indent=2, sort_keys=True)
                handle.write("\n")
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp, self._path)
        except BaseException:
            try:
                os.unlink(tmp)
            except FileNotFoundError:
                pass
            raise

    def add(self, label: str, *, replace: bool = False) -> str:
        """Crea (o rota, con replace) el token de un dispositivo. Se devuelve UNA vez; solo se guarda su hash."""
        if not LABEL_RE.fullmatch(label):
            raise ValueError("el nombre debe ser minúsculas, números, - o _ (máx. 32)")
        with self._exclusive():
            data = self._read_raw()
            if label in data["devices"] and not replace:
                raise ValueError("ese dispositivo ya tiene token: usa rotate")
            if label not in data["devices"] and replace:
                raise ValueError("ese dispositivo no existe: usa add")
            token = new_token()
            data["devices"][label] = {"sha256": hash_token(token), "created": datetime.now(timezone.utc).isoformat(timespec="seconds")}
            self._write_raw(data)
            return token

    def revoke(self, label: str) -> bool:
        with self._exclusive():
            data = self._read_raw()
            if label not in data["devices"]:
                return False
            del data["devices"][label]
            self._write_raw(data)
            return True

    def labels(self) -> list[str]:
        return sorted(self._read_raw()["devices"])

    def labels_safe(self) -> list[str]:
        try:
            return self.labels()
        except (OSError, ValueError, KeyError, TypeError):
            return []
