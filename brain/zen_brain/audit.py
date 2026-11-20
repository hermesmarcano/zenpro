"""Registro de auditoría: JSON Lines, solo se añade al final, permisos 600.

REGLA: aquí no entra texto del usuario ni del modelo (ni notificaciones, ni transcripciones, ni borradores):
solo identificadores, tamaños y decisiones con códigos fijos. Los valores se serializan con json.dumps, así que
un valor con saltos de línea no puede falsificar otra entrada.

`log()` NUNCA lanza: un disco lleno o un archivo movido no debe matar a los hilos que procesan eventos. Devuelve
`False` si no pudo escribir, y quien actúa (brain.py) no emite órdenes sin rastro.
"""

from __future__ import annotations

import json
import os
import sys
import threading
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

MAX_BYTES = 20 * 1024 * 1024
KEEP = 5


class Audit:
    def __init__(self, path: Path, clock: Callable[[], float] = time.time) -> None:
        self._path = path
        self._clock = clock
        self._lock = threading.Lock()
        self._fd = -1
        self._warned = False
        path.parent.mkdir(parents=True, exist_ok=True)
        self._fd = self._open()

    def _open(self) -> int:
        fd = os.open(self._path, os.O_WRONLY | os.O_APPEND | os.O_CREAT, 0o600)
        os.fchmod(fd, 0o600)
        return fd

    def log(self, **fields: object) -> bool:
        entry = {"ts": datetime.fromtimestamp(self._clock(), timezone.utc).isoformat(timespec="milliseconds")}
        entry.update(fields)
        try:
            line = (json.dumps(entry, ensure_ascii=True, separators=(",", ":"), default=str) + "\n").encode("ascii")
            with self._lock:
                self._follow_the_file()
                if os.fstat(self._fd).st_size + len(line) > MAX_BYTES:
                    self._rotate()
                os.write(self._fd, line)
            self._warned = False
            return True
        except Exception as exc:  # noqa: BLE001 — la auditoría jamás debe tumbar al llamador
            if not self._warned:
                self._warned = True
                print(f"zen-brain: auditoría no disponible ({type(exc).__name__})", file=sys.stderr, flush=True)
            return False

    def _follow_the_file(self) -> None:
        """Si alguien movió o borró el archivo (logrotate, un `mv`), se abre uno nuevo en su sitio en vez de seguir escribiendo
        en un archivo que ya no está en la ruta."""
        if self._fd < 0:
            self._fd = self._open()
            return
        try:
            moved = os.stat(self._path).st_ino != os.fstat(self._fd).st_ino
        except FileNotFoundError:
            moved = True
        if moved:
            new_fd = self._open()
            old_fd, self._fd = self._fd, new_fd
            os.close(old_fd)

    def _rotate(self) -> None:
        # Se mueve lo viejo ANTES de cerrar nada y se abre el nuevo archivo antes de soltar el descriptor actual:
        # si algo falla, el descriptor sigue siendo válido (nunca se queda uno cerrado que otro archivo pueda reutilizar).
        for index in range(KEEP - 1, 0, -1):
            src = self._path.with_name(f"{self._path.name}.{index}")
            if src.exists():
                os.replace(src, self._path.with_name(f"{self._path.name}.{index + 1}"))
        os.replace(self._path, self._path.with_name(f"{self._path.name}.1"))
        new_fd = self._open()
        old_fd, self._fd = self._fd, new_fd
        os.close(old_fd)

    def close(self) -> None:
        with self._lock:
            if self._fd >= 0:
                os.close(self._fd)
                self._fd = -1
