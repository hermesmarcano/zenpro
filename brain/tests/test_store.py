import os
import shutil
import stat
import tempfile
import threading
import unittest
from pathlib import Path

from zen_brain.store import MAX_COMMANDS_PER_DEVICE, Store


class Clock:
    def __init__(self) -> None:
        self.now = 10_000.0

    def __call__(self) -> float:
        return self.now


class StoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-store-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.clock = Clock()
        self.store = Store(self.tmp / "state.db", self.clock)
        self.addCleanup(self.store.close)

    def test_database_files_are_private(self):
        self.assertEqual(stat.S_IMODE(os.stat(self.tmp / "state.db").st_mode), 0o600)
        self.store.add_command("phone", {"id": "1"}, 60)
        for leftover in self.tmp.glob("state.db-*"):
            self.assertEqual(stat.S_IMODE(os.stat(leftover).st_mode) & 0o077, 0, leftover.name)

    def test_seen_events_are_deduplicated_and_can_be_released(self):
        self.assertTrue(self.store.mark_seen("phone", "e1"))
        self.assertFalse(self.store.mark_seen("phone", "e1"))
        self.assertTrue(self.store.mark_seen("otro", "e1"))
        self.store.unmark_seen("phone", "e1")
        self.assertTrue(self.store.mark_seen("phone", "e1"))

    def test_the_cursor_advances_and_only_new_commands_come_back(self):
        for n in range(3):
            self.store.add_command("phone", {"id": str(n)}, 60)
        cursor, first = self.store.fetch_commands("phone", "")
        self.assertEqual([c["id"] for c in first], ["0", "1", "2"])
        again_cursor, again = self.store.fetch_commands("phone", cursor)
        self.assertEqual((again, again_cursor), ([], cursor))
        self.store.add_command("phone", {"id": "3"}, 60)
        _, fresh = self.store.fetch_commands("phone", cursor)
        self.assertEqual([c["id"] for c in fresh], ["3"])

    def test_each_device_only_sees_its_own_commands(self):
        self.store.add_command("uno", {"id": "a"}, 60)
        self.store.add_command("dos", {"id": "b"}, 60)
        self.assertEqual([c["id"] for c in self.store.fetch_commands("uno", "")[1]], ["a"])
        self.assertEqual([c["id"] for c in self.store.fetch_commands("dos", "")[1]], ["b"])
        self.assertEqual(self.store.fetch_commands("tres", "")[1], [])

    def test_expired_commands_are_never_delivered(self):
        self.store.add_command("phone", {"id": "viejo"}, 10)
        self.clock.now += 11
        self.assertEqual(self.store.fetch_commands("phone", "")[1], [])

    def test_a_cursor_from_another_epoch_or_garbage_starts_over_safely(self):
        self.store.add_command("phone", {"id": "1"}, 60)
        other = Store(self.tmp / "otra.db", self.clock)
        self.addCleanup(other.close)
        self.assertNotEqual(other.epoch, self.store.epoch)
        foreign = f"{other.epoch}.5"
        for cursor in (foreign, "basura", "..", f"{self.store.epoch}.abc", f"{self.store.epoch}.{'9' * 30}", "x" * 1000):
            self.assertEqual([c["id"] for c in self.store.fetch_commands("phone", cursor)[1]], ["1"], cursor)

    def test_a_cursor_in_the_future_is_corrected(self):
        self.store.add_command("phone", {"id": "1"}, 60)
        cursor, commands = self.store.fetch_commands("phone", f"{self.store.epoch}.9999")
        self.assertEqual((cursor, commands), (f"{self.store.epoch}.1", []))

    def test_the_queue_per_device_is_capped(self):
        for n in range(MAX_COMMANDS_PER_DEVICE + 5):
            self.store.add_command("phone", {"id": str(n)}, 60)
        # El tope se aplica al leer por tandas de FETCH_LIMIT: se recorre con el cursor.
        ids, cursor = [], ""
        while True:
            cursor, batch = self.store.fetch_commands("phone", cursor)
            if not batch:
                break
            ids += [c["id"] for c in batch]
        self.assertEqual(len(ids), MAX_COMMANDS_PER_DEVICE)
        self.assertEqual(ids[-1], str(MAX_COMMANDS_PER_DEVICE + 4))

    def test_rate_limit_counts_a_sliding_window(self):
        self.assertTrue(all(self.store.try_consume("phone", "block", 3, 3600) for _ in range(3)))
        self.assertFalse(self.store.try_consume("phone", "block", 3, 3600))
        self.assertTrue(self.store.try_consume("otro", "block", 3, 3600))
        self.assertTrue(self.store.try_consume("phone", "notify", 3, 3600))
        self.clock.now += 3601
        self.assertTrue(self.store.try_consume("phone", "block", 3, 3600))
        self.assertFalse(self.store.try_consume("phone", "block", 0, 3600))

    def test_rate_limit_is_atomic_across_threads(self):
        results: list[bool] = []
        lock = threading.Lock()

        def worker() -> None:
            ok = self.store.try_consume("phone", "call", 5, 3600)
            with lock:
                results.append(ok)

        threads = [threading.Thread(target=worker) for _ in range(40)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(sum(results), 5)

    def test_weighted_budgets_add_up_inside_the_window(self):
        self.assertTrue(self.store.try_consume("phone", "block_minutes", 240, 86400, cost=200))
        self.assertFalse(self.store.try_consume("phone", "block_minutes", 240, 86400, cost=60))
        self.assertTrue(self.store.try_consume("phone", "block_minutes", 240, 86400, cost=40))
        self.clock.now += 86401
        self.assertTrue(self.store.try_consume("phone", "block_minutes", 240, 86400, cost=240))

    def test_several_budgets_are_all_or_nothing(self):
        self.assertIsNone(self.store.try_consume_all("phone", [("hora", 1, 3600, 1), ("dia", 5, 86400, 1)]))
        # El de la hora ya está agotado: no debe gastarse nada del día.
        self.assertEqual(self.store.try_consume_all("phone", [("dia", 5, 86400, 1), ("hora", 1, 3600, 1)]), "hora")
        self.assertEqual(self.store.count_actions("phone", "dia", 86400), 1)

    def test_a_cursor_with_unicode_digits_is_treated_as_garbage(self):
        self.store.add_command("phone", {"id": "1"}, 60)
        self.assertEqual([c["id"] for c in self.store.fetch_commands("phone", f"{self.store.epoch}.²")[1]], ["1"])

    def test_purge_empties_the_wal_so_deleted_text_does_not_linger(self):
        self.store.add_command("phone", {"id": "1", "text": "BORRADOR-QUE-CADUCA-77"}, 1)
        self.clock.now += 7200
        self.store.purge()
        for leftover in self.tmp.glob("state.db*"):
            self.assertNotIn(b"BORRADOR-QUE-CADUCA-77", leftover.read_bytes(), leftover.name)

    def test_purge_forgets_old_seen_events_and_actions(self):
        self.store.mark_seen("phone", "e1")
        self.store.try_consume("phone", "call", 5, 3600)
        self.clock.now += 49 * 3600
        self.store.purge()
        self.assertTrue(self.store.mark_seen("phone", "e1"))
        self.assertEqual(self.store.count_actions("phone", "call", 10**9), 0)


if __name__ == "__main__":
    unittest.main()
