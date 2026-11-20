"""Alertas y freno de emergencia, POR DISPOSITIVO.

Si el modelo empieza a responder basura (formato roto) o a intentar cosas que un modelo sano no intenta —bloquear Ajustes
o una app fuera de la lista, órdenes inventadas, repetir bloqueos o pausas—, el adaptador deja de consultarlo durante un
rato para ESE dispositivo y lo deja escrito en la auditoría. Es un cortacircuitos: lo seguro es no actuar.

Es por dispositivo para que un teléfono no deje ciego a otro, y no se prolonga mientras ya está activo.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Callable

from .audit import Audit


@dataclass
class _Device:
    results: deque = field(default_factory=lambda: deque(maxlen=20))
    blocks: deque = field(default_factory=deque)
    pauses: deque = field(default_factory=deque)
    rejects: deque = field(default_factory=deque)
    cooldown_until: float = 0.0


class Monitor:
    # Intentos que un modelo sano no hace (o que indican que está topando con los límites una y otra vez).
    SUSPICIOUS = frozenset({"app_not_allowed", "app_protected", "unknown_type", "no_conversation", "budget", "rate_limited"})

    def __init__(
        self,
        audit: Audit,
        clock: Callable[[], float] = time.time,
        *,
        nonjson_threshold: int = 5,
        block_burst: int = 3,
        pause_burst: int = 3,
        reject_burst: int = 5,
        window_s: float = 600.0,
        cooldown_s: float = 600.0,
    ) -> None:
        self._audit = audit
        self._clock = clock
        self._lock = threading.Lock()
        self._devices: dict[str, _Device] = {}
        self._nonjson_threshold = nonjson_threshold
        self._block_burst = block_burst
        self._pause_burst = pause_burst
        self._reject_burst = reject_burst
        self._window_s = window_s
        self._cooldown_s = cooldown_s
        self.total_calls = 0
        self.total_nonjson = 0

    def _device(self, device: str) -> _Device:
        return self._devices.setdefault(device, _Device())

    def in_cooldown(self, device: str) -> bool:
        with self._lock:
            return self._clock() < self._device(device).cooldown_until

    def record_model_result(self, device: str, valid_json: bool) -> None:
        with self._lock:
            state = self._device(device)
            self.total_calls += 1
            if not valid_json:
                self.total_nonjson += 1
            state.results.append(valid_json)
            if sum(1 for ok in state.results if not ok) >= self._nonjson_threshold:
                self._trip(device, state, "nonjson_burst")
                state.results.clear()

    def record_block(self, device: str) -> None:
        self._burst(device, "blocks", self._block_burst, "block_burst")

    def record_pause(self, device: str) -> None:
        self._burst(device, "pauses", self._pause_burst, "pause_burst")

    def record_rejections(self, device: str, codes: list[str]) -> None:
        suspicious = sum(1 for code in codes if code in self.SUSPICIOUS)
        if not suspicious:
            return
        now = self._clock()
        with self._lock:
            state = self._device(device)
            state.rejects.extend([now] * suspicious)
            self._expire(state.rejects, now)
            if len(state.rejects) >= self._reject_burst:
                self._trip(device, state, "rejected_burst")
                state.rejects.clear()

    def _burst(self, device: str, attr: str, threshold: int, code: str) -> None:
        now = self._clock()
        with self._lock:
            state = self._device(device)
            events: deque = getattr(state, attr)
            events.append(now)
            self._expire(events, now)
            if len(events) >= threshold:
                self._trip(device, state, code)
                events.clear()

    def _expire(self, events: deque, now: float) -> None:
        while events and now - events[0] > self._window_s:
            events.popleft()

    def _trip(self, device: str, state: _Device, code: str) -> None:
        now = self._clock()
        if now < state.cooldown_until:
            return  # ya está en pausa: no se alarga ni se repite la alerta
        state.cooldown_until = now + self._cooldown_s
        self._audit.log(type="alert", code=code, device=device, cooldown_s=int(self._cooldown_s))
