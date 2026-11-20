"""Alertas y freno de emergencia.

Si el modelo empieza a responder basura (formato roto) o a pedir bloqueos en ráfaga —señal de que lo están
manipulando o de que falla—, el adaptador deja de consultarlo durante un rato y lo deja escrito en la auditoría.
Es un cortacircuitos: lo seguro es no actuar.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from typing import Callable

from .audit import Audit


class Monitor:
    def __init__(
        self,
        audit: Audit,
        clock: Callable[[], float] = time.time,
        *,
        window: int = 20,
        nonjson_threshold: int = 5,
        block_burst: int = 3,
        block_window_s: float = 600.0,
        cooldown_s: float = 600.0,
        reject_burst: int = 5,
        pause_burst: int = 3,
    ) -> None:
        self._audit = audit
        self._clock = clock
        self._lock = threading.Lock()
        self._results: deque[bool] = deque(maxlen=window)
        self._blocks: deque[float] = deque()
        self._pauses: deque[float] = deque()
        self._rejects: deque[float] = deque()
        self._reject_burst = reject_burst
        self._pause_burst = pause_burst
        self._nonjson_threshold = nonjson_threshold
        self._block_burst = block_burst
        self._block_window_s = block_window_s
        self._cooldown_s = cooldown_s
        self._cooldown_until = 0.0
        self.total_calls = 0
        self.total_nonjson = 0

    def in_cooldown(self) -> bool:
        return self._clock() < self._cooldown_until

    def record_model_result(self, valid_json: bool) -> None:
        with self._lock:
            self.total_calls += 1
            if not valid_json:
                self.total_nonjson += 1
            self._results.append(valid_json)
            if sum(1 for ok in self._results if not ok) >= self._nonjson_threshold:
                self._trip("nonjson_burst")
                self._results.clear()

    def record_block(self) -> None:
        now = self._clock()
        with self._lock:
            self._blocks.append(now)
            while self._blocks and now - self._blocks[0] > self._block_window_s:
                self._blocks.popleft()
            if len(self._blocks) >= self._block_burst:
                self._trip("block_burst")
                self._blocks.clear()

    def record_pause(self) -> None:
        now = self._clock()
        with self._lock:
            self._pauses.append(now)
            while self._pauses and now - self._pauses[0] > self._block_window_s:
                self._pauses.popleft()
            if len(self._pauses) >= self._pause_burst:
                self._trip("pause_burst")
                self._pauses.clear()

    # Intentos que un modelo sano no hace: bloquear Ajustes o una app fuera de la lista, órdenes inventadas, responder
    # a una conversación que no existe. Cinco en diez minutos huelen a manipulación: se deja de consultar al modelo.
    SUSPICIOUS = frozenset({"app_not_allowed", "app_protected", "unknown_type", "no_conversation", "budget"})

    def record_rejections(self, codes: list[str]) -> None:
        suspicious = sum(1 for code in codes if code in self.SUSPICIOUS)
        if not suspicious:
            return
        now = self._clock()
        with self._lock:
            self._rejects.extend([now] * suspicious)
            while self._rejects and now - self._rejects[0] > self._block_window_s:
                self._rejects.popleft()
            if len(self._rejects) >= self._reject_burst:
                self._trip("rejected_burst")
                self._rejects.clear()

    def _trip(self, code: str) -> None:
        self._cooldown_until = self._clock() + self._cooldown_s
        self._audit.log(type="alert", code=code, cooldown_s=int(self._cooldown_s))
