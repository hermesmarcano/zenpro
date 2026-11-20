"""La barrera real: lo que dice el modelo se trata como NO fiable y se valida con un esquema estricto.

Esto solo comprueba la forma y los rangos de cada orden. La política que depende del estado (lista de apps
permitidas, conversación del evento, límites por hora) se aplica después, en brain.py.
"""

from __future__ import annotations

import json
import re
import unicodedata
from dataclasses import dataclass, field

from . import config
from .sanitize import clean_line, clean_text

MAX_CONTENT_CHARS = 64_000
MAX_COMMANDS = 3
DRAFT_MAX_CHARS = 500
NOTIFY_MAX_CHARS = 200

_ALLOWED_KEYS = {
    "draft_reply": {"type", "text"},
    "notify": {"type", "text"},
    "block": {"type", "app", "minutes"},
    "pause": {"type", "minutes"},
}
# Sin enlaces en los avisos: esquemas, www., cualquier «algo.tld», direcciones IP y trucos de ofuscación (`evil[.]com`).
# Es una red amplia a propósito: un aviso rechazado por «p.ej.» cuesta poco; uno con un enlace malo, mucho.
_URL = re.compile(
    r"(?i)([a-z][a-z0-9+.\-]*://|\bwww\.|\b(mailto|tel|sms|intent|javascript|data|file):|"
    r"\b\d{1,3}(\.\d{1,3}){3}\b|[\w\-]+(\.[\w\-]+)*\.[^\W\d_]{2,}\b)"
)
_DEFANG = re.compile(r"(?i)[\[\(\{]\s*(\.|dot|punto)\s*[\]\)\}]")


def has_link(text: str) -> bool:
    """¿Parece llevar un enlace? Se normaliza antes (NFKC: «．» de ancho completo → «.»; «[.]» → «.»)."""
    flat = _DEFANG.sub(".", unicodedata.normalize("NFKC", text).replace("\u3002", "."))
    return _URL.search(flat) is not None


@dataclass(frozen=True)
class Candidate:
    """Orden con la forma correcta; todavía sin la política que depende del estado."""

    type: str
    text: str = ""
    app: str = ""
    minutes: int = 0


@dataclass
class ModelOutput:
    valid_json: bool
    commands: list[Candidate] = field(default_factory=list)
    # Códigos fijos (nunca texto del modelo) de por qué se descartó algo.
    rejected: list[str] = field(default_factory=list)


class _DuplicateKey(ValueError):
    pass


def _no_duplicates(pairs: list[tuple[str, object]]) -> dict:
    result: dict = {}
    for key, value in pairs:
        if key in result:
            raise _DuplicateKey(key)
        result[key] = value
    return result


def _reject_constant(_: str) -> object:
    raise ValueError("constante no permitida")


def parse_model_output(content: str, cfg: config.Config) -> ModelOutput:
    if not isinstance(content, str) or len(content) > MAX_CONTENT_CHARS:
        return ModelOutput(False, rejected=["not_json"])
    try:
        data = json.loads(content, object_pairs_hook=_no_duplicates, parse_constant=_reject_constant)
    except (ValueError, RecursionError):
        return ModelOutput(False, rejected=["not_json"])
    if not isinstance(data, dict) or not isinstance(data.get("commands"), list):
        return ModelOutput(False, rejected=["bad_shape"])

    out = ModelOutput(True)
    items = data["commands"]
    if len(items) > MAX_COMMANDS:
        out.rejected.extend(["too_many"] * (len(items) - MAX_COMMANDS))
        items = items[:MAX_COMMANDS]
    for item in items:
        candidate, reason = _check_command(item, cfg)
        if candidate is None:
            out.rejected.append(reason)
        else:
            out.commands.append(candidate)
    return out


def _int_minutes(value: object, high: int) -> int | None:
    if isinstance(value, bool):
        return None
    if not isinstance(value, int):  # los decimales (5.0, 1e2) no son «enteros»: se rechazan
        return None
    return max(1, min(high, value))  # fuera de rango → se acota, no se rechaza


def _check_command(item: object, cfg: config.Config) -> tuple[Candidate | None, str]:
    if not isinstance(item, dict):
        return None, "bad_command"
    kind = item.get("type")
    if not isinstance(kind, str) or kind not in _ALLOWED_KEYS:
        return None, "unknown_type"
    if set(item) != _ALLOWED_KEYS[kind]:
        return None, "bad_schema"

    if kind == "draft_reply":
        text = clean_text(item["text"], DRAFT_MAX_CHARS)
        return (Candidate(kind, text=text), "") if text else (None, "empty_text")
    if kind == "notify":
        raw = item["text"]
        text = clean_line(raw, NOTIFY_MAX_CHARS)
        if not text:
            return None, "empty_text"
        if has_link(text):
            return None, "url_in_notify"
        return Candidate(kind, text=text), ""
    if kind == "block":
        app = item["app"]
        if not isinstance(app, str) or len(app) > 255 or not config.PACKAGE_RE.fullmatch(app):
            return None, "bad_app"
        minutes = _int_minutes(item["minutes"], cfg.block_max_minutes)
        return (Candidate(kind, app=app, minutes=minutes), "") if minutes is not None else (None, "bad_minutes")
    minutes = _int_minutes(item["minutes"], cfg.pause_max_minutes)  # pause
    return (Candidate(kind, minutes=minutes), "") if minutes is not None else (None, "bad_minutes")
