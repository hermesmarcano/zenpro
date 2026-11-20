import json
import os
import shutil
import stat
import tempfile
import unittest
from pathlib import Path

from zen_brain.tokens import TokenStore, hash_token


class Clock:
    def __init__(self) -> None:
        self.now = 1000.0

    def __call__(self) -> float:
        return self.now


class TokenStoreTest(unittest.TestCase):
    def setUp(self) -> None:
        self.tmp = Path(tempfile.mkdtemp(prefix="zen-tokens-"))
        self.addCleanup(shutil.rmtree, self.tmp, ignore_errors=True)
        self.path = self.tmp / "tokens.json"
        self.clock = Clock()

    def server_side(self) -> TokenStore:
        return TokenStore(self.path, clock=self.clock)

    def test_add_stores_only_the_hash_with_private_permissions(self):
        token = TokenStore(self.path).add("redmi")
        self.assertGreaterEqual(len(token), 43)
        raw = self.path.read_text()
        self.assertNotIn(token, raw)
        self.assertIn(hash_token(token), raw)
        self.assertEqual(stat.S_IMODE(os.stat(self.path).st_mode), 0o600)

    def test_authenticate_returns_the_device_and_rejects_everything_else(self):
        admin = TokenStore(self.path)
        token = admin.add("redmi")
        store = self.server_side()
        self.assertEqual(store.authenticate(token), "redmi")
        self.assertIsNone(store.authenticate(token[:-1] + ("A" if token[-1] != "A" else "B")))
        self.assertIsNone(store.authenticate(""))
        self.assertIsNone(store.authenticate("x" * 31))
        self.assertIsNone(store.authenticate("x" * 300))
        self.assertIsNone(store.authenticate("ñ" * 40))

    def test_each_device_has_its_own_token(self):
        admin = TokenStore(self.path)
        a, b = admin.add("uno"), admin.add("dos")
        store = self.server_side()
        self.assertEqual((store.authenticate(a), store.authenticate(b)), ("uno", "dos"))

    def test_revocation_and_rotation_apply_without_restart(self):
        admin = TokenStore(self.path)
        old = admin.add("redmi")
        store = self.server_side()
        self.assertEqual(store.authenticate(old), "redmi")

        new = admin.add("redmi", replace=True)
        self.clock.now += 2
        self.assertIsNone(store.authenticate(old))
        self.assertEqual(store.authenticate(new), "redmi")

        self.assertTrue(admin.revoke("redmi"))
        self.clock.now += 2
        self.assertIsNone(store.authenticate(new))
        self.assertFalse(admin.revoke("redmi"))

    def test_a_corrupt_file_fails_closed_and_recovers_when_fixed(self):
        admin = TokenStore(self.path)
        token = admin.add("redmi")
        store = self.server_side()
        self.assertEqual(store.authenticate(token), "redmi")

        self.path.write_text("{ esto no es json")
        self.clock.now += 2
        self.assertIsNone(store.authenticate(token))
        self.assertTrue(store.broken)

        self.path.write_text(json.dumps({"devices": {"redmi": {"sha256": hash_token(token)}}}))
        self.clock.now += 2
        self.assertEqual(store.authenticate(token), "redmi")
        self.assertFalse(store.broken)

    def test_an_entry_with_a_bad_label_or_hash_breaks_the_whole_file(self):
        self.path.write_text(json.dumps({"devices": {"MAL NOMBRE": {"sha256": "0" * 64}}}))
        store = self.server_side()
        self.assertIsNone(store.authenticate("x" * 40))
        self.assertTrue(store.broken)

    def test_hostile_token_files_fail_closed_without_raising(self):
        good = TokenStore(self.path).add("redmi")
        store = self.server_side()
        self.assertEqual(store.authenticate(good), "redmi")
        for content in (
            json.dumps({"devices": {"redmi": {"sha256": "é" * 64}}}),          # no ASCII: antes lanzaba TypeError en cada intento
            json.dumps({"devices": {"redmi": {"sha256": "A" * 64}}}),          # mayúsculas: no es un hash válido
            '{"devices":' + "[" * 100_000 + "}",                                # anidado: RecursionError
            json.dumps({"devices": []}),
            "null",
        ):
            self.path.write_text(content)
            self.clock.now += 2
            self.assertIsNone(store.authenticate(good), content[:30])  # y el token revocado NO sigue valiendo con una caché vieja
            self.assertTrue(store.broken, content[:30])

    def test_labels_must_match_completely(self):
        admin = TokenStore(self.path)
        with self.assertRaises(ValueError):
            admin.add("tablet\n")  # un salto de línea final no es un nombre válido

    def test_concurrent_admins_do_not_lose_each_others_changes(self):
        import threading

        admin = TokenStore(self.path)
        errors: list[Exception] = []

        def add(label: str) -> None:
            try:
                admin.add(label)
            except Exception as exc:  # noqa: BLE001
                errors.append(exc)

        threads = [threading.Thread(target=add, args=(f"dev{i}",)) for i in range(12)]
        for t in threads:
            t.start()
        for t in threads:
            t.join()
        self.assertEqual(errors, [])
        self.assertEqual(len(admin.labels()), 12)

    def test_administration_validates_names(self):
        admin = TokenStore(self.path)
        admin.add("redmi")
        for label in ("", "Mayus", "con espacio", "x" * 33, "../etc"):
            with self.assertRaises(ValueError):
                admin.add(label)
        with self.assertRaises(ValueError):
            admin.add("redmi")  # ya existe: hay que rotar
        with self.assertRaises(ValueError):
            admin.add("otro", replace=True)  # rotar algo que no existe
        self.assertEqual(admin.labels(), ["redmi"])


if __name__ == "__main__":
    unittest.main()
