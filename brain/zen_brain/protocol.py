"""El protocolo del teléfono (docs/XIAOMI-LAYER.md) traducido al evento interno del adaptador.

El teléfono solo manda eventos y audio; nunca órdenes. Lo que no se entiende se descarta y se cuenta, pero no
se rechaza el lote (un 4xx haría que el teléfono reintente y acabe tirando eventos buenos).
"""

from __future__ import annotations

import json
import re
from dataclasses import dataclass, field
from typing import Mapping

from . import config
from .sanitize import clean_line, clean_text

MAX_TEXT = 4_000
MAX_EVENTS_PER_BATCH = 100
ID_RE = re.compile(r"^[A-Za-z0-9._:\-]{1,128}$")
# El teléfono manda `kind` en minúsculas. Otros tipos (usage, system, override, audio) no se envían al modelo.
PHONE_KINDS = {"notification": "notification", "screen": "screen"}


class ProtocolError(Exception):
    def __init__(self, status: int, code: str) -> None:
        super().__init__(code)
        self.status = status
        self.code = code


@dataclass(frozen=True)
class Event:
    event_id: str
    kind: str  # notification | screen | transcript
    app: str
    sender: str
    text: str
    ts_ms: int
    # Clave opaca de la conversación (solo para notificaciones contestables). El modelo nunca la ve:
    # el adaptador la adjunta a un borrador para que solo se pueda responder a ESA conversación.
    conv: str | None = None


@dataclass
class Parsed:
    events: list[Event] = field(default_factory=list)
    dropped: dict[str, int] = field(default_factory=dict)

    def drop(self, reason: str) -> None:
        self.dropped[reason] = self.dropped.get(reason, 0) + 1


def _bounded_ts(value: object, now_ms: int) -> int:
    if isinstance(value, int) and not isinstance(value, bool) and abs(value - now_ms) <= 7 * 24 * 3600 * 1000:
        return value
    return now_ms


def parse_events(raw: bytes, now_ms: int) -> Parsed:
    try:
        body = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, ValueError, RecursionError):
        raise ProtocolError(400, "bad_json") from None
    if not isinstance(body, dict) or not isinstance(body.get("events"), list):
        raise ProtocolError(400, "bad_shape")
    items = body["events"]
    if len(items) > MAX_EVENTS_PER_BATCH:
        raise ProtocolError(413, "too_many_events")

    parsed = Parsed()
    for item in items:
        if not isinstance(item, dict):
            parsed.drop("invalid")
            continue
        event_id = item.get("id")
        if not isinstance(event_id, str) or not ID_RE.fullmatch(event_id):
            parsed.drop("invalid")
            continue
        kind = PHONE_KINDS.get(item.get("kind")) if isinstance(item.get("kind"), str) else None
        if kind is None:
            parsed.drop("kind")
            continue
        app = item.get("package")
        if not isinstance(app, str) or not config.PACKAGE_RE.fullmatch(app) or len(app) > 255:
            parsed.drop("invalid")
            continue
        conv = None
        extra = item.get("extra")
        if kind == "notification" and isinstance(extra, dict) and isinstance(extra.get("conv"), str):
            candidate = extra["conv"]
            # Solo la longitud y que no traiga controles: es una clave opaca que se devuelve tal cual al teléfono.
            if 0 < len(candidate) <= 512 and candidate.isprintable():
                conv = candidate
        parsed.events.append(
            Event(
                event_id=event_id,
                kind=kind,
                app=app,
                sender=clean_line(item.get("title"), 100),
                text=clean_text(item.get("text"), MAX_TEXT),
                ts_ms=_bounded_ts(item.get("at"), now_ms),
                conv=conv,
            )
        )
    return parsed


@dataclass(frozen=True)
class AudioMeta:
    segment_id: str
    started_ms: int


def parse_audio_headers(headers: Mapping[str, str], now_ms: int) -> AudioMeta:
    segment = headers.get("X-Zen-Segment-Id", "")
    if not ID_RE.fullmatch(segment):
        raise ProtocolError(400, "bad_segment_id")
    started = headers.get("X-Zen-Started-At", "")
    started_ms = int(started) if started.isascii() and started.isdigit() and len(started) <= 15 else now_ms
    return AudioMeta(segment, _bounded_ts(started_ms, now_ms))
