import json
import os
from pathlib import Path
import signal
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[3]
REPORT_SCRIPT = (
    ROOT
    / "deployment"
    / "raspberry-pi"
    / "roles"
    / "moon_service_host"
    / "files"
    / "moon-service-github-report"
)


FAKE_CURL = r"""#!/usr/bin/env bash
set -euo pipefail
method=GET
body=""
url=""
previous=""
printf '%s\n' "$*" >>"$FAKE_GITHUB_ROOT/curl-arguments.log"
for argument in "$@"; do
    if [[ "$previous" == --request ]]; then
        method="$argument"
    elif [[ "$previous" == --data-binary ]]; then
        body="$argument"
    elif [[ "$argument" == https://* ]]; then
        url="$argument"
    fi
    previous="$argument"
done

[[ "${FAKE_GITHUB_FAIL:-0}" != 1 ]] || exit 22
if [[ -n "${FAKE_GITHUB_SLEEP_SECONDS:-}" ]]; then
    sleep "$FAKE_GITHUB_SLEEP_SECONDS"
fi

if [[ "$url" == */app/installations/*/access_tokens && "$method" == POST ]]; then
    printf '{"token":"ghs_test-installation-token"}\n'
elif [[ "$url" == *'/deployments?sha='* && "$method" == GET ]]; then
    cat "$FAKE_GITHUB_ROOT/deployments.json"
elif [[ "$url" == *'/statuses?per_page=1' && "$method" == GET ]]; then
    cat "$FAKE_GITHUB_ROOT/statuses.json"
elif [[ "$url" == */statuses && "$method" == POST ]]; then
    printf '%s\n' "$body" >>"$FAKE_GITHUB_ROOT/reports.jsonl"
    printf '{"id":99,"state":%s}\n' "$(jq -c '.state' <<<"$body")"
else
    printf 'unexpected fake GitHub request: %s %s\n' "$method" "$url" >&2
    exit 2
fi
"""


def read_env(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split("=", 1)
        result[key] = value
    return result


class GitHubDeploymentReportTest(unittest.TestCase):
    digest = "sha256:" + "a" * 64
    revision = "1" * 40

    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.config = self.root / "config"
        self.config.mkdir()
        self.private_key = self.config / "github-app.pem"
        subprocess.run(
            [
                "openssl",
                "genpkey",
                "-quiet",
                "-algorithm",
                "RSA",
                "-pkeyopt",
                "rsa_keygen_bits:2048",
                "-out",
                str(self.private_key),
            ],
            check=True,
            capture_output=True,
            text=True,
        )
        self.private_key.chmod(0o600)
        self.fake_curl = self.root / "curl"
        self.fake_curl.write_text(FAKE_CURL, encoding="utf-8")
        self.fake_curl.chmod(0o755)
        (self.root / "statuses.json").write_text(
            '[{"id":1,"state":"queued"}]\n', encoding="utf-8"
        )
        self.set_deployments([self.deployment(41)])
        (self.config / "host.env").write_text(
            "\n".join(
                [
                    "MOON_GITHUB_REPORTING_ENABLED=true",
                    "MOON_GITHUB_REPOSITORY=rapucha/moon-service",
                    "MOON_GITHUB_DEPLOYMENT_TASK=deploy:raspberry-pi",
                    "MOON_GITHUB_DEPLOYMENT_ENVIRONMENT=raspberry-pi",
                    "MOON_GITHUB_APP_ID=1234",
                    "MOON_GITHUB_APP_INSTALLATION_ID=5678",
                    f"MOON_GITHUB_APP_PRIVATE_KEY_FILE={self.private_key}",
                    "MOON_IMAGE_REPOSITORY=ghcr.io/rapucha/moon-service",
                    "MOON_GITHUB_API_URL=https://api.github.test",
                    f"MOON_STATE_DIR={self.state}",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.environment = os.environ | {
            "FAKE_GITHUB_ROOT": str(self.root),
            "MOON_CONFIG_DIR": str(self.config),
            "MOON_CURL_BIN": str(self.fake_curl),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def deployment(self, deployment_id: int, *, digest=None, revision=None):
        digest = digest or self.digest
        revision = revision or self.revision
        return {
            "id": deployment_id,
            "sha": revision,
            "task": "deploy:raspberry-pi",
            "environment": "raspberry-pi",
            "payload": {
                "schema_version": 1,
                "image_repository": "ghcr.io/rapucha/moon-service",
                "image_digest": digest,
                "image_revision": revision,
            },
        }

    def set_deployments(self, deployments):
        (self.root / "deployments.json").write_text(
            json.dumps(deployments), encoding="utf-8"
        )

    def run_report(self, state="success", **environment):
        values = self.environment | {key: str(value) for key, value in environment.items()}
        return subprocess.run(
            [
                "bash",
                str(REPORT_SCRIPT),
                state,
                self.digest,
                self.revision,
                "Pi verified the exact deployment identity",
            ],
            check=False,
            capture_output=True,
            text=True,
            env=values,
        )

    def reports(self):
        path = self.root / "reports.jsonl"
        if not path.exists():
            return []
        return [json.loads(line) for line in path.read_text().splitlines()]

    def test_reports_success_for_every_matching_active_deployment(self):
        self.set_deployments([self.deployment(41), self.deployment(42)])

        result = self.run_report()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(["success", "success"], [item["state"] for item in self.reports()])
        self.assertEqual(
            ["raspberry-pi", "raspberry-pi"],
            [item["environment"] for item in self.reports()],
        )
        last_report = read_env(self.state / "last-github-report.env")
        self.assertEqual("reported", last_report["RESULT"])
        self.assertEqual("42,41", last_report["DEPLOYMENT_IDS"])
        curl_arguments = (self.root / "curl-arguments.log").read_text()
        self.assertNotIn("ghs_test-installation-token", curl_arguments)
        self.assertNotIn("Authorization: Bearer", curl_arguments)

    def test_ignores_payload_with_a_different_digest(self):
        self.set_deployments(
            [self.deployment(41, digest="sha256:" + "b" * 64)]
        )

        result = self.run_report()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.reports())
        self.assertEqual(
            "no-match", read_env(self.state / "last-github-report.env")["RESULT"]
        )

    def test_waits_for_workflow_to_create_the_initial_queued_status(self):
        (self.root / "statuses.json").write_text("[]\n", encoding="utf-8")

        result = self.run_report()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.reports())
        self.assertEqual(
            "awaiting-queued",
            read_env(self.state / "last-github-report.env")["RESULT"],
        )

    def test_does_not_overwrite_a_terminal_status(self):
        (self.root / "statuses.json").write_text(
            '[{"id":2,"state":"success"}]\n', encoding="utf-8"
        )

        result = self.run_report("failure")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.reports())
        self.assertEqual(
            "already-terminal",
            read_env(self.state / "last-github-report.env")["RESULT"],
        )

    def test_in_progress_retry_is_recorded_as_nonterminal(self):
        (self.root / "statuses.json").write_text(
            '[{"id":2,"state":"in_progress"}]\n', encoding="utf-8"
        )

        result = self.run_report("in_progress")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual([], self.reports())
        self.assertEqual(
            "already-in-progress",
            read_env(self.state / "last-github-report.env")["RESULT"],
        )

    def test_records_api_failure_without_persisting_a_token(self):
        result = self.run_report(FAKE_GITHUB_FAIL=1)

        self.assertEqual(1, result.returncode)
        self.assertEqual(
            "api-failed", read_env(self.state / "last-github-report.env")["RESULT"]
        )
        self.assertEqual([], list(self.state.glob(".github-auth.*")))
        self.assertNotIn("ghs_", result.stdout + result.stderr)

    def test_rejects_an_invalid_deployment_list_response(self):
        (self.root / "deployments.json").write_text('{}\n', encoding="utf-8")

        result = self.run_report()

        self.assertEqual(1, result.returncode)
        self.assertEqual(
            "api-failed", read_env(self.state / "last-github-report.env")["RESULT"]
        )
        self.assertEqual([], self.reports())

    def test_term_exits_and_removes_the_temporary_authorization_header(self):
        values = self.environment | {"FAKE_GITHUB_SLEEP_SECONDS": "10"}
        process = subprocess.Popen(
            [
                "bash",
                str(REPORT_SCRIPT),
                "success",
                self.digest,
                self.revision,
                "Pi verified the exact deployment identity",
            ],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=values,
            start_new_session=True,
        )
        deadline = time.monotonic() + 2
        while not list(self.state.glob(".github-auth.*")) and time.monotonic() < deadline:
            time.sleep(0.01)

        os.killpg(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=3)

        self.assertNotEqual(0, process.returncode, stdout + stderr)
        self.assertEqual([], list(self.state.glob(".github-auth.*")))


if __name__ == "__main__":
    unittest.main()
