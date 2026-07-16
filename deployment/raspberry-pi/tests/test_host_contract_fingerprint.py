import importlib.util
from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "deployment" / "raspberry-pi" / "host-contract-fingerprint.py"
SPEC = importlib.util.spec_from_file_location("host_contract_fingerprint", SCRIPT)
assert SPEC and SPEC.loader
FINGERPRINT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(FINGERPRINT)


class HostContractFingerprintTest(unittest.TestCase):
    def test_path_and_content_are_framed_deterministically(self):
        entries = [("role/a", b"bc"), ("role/ab", b"c")]

        self.assertEqual(FINGERPRINT.fingerprint(entries), FINGERPRINT.fingerprint(entries))
        self.assertNotEqual(
            FINGERPRINT.fingerprint(entries),
            FINGERPRINT.fingerprint([("role/a", b"b"), ("role/ab", b"cc")]),
        )

    def test_working_tree_contract_contains_only_tracked_role_inputs(self):
        entries = FINGERPRINT.working_tree_entries(ROOT)

        self.assertTrue(entries)
        self.assertTrue(
            all(path.startswith(FINGERPRINT.ROLE_PATH + "/") for path, _ in entries)
        )
        self.assertFalse(any("inventory" in path for path, _ in entries))
        self.assertRegex(
            FINGERPRINT.fingerprint(entries), re.compile(r"^sha256:[0-9a-f]{64}$")
        )

    def test_committed_revision_identity_is_reproducible(self):
        first = FINGERPRINT.revision_entries(ROOT, "HEAD")
        second = FINGERPRINT.revision_entries(ROOT, "HEAD")

        self.assertEqual(FINGERPRINT.fingerprint(first), FINGERPRINT.fingerprint(second))

    def test_applied_identity_is_recorded_only_after_final_assertions(self):
        tasks = (ROOT / FINGERPRINT.ROLE_PATH / "tasks" / "main.yml").read_text()

        self.assertLess(tasks.index("Require finite waiting"), tasks.index("Record the applied"))
        self.assertLess(tasks.index("Record the applied"), tasks.index("Report successful"))


if __name__ == "__main__":
    unittest.main()
