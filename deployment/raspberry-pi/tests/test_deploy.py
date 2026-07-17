import fcntl
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[3]
DEPLOY_SCRIPT = (
    ROOT
    / "deployment"
    / "raspberry-pi"
    / "roles"
    / "moon_service_host"
    / "files"
    / "moon-service-deploy"
)
REJECTED_AT = "2026-07-18T12:34:56Z"
REJECTED_KEYS = {
    "MOON_IMAGE_REPOSITORY",
    "MOON_IMAGE_DIGEST",
    "MOON_IMAGE_REVISION",
    "MOON_DEPLOYED_AT",
    "MOON_REJECTION_STAGE",
    "MOON_REJECTED_AT",
}


FAKE_DOCKER = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_STATE/commands.log"

if [[ "$1" == buildx ]]; then
    [[ "${FAKE_REGISTRY_FAIL:-0}" != 1 ]] || exit 1
    cat "$FAKE_MANIFEST_FILE"
    exit 0
fi

if [[ "$1" == pull ]]; then
    [[ "${FAKE_PULL_FAIL:-0}" != 1 ]] || exit 1
    printf '%s\n' "$2" >>"$FAKE_DOCKER_STATE/images"
    exit 0
fi

if [[ "$1" == compose ]]; then
    env_file=""
    operation=""
    previous=""
    for argument in "$@"; do
        if [[ "$previous" == --env-file ]]; then
            env_file="$argument"
        fi
        if [[ "$argument" == up || "$argument" == down ]]; then
            operation="$argument"
        fi
        previous="$argument"
    done
    if [[ "$operation" == up ]]; then
        revision="$(awk -F= '$1 == "MOON_IMAGE_REVISION" {print $2}' "$env_file")"
        digest="$(awk -F= '$1 == "MOON_IMAGE_DIGEST" {print $2}' "$env_file")"
        [[ "$revision" != "${FAKE_COMPOSE_FAIL_REVISION:-none}" ]] || exit 1
        printf '%s\n' "$revision" >"$FAKE_DOCKER_STATE/active-revision"
        printf '%s\n' "$digest" >"$FAKE_DOCKER_STATE/active-digest"
    elif [[ "$operation" == down ]]; then
        rm -f "$FAKE_DOCKER_STATE/active-revision" "$FAKE_DOCKER_STATE/active-digest"
    fi
    exit 0
fi

if [[ "$1" == inspect ]]; then
    [[ -f "$FAKE_DOCKER_STATE/active-revision" ]] || exit 1
    revision="$(cat "$FAKE_DOCKER_STATE/active-revision")"
    digest="$(cat "$FAKE_DOCKER_STATE/active-digest")"
    reported_digest="${FAKE_CONTAINER_DIGEST:-$digest}"
    reported_revision="${FAKE_CONTAINER_REVISION:-$revision}"
    if [[ "$revision" == "${FAKE_IDENTITY_MISMATCH_REVISION:-none}" ]]; then
        reported_digest="sha256:$(printf 'f%.0s' {1..64})"
    fi
    if [[ "$3" == *State.Status* ]]; then
        printf 'running\n'
    elif [[ "$3" == *State.Health* ]]; then
        if [[ "$revision" == "${FAKE_UNHEALTHY_REVISION:-none}" \
            || ( "$revision" == "${FAKE_FINAL_READINESS_FAIL_REVISION:-none}" \
                && -f "$FAKE_DOCKER_STATE/identity-checked-$revision" ) ]]; then
            printf 'unhealthy\n'
        else
            printf 'healthy\n'
        fi
    elif [[ "$3" == *Config.Image* ]]; then
        printf 'ghcr.io/rapucha/moon-service@%s\n' "$reported_digest"
    elif [[ "$3" == *Config.Labels* ]]; then
        touch "$FAKE_DOCKER_STATE/identity-checked-$revision"
        printf '{"dev.moonservice.image-digest":"%s","dev.moonservice.revision":"%s"}\n' \
            "$reported_digest" "$reported_revision"
    else
        exit 2
    fi
    exit 0
fi

if [[ "$1" == image && "$2" == ls ]]; then
    if [[ -f "$FAKE_DOCKER_STATE/images" ]]; then
        while IFS= read -r reference; do
            printf '%s sha256:fake-%s\n' "$reference" "${reference: -1}"
        done <"$FAKE_DOCKER_STATE/images"
    fi
    exit 0
fi

if [[ "$1" == image && "$2" == inspect ]]; then
    printf 'sha256:fake-%s\n' "${3: -1}"
    exit 0
fi

if [[ "$1" == image && "$2" == rm ]]; then
    exit 0
fi

if [[ "$1" == image && "$2" == prune ]]; then
    exit 0
fi

printf 'unexpected fake docker command: %s\n' "$*" >&2
exit 2
"""


FAKE_CURL = r"""#!/usr/bin/env bash
set -euo pipefail
revision="$(cat "$FAKE_DOCKER_STATE/active-revision")"
printf '{"status":"ok","revision":"%s"}\n' "$revision"
"""


FAKE_DATE = f"""#!/usr/bin/env bash
set -euo pipefail
printf '{REJECTED_AT}\\n'
"""


FAKE_GITHUB_REPORT = r"""#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >>"$FAKE_DOCKER_STATE/github-reports.log"
if [[ -n "${FAKE_GITHUB_REPORT_SLEEP_SECONDS:-}" ]]; then
    sleep "$FAKE_GITHUB_REPORT_SLEEP_SECONDS"
fi
[[ "${FAKE_GITHUB_REPORT_FAIL:-0}" != 1 ]]
"""


def manifest(digest_character: str, revision_character: str) -> tuple[dict, str, str]:
    digest = "sha256:" + digest_character * 64
    revision = revision_character * 40
    value = {
        "digest": digest,
        "annotations": {
            "org.opencontainers.image.revision": revision,
            "org.opencontainers.image.source": "https://github.com/rapucha/moon-service",
        },
        "manifests": [
            {"platform": {"os": "linux", "architecture": "amd64"}},
            {"platform": {"os": "linux", "architecture": "arm64"}},
        ],
    }
    return value, digest, revision


def read_env(path: Path) -> dict[str, str]:
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split("=", 1)
        result[key] = value
    return result


class DeploymentStateTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.state = self.root / "state"
        self.state.mkdir()
        self.config = self.root / "config"
        self.config.mkdir()
        self.compose = self.root / "compose.yml"
        self.compose.write_text("services: {}\n", encoding="utf-8")
        self.manifest_file = self.root / "manifest.json"
        self.fake_docker = self.root / "docker"
        self.fake_docker.write_text(FAKE_DOCKER, encoding="utf-8")
        self.fake_docker.chmod(0o755)
        self.fake_curl = self.root / "curl"
        self.fake_curl.write_text(FAKE_CURL, encoding="utf-8")
        self.fake_curl.chmod(0o755)
        self.fake_date = self.root / "date"
        self.fake_date.write_text(FAKE_DATE, encoding="utf-8")
        self.fake_date.chmod(0o755)
        self.fake_github_report = self.root / "github-report"
        self.fake_github_report.write_text(FAKE_GITHUB_REPORT, encoding="utf-8")
        self.fake_github_report.chmod(0o755)
        self.environment = os.environ.copy()
        self.environment.update(
            {
                "FAKE_DOCKER_STATE": str(self.root),
                "FAKE_MANIFEST_FILE": str(self.manifest_file),
                "MOON_COMPOSE_FILE": str(self.compose),
                "MOON_CONFIG_DIR": str(self.config),
                "MOON_CURL_BIN": str(self.fake_curl),
                "MOON_DATE_BIN": str(self.fake_date),
                "MOON_DOCKER_BIN": str(self.fake_docker),
                "MOON_GITHUB_REPORT_BIN": str(self.fake_github_report),
                "MOON_READY_INTERVAL_SECONDS": "0",
                "MOON_READY_TIMEOUT_SECONDS": "0",
                "MOON_STATE_DIR": str(self.state),
            }
        )

    def tearDown(self):
        self.temporary.cleanup()

    def set_manifest(self, digest_character: str, revision_character: str):
        value, digest, revision = manifest(digest_character, revision_character)
        self.manifest_file.write_text(json.dumps(value), encoding="utf-8")
        return digest, revision

    def run_deploy(self, mode="update", **environment):
        values = self.environment | {key: str(value) for key, value in environment.items()}
        return subprocess.run(
            ["bash", str(DEPLOY_SCRIPT), mode],
            check=False,
            capture_output=True,
            text=True,
            env=values,
        )

    def assert_rejected(self, digest: str, revision: str, stage: str):
        rejected = read_env(self.state / "rejected.env")
        self.assertEqual(REJECTED_KEYS, set(rejected))
        self.assertEqual("ghcr.io/rapucha/moon-service", rejected["MOON_IMAGE_REPOSITORY"])
        self.assertEqual(digest, rejected["MOON_IMAGE_DIGEST"])
        self.assertEqual(revision, rejected["MOON_IMAGE_REVISION"])
        self.assertEqual(REJECTED_AT, rejected["MOON_DEPLOYED_AT"])
        self.assertEqual(stage, rejected["MOON_REJECTION_STAGE"])
        self.assertRegex(rejected["MOON_REJECTED_AT"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$")
        self.assertEqual(REJECTED_AT, rejected["MOON_REJECTED_AT"])

    def test_initial_deployment_records_immutable_identity(self):
        digest, revision = self.set_manifest("a", "1")

        result = self.run_deploy()

        self.assertEqual(0, result.returncode, result.stderr)
        current = read_env(self.state / "current.env")
        self.assertEqual(digest, current["MOON_IMAGE_DIGEST"])
        self.assertEqual(revision, current["MOON_IMAGE_REVISION"])
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual("deployed", read_env(self.state / "last-result.env")["RESULT"])
        self.assertIn(f"pull ghcr.io/rapucha/moon-service@{digest}", (self.root / "commands.log").read_text())

    def test_restart_force_recreates_recorded_current(self):
        digest, revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        commands_before = (self.root / "commands.log").read_text().splitlines()

        result = self.run_deploy("restart")

        self.assertEqual(0, result.returncode, result.stderr)
        current = read_env(self.state / "current.env")
        self.assertEqual(digest, current["MOON_IMAGE_DIGEST"])
        self.assertEqual(revision, current["MOON_IMAGE_REVISION"])
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual("restarted", read_env(self.state / "last-result.env")["RESULT"])
        restart_commands = (self.root / "commands.log").read_text().splitlines()[
            len(commands_before) :
        ]
        compose_commands = [
            command for command in restart_commands if command.startswith("compose ")
        ]
        self.assertEqual(1, len(compose_commands))
        self.assertIn("--force-recreate", compose_commands[0])

    def test_healthy_update_retains_previous_known_good_digest(self):
        first_digest, first_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        second_digest, second_revision = self.set_manifest("b", "2")

        result = self.run_deploy()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(second_digest, read_env(self.state / "current.env")["MOON_IMAGE_DIGEST"])
        self.assertEqual(first_digest, read_env(self.state / "previous.env")["MOON_IMAGE_DIGEST"])
        self.assertEqual(first_revision, read_env(self.state / "previous.env")["MOON_IMAGE_REVISION"])
        self.assertEqual(second_revision, (self.root / "active-revision").read_text().strip())

    def test_deployment_reports_in_progress_and_exact_success(self):
        digest, revision = self.set_manifest("a", "1")

        result = self.run_deploy(MOON_GITHUB_REPORTING_ENABLED="true")

        self.assertEqual(0, result.returncode, result.stderr)
        reports = (self.root / "github-reports.log").read_text().splitlines()
        self.assertEqual(2, len(reports))
        self.assertEqual(
            f"in_progress {digest} {revision} "
            "Pi is pulling and verifying the requested immutable image",
            reports[0],
        )
        self.assertEqual(
            f"success {digest} {revision} "
            "Pi is healthy on the exact requested image revision and digest",
            reports[1],
        )

    def test_already_current_poll_retries_success_report(self):
        digest, revision = self.set_manifest("a", "1")
        self.assertEqual(
            0,
            self.run_deploy(MOON_GITHUB_REPORTING_ENABLED="true").returncode,
        )

        result = self.run_deploy(MOON_GITHUB_REPORTING_ENABLED="true")

        self.assertEqual(0, result.returncode, result.stderr)
        reports = (self.root / "github-reports.log").read_text().splitlines()
        self.assertEqual(3, len(reports))
        self.assertEqual(
            f"success {digest} {revision} "
            "Pi is healthy on the exact requested image revision and digest",
            reports[-1],
        )

    def test_reporting_outage_does_not_undo_a_healthy_deployment(self):
        digest, revision = self.set_manifest("a", "1")

        result = self.run_deploy(
            MOON_GITHUB_REPORTING_ENABLED="true", FAKE_GITHUB_REPORT_FAIL=1
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(digest, read_env(self.state / "current.env")["MOON_IMAGE_DIGEST"])
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertIn("a later poll will retry", result.stdout)

    def test_slow_reporting_is_bounded_and_does_not_block_deployment(self):
        digest, revision = self.set_manifest("a", "1")

        started = time.monotonic()
        result = self.run_deploy(
            MOON_GITHUB_REPORTING_ENABLED="true",
            MOON_GITHUB_REPORT_TIMEOUT_SECONDS=1,
            FAKE_GITHUB_REPORT_SLEEP_SECONDS=10,
        )
        elapsed = time.monotonic() - started

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertLess(elapsed, 5)
        self.assertEqual(digest, read_env(self.state / "current.env")["MOON_IMAGE_DIGEST"])
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual(2, result.stdout.count("a later poll will retry"))

    def test_candidate_with_mismatched_container_identity_is_rejected(self):
        _, first_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, candidate_revision = self.set_manifest("b", "2")

        result = self.run_deploy(
            FAKE_IDENTITY_MISMATCH_REVISION=candidate_revision,
            MOON_GITHUB_REPORTING_ENABLED="true",
        )

        self.assertEqual(1, result.returncode)
        self.assertEqual(first_revision, (self.root / "active-revision").read_text().strip())
        self.assert_rejected(candidate_digest, candidate_revision, "identity")
        self.assertTrue(
            (self.root / "github-reports.log")
            .read_text()
            .splitlines()[-1]
            .startswith(f"failure {candidate_digest} {candidate_revision} ")
        )

    def test_candidate_compose_failure_records_stage_and_rolls_back(self):
        _, current_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, candidate_revision = self.set_manifest("b", "2")

        result = self.run_deploy(FAKE_COMPOSE_FAIL_REVISION=candidate_revision)

        self.assertEqual(1, result.returncode)
        self.assertEqual(current_revision, (self.root / "active-revision").read_text().strip())
        self.assert_rejected(candidate_digest, candidate_revision, "compose")
        self.assertEqual("rolled-back", read_env(self.state / "last-result.env")["RESULT"])

    def test_unhealthy_candidate_rolls_back_once_and_is_quarantined(self):
        _, current_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, candidate_revision = self.set_manifest("b", "2")

        failed = self.run_deploy(FAKE_UNHEALTHY_REVISION=candidate_revision)

        self.assertEqual(1, failed.returncode)
        self.assertEqual(current_revision, (self.root / "active-revision").read_text().strip())
        self.assert_rejected(candidate_digest, candidate_revision, "readiness")
        self.assertEqual("rolled-back", read_env(self.state / "last-result.env")["RESULT"])
        rejected_before = (self.state / "rejected.env").read_text(encoding="utf-8")
        pulls_before = sum(
            line.startswith("pull ") for line in (self.root / "commands.log").read_text().splitlines()
        )

        skipped = self.run_deploy(FAKE_UNHEALTHY_REVISION=candidate_revision)

        self.assertEqual(0, skipped.returncode, skipped.stderr)
        pulls_after = sum(
            line.startswith("pull ") for line in (self.root / "commands.log").read_text().splitlines()
        )
        self.assertEqual(pulls_before, pulls_after)
        self.assertEqual("skipped", read_env(self.state / "last-result.env")["RESULT"])
        self.assertEqual(rejected_before, (self.state / "rejected.env").read_text(encoding="utf-8"))

        third_digest, _ = self.set_manifest("c", "3")
        self.assertEqual(0, self.run_deploy().returncode)
        self.assertEqual(third_digest, read_env(self.state / "current.env")["MOON_IMAGE_DIGEST"])
        self.assertFalse((self.state / "rejected.env").exists())

    def test_final_readiness_recheck_records_readiness_stage(self):
        self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, candidate_revision = self.set_manifest("b", "2")

        result = self.run_deploy(FAKE_FINAL_READINESS_FAIL_REVISION=candidate_revision)

        self.assertEqual(1, result.returncode)
        self.assert_rejected(candidate_digest, candidate_revision, "readiness")

    def test_legacy_rejected_state_remains_compatible_with_resume(self):
        _, current_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, candidate_revision = self.set_manifest("b", "2")
        (self.state / "rejected.env").write_text(
            "MOON_IMAGE_REPOSITORY=ghcr.io/rapucha/moon-service\n"
            f"MOON_IMAGE_DIGEST={candidate_digest}\n"
            f"MOON_IMAGE_REVISION={candidate_revision}\n"
            f"MOON_DEPLOYED_AT={REJECTED_AT}\n",
            encoding="utf-8",
        )

        skipped = self.run_deploy()
        self.assertEqual(0, skipped.returncode, skipped.stderr)
        self.assertEqual(current_revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual("skipped", read_env(self.state / "last-result.env")["RESULT"])

        resumed = self.run_deploy("resume")
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        self.assertEqual(candidate_revision, (self.root / "active-revision").read_text().strip())
        self.assertFalse((self.state / "rejected.env").exists())

    def test_registry_failure_keeps_current_revision_running(self):
        _, revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)

        result = self.run_deploy(FAKE_REGISTRY_FAIL=1)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual("deferred", read_env(self.state / "last-result.env")["RESULT"])

    def test_poll_reconciles_recorded_digest_before_registry_discovery(self):
        digest, revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        _, interrupted_revision = self.set_manifest("b", "2")
        (self.root / "active-revision").write_text(interrupted_revision + "\n", encoding="utf-8")
        (self.root / "active-digest").write_text("sha256:" + "b" * 64 + "\n", encoding="utf-8")

        result = self.run_deploy(FAKE_REGISTRY_FAIL=1)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual(digest, (self.root / "active-digest").read_text().strip())

    def test_pull_failure_keeps_current_revision_running(self):
        _, revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        candidate_digest, _ = self.set_manifest("b", "2")

        result = self.run_deploy(FAKE_PULL_FAIL=1)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(revision, (self.root / "active-revision").read_text().strip())
        last_result = read_env(self.state / "last-result.env")
        self.assertEqual("deferred", last_result["RESULT"])
        self.assertEqual(candidate_digest, last_result["DIGEST"])

    def test_cleanup_removes_only_images_older_than_rollback_generation(self):
        first_digest, _ = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        second_digest, _ = self.set_manifest("b", "2")
        self.assertEqual(0, self.run_deploy().returncode)

        commands_after_two = (self.root / "commands.log").read_text()
        self.assertNotIn(f"image rm ghcr.io/rapucha/moon-service@{first_digest}", commands_after_two)
        self.assertNotIn(f"image rm ghcr.io/rapucha/moon-service@{second_digest}", commands_after_two)

        third_digest, _ = self.set_manifest("c", "3")
        self.assertEqual(0, self.run_deploy().returncode)
        commands_after_three = (self.root / "commands.log").read_text()

        self.assertIn(f"image rm ghcr.io/rapucha/moon-service@{first_digest}", commands_after_three)
        self.assertNotIn(f"image rm ghcr.io/rapucha/moon-service@{second_digest}", commands_after_three)
        self.assertNotIn(f"image rm ghcr.io/rapucha/moon-service@{third_digest}", commands_after_three)

    def test_manual_rollback_holds_updates_until_resume(self):
        _, first_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        _, second_revision = self.set_manifest("b", "2")
        self.assertEqual(0, self.run_deploy().returncode)

        rollback = self.run_deploy("rollback")

        self.assertEqual(0, rollback.returncode, rollback.stderr)
        self.assertEqual(first_revision, read_env(self.state / "current.env")["MOON_IMAGE_REVISION"])
        self.assertEqual(second_revision, read_env(self.state / "previous.env")["MOON_IMAGE_REVISION"])
        self.assertTrue((self.state / "automatic-updates-held").exists())

        held = self.run_deploy()
        self.assertEqual(0, held.returncode, held.stderr)
        self.assertEqual(first_revision, (self.root / "active-revision").read_text().strip())

        resumed = self.run_deploy("resume")
        self.assertEqual(0, resumed.returncode, resumed.stderr)
        self.assertFalse((self.state / "automatic-updates-held").exists())
        self.assertEqual(second_revision, read_env(self.state / "current.env")["MOON_IMAGE_REVISION"])

    def test_boot_reconciliation_restores_previous_when_current_is_unhealthy(self):
        _, first_revision = self.set_manifest("a", "1")
        self.assertEqual(0, self.run_deploy().returncode)
        second_digest, second_revision = self.set_manifest("b", "2")
        self.assertEqual(0, self.run_deploy().returncode)

        result = self.run_deploy(FAKE_UNHEALTHY_REVISION=second_revision)

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(first_revision, read_env(self.state / "current.env")["MOON_IMAGE_REVISION"])
        self.assert_rejected(second_digest, second_revision, "readiness")
        self.assertEqual(first_revision, (self.root / "active-revision").read_text().strip())
        self.assertEqual("boot-rollback", read_env(self.state / "last-result.env")["RESULT"])

    def test_lock_prevents_overlapping_attempts(self):
        self.set_manifest("a", "1")
        lock_path = self.state / "deploy.lock"
        with lock_path.open("w", encoding="utf-8") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_deploy()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("holds the lock", result.stdout)
        self.assertFalse((self.state / "current.env").exists())

    def test_operator_action_fails_when_lock_cannot_be_acquired(self):
        lock_path = self.state / "deploy.lock"
        with lock_path.open("w", encoding="utf-8") as lock:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            result = self.run_deploy("rollback", MOON_OPERATOR_LOCK_TIMEOUT_SECONDS=0)

        self.assertEqual(75, result.returncode)
        self.assertIn("could not acquire", result.stdout)


if __name__ == "__main__":
    unittest.main()
