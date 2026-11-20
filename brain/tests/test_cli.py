import contextlib
import io
import json
import os
import shutil
import stat
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from zen_brain import cli
from pathlib import Path

from zen_brain.config import Config, ConfigError, from_env
from zen_brain.tokens import TokenStore

KEY = "h" * 40


def run(argv, env):
    out, err = io.StringIO(), io.StringIO()
    with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
        code = cli.main(argv, env)
    return code, out.getvalue(), err.getvalue()


class ConfigTest(unittest.TestCase):
    def test_defaults_are_the_safe_ones(self):
        cfg = from_env({"ZEN_HERMES_API_KEY": KEY})
        self.assertEqual(cfg.block_allowlist, frozenset())
        self.assertEqual(cfg.model_kinds, frozenset({"notification", "transcript"}))
        self.assertEqual((cfg.session_mode, cfg.hermes_model, cfg.port), ("event", "zen-brain", 8787))
        self.assertTrue(cfg.hermes_url.startswith("http://127.0.0.1:"))

    def test_bad_values_stop_the_start(self):
        bad = [
            {},  # sin clave de Hermes
            {"ZEN_HERMES_API_KEY": "corta"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_PHONE_TOKEN": "corto"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_PHONE_TOKEN": "a" * 40},  # ya no se admite ningún token por entorno
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_HERMES_URL": "http://10.0.0.5:8642/v1/chat/completions"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_ALLOWLIST": "com.android.settings"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_ALLOWLIST": "com.mibanco.app"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_ALLOWLIST": "com.hermesmarcano.zen.xiaomi"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_ALLOWLIST": "no es un paquete"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_MODEL_KINDS": "notification,usage"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_SESSION_MODE": "siempre"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_MAX_MINUTES": "9999"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_PORT": "80"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_MAX_CALLS_PER_HOUR": "mucho"},
            {"ZEN_HERMES_API_KEY": KEY, "ZEN_STT": "nube"},
        ]
        for env in bad:
            with self.assertRaises(ConfigError, msg=str(env)):
                from_env(env)

    def test_timezone_and_calendar_settings(self):
        cfg = from_env({"ZEN_HERMES_API_KEY": KEY})
        self.assertEqual((cfg.timezone, cfg.calendar, cfg.max_calendar_per_hour), ("UTC", True, 6))
        cfg = from_env({"ZEN_HERMES_API_KEY": KEY, "ZEN_TIMEZONE": "America/Sao_Paulo", "ZEN_CALENDAR": "off", "ZEN_MAX_CALENDAR_PER_HOUR": "2"})
        self.assertEqual((cfg.timezone, cfg.calendar, cfg.max_calendar_per_hour), ("America/Sao_Paulo", False, 2))
        for bad in ({"ZEN_TIMEZONE": "Marte/Olimpo"}, {"ZEN_TIMEZONE": "../../etc/passwd"}, {"ZEN_CALENDAR": "quizás"}, {"ZEN_MAX_CALENDAR_PER_HOUR": "99"}):
            with self.assertRaises(ConfigError, msg=str(bad)):
                from_env({"ZEN_HERMES_API_KEY": KEY, **bad})

    def test_a_valid_override(self):
        cfg = from_env({"ZEN_HERMES_API_KEY": KEY, "ZEN_BLOCK_ALLOWLIST": "com.zhiliaoapp.musically, com.instagram.android",
                        "ZEN_MODEL_KINDS": "notification,screen", "ZEN_STT": "off", "ZEN_PORT": "9000"})
        self.assertEqual(cfg.block_allowlist, frozenset({"com.zhiliaoapp.musically", "com.instagram.android"}))
        self.assertEqual((cfg.stt_enabled, cfg.port, "screen" in cfg.model_kinds), (False, 9000, True))


class CliTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-cli-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.env = {
            "ZEN_HERMES_API_KEY": KEY, "ZEN_DATA_DIR": str(self.tmp / "data"), "ZEN_AUDIT_PATH": str(self.tmp / "audit.jsonl"),
            "ZEN_TOKENS_FILE": str(self.tmp / "tokens.json"), "ZEN_COST_PER_1K_TOKENS": "0.5",
        }

    def test_check_private_flags_open_permissions(self):
        f = self.tmp / "secreto"
        f.write_text("x")
        os.chmod(f, 0o644)
        self.assertIn("permisos demasiado abiertos", cli.check_private(f) or "")
        os.chmod(f, 0o600)
        self.assertIsNone(cli.check_private(f))
        self.assertIsNone(cli.check_private(self.tmp / "no-existe"))
        d = self.tmp / "dir"
        d.mkdir(mode=0o755)
        os.chmod(d, 0o755)
        self.assertIn("chmod 700", cli.check_private(d, directory=True) or "")

    def test_tokens_add_prints_the_token_once_and_stores_only_its_hash(self):
        code, out, _ = run(["tokens", "add", "redmi"], self.env)
        self.assertEqual(code, 0)
        token = out.strip().splitlines()[-1]
        self.assertEqual(len(token), 43)
        self.assertNotIn(token, (self.tmp / "tokens.json").read_text())
        self.assertEqual(stat.S_IMODE(os.stat(self.tmp / "tokens.json").st_mode), 0o600)
        self.assertEqual(TokenStore(self.tmp / "tokens.json").authenticate(token), "redmi")
        self.assertEqual(run(["tokens", "list"], self.env)[1].strip(), "redmi")
        self.assertEqual(run(["tokens", "add", "redmi"], self.env)[0], 1)  # ya existe: rotar
        code, out, _ = run(["tokens", "rotate", "redmi"], self.env)
        self.assertNotEqual(out.strip().splitlines()[-1], token)
        self.assertEqual(run(["tokens", "revoke", "redmi"], self.env)[1].strip(), "Revocado.")
        self.assertEqual(run(["tokens", "list"], self.env)[1].strip(), "")

    def test_stats_summarizes_the_last_hours_from_the_audit_log(self):
        now = datetime.now(timezone.utc)
        rows = [
            {"ts": (now - timedelta(minutes=5)).isoformat(), "type": "event", "decision": "processed", "model_ms": 100, "tokens": 1000, "commands_emitted": ["pause"], "rejected": ["bad_schema"]},
            {"ts": (now - timedelta(minutes=4)).isoformat(), "type": "event", "decision": "model_invalid", "model_ms": 300, "tokens": 1000, "commands_emitted": [], "rejected": ["not_json"]},
            {"ts": (now - timedelta(minutes=3)).isoformat(), "type": "event", "decision": "skipped_kind"},
            {"ts": (now - timedelta(hours=30)).isoformat(), "type": "event", "decision": "processed", "tokens": 99999},
            {"ts": now.isoformat(), "type": "alert", "code": "block_burst"},
        ]
        (self.tmp / "audit.jsonl").write_text("\n".join(json.dumps(r) for r in rows) + "\nbasura\n")
        code, out, _ = run(["stats", "--hours", "24"], self.env)
        stats = json.loads(out)
        self.assertEqual(code, 0)
        self.assertEqual(stats["decisiones"], {"processed": 1, "model_invalid": 1, "skipped_kind": 1})
        self.assertEqual((stats["respuestas_no_json_pct"], stats["tokens"], stats["coste_estimado"], stats["alertas"]), (50.0, 2000, 1.0, 1))
        self.assertEqual((stats["ordenes_emitidas"], stats["ordenes_rechazadas"], stats["ordenes_rechazadas_pct"]), (1, 2, 66.7))

    def test_the_default_audit_path_is_inside_the_directories_the_service_may_write(self):
        self.assertEqual(Config().audit_path, Path("/opt/zen-brain/audit/audit.jsonl"))

    def test_the_env_file_is_read_without_a_shell(self):
        env_file = self.tmp / ".env"
        env_file.write_text('# comentario\nZEN_PORT=9001\nZEN_BLOCK_ALLOWLIST="a.b.c, d.e.f"\nMAL FORMADO CON ESPACIOS\nZEN_X = 1\nexport NO=1\n\nZEN_STT=off\n')
        values = cli.load_env_file(env_file)
        self.assertEqual(values["ZEN_PORT"], "9001")
        self.assertEqual(values["ZEN_BLOCK_ALLOWLIST"], "a.b.c, d.e.f")
        self.assertEqual(values["ZEN_STT"], "off")
        self.assertNotIn("NO", values)
        # Y lo usa el CLI: `tokens` solo necesita ZEN_ENV_FILE.
        env_file.write_text(f"ZEN_TOKENS_FILE={self.tmp / 'desde-env.json'}\n")
        code, out, _ = run(["tokens", "add", "redmi"], {"ZEN_ENV_FILE": str(env_file)})
        self.assertEqual(code, 0)
        self.assertTrue((self.tmp / "desde-env.json").exists())
        # Lo que ya está en el entorno manda sobre el archivo.
        code, out, _ = run(["tokens", "list"], {"ZEN_ENV_FILE": str(env_file), "ZEN_TOKENS_FILE": str(self.tmp / "otro.json")})
        self.assertEqual(out.strip(), "")

    def test_an_unreadable_env_file_is_reported_not_crashed(self):
        code, _, err = run(["check"], {"ZEN_ENV_FILE": str(self.tmp / "no-existe")})
        self.assertEqual(code, 2)
        self.assertIn("ZEN_ENV_FILE", err)

    def test_a_broken_tokens_file_gives_an_error_message_not_a_traceback(self):
        (self.tmp / "tokens.json").write_text("[]")
        for args in (["tokens", "list"], ["tokens", "add", "redmi"], ["tokens", "revoke", "redmi"]):
            code, _, err = run(args, self.env)
            self.assertEqual(code, 1, args)
            self.assertIn("Error", err)

    def test_the_repr_of_the_config_never_shows_the_secret(self):
        self.assertNotIn(KEY, repr(from_env({"ZEN_HERMES_API_KEY": KEY})))

    def test_revoking_works_even_if_the_rest_of_the_config_is_broken(self):
        # En una emergencia no puede depender de que la allowlist o la URL estén bien.
        env = {**self.env, "ZEN_BLOCK_ALLOWLIST": "com.android.settings", "ZEN_HERMES_URL": "http://10.1.1.1/x"}
        del env["ZEN_HERMES_API_KEY"]
        TokenStore(self.tmp / "tokens.json").add("redmi")
        code, out, _ = run(["tokens", "revoke", "redmi"], env)
        self.assertEqual((code, out.strip()), (0, "Revocado."))
        self.assertEqual(TokenStore(self.tmp / "tokens.json").labels(), [])

    def test_serve_also_refuses_a_directory_others_can_write_to(self):
        tokens_dir = self.tmp / "abierto"
        tokens_dir.mkdir()
        os.chmod(tokens_dir, 0o777)
        env = {**self.env, "ZEN_TOKENS_FILE": str(tokens_dir / "tokens.json")}
        code, _, err = run(["serve"], env)
        self.assertEqual(code, 2)
        self.assertIn("otros usuarios pueden escribir", err)

    def test_serve_refuses_to_start_with_open_permissions(self):
        tokens = self.tmp / "tokens.json"
        TokenStore(tokens).add("redmi")
        os.chmod(tokens, 0o644)
        code, _, err = run(["serve"], self.env)
        self.assertEqual(code, 2)
        self.assertIn("permisos demasiado abiertos", err)

    def test_invalid_config_is_reported_not_raised(self):
        code, _, err = run(["check"], {"ZEN_DATA_DIR": str(self.tmp)})
        self.assertEqual(code, 2)
        self.assertIn("ZEN_HERMES_API_KEY", err)


if __name__ == "__main__":
    unittest.main()
