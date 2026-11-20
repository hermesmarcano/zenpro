"""Configuración, siempre por entorno (en producción systemd carga /opt/zen-brain/.env, permisos 600)."""

from __future__ import annotations

import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping
from urllib.parse import urlsplit
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from . import protected

PACKAGE_RE = re.compile(r"^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
LABEL_RE = re.compile(r"^[a-z0-9][a-z0-9_-]{0,31}$")
LOOPBACK_HOSTS = frozenset({"127.0.0.1", "localhost", "::1"})
MIN_SECRET_CHARS = 32
KINDS = frozenset({"notification", "screen", "transcript"})


class ConfigError(ValueError):
    """Configuración inválida: el adaptador no arranca (mejor que arrancar a medias)."""


@dataclass(frozen=True)
class Config:
    # Siempre loopback: el acceso desde el teléfono lo da `tailscale serve` (TLS) o un proxy local.
    port: int = 8787
    hermes_url: str = "http://127.0.0.1:8642/v1/chat/completions"
    hermes_key: str = field(default="", repr=False)  # un secreto no debe salir en un repr/log por accidente
    hermes_model: str = "zen-brain"
    hermes_timeout_s: float = 60.0

    data_dir: Path = Path("/opt/zen-brain/data")
    audit_path: Path = Path("/opt/zen-brain/audit/audit.jsonl")
    tokens_file: Path = Path("/opt/zen-brain/tokens.json")

    # Apps que el modelo puede pedir bloquear. Vacía = el cerebro no bloquea nada (valor seguro por defecto).
    block_allowlist: frozenset[str] = frozenset()
    block_max_minutes: int = 240
    pause_max_minutes: int = 120
    # Propuestas de evento para el calendario (la persona las confirma en su calendario; nunca se agenda solo).
    calendar: bool = True
    timezone: str = "UTC"  # la hora LOCAL de la persona: el modelo da «mañana a las 9» y el adaptador lo convierte
    max_calendar_per_hour: int = 6
    # Tope acumulado en 24 h: aunque las órdenes se repitan, no se encadenan bloqueos o pausas sin fin.
    block_minutes_per_day: int = 240
    pause_minutes_per_day: int = 120

    # Qué eventos llegan al modelo. «screen» va apagado: es mucho texto, cuesta y expone más.
    model_kinds: frozenset[str] = frozenset({"notification", "transcript"})
    max_calls_per_hour: int = 60
    max_calls_per_day: int = 400
    max_blocks_per_hour: int = 3
    max_notifies_per_hour: int = 6
    max_drafts_per_hour: int = 20
    max_pauses_per_hour: int = 2
    # Eventos que se aceptan por hora y dispositivo. Más es una avalancha (o un teléfono roto): 429.
    max_events_per_hour: int = 2000

    workers: int = 2
    queue_max: int = 200
    audio_queue_max: int = 8
    # «event»: sesión nueva por evento (un mensaje malicioso no contamina los siguientes). «device»: un hilo por teléfono.
    session_mode: str = "event"

    stt_enabled: bool = True
    whisper_model: str = "small"
    whisper_language: str = "es"
    cost_per_1k_tokens: float = 0.0


def _int(env: Mapping[str, str], name: str, default: int, low: int, high: int) -> int:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = int(raw.strip())
    except ValueError:
        raise ConfigError(f"{name} debe ser un entero") from None
    if not low <= value <= high:
        raise ConfigError(f"{name} debe estar entre {low} y {high}")
    return value


def _float(env: Mapping[str, str], name: str, default: float, low: float, high: float) -> float:
    raw = env.get(name)
    if raw is None or raw.strip() == "":
        return default
    try:
        value = float(raw.strip())
    except ValueError:
        raise ConfigError(f"{name} debe ser un número") from None
    if not low <= value <= high:
        raise ConfigError(f"{name} debe estar entre {low} y {high}")
    return value


def _csv(raw: str | None) -> list[str]:
    return [part.strip() for part in (raw or "").split(",") if part.strip()]


def check_hermes_url(url: str) -> None:
    parts = urlsplit(url)
    if parts.scheme not in ("http", "https") or not parts.hostname:
        raise ConfigError("ZEN_HERMES_URL no es una URL válida")
    if parts.hostname.lower() not in LOOPBACK_HOSTS:
        raise ConfigError("ZEN_HERMES_URL debe apuntar a loopback: la API de Hermes no se expone a la red")
    if parts.username or parts.password:
        raise ConfigError("ZEN_HERMES_URL no debe llevar usuario ni contraseña")


def from_env(env: Mapping[str, str], *, require_hermes_key: bool = True) -> Config:
    base = Config()
    url = (env.get("ZEN_HERMES_URL") or base.hermes_url).strip()
    check_hermes_url(url)

    key = (env.get("ZEN_HERMES_API_KEY") or "").strip()
    if require_hermes_key and len(key) < MIN_SECRET_CHARS:
        raise ConfigError(f"ZEN_HERMES_API_KEY falta o es demasiado corta (mínimo {MIN_SECRET_CHARS} caracteres)")

    if (env.get("ZEN_PHONE_TOKEN") or "").strip():
        # Un token en el entorno no se puede revocar ni rotar sin reiniciar: ya no se admite.
        raise ConfigError("ZEN_PHONE_TOKEN ya no se admite: crea el token con `python -m zen_brain tokens add <nombre>`")

    allowlist = _csv(env.get("ZEN_BLOCK_ALLOWLIST"))
    for pkg in allowlist:
        if not PACKAGE_RE.match(pkg) or len(pkg) > 255:
            raise ConfigError(f"ZEN_BLOCK_ALLOWLIST: «{pkg}» no es un nombre de paquete")
        if protected.is_never_block(pkg) or protected.is_sensitive(pkg):
            raise ConfigError(f"ZEN_BLOCK_ALLOWLIST: «{pkg}» es una app protegida y nunca se bloquea")

    kinds = frozenset(_csv(env.get("ZEN_MODEL_KINDS"))) or base.model_kinds
    if not kinds <= KINDS:
        raise ConfigError("ZEN_MODEL_KINDS solo admite notification, screen y transcript")

    mode = (env.get("ZEN_SESSION_MODE") or base.session_mode).strip()
    if mode not in ("event", "device"):
        raise ConfigError("ZEN_SESSION_MODE debe ser event o device")

    calendar = (env.get("ZEN_CALENDAR") or "on").strip().lower()
    if calendar not in ("on", "off"):
        raise ConfigError("ZEN_CALENDAR debe ser on u off")
    tz = (env.get("ZEN_TIMEZONE") or base.timezone).strip()
    try:
        ZoneInfo(tz)
    except (ZoneInfoNotFoundError, ValueError):
        raise ConfigError("ZEN_TIMEZONE no es una zona horaria conocida (p. ej. America/Sao_Paulo)") from None

    stt = (env.get("ZEN_STT") or "whisper").strip().lower()
    if stt not in ("whisper", "off"):
        raise ConfigError("ZEN_STT debe ser whisper u off")

    data_dir = Path(env.get("ZEN_DATA_DIR") or base.data_dir)
    return Config(
        port=_int(env, "ZEN_PORT", base.port, 1024, 65535),
        hermes_url=url,
        hermes_key=key,
        hermes_model=(env.get("ZEN_HERMES_MODEL") or base.hermes_model).strip(),
        hermes_timeout_s=_float(env, "ZEN_HERMES_TIMEOUT", base.hermes_timeout_s, 1.0, 120.0),
        data_dir=data_dir,
        audit_path=Path(env.get("ZEN_AUDIT_PATH") or base.audit_path),
        tokens_file=Path(env.get("ZEN_TOKENS_FILE") or base.tokens_file),
        block_allowlist=frozenset(allowlist),
        block_max_minutes=_int(env, "ZEN_BLOCK_MAX_MINUTES", base.block_max_minutes, 1, 1440),
        pause_max_minutes=_int(env, "ZEN_PAUSE_MAX_MINUTES", base.pause_max_minutes, 1, 1440),
        calendar=calendar == "on",
        timezone=tz,
        max_calendar_per_hour=_int(env, "ZEN_MAX_CALENDAR_PER_HOUR", base.max_calendar_per_hour, 0, 50),
        block_minutes_per_day=_int(env, "ZEN_BLOCK_MINUTES_PER_DAY", base.block_minutes_per_day, 1, 1440),
        pause_minutes_per_day=_int(env, "ZEN_PAUSE_MINUTES_PER_DAY", base.pause_minutes_per_day, 1, 1440),
        model_kinds=kinds,
        max_calls_per_hour=_int(env, "ZEN_MAX_CALLS_PER_HOUR", base.max_calls_per_hour, 1, 10_000),
        max_calls_per_day=_int(env, "ZEN_MAX_CALLS_PER_DAY", base.max_calls_per_day, 1, 100_000),
        max_blocks_per_hour=_int(env, "ZEN_MAX_BLOCKS_PER_HOUR", base.max_blocks_per_hour, 0, 100),
        max_notifies_per_hour=_int(env, "ZEN_MAX_NOTIFIES_PER_HOUR", base.max_notifies_per_hour, 0, 100),
        max_drafts_per_hour=_int(env, "ZEN_MAX_DRAFTS_PER_HOUR", base.max_drafts_per_hour, 0, 1000),
        max_pauses_per_hour=_int(env, "ZEN_MAX_PAUSES_PER_HOUR", base.max_pauses_per_hour, 0, 100),
        max_events_per_hour=_int(env, "ZEN_MAX_EVENTS_PER_HOUR", base.max_events_per_hour, 10, 100_000),
        workers=_int(env, "ZEN_WORKERS", base.workers, 1, 8),
        queue_max=_int(env, "ZEN_QUEUE_MAX", base.queue_max, 1, 5000),
        audio_queue_max=_int(env, "ZEN_AUDIO_QUEUE_MAX", base.audio_queue_max, 1, 64),
        session_mode=mode,
        stt_enabled=stt == "whisper",
        whisper_model=(env.get("ZEN_WHISPER_MODEL") or base.whisper_model).strip(),
        whisper_language=(env.get("ZEN_WHISPER_LANGUAGE") or base.whisper_language).strip(),
        cost_per_1k_tokens=_float(env, "ZEN_COST_PER_1K_TOKENS", base.cost_per_1k_tokens, 0.0, 1000.0),
    )
