"""Estado mínimo del adaptador en SQLite (permisos 600, borrado seguro).

Guarda: la cola de órdenes que el teléfono recoge, los ids de evento ya vistos (para no procesar dos veces un
lote reenviado) y las marcas de tiempo con las que se limitan las acciones. NO guarda el texto de los eventos.
"""

from __future__ import annotations

import json
import os
import secrets
import sqlite3
import threading
import time
from pathlib import Path
from typing import Callable

SEEN_TTL_S = 48 * 3600
ACTIONS_TTL_S = 25 * 3600
MAX_SEEN_PER_DEVICE = 20_000
MAX_COMMANDS_PER_DEVICE = 200
FETCH_LIMIT = 50


class Store:
    def __init__(self, path: Path, clock: Callable[[], float] = time.time) -> None:
        self._clock = clock
        self._lock = threading.RLock()
        path.parent.mkdir(parents=True, exist_ok=True)
        previous = os.umask(0o077)
        try:
            self._db = sqlite3.connect(str(path), check_same_thread=False, isolation_level=None)
        finally:
            os.umask(previous)
        os.chmod(path, 0o600)
        with self._lock:
            # secure_delete: lo borrado (borradores, ids) se sobrescribe en vez de quedar en el archivo.
            for pragma in ("journal_mode=WAL", "synchronous=NORMAL", "secure_delete=ON"):
                self._db.execute(f"PRAGMA {pragma}")
            self._db.executescript(
                """
                CREATE TABLE IF NOT EXISTS meta(k TEXT PRIMARY KEY, v TEXT NOT NULL);
                CREATE TABLE IF NOT EXISTS commands(
                    seq INTEGER PRIMARY KEY AUTOINCREMENT, device TEXT NOT NULL, body TEXT NOT NULL,
                    created REAL NOT NULL, expires REAL NOT NULL);
                CREATE INDEX IF NOT EXISTS commands_device ON commands(device, seq);
                CREATE TABLE IF NOT EXISTS seen(
                    device TEXT NOT NULL, event_id TEXT NOT NULL, ts REAL NOT NULL, PRIMARY KEY(device, event_id));
                CREATE TABLE IF NOT EXISTS actions(device TEXT NOT NULL, kind TEXT NOT NULL, ts REAL NOT NULL, amount REAL NOT NULL DEFAULT 1);
                CREATE INDEX IF NOT EXISTS actions_lookup ON actions(device, kind, ts);
                """
            )
            if "amount" not in {row[1] for row in self._db.execute("PRAGMA table_info(actions)")}:
                self._db.execute("ALTER TABLE actions ADD COLUMN amount REAL NOT NULL DEFAULT 1")
            row = self._db.execute("SELECT v FROM meta WHERE k='epoch'").fetchone()
            if row is None:
                self._db.execute("INSERT INTO meta(k, v) VALUES('epoch', ?)", (secrets.token_hex(4),))
                row = self._db.execute("SELECT v FROM meta WHERE k='epoch'").fetchone()
            self.epoch: str = row[0]

    # ---- eventos vistos ---------------------------------------------------------------------------------------

    def mark_seen(self, device: str, event_id: str) -> bool:
        """True si el evento es nuevo (y lo anota); False si ya se había visto."""
        with self._lock:
            cur = self._db.execute("INSERT OR IGNORE INTO seen(device, event_id, ts) VALUES(?,?,?)", (device, event_id, self._clock()))
            return cur.rowcount == 1

    def unmark_seen(self, device: str, event_id: str) -> None:
        with self._lock:
            self._db.execute("DELETE FROM seen WHERE device=? AND event_id=?", (device, event_id))

    # ---- órdenes ------------------------------------------------------------------------------------------------

    def add_command(self, device: str, body: dict, ttl_s: float) -> int:
        now = self._clock()
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                cur = self._db.execute(
                    "INSERT INTO commands(device, body, created, expires) VALUES(?,?,?,?)",
                    (device, json.dumps(body, ensure_ascii=False, separators=(",", ":")), now, now + ttl_s),
                )
                # Tope por dispositivo: si el teléfono no recoge, no crece sin límite (se pierden las más viejas).
                self._db.execute(
                    "DELETE FROM commands WHERE device=? AND seq <= "
                    "(SELECT seq FROM commands WHERE device=? ORDER BY seq DESC LIMIT 1 OFFSET ?)",
                    (device, device, MAX_COMMANDS_PER_DEVICE),
                )
                self._db.execute("COMMIT")
            except BaseException:
                self._db.execute("ROLLBACK")
                raise
            return int(cur.lastrowid)

    def fetch_commands(self, device: str, cursor: str) -> tuple[str, list[dict]]:
        """Órdenes posteriores a `cursor` («época.secuencia»). Un cursor de otra época (base nueva) empieza de cero."""
        after = 0
        epoch, _, number = cursor.partition(".")
        if epoch == self.epoch and number.isascii() and number.isdigit() and len(number) <= 15:
            after = int(number)
        now = self._clock()
        with self._lock:
            rows = self._db.execute(
                "SELECT seq, body FROM commands WHERE device=? AND seq>? AND expires>? ORDER BY seq LIMIT ?",
                (device, after, now, FETCH_LIMIT),
            ).fetchall()
            top = self._db.execute("SELECT COALESCE(MAX(seq), 0) FROM commands").fetchone()[0]
        # Sin nada nuevo se avanza hasta el final de la cola: lo que hubiera para este dispositivo estaría en
        # `rows` (se leyó dentro del mismo candado), y lo caducado no se entregará nunca. Así un cursor «en el
        # futuro» (base nueva, época distinta) también se corrige solo.
        last = rows[-1][0] if rows else top
        return f"{self.epoch}.{last}", [json.loads(body) for _, body in rows]

    # ---- límites ---------------------------------------------------------------------------------------------------

    def try_consume(self, device: str, kind: str, limit: float, window_s: float, cost: float = 1.0) -> bool:
        """Apunta `cost` si, sumado a lo gastado en la última `window_s`, no pasa de `limit`. Atómico entre hilos."""
        return self.try_consume_all(device, [(kind, limit, window_s, cost)]) is None

    def try_consume_all(self, device: str, budgets: list[tuple[str, float, float, float]]) -> str | None:
        """Varios presupuestos a la vez, TODOS o ninguno (no se gasta uno si otro falla). Devuelve el tipo que falló, o None."""
        now = self._clock()
        with self._lock:
            self._db.execute("BEGIN IMMEDIATE")
            try:
                for kind, limit, window_s, cost in budgets:
                    used = self._db.execute(
                        "SELECT COALESCE(SUM(amount), 0) FROM actions WHERE device=? AND kind=? AND ts>?", (device, kind, now - window_s)
                    ).fetchone()[0]
                    if limit <= 0 or used + cost > limit:
                        self._db.execute("ROLLBACK")
                        return kind
                for kind, _limit, _window, cost in budgets:
                    self._db.execute("INSERT INTO actions(device, kind, ts, amount) VALUES(?,?,?,?)", (device, kind, now, cost))
                self._db.execute("COMMIT")
                return None
            except BaseException:
                try:
                    self._db.execute("ROLLBACK")
                except sqlite3.Error:
                    pass
                raise

    def count_actions(self, device: str, kind: str, window_s: float) -> int:
        with self._lock:
            return self._db.execute(
                "SELECT COUNT(*) FROM actions WHERE device=? AND kind=? AND ts>?", (device, kind, self._clock() - window_s)
            ).fetchone()[0]

    # ---- mantenimiento -----------------------------------------------------------------------------------------

    def purge(self) -> None:
        now = self._clock()
        with self._lock:
            self._db.execute("DELETE FROM commands WHERE expires<=?", (now - 3600,))
            self._db.execute("DELETE FROM seen WHERE ts<?", (now - SEEN_TTL_S,))
            self._db.execute("DELETE FROM actions WHERE ts<?", (now - ACTIONS_TTL_S,))
            for (device,) in self._db.execute("SELECT DISTINCT device FROM seen").fetchall():
                self._db.execute(
                    "DELETE FROM seen WHERE device=? AND ts < (SELECT ts FROM seen WHERE device=? ORDER BY ts DESC LIMIT 1 OFFSET ?)",
                    (device, device, MAX_SEEN_PER_DEVICE),
                )
            # Con WAL, lo borrado sigue en el archivo -wal hasta que se vacía: así los borradores caducados no quedan en disco.
            self._db.execute("PRAGMA wal_checkpoint(TRUNCATE)")

    def close(self) -> None:
        with self._lock:
            self._db.close()
