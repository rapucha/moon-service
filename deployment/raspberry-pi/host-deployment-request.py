#!/usr/bin/env python3
"""Create or reuse the GitHub Deployment for the tracked Pi host contract."""

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
FINGERPRINT_PATTERN = re.compile(r"sha256:[0-9a-f]{64}")
REPORTER_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9-]{0,99}\[bot\]")
REVISION_PATTERN = re.compile(r"[0-9a-f]{40}")
UNFINISHED_STATES = {"none", "queued", "pending", "in_progress"}
TERMINAL_STATES = {"success", "failure", "error", "inactive"}


class ContractError(RuntimeError):
    """The workflow inputs or GitHub response violated the producer contract."""


class GitHubApi:
    def call(self, *arguments: str, input_value: Any | None = None) -> Any:
        result = subprocess.run(
            ["gh", "api", *API_HEADERS, *arguments],
            input=(json.dumps(input_value, separators=(",", ":"))
                   if input_value is not None else None),
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

    def deployments(self, repository: str) -> Any:
        return self.call(
            "--method", "GET", "--paginate", "--slurp",
            f"repos/{repository}/deployments",
            "--field", f"environment={ENVIRONMENT}",
            "--field", f"task={TASK}",
            "--field", "per_page=100",
        )

    def statuses(self, repository: str, deployment_id: int, per_page: int) -> Any:
        return self.call(
            f"repos/{repository}/deployments/{deployment_id}/statuses?per_page={per_page}"
        )

    def create_deployment(self, repository: str, request: dict[str, Any]) -> Any:
        return self.call(
            "--method", "POST", f"repos/{repository}/deployments", "--input", "-",
            input_value=request,
        )

    def post_status(
        self,
        repository: str,
        deployment_id: int,
        state: str,
        description: str,
        log_url: str | None = None,
    ) -> None:
        arguments = [
            "--method", "POST",
            f"repos/{repository}/deployments/{deployment_id}/statuses",
            "--field", f"state={state}",
            "--field", f"environment={ENVIRONMENT}",
            "--field", "auto_inactive=false",
            "--raw-field", f"description={description}",
        ]
        if log_url is not None:
            arguments.extend(("--raw-field", f"log_url={log_url}"))
        self.call(*arguments)


def positive_id(value: Any) -> int:
    if type(value) is not int or value <= 0:
        raise ContractError("deployment ID must be a positive integer")
    return value


def valid_deployments(pages: Any, reporter: str) -> list[dict[str, Any]]:
    if not isinstance(pages, list) or any(not isinstance(page, list) for page in pages):
        raise ContractError("invalid paginated deployment response")
    deployments = []
    for value in (item for page in pages for item in page):
        if not isinstance(value, dict):
            continue
        payload = value.get("payload")
        if not isinstance(payload, dict):
            continue
        fingerprint = payload.get("host_configuration_fingerprint")
        if (
            type(value.get("id")) is int
            and value["id"] > 0
            and value.get("task") == TASK
            and value.get("environment") == ENVIRONMENT
            and type(payload.get("schema_version")) is int
            and payload["schema_version"] == 1
            and isinstance(fingerprint, str)
            and FINGERPRINT_PATTERN.fullmatch(fingerprint)
            and payload.get("deployment_reporter_login") == reporter
        ):
            deployments.append(value)
    return deployments


def newest(deployments: list[dict[str, Any]]) -> dict[str, Any] | None:
    return max(deployments, key=lambda deployment: deployment["id"], default=None)


def latest_state(statuses: Any) -> str:
    if not isinstance(statuses, list):
        raise ContractError("invalid deployment status response")
    if not statuses:
        return "none"
    for status in statuses:
        if (
            not isinstance(status, dict)
            or type(status.get("id")) is not int
            or status["id"] <= 0
            or not isinstance(status.get("state"), str)
        ):
            raise ContractError("invalid deployment status response")
    return max(statuses, key=lambda status: status["id"])["state"]


def authority_action(
    authority: dict[str, Any] | None,
    fingerprint: str,
    statuses: Any | None,
    reporter: str,
) -> str:
    if authority is None or authority["payload"]["host_configuration_fingerprint"] != fingerprint:
        return "create"
    if not isinstance(statuses, list):
        raise ContractError("matching deployment statuses were not provided")
    if any(
        status.get("state") == "success"
        and isinstance(status.get("creator"), dict)
        and status["creator"].get("login") == reporter
        for status in statuses
        if isinstance(status, dict)
    ):
        return "reuse_success"
    state = latest_state(statuses)
    if state == "none":
        return "initialize"
    if state in UNFINISHED_STATES:
        return "reuse_unfinished"
    if state in TERMINAL_STATES:
        return "create"
    raise ContractError(f"unsupported host deployment state {state}")


def host_fingerprint(revision: str) -> str:
    script = Path(__file__).with_name("host-contract-fingerprint.py")
    result = subprocess.run(
        [sys.executable, str(script), "--revision", revision],
        text=True,
        capture_output=True,
        check=True,
    )
    fingerprint = result.stdout.strip()
    if not FINGERPRINT_PATTERN.fullmatch(fingerprint):
        raise ContractError("invalid host configuration fingerprint")
    return fingerprint


def deployment_request(
    revision: str, fingerprint: str, reporter: str, run_id: int, run_url: str
) -> dict[str, Any]:
    return {
        "ref": revision,
        "task": TASK,
        "auto_merge": False,
        "required_contexts": [],
        "payload": {
            "schema_version": 1,
            "host_configuration_fingerprint": fingerprint,
            "deployment_reporter_login": reporter,
            "workflow_run_id": run_id,
            "workflow_run_url": run_url,
        },
        "environment": ENVIRONMENT,
        "description": f"Apply Pi host configuration {fingerprint[7:19]}",
        "transient_environment": False,
        "production_environment": False,
    }


def validate_created(
    response: Any, revision: str, fingerprint: str, reporter: str
) -> int:
    if not isinstance(response, dict):
        raise ContractError("invalid created deployment response")
    payload = response.get("payload")
    deployment_id = positive_id(response.get("id"))
    if not (
        response.get("sha") == revision
        and response.get("task") == TASK
        and response.get("environment") == ENVIRONMENT
        and isinstance(payload, dict)
        and payload.get("schema_version") == 1
        and payload.get("host_configuration_fingerprint") == fingerprint
        and payload.get("deployment_reporter_login") == reporter
    ):
        raise ContractError("created deployment did not match its request")
    return deployment_id


def required_environment(name: str, pattern: re.Pattern[str] | None = None) -> str:
    value = os.environ.get(name, "")
    if not value or (pattern is not None and not pattern.fullmatch(value)):
        raise ContractError(f"invalid {name}")
    return value


def run(api: GitHubApi) -> None:
    repository = required_environment("GITHUB_REPOSITORY")
    revision = required_environment("HOST_REVISION", REVISION_PATTERN)
    reporter = required_environment("EXPECTED_REPORTER_LOGIN", REPORTER_PATTERN)
    server_url = required_environment("GITHUB_SERVER_URL")
    run_id_text = required_environment("GITHUB_RUN_ID", re.compile(r"[1-9][0-9]*"))
    run_id = int(run_id_text)
    run_url = f"{server_url}/{repository}/actions/runs/{run_id}"
    fingerprint = host_fingerprint(revision)

    deployments = valid_deployments(api.deployments(repository), reporter)
    authority = newest(deployments)
    statuses = (
        api.statuses(repository, authority["id"], 100)
        if authority is not None
        and authority["payload"]["host_configuration_fingerprint"] == fingerprint
        else None
    )
    action = authority_action(authority, fingerprint, statuses, reporter)

    if action == "reuse_success":
        deployment_id = authority["id"]
        print(f"Host configuration {fingerprint} already succeeded in deployment {deployment_id}")
    elif action == "reuse_unfinished":
        deployment_id = authority["id"]
        print(f"Host configuration {fingerprint} is already queued in deployment {deployment_id}")
    elif action == "initialize":
        deployment_id = authority["id"]
        api.post_status(
            repository, deployment_id, "queued",
            "Waiting for manual Ansible host convergence",
        )
        print(f"Initialized queued host-configuration deployment {deployment_id}")
    else:
        response = api.create_deployment(
            repository, deployment_request(revision, fingerprint, reporter, run_id, run_url)
        )
        deployment_id = validate_created(response, revision, fingerprint, reporter)
        api.post_status(
            repository, deployment_id, "queued",
            "Waiting for manual Ansible host convergence", run_url,
        )

    for deployment in deployments:
        prior_id = deployment["id"]
        if prior_id == deployment_id:
            continue
        state = latest_state(api.statuses(repository, prior_id, 1))
        if state in UNFINISHED_STATES:
            api.post_status(
                repository, prior_id, "inactive",
                f"Superseded by host deployment {deployment_id}",
            )
        elif state not in TERMINAL_STATES:
            raise ContractError(f"unsupported prior host deployment state {state}")

    print(f"Host-configuration deployment {deployment_id} represents {fingerprint}")


def main() -> None:
    try:
        run(GitHubApi())
    except (ContractError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error


if __name__ == "__main__":
    main()
