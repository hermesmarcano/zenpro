"""Orquestación: del evento a las órdenes que recogerá el teléfono.

evento → filtros (tipo, app sensible, vacío, freno, presupuesto) → modelo → validación estricta →
política con estado (lista de apps, conversación del evento, límites por hora) → cola de órdenes.

Nada de lo que dice el modelo llega al teléfono sin pasar por `validate` y por `_policy`.
"""

from __future__ import annotations

import queue
import secrets
import threading
import time
from dataclasses import dataclass
from typing import Callable

from . import protected
from .audit import Audit
from .config import Config
from .hermes import HermesClient, HermesError
from .monitor import Monitor
from .prompt import new_boundary, system_prompt, user_message
from .protocol import Event
from .store import Store
from .stt import Transcriber
from .validate import Candidate, parse_model_output

HOUR_S = 3600.0
DAY_S = 86400.0
# Una orden que el teléfono recoge tarde ya no tiene sentido: caduca en el servidor.
TTL_S = {"draft_reply": 1800.0, "notify": 1800.0, "block": 600.0, "pause": 600.0}


@dataclass(frozen=True)
class Job:
    device: str
    event: Event | None = None
    pcm: bytes | None = None
    segment_id: str = ""
    started_ms: int = 0


@dataclass(frozen=True)
class Submit:
    accepted: int = 0
    duplicates: int = 0
    overloaded: bool = False
    # El dispositivo superó su tope de eventos por hora: se le dice que espere (429), sin tocar nada.
    limited: bool = False


class Brain:
    def __init__(
        self,
        cfg: Config,
        store: Store,
        audit: Audit,
        hermes: HermesClient,
        transcriber: Transcriber,
        monitor: Monitor,
        *,
        start_workers: bool = True,
    ) -> None:
        self.cfg = cfg
        self.store = store
        self.audit = audit
        self.hermes = hermes
        self.transcriber = transcriber
        self.monitor = monitor
        self._system = system_prompt(cfg)
        self._events: queue.Queue[Job | None] = queue.Queue(maxsize=cfg.queue_max)
        self._audio: queue.Queue[Job | None] = queue.Queue(maxsize=cfg.audio_queue_max)
        self._threads: list[threading.Thread] = []
        if start_workers:
            for index in range(cfg.workers):
                self._spawn(self._event_worker, f"events-{index}")
            self._spawn(self._audio_worker, "audio-0")

    def _log(self, **fields: object) -> bool:
        """Auditar nunca debe matar un hilo: aunque `audit.log` fallara de forma inesperada, aquí solo devuelve False."""
        try:
            return bool(self.audit.log(**fields))
        except Exception:  # noqa: BLE001
            return False

    def _spawn(self, target: Callable[[], None], name: str) -> None:
        thread = threading.Thread(target=target, name=name, daemon=True)
        thread.start()
        self._threads.append(thread)

    # ---- entrada ----------------------------------------------------------------------------------------------

    def submit_events(self, device: str, events: list[Event]) -> Submit:
        if events and not self.store.try_consume(device, "events", self.cfg.max_events_per_hour, HOUR_S, cost=len(events)):
            return Submit(limited=True)  # el servidor lo registra (con tope por minuto)
        accepted = duplicates = 0
        for event in events:
            if not self.store.mark_seen(device, event.event_id):
                duplicates += 1
                continue
            try:
                self._events.put_nowait(Job(device, event=event))
            except queue.Full:
                self.store.unmark_seen(device, event.event_id)  # que el reintento del teléfono pueda entrar
                self._log(type="event", device=device, event_id=event.event_id, decision="overloaded")
                return Submit(accepted, duplicates, overloaded=True)
            accepted += 1
        return Submit(accepted, duplicates)

    def submit_audio(self, device: str, segment_id: str, started_ms: int, pcm: bytes) -> Submit:
        if not self.store.try_consume(device, "events", self.cfg.max_events_per_hour, HOUR_S):
            return Submit(limited=True)
        event_id = f"a-{segment_id}"
        if not self.store.mark_seen(device, event_id):
            return Submit(duplicates=1)
        try:
            self._audio.put_nowait(Job(device, pcm=pcm, segment_id=event_id, started_ms=started_ms))
        except queue.Full:
            self.store.unmark_seen(device, event_id)
            self._log(type="event", device=device, event_id=event_id, decision="overloaded")
            return Submit(overloaded=True)
        return Submit(accepted=1)

    # ---- hilos ----------------------------------------------------------------------------------------------------

    def _event_worker(self) -> None:
        while True:
            job = self._events.get()
            try:
                if job is None:
                    return
                assert job.event is not None
                self._process(job.device, job.event)
            except Exception as exc:  # un fallo en un evento no debe matar el hilo
                self._log(type="event", decision="internal_error", error=type(exc).__name__)
            finally:
                self._events.task_done()

    def _audio_worker(self) -> None:
        while True:
            job = self._audio.get()
            try:
                if job is None:
                    return
                self._transcribe(job)
            except Exception as exc:
                self._log(type="event", decision="internal_error", error=type(exc).__name__)
            finally:
                self._audio.task_done()

    def _transcribe(self, job: Job) -> None:
        text = ""
        if self.cfg.stt_enabled and job.pcm:
            try:
                text = self.transcriber.transcribe(job.pcm)
            except Exception as exc:  # si falla, el evento sigue sin texto: no bloquea la cola
                self._log(type="event", device=job.device, event_id=job.segment_id, decision="stt_error", error=type(exc).__name__)
        event = Event(job.segment_id, "transcript", "audio", "", text, job.started_ms, None)
        try:
            self._events.put(Job(job.device, event=event), timeout=30)
        except queue.Full:
            self._log(type="event", device=job.device, event_id=job.segment_id, decision="overloaded")

    def drain(self, timeout_s: float = 10.0) -> None:
        """Espera a que no quede trabajo en curso (pruebas y apagado ordenado)."""
        deadline = time.monotonic() + timeout_s
        for q in (self._audio, self._events):
            while q.unfinished_tasks and time.monotonic() < deadline:
                time.sleep(0.01)

    def stop(self) -> None:
        for _ in self._threads:
            for q in (self._events, self._audio):
                try:
                    q.put_nowait(None)
                except queue.Full:
                    pass
        for thread in self._threads:
            thread.join(timeout=5)

    # ---- proceso de un evento ---------------------------------------------------------------------------------

    def _process(self, device: str, event: Event) -> None:
        base = {"type": "event", "device": device, "event_id": event.event_id, "kind": event.kind, "app": event.app, "text_len": len(event.text)}

        def done(decision: str, **extra: object) -> None:
            self._log(**base, decision=decision, **extra)

        if event.kind not in self.cfg.model_kinds:
            return done("skipped_kind")
        if protected.is_sensitive(event.app):
            return done("skipped_sensitive_app")
        if not event.text:
            return done("skipped_empty")
        if self.monitor.in_cooldown(device):
            return done("cooldown")
        failed = self.store.try_consume_all(
            device, [("call_hour", self.cfg.max_calls_per_hour, HOUR_S, 1), ("call_day", self.cfg.max_calls_per_day, DAY_S, 1)]
        )
        if failed is not None:  # todo o nada: un tope que falla no gasta el otro
            return done("rate_limited" if failed == "call_hour" else "budget_exceeded")

        session = f"zen-{device}" if self.cfg.session_mode == "device" else f"zen-{device}-{event.event_id[:48]}"
        try:
            reply = self.hermes.complete(
                self._system,
                user_message(event, new_boundary()),
                idempotency_key=f"zen-{device}-{event.event_id}",
                session_id=session,
            )
        except HermesError as exc:
            if exc.code == "auth":
                self._log(type="alert", code="hermes_auth")
            return done("model_error", reason=exc.code)

        output = parse_model_output(reply.content, self.cfg)
        self.monitor.record_model_result(device, output.valid_json)
        emitted, rejected = self._policy(device, event, output.commands)
        rejected = output.rejected + rejected
        self.monitor.record_rejections(device, rejected)
        # Primero el rastro y después el efecto: si no se puede auditar, no se emite nada (nada sin huella).
        logged = self._log(
            **base,
            decision="processed" if output.valid_json else "model_invalid",
            commands_emitted=[wire["type"] for wire, _ in emitted],
            rejected=rejected,
            model_ms=reply.elapsed_ms,
            tokens=reply.total_tokens,
        )
        if not logged:
            return
        for wire, ttl in emitted:
            try:
                self.store.add_command(device, wire, ttl)
            except Exception as exc:  # noqa: BLE001
                self._log(**base, decision="enqueue_failed", error=type(exc).__name__)
                return
            if wire["type"] == "block":
                self.monitor.record_block(device)
            elif wire["type"] == "pause":
                self.monitor.record_pause(device)

    def _policy(self, device: str, event: Event, candidates: list[Candidate]) -> tuple[list[tuple[dict, float]], list[str]]:
        """Reglas que dependen del estado. Devuelve las órdenes ya en el formato del teléfono."""
        cfg, store = self.cfg, self.store
        emitted: list[tuple[dict, float]] = []
        rejected: list[str] = []
        for c in candidates:
            if c.type == "draft_reply":
                # El destino lo fija el adaptador (la conversación del propio evento), nunca el modelo.
                if event.kind != "notification" or not event.conv:
                    rejected.append("no_conversation")
                    continue
                if not store.try_consume(device, "draft", cfg.max_drafts_per_hour, HOUR_S):
                    rejected.append("rate_limited")
                    continue
                wire = {"type": "draft_reply", "conversation": event.conv, "text": c.text}
            elif c.type == "notify":
                if not store.try_consume(device, "notify", cfg.max_notifies_per_hour, HOUR_S):
                    rejected.append("rate_limited")
                    continue
                wire = {"type": "notify", "title": "ZEN", "text": c.text}  # el título no lo elige el modelo
            elif c.type == "block":
                if c.app not in cfg.block_allowlist:
                    rejected.append("app_not_allowed")
                    continue
                if protected.is_never_block(c.app) or protected.is_sensitive(c.app):
                    rejected.append("app_protected")
                    continue
                # Número por hora Y minutos acumulados por día: bloqueos encadenados no se convierten en un encierro.
                failed = store.try_consume_all(
                    device, [("block", cfg.max_blocks_per_hour, HOUR_S, 1), ("block_minutes", cfg.block_minutes_per_day, DAY_S, c.minutes)]
                )
                if failed is not None:
                    rejected.append("rate_limited" if failed == "block" else "budget")
                    continue
                wire = {"type": "block", "package": c.app, "minutes": c.minutes}
            else:  # pause
                failed = store.try_consume_all(
                    device, [("pause", cfg.max_pauses_per_hour, HOUR_S, 1), ("pause_minutes", cfg.pause_minutes_per_day, DAY_S, c.minutes)]
                )
                if failed is not None:
                    rejected.append("rate_limited" if failed == "pause" else "budget")
                    continue
                wire = {"type": "pause", "minutes": c.minutes}
            wire["id"] = secrets.token_hex(12)
            emitted.append((wire, TTL_S[c.type]))
        return emitted, rejected
