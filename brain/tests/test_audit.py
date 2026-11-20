import json
import os
import shutil
import stat
import tempfile
import unittest
from pathlib import Path

from zen_brain import audit as audit_module
from zen_brain.audit import Audit


class AuditTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-audit-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.path = self.tmp / "audit.jsonl"
        self.audit = Audit(self.path)
        self.addCleanup(self.audit.close)

    def test_entries_are_json_lines_with_private_permissions(self):
        self.assertTrue(self.audit.log(type="event", event_id="e1", decision="processed"))
        (line,) = self.path.read_text().splitlines()
        self.assertEqual(json.loads(line)["event_id"], "e1")
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)

    def test_a_value_with_newlines_cannot_forge_another_entry(self):
        self.audit.log(type="event", event_id='x"}\n{"type":"alert","code":"falso"}')
        lines = self.path.read_text().splitlines()
        self.assertEqual(len(lines), 1)
        self.assertEqual(json.loads(lines[0])["type"], "event")

    def test_a_failing_disk_never_raises_and_reports_false(self):
        os.close(self.audit._fd)  # descriptor inválido: simula un fallo de escritura
        self.audit._fd = 987_654
        self.assertFalse(self.audit.log(type="event"))
        self.audit._fd = -1  # se reabre solo en la siguiente llamada
        self.assertTrue(self.audit.log(type="event", decision="recuperado"))

    def test_a_failed_rotation_leaves_a_valid_descriptor_and_never_a_stray_one(self):
        from unittest import mock

        original = audit_module.MAX_BYTES
        audit_module.MAX_BYTES = 200
        self.addCleanup(setattr, audit_module, "MAX_BYTES", original)
        self.audit.log(type="event", pad="x" * 150)
        before = self.audit._fd
        with mock.patch.object(audit_module.os, "replace", side_effect=PermissionError("sin permiso")):
            self.assertFalse(self.audit.log(type="event", pad="y" * 150))
        self.assertEqual(self.audit._fd, before)
        os.fstat(self.audit._fd)  # sigue siendo un descriptor nuestro: no quedó cerrado para que otro lo reutilice
        self.assertTrue(self.audit.log(type="event", decision="recuperado"))  # y en cuanto se puede, rota y sigue

    def test_if_the_file_is_moved_or_deleted_a_new_one_is_opened_in_its_place(self):
        self.audit.log(type="event", n=1)
        moved = self.tmp / "audit.jsonl.movido"
        self.path.rename(moved)  # lo que hace un logrotate externo
        self.assertTrue(self.audit.log(type="event", n=2))
        self.assertEqual([json.loads(l)["n"] for l in self.path.read_text().splitlines()], [2])
        self.assertEqual([json.loads(l)["n"] for l in moved.read_text().splitlines()], [1])
        self.path.unlink()
        self.assertTrue(self.audit.log(type="event", n=3))
        self.assertEqual([json.loads(l)["n"] for l in self.path.read_text().splitlines()], [3])

    def test_rotation_keeps_the_history_in_numbered_files(self):
        original = audit_module.MAX_BYTES
        audit_module.MAX_BYTES = 300
        self.addCleanup(setattr, audit_module, "MAX_BYTES", original)
        for n in range(12):
            self.audit.log(type="event", n=n, pad="z" * 80)
        self.assertTrue((self.tmp / "audit.jsonl.1").exists())
        total = sum(1 for p in self.tmp.glob("audit.jsonl*") for _ in p.read_text().splitlines())
        self.assertEqual(total, 12)


class ShortWriteTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-audit-short-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.path = self.tmp / "audit.jsonl"
        self.audit = Audit(self.path)
        self.addCleanup(self.audit.close)

    def test_a_write_that_returns_less_than_the_line_is_completed(self):
        from unittest import mock

        real = os.write
        with mock.patch.object(audit_module.os, "write", side_effect=lambda fd, data: real(fd, bytes(data)[:7])):
            self.assertTrue(self.audit.log(type="event", n=1))
        self.assertEqual([json.loads(l)["n"] for l in self.path.read_text().splitlines()], [1])

    def test_a_write_that_fails_halfway_leaves_no_half_line_and_reports_false(self):
        from unittest import mock

        self.audit.log(type="event", n=0)
        before = self.path.read_bytes()
        real = os.write
        calls = {"n": 0}

        def flaky(fd, data):
            calls["n"] += 1
            if calls["n"] == 1:
                return real(fd, bytes(data)[:10])  # escribe un trozo…
            raise OSError(28, "No space left on device")  # …y se llena el disco

        with mock.patch.object(audit_module.os, "write", side_effect=flaky):
            self.assertFalse(self.audit.log(type="event", n=1))
        self.assertEqual(self.path.read_bytes(), before)  # sin media línea
        self.assertFalse(self.audit.ok)
        self.assertTrue(self.audit.log(type="event", n=2))
        self.assertTrue(self.audit.ok)
        self.assertEqual([json.loads(l)["n"] for l in self.path.read_text().splitlines()], [0, 2])

    def test_a_broken_stderr_does_not_make_log_raise(self):
        import contextlib

        class Broken:
            def write(self, *_):
                raise OSError("stderr roto")

            def flush(self):
                raise OSError("stderr roto")

        os.close(self.audit._fd)
        self.audit._fd = 987_654
        with contextlib.redirect_stderr(Broken()):
            self.assertFalse(self.audit.log(type="event"))


if __name__ == "__main__":
    unittest.main()
