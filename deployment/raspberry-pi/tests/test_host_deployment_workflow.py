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

    def create_deployment(self, repository, body):
        self.calls.append(("create", repository, body))
        if isinstance(self.create_override, Exception):
            raise self.create_override
        if self.create_override is not None:
            return self.create_override
        return {**body, "id": 31, "sha": body["ref"]}

    def post_status(self, repository, deployment_id, body):
        self.calls.append(("status", repository, deployment_id, body))
        if isinstance(self.status_override, Exception):
            raise self.status_override
        if self.status_override is not None:
            return self.status_override
        return {"id": 32, **body}


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
        cases = (
            (self.base, self.base, self.activation, "first activation"),
            (self.unchanged, self.unchanged, self.role_changed, "role change"),
            (self.after_role, self.after_role, self.helper_changed, "helper change"),
            (
                self.role_changed,
                self.unchanged,
                self.after_role,
                "replaced pending promotion",
            ),
            (
                self.unchanged,
                self.after_role,
                self.after_role,
                "older rerun after newer promotion",
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
        cases = (
            ("invalid", self.activation, self.helper_changed),
            ("0" * 40, self.activation, self.helper_changed),
            ("f" * 40, self.activation, self.helper_changed),
            (self.sibling, self.activation, self.helper_changed),
            (self.activation, self.sibling, self.helper_changed),
            (self.helper_changed, self.sibling, self.merged),
        )
        for push_before, previous_promoted, revision in cases:
            api = FakeApi()
            with self.subTest(
                push_before=push_before, previous_promoted=previous_promoted
            ), self.environment(
                push_before, previous_promoted, revision
            ), self.assertRaises(REQUEST.ContractError):
                REQUEST.run(api, self.root)
            self.assertEqual([], api.calls)

    def test_changed_range_creates_exact_request_and_queued_status(self):
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
        status = api.calls[1][3]
        self.assertEqual("queued", status["state"])
        self.assertEqual(REQUEST.ENVIRONMENT, status["environment"])

    def test_api_failure_and_invalid_responses_fail_closed(self):
        apis = (
            FakeApi(create_override={"id": 0}),
            FakeApi(status_override={"id": 32, "state": "success"}),
            FakeApi(create_override=REQUEST.ContractError("API failed")),
            FakeApi(status_override=REQUEST.ContractError("status failed")),
        )
        for api in apis:
            with self.subTest(
                create=type(api.create_override).__name__,
                status=type(api.status_override).__name__,
            ), self.environment(
                self.base, self.base, self.activation
            ), mock.patch.object(
                REQUEST, "host_fingerprint", return_value=self.fingerprint
            ), self.assertRaises(REQUEST.ContractError):
                REQUEST.run(api, self.root)

    def test_api_wrapper_converts_cli_and_json_failures(self):
        failures = (
            subprocess.CompletedProcess(["gh"], 1, "", "request failed"),
            subprocess.CompletedProcess(["gh"], 0, "not-json", ""),
        )
        for result in failures:
            with self.subTest(returncode=result.returncode), mock.patch.object(
                REQUEST.subprocess, "run", return_value=result
            ), self.assertRaises(REQUEST.ContractError):
                REQUEST.GitHubApi().call("repos/rapucha/moon-service/deployments", {})


class HostDeploymentWorkflowTest(unittest.TestCase):
    def test_workflow_passes_the_exact_push_range_to_the_small_producer(self):
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
