import importlib.util
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "test-and-publish-container.yml"
SCRIPT = ROOT / "deployment" / "raspberry-pi" / "host-deployment-request.py"
SPEC = importlib.util.spec_from_file_location("host_deployment_request", SCRIPT)
assert SPEC and SPEC.loader
REQUEST = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REQUEST
SPEC.loader.exec_module(REQUEST)


class FakeApi:
    def __init__(self, create_override=None, status_override=None):
        self.calls = []
        self.create_override = create_override
        self.status_override = status_override

    def __call__(self, endpoint, body):
        operation = "status" if endpoint.endswith("/statuses") else "create"
        self.calls.append((operation, endpoint, body))
        override = self.status_override if operation == "status" else self.create_override
        if isinstance(override, Exception):
            raise override
        if override is not None:
            return override
        return {"id": 32, **body} if operation == "status" else {
            **body, "id": 31, "sha": body["ref"]
        }


class HostDeploymentRequestTest(unittest.TestCase):
    fingerprint = "sha256:" + "a" * 64

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.git("init", "--initial-branch=main")
        self.git("config", "user.name", "Moon Service Test")
        self.git("config", "user.email", "test@example.invalid")
        self.write(REQUEST.ROLE_PATH + "/tasks/main.yml", "---\n")
        self.write(REQUEST.FINGERPRINT_HELPER_PATH, "helper-v1\n")
        self.write("README.md", "base\n")
        self.base = self.commit("base")
        self.write(REQUEST.PRODUCER_PATH, "producer\n")
        self.activation = self.commit("activation")
        self.write("README.md", "unrelated\n")
        self.unchanged = self.commit("unrelated")
        self.write(REQUEST.ROLE_PATH + "/tasks/main.yml", "---\nchanged: true\n")
        self.role_changed = self.commit("role")
        self.write("README.md", "after role\n")
        self.after_role = self.commit("after role")
        self.write(REQUEST.FINGERPRINT_HELPER_PATH, "helper-v2\n")
        self.helper_changed = self.commit("helper")
        self.git("switch", "--create", "sibling", self.activation)
        self.write("sibling.txt", "unrelated history\n")
        self.sibling = self.commit("sibling")
        self.git("switch", "main")
        self.git("merge", "--no-ff", "--no-edit", "sibling")
        self.merged = self.git("rev-parse", "HEAD")

    def tearDown(self):
        self.temporary.cleanup()

    def git(self, *arguments):
        return subprocess.run(
            ["git", *arguments], cwd=self.root, check=True,
            capture_output=True, text=True,
        ).stdout.strip()

    def write(self, relative, value):
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(value, encoding="utf-8")

    def commit(self, message):
        self.git("add", ".")
        self.git("commit", "--quiet", "--message", message)
        return self.git("rev-parse", "HEAD")

    def environment(self, push_before, previous_promoted, revision):
        return mock.patch.dict(
            os.environ,
            {
                "HOST_PUSH_BEFORE_REVISION": push_before,
                "HOST_PREVIOUS_PROMOTED_REVISION": previous_promoted,
                "HOST_REVISION": revision,
                "GITHUB_REPOSITORY": "rapucha/moon-service",
                "GITHUB_SERVER_URL": "https://github.example",
                "GITHUB_RUN_ID": "12345",
            },
            clear=True,
        )

    def test_first_activation_and_tracked_changes_queue(self):
        """Queue activation and each tracked net change. Linear Git histories model
        replacement/rerun ordering so a pending workflow cannot hide a host change."""
        cases = (
            (self.base, self.base, self.activation, "activation: baseline lacks producer, so queue current state"),
            (self.unchanged, self.unchanged, self.role_changed, "role change: role bytes affect identity, so queue"),
            (self.after_role, self.after_role, self.helper_changed,
             "helper change: hash rules affect identity, so queue"),
            (
                self.role_changed, self.unchanged, self.after_role,
                "replaced promotion: older GHCR baseline preserves the skipped role change",
            ),
            (
                self.unchanged, self.after_role, self.after_role,
                "older rerun: older push baseline spans the promoted host change",
            ),
        )
        for push_before, previous_promoted, revision, label in cases:
            with self.subTest(label):
                self.assertTrue(
                    REQUEST.host_contract_changed(
                        self.root, push_before, previous_promoted, revision
                    )
                )

    def test_unchanged_range_performs_no_api_write(self):
        """Skip all API writes for a README-only range. The fingerprint contract
        excludes that path, preventing application-only pushes from creating noise."""
        self.assertFalse(
            REQUEST.host_contract_changed(
                self.root, self.activation, self.activation, self.unchanged
            )
        )
        api = FakeApi()

        with self.environment(self.activation, self.activation, self.unchanged):
            REQUEST.run(api, self.root)

        self.assertEqual([], api.calls)

    def test_invalid_or_unrelated_baseline_fails_before_api_write(self):
        """Reject malformed, missing, future, or unrelated baselines before writes.
        Git ranges must be available linear ancestors so ambiguous history fails safe."""
        cases = (
            ("invalid", self.activation, self.helper_changed, "malformed push baseline has no Git identity"),
            ("0" * 40, self.activation, self.helper_changed, "zero push baseline is not an allowed branch update"),
            ("f" * 40, self.activation, self.helper_changed, "missing push commit cannot define a trustworthy range"),
            (self.sibling, self.activation, self.helper_changed,
             "non-ancestor push baseline cannot lead to promoted revision"),
            (self.activation, self.sibling, self.helper_changed,
             "non-ancestor GHCR baseline cannot lead to promoted revision"),
            (self.helper_changed, self.sibling, self.merged, "unrelated baselines make the older range ambiguous"),
        )
        for push_before, previous_promoted, revision, label in cases:
            api = FakeApi()
            with self.subTest(label), self.environment(
                push_before, previous_promoted, revision
            ), self.assertRaises(REQUEST.ContractError):
                REQUEST.run(api, self.root)
            self.assertEqual([], api.calls)

    def test_changed_range_creates_exact_request_and_queued_status(self):
        """Create the exact promoted request and queued status for a host change.
        API echoes are untrusted, so task, environment, revision, and fingerprint are checked."""
        api = FakeApi()

        with self.environment(self.base, self.base, self.activation), mock.patch.object(
            REQUEST, "host_fingerprint", return_value=self.fingerprint
        ):
            REQUEST.run(api, self.root)

        self.assertEqual(["create", "status"], [call[0] for call in api.calls])
        request = api.calls[0][2]
        self.assertEqual(self.activation, request["ref"])
        self.assertEqual(REQUEST.TASK, request["task"])
        self.assertEqual(REQUEST.ENVIRONMENT, request["environment"])
        self.assertEqual(
            self.fingerprint, request["payload"]["host_configuration_fingerprint"]
        )
        status = api.calls[1][2]
        self.assertEqual("queued", status["state"])
        self.assertEqual(REQUEST.ENVIRONMENT, status["environment"])

    def test_api_failure_and_invalid_responses_fail_closed(self):
        """Propagate API failures and reject malformed create/status echoes. This
        prevents a failed or mismatched write from being reported as a queued signal."""
        apis = (
            (FakeApi(create_override={"id": 0}), "deployment ID must be a positive integer"),
            (FakeApi(status_override={"id": 32, "state": "success"}), "queued status field mismatches"),
            (FakeApi(create_override=REQUEST.ContractError("API failed")), "API failed"),
            (FakeApi(status_override=REQUEST.ContractError("status failed")), "status failed"),
        )
        for api, error in apis:
            with self.subTest(error), self.environment(
                self.base, self.base, self.activation
            ), mock.patch.object(
                REQUEST, "host_fingerprint", return_value=self.fingerprint
            ), self.assertRaisesRegex(REQUEST.ContractError, error):
                REQUEST.run(api, self.root)

    def test_api_wrapper_converts_cli_and_json_failures(self):
        """Convert nonzero gh exits and malformed JSON into actionable contract errors.
        The CLI boundary is untrusted; bounded details preserve diagnosis without hangs."""
        failures = (
            (subprocess.CompletedProcess(["gh"], 1, "", "request failed"), "returncode=1"),
            (subprocess.CompletedProcess(["gh"], 0, "not-json", ""), "invalid JSON"),
        )
        for result, error in failures:
            with self.subTest(error), mock.patch.object(
                REQUEST.subprocess, "run", return_value=result
            ), self.assertRaisesRegex(REQUEST.ContractError, error):
                REQUEST.github_api_call("repos/rapucha/moon-service/deployments", {})


class HostDeploymentWorkflowTest(unittest.TestCase):
    def test_workflow_passes_the_exact_push_range_to_the_small_producer(self):
        """Keep YAML as thin wiring for the exact promoted range and utility call.
        Text assertions catch orchestration drift without duplicating producer logic."""
        workflow = WORKFLOW.read_text(encoding="utf-8")
        promotion_anchor = workflow.index("  promote-main:")
        job_anchor = workflow.index("  queue-raspberry-pi-host-config:")
        confirmation_anchor = workflow.index("\n\n  confirm-raspberry-pi:", job_anchor)
        promotion = workflow[promotion_anchor:job_anchor]
        job = workflow[job_anchor:confirmation_anchor]

        self.assertIn("group: moon-service-ghcr-main-promotion", promotion)
        self.assertIn(
            "previous_revision: ${{ steps.promotion.outputs.previous_revision }}",
            promotion,
        )
        self.assertIn('echo "previous_revision=$current_revision"', promotion)
        self.assertIn(
            "HOST_PREVIOUS_PROMOTED_REVISION: "
            "${{ needs.promote-main.outputs.previous_revision }}",
            job,
        )
        self.assertIn("HOST_PUSH_BEFORE_REVISION: ${{ github.event.before }}", job)
        self.assertIn("HOST_REVISION: ${{ needs.promote-main.outputs.revision }}", job)
        self.assertIn(
            "run: python3 deployment/raspberry-pi/host-deployment-request.py", job
        )
        self.assertNotIn("\n    concurrency:", job)
        self.assertNotIn("--paginate", job)
        self.assertNotIn("EXPECTED_REPORTER_LOGIN", job)


if __name__ == "__main__":
    unittest.main()
