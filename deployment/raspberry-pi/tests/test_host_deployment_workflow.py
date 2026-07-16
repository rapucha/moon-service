import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "test-and-publish-container.yml"
SCRIPT = ROOT / "deployment" / "raspberry-pi" / "host-deployment-request.py"
SPEC = importlib.util.spec_from_file_location("host_deployment_request", SCRIPT)
assert SPEC and SPEC.loader
REQUEST = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REQUEST
SPEC.loader.exec_module(REQUEST)

FAKE_GH = r"""#!/usr/bin/env python3
import json, os, re, sys
from pathlib import Path

root = Path(os.environ["FAKE_GH_ROOT"])
args = sys.argv[1:]
method = args[args.index("--method") + 1] if "--method" in args else "GET"
endpoint = next((value for value in args if value.startswith("repos/")), "")
body = sys.stdin.read()
with (root / "calls.jsonl").open("a", encoding="utf-8") as log:
    log.write(json.dumps({"args": args, "method": method, "endpoint": endpoint,
                          "body": json.loads(body) if body else None}) + "\n")

if endpoint.endswith("/deployments") and method == "GET":
    print((root / "deployment-pages.json").read_text(encoding="utf-8"))
elif match := re.search(r"/deployments/(\d+)/statuses", endpoint):
    deployment_id = match.group(1)
    if method == "GET":
        path = root / f"statuses-{deployment_id}.json"
        print(path.read_text(encoding="utf-8") if path.exists() else "[]")
    else:
        state = next(value.removeprefix("state=") for value in args
                     if value.startswith("state="))
        print(json.dumps({"id": 999, "state": state}))
elif endpoint.endswith("/deployments") and method == "POST":
    request = json.loads(body)
    request["id"] = int((root / "new-id").read_text())
    request["sha"] = request.pop("ref")
    print(json.dumps(request))
else:
    raise SystemExit(f"unexpected gh call: {method} {endpoint}")
"""


class HostDeploymentDecisionTest(unittest.TestCase):
    reporter = "moon-service-agent[bot]"
    fingerprint = "sha256:" + "a" * 64

    def deployment(self, deployment_id, fingerprint=None):
        return {
            "id": deployment_id,
            "task": REQUEST.TASK,
            "environment": REQUEST.ENVIRONMENT,
            "payload": {
                "schema_version": 1,
                "host_configuration_fingerprint": fingerprint or self.fingerprint,
                "deployment_reporter_login": self.reporter,
            },
        }

    def test_selects_newest_valid_deployment_across_pages(self):
        older = self.deployment(10)
        newest = self.deployment(20, "sha256:" + "b" * 64)
        pages = [[older, {"id": 99, "payload": "opaque"}], [newest]]

        deployments = REQUEST.valid_deployments(pages, self.reporter)

        self.assertEqual([10, 20], [value["id"] for value in deployments])
        self.assertEqual(20, REQUEST.newest(deployments)["id"])
        with self.assertRaises(REQUEST.ContractError):
            REQUEST.valid_deployments([older], self.reporter)

    def test_authority_actions_cover_reuse_initialization_and_creation(self):
        authority = self.deployment(20)
        reporter_success = {"id": 1, "state": "success",
                            "creator": {"login": self.reporter}}
        queued = {"id": 2, "state": "queued", "creator": {"login": "actions"}}

        self.assertEqual("reuse_success", REQUEST.authority_action(
            authority, self.fingerprint, [reporter_success], self.reporter))
        self.assertEqual("reuse_unfinished", REQUEST.authority_action(
            authority, self.fingerprint, [queued], self.reporter))
        self.assertEqual("initialize", REQUEST.authority_action(
            authority, self.fingerprint, [], self.reporter))
        self.assertEqual("create", REQUEST.authority_action(
            authority, "sha256:" + "b" * 64, None, self.reporter))
        self.assertEqual("create", REQUEST.authority_action(
            authority, self.fingerprint,
            [{"id": 3, "state": "success", "creator": {"login": "other"}}],
            self.reporter))


class HostDeploymentWorkflowTest(unittest.TestCase):
    reporter = "moon-service-agent[bot]"

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake_gh = self.bin / "gh"
        fake_gh.write_text(FAKE_GH, encoding="utf-8")
        fake_gh.chmod(0o755)
        (self.root / "new-id").write_text("30\n", encoding="utf-8")
        self.revision = subprocess.run(
            ["git", "rev-parse", "HEAD"], cwd=ROOT, check=True,
            capture_output=True, text=True,
        ).stdout.strip()
        self.fingerprint = REQUEST.host_fingerprint(self.revision)

    def tearDown(self):
        self.temporary.cleanup()

    def deployment(self, deployment_id, fingerprint=None):
        return {
            "id": deployment_id, "task": REQUEST.TASK,
            "environment": REQUEST.ENVIRONMENT,
            "payload": {"schema_version": 1,
                        "host_configuration_fingerprint": fingerprint or self.fingerprint,
                        "deployment_reporter_login": self.reporter},
        }

    @staticmethod
    def workflow_command():
        lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
        anchor = lines.index("      - name: Create or reuse the host-configuration request")
        run = next(line for line in lines[anchor:] if line.startswith("        run: "))
        return run.removeprefix("        run: ")

    def run_workflow(self, pages, statuses):
        (self.root / "deployment-pages.json").write_text(
            json.dumps(pages), encoding="utf-8")
        for deployment_id, value in statuses.items():
            (self.root / f"statuses-{deployment_id}.json").write_text(
                json.dumps(value), encoding="utf-8")
        environment = os.environ | {
            "PATH": f"{self.bin}:{os.environ['PATH']}",
            "FAKE_GH_ROOT": str(self.root),
            "EXPECTED_REPORTER_LOGIN": self.reporter,
            "HOST_REVISION": self.revision,
            "GH_TOKEN": "synthetic-test-token",
            "GITHUB_REPOSITORY": "rapucha/moon-service",
            "GITHUB_SERVER_URL": "https://github.example",
            "GITHUB_RUN_ID": "12345",
        }
        result = subprocess.run(
            ["bash", "-c", self.workflow_command()], cwd=ROOT, env=environment,
            check=False, capture_output=True, text=True,
        )
        calls = [json.loads(line) for line in (self.root / "calls.jsonl").read_text().splitlines()]
        self.assertEqual(0, result.returncode, result.stderr)
        return result, calls

    @staticmethod
    def posted(calls, deployment_id, state):
        endpoint = f"/deployments/{deployment_id}/statuses"
        return any(call["method"] == "POST" and endpoint in call["endpoint"]
                   and f"state={state}" in call["args"] for call in calls)

    def test_workflow_is_a_thin_utility_invocation(self):
        self.assertEqual(
            "python3 deployment/raspberry-pi/host-deployment-request.py",
            self.workflow_command(),
        )
        workflow = WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("      - name: Create and queue exact Raspberry Pi deployment request")
        application_step = workflow[start:workflow.index("\n  queue-raspberry-pi-host-config:", start)]
        self.assertNotIn("--paginate --slurp", application_step)

    def test_reuses_newest_success_and_cleans_every_older_page(self):
        pages = [[self.deployment(10), self.deployment(20), {"id": 99, "payload": "opaque"}],
                 [self.deployment(5, "sha256:" + "b" * 64)]]
        statuses = {
            5: [{"id": 1, "state": "pending"}],
            10: [{"id": 2, "state": "queued"}],
            20: [{"id": 3, "state": "success", "creator": {"login": self.reporter}}],
        }

        result, calls = self.run_workflow(pages, statuses)

        listing = next(call for call in calls if call["endpoint"].endswith("/deployments"))
        self.assertEqual("GET", listing["method"])
        self.assertTrue({"--paginate", "--slurp"}.issubset(listing["args"]))
        self.assertIn("already succeeded in deployment 20", result.stdout)
        self.assertTrue(self.posted(calls, 10, "inactive"))
        self.assertTrue(self.posted(calls, 5, "inactive"))
        self.assertFalse(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments")
                             for call in calls))

    def test_newest_different_fingerprint_creates_and_supersedes_it(self):
        other = "sha256:" + "b" * 64
        pages = [[self.deployment(10), self.deployment(20, other)]]
        statuses = {10: [{"id": 1, "state": "success"}],
                    20: [{"id": 2, "state": "queued"}]}

        _, calls = self.run_workflow(pages, statuses)

        creation = next(call for call in calls if call["method"] == "POST"
                        and call["endpoint"].endswith("/deployments"))
        self.assertEqual(self.fingerprint,
                         creation["body"]["payload"]["host_configuration_fingerprint"])
        self.assertTrue(self.posted(calls, 30, "queued"))
        self.assertTrue(self.posted(calls, 20, "inactive"))

    def test_statusless_authority_is_initialized_without_recreation(self):
        result, calls = self.run_workflow([[self.deployment(20)]], {20: []})

        self.assertIn("Initialized queued host-configuration deployment 20", result.stdout)
        self.assertTrue(self.posted(calls, 20, "queued"))
        self.assertFalse(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments")
                             for call in calls))


if __name__ == "__main__":
    unittest.main()
