"""Línea de órdenes: serve | tokens | stats | check | fetch-model."""

from __future__ import annotations

import argparse
import json
import os
import signal
import stat
import sys
import threading
import time
from datetime import datetime, timedelta, timezone
from pathlib import Path
from typing import Mapping, Sequence

from . import __version__
from .audit import Audit
from .brain import Brain
from .config import Config, ConfigError, from_env
from .hermes import HermesClient
from .monitor import Monitor
from .server import App, make_server
from .store import Store
from .stt import NullTranscriber, Transcriber, WhisperTranscriber
from .tokens import TokenStore


def check_private(path: Path, *, directory: bool = False) -> str | None:
    """Mensaje de error si `path` existe y no es solo del usuario actual (sin permisos para grupo/otros)."""
    try:
        st = path.stat()
    except FileNotFoundError:
        return None
    if st.st_uid != os.geteuid():
        return f"{path}: debe ser del usuario que ejecuta el adaptador"
    if st.st_mode & (stat.S_IRWXG | stat.S_IRWXO):
        return f"{path}: permisos demasiado abiertos (usa chmod {'700' if directory else '600'})"
    return None


def check_not_writable_by_others(directory: Path) -> str | None:
    """Un directorio donde otros pueden escribir permitiría cambiar o reemplazar los archivos que hay dentro."""
    try:
        mode = directory.stat().st_mode
    except FileNotFoundError:
        return None
    if mode & (stat.S_IWGRP | stat.S_IWOTH):
        return f"{directory}: otros usuarios pueden escribir (usa chmod go-w)"
    return None


def permission_problems(cfg: Config, env: Mapping[str, str]) -> list[str]:
    checks = [(cfg.data_dir, True), (cfg.audit_path, False), (cfg.tokens_file, False)]
    if env.get("ZEN_ENV_FILE"):
        checks.append((Path(env["ZEN_ENV_FILE"]), False))
    problems = [problem for path, is_dir in checks if (problem := check_private(path, directory=is_dir))]
    for parent in sorted({cfg.audit_path.parent, cfg.tokens_file.parent}):
        if problem := check_not_writable_by_others(parent):
            problems.append(problem)
    return problems


def tokens_path(env: Mapping[str, str]) -> Path:
    """Administrar tokens solo necesita saber dónde está el archivo: una configuración rota no debe impedir revocar."""
    return Path(env.get("ZEN_TOKENS_FILE") or Config().tokens_file)


def build(cfg: Config, transcriber: Transcriber | None = None) -> App:
    cfg.data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
    audit = Audit(cfg.audit_path)
    store = Store(cfg.data_dir / "state.db")
    hermes = HermesClient(cfg.hermes_url, cfg.hermes_key, cfg.hermes_model, cfg.hermes_timeout_s)
    if transcriber is None:
        transcriber = WhisperTranscriber(cfg.whisper_model, cfg.whisper_language) if cfg.stt_enabled else NullTranscriber()
    brain = Brain(cfg, store, audit, hermes, transcriber, Monitor(audit))
    return App(cfg, TokenStore(cfg.tokens_file), brain, store, audit)


def cmd_serve(env: Mapping[str, str]) -> int:
    cfg = from_env(env)
    problems = permission_problems(cfg, env)
    if problems:
        print("No arranco: " + "; ".join(problems), file=sys.stderr)
        return 2
    app = build(cfg)
    server = make_server(app)
    stop = threading.Event()

    def maintenance() -> None:
        ticks = 0
        tokens_alerted = False
        while not stop.wait(60):
            try:  # un fallo aquí no debe matar el hilo (sin él no habría purga ni avisos)
                app.flush_auth_failures()
                if app.tokens.broken and not tokens_alerted:
                    app.audit.log(type="alert", code="tokens_broken")  # tokens.json ilegible: nadie puede entrar
                tokens_alerted = app.tokens.broken
                ticks += 1
                if ticks % 10 == 0:
                    app.store.purge()
            except Exception as exc:  # noqa: BLE001
                print(f"zen-brain: fallo de mantenimiento ({type(exc).__name__})", file=sys.stderr, flush=True)

    threading.Thread(target=maintenance, name="maintenance", daemon=True).start()
    signal.signal(signal.SIGTERM, lambda *_: threading.Thread(target=server.shutdown, daemon=True).start())
    print(f"zen-brain {__version__} escuchando en 127.0.0.1:{cfg.port} (solo loopback)", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        stop.set()
        server.server_close()
        app.brain.drain(10)
        app.brain.stop()
        app.flush_auth_failures()
        app.audit.close()
        app.store.close()
    return 0


def cmd_tokens(env: Mapping[str, str], args: argparse.Namespace) -> int:
    store = TokenStore(tokens_path(env))
    try:
        if args.action in ("add", "rotate"):
            token = store.add(args.label, replace=args.action == "rotate")
            print(f"Token de «{args.label}» (se muestra UNA vez; pégalo en la app y no lo guardes en ningún otro sitio):\n{token}")
        elif args.action == "revoke":
            print("Revocado." if store.revoke(args.label) else "Ese dispositivo no existe.")
        else:
            for label in store.labels():
                print(label)
    except ValueError as exc:
        print(f"Error: {exc}", file=sys.stderr)
        return 1
    return 0


def cmd_stats(env: Mapping[str, str], hours: int) -> int:
    cfg = from_env(env, require_hermes_key=False)
    since = datetime.now(timezone.utc) - timedelta(hours=hours)
    decisions: dict[str, int] = {}
    latencies: list[int] = []
    tokens = 0
    rejected = emitted = alerts = 0
    try:
        lines = cfg.audit_path.read_text(encoding="utf-8").splitlines()
    except FileNotFoundError:
        lines = []
    for line in lines:
        try:
            entry = json.loads(line)
            when = datetime.fromisoformat(entry["ts"])
        except (ValueError, KeyError):
            continue
        if when < since:
            continue
        if entry.get("type") == "alert":
            alerts += 1
        if entry.get("type") != "event":
            continue
        decision = str(entry.get("decision"))
        decisions[decision] = decisions.get(decision, 0) + 1
        if isinstance(entry.get("model_ms"), int):
            latencies.append(entry["model_ms"])
        tokens += entry["tokens"] if isinstance(entry.get("tokens"), int) else 0
        emitted += len(entry.get("commands_emitted") or [])
        rejected += len(entry.get("rejected") or [])
    latencies.sort()
    calls = decisions.get("processed", 0) + decisions.get("model_invalid", 0)
    pct = lambda p: latencies[min(len(latencies) - 1, int(len(latencies) * p))] if latencies else None  # noqa: E731
    print(json.dumps({
        "horas": hours,
        "eventos_por_minuto": round(sum(decisions.values()) / (hours * 60), 3),
        "decisiones": decisions,
        "latencia_ms": {"p50": pct(0.5), "p95": pct(0.95)},
        "respuestas_no_json_pct": round(100 * decisions.get("model_invalid", 0) / calls, 1) if calls else 0.0,
        "ordenes_emitidas": emitted,
        "ordenes_rechazadas": rejected,
        "ordenes_rechazadas_pct": round(100 * rejected / (rejected + emitted), 1) if rejected + emitted else 0.0,
        "tokens": tokens,
        "coste_estimado": round(tokens / 1000 * cfg.cost_per_1k_tokens, 4),
        "alertas": alerts,
    }, ensure_ascii=False, indent=2))
    return 0


def cmd_check(env: Mapping[str, str]) -> int:
    try:
        cfg = from_env(env)
    except ConfigError as exc:
        print(f"Configuración inválida: {exc}", file=sys.stderr)
        return 2
    problems = permission_problems(cfg, env)
    for problem in problems:
        print(f"PERMISOS: {problem}", file=sys.stderr)
    tokens = TokenStore(cfg.tokens_file)
    if not tokens.labels_safe():
        print("AVISO: no hay ningún token de teléfono (usa `tokens add <nombre>`).", file=sys.stderr)
    print("Configuración correcta." if not problems else "Hay problemas de permisos.")
    return 0 if not problems else 2


def cmd_fetch_model(env: Mapping[str, str]) -> int:
    cfg = from_env(env, require_hermes_key=False)
    WhisperTranscriber(cfg.whisper_model, cfg.whisper_language)._load()
    print(f"Modelo «{cfg.whisper_model}» descargado.")
    return 0


def main(argv: Sequence[str] | None = None, env: Mapping[str, str] | None = None) -> int:
    env = os.environ if env is None else env
    parser = argparse.ArgumentParser(prog="zen_brain")
    sub = parser.add_subparsers(dest="cmd", required=True)
    sub.add_parser("serve")
    sub.add_parser("check")
    sub.add_parser("fetch-model")
    stats = sub.add_parser("stats")
    stats.add_argument("--hours", type=int, default=24)
    tokens = sub.add_parser("tokens")
    tokens.add_argument("action", choices=["add", "rotate", "revoke", "list"])
    tokens.add_argument("label", nargs="?", default="")
    args = parser.parse_args(argv)
    try:
        if args.cmd == "serve":
            return cmd_serve(env)
        if args.cmd == "tokens":
            if args.action != "list" and not args.label:
                parser.error("falta el nombre del dispositivo")
            return cmd_tokens(env, args)
        if args.cmd == "stats":
            return cmd_stats(env, max(1, args.hours))
        if args.cmd == "check":
            return cmd_check(env)
        return cmd_fetch_model(env)
    except ConfigError as exc:
        print(f"Configuración inválida: {exc}", file=sys.stderr)
        return 2
