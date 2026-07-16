import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "test-and-publish-container.yml"
FINGERPRINT = ROOT / "deployment" / "raspberry-pi" / "host-contract-fingerprint.py"

FAKE_GH = r"""#!/usr/bin/env python3
import json, os, re, sys
from pathlib import Path

root = Path(os.environ["FAKE_GH_ROOT"])
args = sys.argv[1:]
method = args[args.index("--method") + 1] if "--method" in args else "GET"
endpoint = next((value for value in args if value.startswith("repos/")), "")
body = sys.stdin.read()
with (root / "calls.jsonl").open("a", encoding="utf-8") as log:
    log.write(json.dumps({"args": args, "method": method, "endpoint": endpoint}) + "\n")

if endpoint.endswith("/deployments") and method == "GET":
    print((root / "deployment-pages.json").read_text(encoding="utf-8"))
elif match := re.search(r"/deployments/(\d+)/statuses", endpoint):
    deployment_id = match.group(1)
    if method == "GET":
        path = root / f"statuses-{deployment_id}.json"
        print(path.read_text(encoding="utf-8") if path.exists() else "[]")
    else:
        print('{"id":999}')
elif endpoint.endswith("/deployments") and method == "POST":
    request = json.loads(body)
    request["id"] = int((root / "new-id").read_text())
    request["sha"] = request.pop("ref")
    print(json.dumps(request))
else:
    raise SystemExit(f"unexpected gh call: {method} {endpoint}")
"""


def workflow_script() -> str:
    lines = WORKFLOW.read_text(encoding="utf-8").splitlines()
    anchor = lines.index("      - name: Create or reuse the host-configuration request")
    run = next(index for index in range(anchor, len(lines)) if lines[index] == "        run: |")
    body = []
    for line in lines[run + 1 :]:
        if line and not line.startswith("          "):
            break
        body.append(line[10:] if line else "")
    return "\n".join(body) + "\n"


class HostDeploymentWorkflowTest(unittest.TestCase):
    reporter = "moon-service-agent[bot]"
    revision = "1" * 40

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fake_gh = self.bin / "gh"
        fake_gh.write_text(FAKE_GH, encoding="utf-8")
        fake_gh.chmod(0o755)
        (self.root / "new-id").write_text("30\n", encoding="utf-8")
        self.fingerprint = subprocess.run(
            ["python3", str(FINGERPRINT)], cwd=ROOT, check=True,
            capture_output=True, text=True,
        ).stdout.strip()

    def tearDown(self):
        self.temporary.cleanup()

    def test_pagination_is_scoped_to_the_host_deployment_step(self):
        workflow = WORKFLOW.read_text(encoding="utf-8")
        start = workflow.index("      - name: Create and queue exact Raspberry Pi deployment request")
        end = workflow.index("\n  queue-raspberry-pi-host-config:", start)
        application_step = workflow[start:end]

        self.assertIn('deployments_json="$(gh api', application_step)
        self.assertNotIn("deployment_pages_json", application_step)
        self.assertNotIn("--paginate --slurp", application_step)

    def deployment(self, deployment_id, fingerprint):
        return {
            "id": deployment_id,
            "task": "provision:raspberry-pi",
            "environment": "raspberry-pi-host-config",
            "payload": {
                "schema_version": 1,
                "host_configuration_fingerprint": fingerprint,
                "deployment_reporter_login": self.reporter,
            },
        }

    def run_workflow(self, deployments, statuses, additional_pages=()):
        (self.root / "deployment-pages.json").write_text(
            json.dumps([deployments, *additional_pages]), encoding="utf-8"
        )
        for deployment_id, value in statuses.items():
            (self.root / f"statuses-{deployment_id}.json").write_text(
                json.dumps(value), encoding="utf-8"
            )
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
            ["bash", "-c", workflow_script()], cwd=ROOT, env=environment,
            check=False, capture_output=True, text=True,
        )
        calls_path = self.root / "calls.jsonl"
        calls = [json.loads(line) for line in calls_path.read_text().splitlines()]
        self.assertEqual(0, result.returncode, result.stderr)
        return result, calls

    @staticmethod
    def status_post(calls, deployment_id, state):
        endpoint = f"/deployments/{deployment_id}/statuses"
        return any(
            call["method"] == "POST"
            and endpoint in call["endpoint"]
            and f"state={state}" in call["args"]
            for call in calls
        )

    def test_reuses_authoritative_success_and_cleans_older_duplicate(self):
        deployments = [
            self.deployment(10, self.fingerprint),
            self.deployment(20, self.fingerprint),
            {
                "id": 99,
                "task": "provision:raspberry-pi",
                "environment": "raspberry-pi-host-config",
                "payload": "opaque",
            },
        ]
        statuses = {
            10: [{"id": 1, "state": "queued", "creator": {"login": "github-actions[bot]"}}],
            20: [{"id": 2, "state": "success", "creator": {"login": self.reporter}}],
        }

        result, calls = self.run_workflow(deployments, statuses)

        listing = next(call for call in calls if call["endpoint"].endswith("/deployments"))
        self.assertIn("--method", listing["args"])
        self.assertEqual("GET", listing["method"])
        self.assertIn("--paginate", listing["args"])
        self.assertIn("--slurp", listing["args"])
        self.assertIn("already succeeded in deployment 20", result.stdout)
        self.assertTrue(self.status_post(calls, 10, "inactive"))
        self.assertFalse(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments") for call in calls))

    def test_older_success_does_not_override_newest_different_fingerprint(self):
        other = "sha256:" + "e" * 64
        deployments = [self.deployment(10, self.fingerprint), self.deployment(20, other)]
        statuses = {
            10: [{"id": 1, "state": "success", "creator": {"login": self.reporter}}],
            20: [{"id": 2, "state": "success", "creator": {"login": self.reporter}}],
            5: [{"id": 3, "state": "queued", "creator": {"login": "github-actions[bot]"}}],
        }

        _, calls = self.run_workflow(
            deployments, statuses, additional_pages=([self.deployment(5, other)],)
        )

        self.assertTrue(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments") for call in calls))
        self.assertTrue(self.status_post(calls, 30, "queued"))
        self.assertTrue(self.status_post(calls, 5, "inactive"))

    def test_reuses_queued_match_and_cleans_older_unfinished(self):
        other = "sha256:" + "e" * 64
        deployments = [self.deployment(10, other), self.deployment(20, self.fingerprint)]
        statuses = {
            10: [{"id": 1, "state": "pending", "creator": {"login": "github-actions[bot]"}}],
            20: [{"id": 2, "state": "queued", "creator": {"login": "github-actions[bot]"}}],
        }

        result, calls = self.run_workflow(deployments, statuses)

        self.assertIn("already queued in deployment 20", result.stdout)
        self.assertTrue(self.status_post(calls, 10, "inactive"))
        self.assertFalse(self.status_post(calls, 20, "queued"))
        self.assertFalse(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments") for call in calls))

    def test_initializes_statusless_match_and_cleans_older_unfinished(self):
        other = "sha256:" + "e" * 64
        deployments = [self.deployment(10, other), self.deployment(20, self.fingerprint)]

        result, calls = self.run_workflow(deployments, {10: [], 20: []})

        self.assertIn("Initialized queued host-configuration deployment 20", result.stdout)
        self.assertTrue(self.status_post(calls, 20, "queued"))
        self.assertTrue(self.status_post(calls, 10, "inactive"))
        self.assertFalse(any(call["method"] == "POST" and call["endpoint"].endswith("/deployments") for call in calls))


if __name__ == "__main__":
    unittest.main()
