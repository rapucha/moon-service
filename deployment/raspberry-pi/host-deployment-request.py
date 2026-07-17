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


API_HEADERS = (
    "--header",
    "Accept: application/vnd.github+json",
    "--header",
    "X-GitHub-Api-Version: 2026-03-10",
)
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


class ContractError(RuntimeError):
    """The workflow inputs, Git history, or API response violated the contract."""


def command(root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        text=True,
        capture_output=True,
        check=False,
    )


def validate_revision(value: str, name: str) -> None:
    if not REVISION_PATTERN.fullmatch(value) or value == ZERO_REVISION:
        raise ContractError(f"invalid {name}")


def require_commit(root: Path, revision: str, name: str) -> None:
    result = command(root, "rev-parse", "--verify", f"{revision}^{{commit}}")
    if result.returncode != 0 or result.stdout.strip() != revision:
        raise ContractError(f"{name} is not an available commit")


def is_ancestor(root: Path, ancestor: str, revision: str) -> bool:
    result = command(root, "merge-base", "--is-ancestor", ancestor, revision)
    if result.returncode == 0:
        return True
    if result.returncode == 1:
        return False
    raise ContractError("unable to validate the promoted host range")


def host_change_baseline(
    root: Path, push_before: str, previous_promoted: str, revision: str
) -> str:
    baselines = (
        (push_before, "HOST_PUSH_BEFORE_REVISION"),
        (previous_promoted, "HOST_PREVIOUS_PROMOTED_REVISION"),
    )
    for value, name in (*baselines, (revision, "HOST_REVISION")):
        validate_revision(value, name)
        require_commit(root, value, name)

    for value, name in baselines:
        if not is_ancestor(root, value, revision):
            raise ContractError(f"{name} is not an ancestor of HOST_REVISION")

    if is_ancestor(root, push_before, previous_promoted):
        return push_before
    if is_ancestor(root, previous_promoted, push_before):
        return previous_promoted
    raise ContractError("host range baselines are not on one history")


def host_contract_changed(
    root: Path, push_before: str, previous_promoted: str, revision: str
) -> bool:
    before = host_change_baseline(root, push_before, previous_promoted, revision)

    producer = command(root, "ls-tree", "--name-only", before, "--", PRODUCER_PATH)
    if producer.returncode != 0:
        raise ContractError("unable to inspect the producer activation state")
    if producer.stdout.strip() != PRODUCER_PATH:
        return True

    difference = command(root, "diff", "--quiet", before, revision, "--", *TRIGGER_PATHS)
    if difference.returncode == 0:
        return False
    if difference.returncode == 1:
        return True
    raise ContractError("unable to compare the promoted host contract")


def host_fingerprint(root: Path, revision: str) -> str:
    result = subprocess.run(
        [sys.executable, str(root / FINGERPRINT_HELPER_PATH), "--revision", revision],
        text=True,
        capture_output=True,
        check=False,
    )
    fingerprint = result.stdout.strip()
    if result.returncode != 0 or not FINGERPRINT_PATTERN.fullmatch(fingerprint):
        raise ContractError("invalid host configuration fingerprint")
    return fingerprint


class GitHubApi:
    def call(self, endpoint: str, body: dict[str, Any]) -> Any:
        result = subprocess.run(
            ["gh", "api", *API_HEADERS, "--method", "POST", endpoint, "--input", "-"],
            input=json.dumps(body, separators=(",", ":")),
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise ContractError("GitHub API request failed")
        try:
            return json.loads(result.stdout)
        except json.JSONDecodeError as error:
            raise ContractError("GitHub API returned invalid JSON") from error

    def create_deployment(self, repository: str, body: dict[str, Any]) -> Any:
        return self.call(f"repos/{repository}/deployments", body)

    def post_status(
        self, repository: str, deployment_id: int, body: dict[str, Any]
    ) -> Any:
        return self.call(f"repos/{repository}/deployments/{deployment_id}/statuses", body)


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
        raise ContractError("deployment ID must be a positive integer")
    return value


def validate_created(response: Any, request: dict[str, Any]) -> int:
    if not isinstance(response, dict):
        raise ContractError("invalid created deployment response")
    deployment_id = positive_id(response.get("id"))
    if not (
        response.get("sha") == request["ref"]
        and response.get("ref") == request["ref"]
        and response.get("task") == request["task"]
        and response.get("environment") == request["environment"]
        and response.get("payload") == request["payload"]
        and response.get("transient_environment") is False
        and response.get("production_environment") is False
    ):
        raise ContractError("created deployment did not match its request")
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
    if not (
        isinstance(response, dict)
        and response.get("state") == request["state"]
        and response.get("environment") == request["environment"]
        and response.get("log_url") == request["log_url"]
    ):
        raise ContractError("created status did not match its request")


def required_environment(name: str, pattern: re.Pattern[str]) -> str:
    value = os.environ.get(name, "")
    if not pattern.fullmatch(value):
        raise ContractError(f"invalid {name}")
    return value


def run(api: GitHubApi, root: Path | None = None) -> None:
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
    deployment_id = validate_created(api.create_deployment(repository, request), request)
    status = queued_status(run_url)
    validate_queued(api.post_status(repository, deployment_id, status), status)
    print(f"Queued host-configuration deployment {deployment_id} for {fingerprint}")


def main() -> None:
    try:
        run(GitHubApi())
    except ContractError as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
