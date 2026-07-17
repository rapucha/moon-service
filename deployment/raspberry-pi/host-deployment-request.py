#!/usr/bin/env python3
"""Queue a GitHub Deployment when the promoted Pi host contract changed."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


API_HEADERS = ("--header", "Accept: application/vnd.github+json", "--header", "X-GitHub-Api-Version: 2026-03-10")
ENVIRONMENT = "raspberry-pi-host-config"
TASK = "provision:raspberry-pi"
ROLE_PATH = "deployment/raspberry-pi/roles/moon_service_host"
FINGERPRINT_HELPER_PATH = "deployment/raspberry-pi/host-contract-fingerprint.py"
PRODUCER_PATH = "deployment/raspberry-pi/host-deployment-request.py"
TRIGGER_PATHS = (ROLE_PATH, FINGERPRINT_HELPER_PATH)
FINGERPRINT_PATTERN = re.compile(r"sha256:[0-9a-f]{64}")
REVISION_PATTERN = re.compile(r"[0-9a-f]{40}")
REPOSITORY_PATTERN = re.compile(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
RUN_ID_PATTERN = re.compile(r"[1-9][0-9]*")
SERVER_URL_PATTERN = re.compile(r"https://[^/\s]+")
ZERO_REVISION = "0" * 40
DIAGNOSTIC_LIMIT = 500


class ContractError(RuntimeError):
    """The workflow inputs, Git history, or API response violated the contract."""


def bounded(value: Any) -> str:
    rendered = repr(value)
    return rendered if len(rendered) <= DIAGNOSTIC_LIMIT else rendered[:483] + "...<truncated>"


def command_details(result: subprocess.CompletedProcess[str]) -> str:
    return f"returncode={result.returncode}, stdout={bounded(result.stdout)}, stderr={bounded(result.stderr)}"


def command_error(action: str, result: subprocess.CompletedProcess[str]) -> ContractError:
    return ContractError(f"{action}: {command_details(result)}")


def command(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
        check=False,
    )


def validate_revision(value: str, name: str) -> None:
    if not REVISION_PATTERN.fullmatch(value) or value == ZERO_REVISION:
        raise ContractError(f"{name} must be a nonzero 40-character lowercase Git SHA; got {bounded(value)}")


def require_commit(root: Path, revision: str, name: str) -> None:
    result = command(root, "rev-parse", "--verify", f"{revision}^{{commit}}")
    if result.returncode != 0 or result.stdout.strip() != revision:
        raise ContractError(f"{name} {revision} is not an available commit: {command_details(result)}")


def is_ancestor(root: Path, ancestor: str, revision: str) -> bool:
    result = command(root, "merge-base", "--is-ancestor", ancestor, revision)
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise command_error(
        f"git merge-base could not test whether {ancestor} is an ancestor of {revision}", result
    )


def host_change_baseline(root: Path, push_before: str, previous_promoted: str, revision: str) -> str:
    baselines = (
        (push_before, "HOST_PUSH_BEFORE_REVISION"),
        (previous_promoted, "HOST_PREVIOUS_PROMOTED_REVISION"),
    )
    for value, name in (*baselines, (revision, "HOST_REVISION")):
        validate_revision(value, name)
        require_commit(root, value, name)

    for value, name in baselines:
        if not is_ancestor(root, value, revision):
            raise ContractError(
                f"{name} {value} is not an ancestor of HOST_REVISION {revision}"
            )

    if is_ancestor(root, push_before, previous_promoted):
        return push_before
    if is_ancestor(root, previous_promoted, push_before):
        return previous_promoted
    raise ContractError(
        f"host range baselines are not linearly related: "
        f"push_before={push_before}, previous_promoted={previous_promoted}"
    )


def host_contract_changed(root: Path, push_before: str, previous_promoted: str, revision: str) -> bool:
    before = host_change_baseline(root, push_before, previous_promoted, revision)

    producer = command(root, "ls-tree", "--name-only", before, "--", PRODUCER_PATH)
    if producer.returncode != 0:
        raise command_error(f"git ls-tree could not inspect {PRODUCER_PATH} at {before}", producer)
    if producer.stdout.strip() != PRODUCER_PATH:
        return True

    difference = command(root, "diff", "--quiet", before, revision, "--", *TRIGGER_PATHS)
    if difference.returncode == 0:
        return False
    if difference.returncode == 1:
        return True
    raise command_error(
        f"git diff could not compare {bounded(TRIGGER_PATHS)} from {before} to {revision}",
        difference,
    )


def host_fingerprint(root: Path, revision: str) -> str:
    result = subprocess.run(
        [sys.executable, str(root / FINGERPRINT_HELPER_PATH), "--revision", revision],
        text=True,
        capture_output=True,
        check=False,
    )
    fingerprint = result.stdout.strip()
    if result.returncode != 0:
        raise command_error(f"host fingerprint helper failed for {revision}", result)
    if not FINGERPRINT_PATTERN.fullmatch(fingerprint):
        raise ContractError(
            f"host fingerprint helper returned an invalid value for {revision}: "
            f"{command_details(result)}"
        )
    return fingerprint


def github_api_call(endpoint: str, body: dict[str, Any]) -> Any:
    result = subprocess.run(
        ["gh", "api", *API_HEADERS, "--method", "POST", endpoint, "--input", "-"],
        input=json.dumps(body, separators=(",", ":")),
        text=True,
        capture_output=True,
        check=False,
    )
    if result.returncode != 0:
        raise command_error(f"GitHub API POST {endpoint} failed", result)
    try:
        return json.loads(result.stdout)
    except json.JSONDecodeError as error:
        raise ContractError(
            f"GitHub API POST {endpoint} returned invalid JSON at character "
            f"{error.pos} ({error.msg}): "
            f"stdout={bounded(result.stdout)}"
        ) from error


def deployment_request(
    revision: str, fingerprint: str, run_id: int, run_url: str
) -> dict[str, Any]:
    return {
        "ref": revision,
        "task": TASK,
        "auto_merge": False,
        "required_contexts": [],
        "payload": {
            "schema_version": 1,
            "host_configuration_fingerprint": fingerprint,
            "workflow_run_id": run_id,
            "workflow_run_url": run_url,
        },
        "environment": ENVIRONMENT,
        "description": f"Apply Pi host configuration {fingerprint[7:19]}",
        "transient_environment": False,
        "production_environment": False,
    }


def positive_id(value: Any) -> int:
    if type(value) is not int or value <= 0:
        detail = f"{bounded(value)} ({type(value).__name__})"
        raise ContractError(f"deployment ID must be a positive integer; got {detail}")
    return value


def require_fields(response: dict[str, Any], expected: dict[str, Any], name: str) -> None:
    mismatches = {
        field: (value, response.get(field))
        for field, value in expected.items() if response.get(field) != value
    }
    if mismatches:
        raise ContractError(f"{name} field mismatches (expected, actual): {bounded(mismatches)}")


def validate_created(response: Any, request: dict[str, Any]) -> int:
    if not isinstance(response, dict):
        raise ContractError(
            f"created deployment response must be an object; got {type(response).__name__} {bounded(response)}"
        )
    deployment_id = positive_id(response.get("id"))
    expected = {name: request[name] for name in ("ref", "task", "environment", "payload")}
    expected.update(sha=request["ref"], transient_environment=False, production_environment=False)
    require_fields(response, expected, "created deployment")
    return deployment_id


def queued_status(run_url: str) -> dict[str, Any]:
    return {
        "state": "queued",
        "environment": ENVIRONMENT,
        "description": "Waiting for manual Ansible host convergence",
        "log_url": run_url,
        "auto_inactive": False,
    }


def validate_queued(response: Any, request: dict[str, Any]) -> None:
    if not isinstance(response, dict):
        raise ContractError(
            f"queued status response must be an object; got {type(response).__name__} {bounded(response)}"
        )
    expected = {name: request[name] for name in ("state", "environment", "log_url")}
    require_fields(response, expected, "queued status")


def required_environment(name: str, pattern: re.Pattern[str]) -> str:
    value = os.environ.get(name, "")
    if not pattern.fullmatch(value):
        raise ContractError(f"invalid {name}: got {bounded(value)}")
    return value


def run(api_call=github_api_call, root: Path | None = None) -> None:
    root = root or Path(__file__).resolve().parents[2]
    push_before = required_environment("HOST_PUSH_BEFORE_REVISION", REVISION_PATTERN)
    previous_promoted = required_environment(
        "HOST_PREVIOUS_PROMOTED_REVISION", REVISION_PATTERN
    )
    revision = required_environment("HOST_REVISION", REVISION_PATTERN)
    if not host_contract_changed(root, push_before, previous_promoted, revision):
        print("Tracked host configuration did not change; no request queued")
        return

    repository = required_environment("GITHUB_REPOSITORY", REPOSITORY_PATTERN)
    server_url = required_environment("GITHUB_SERVER_URL", SERVER_URL_PATTERN)
    run_id = int(required_environment("GITHUB_RUN_ID", RUN_ID_PATTERN))
    run_url = f"{server_url}/{repository}/actions/runs/{run_id}"
    fingerprint = host_fingerprint(root, revision)
    request = deployment_request(revision, fingerprint, run_id, run_url)
    deployment_id = validate_created(
        api_call(f"repos/{repository}/deployments", request), request
    )
    status = queued_status(run_url)
    validate_queued(
        api_call(f"repos/{repository}/deployments/{deployment_id}/statuses", status),
        status,
    )
    print(f"Queued host-configuration deployment {deployment_id} for {fingerprint}")


def main() -> None:
    try:
        run()
    except ContractError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
