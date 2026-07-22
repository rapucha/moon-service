#!/usr/bin/env python3
"""Validate a pull request before publishing its preview image."""

from __future__ import annotations

import argparse
import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Mapping, Sequence

API_ROOT = "https://api.github.com"
API_VERSION = "2026-03-10"
REPOSITORY = "rapucha/moon-service"
OWNER = "rapucha"
BASE_BRANCH = "main"
PREVIEW_WORKFLOW_PATH = ".github/workflows/publish-pr-preview.yml"
PREVIEW_WORKFLOW_REF = (
    f"{REPOSITORY}/{PREVIEW_WORKFLOW_PATH}@refs/heads/{BASE_BRANCH}"
)
TEST_WORKFLOW_FILE = "test-and-publish-container.yml"
TEST_WORKFLOW_PATH = f".github/workflows/{TEST_WORKFLOW_FILE}"
TEST_WORKFLOW_NAME = "Test and publish container"
REQUIRED_JOBS = ("Backend tests", "Frontend tests", "Deployment tests")
PAGE_SIZE = 100
MAX_COLLECTION_ITEMS = 1_000
MAX_RESPONSE_BYTES = 8 * 1024 * 1024
SHA_PATTERN = re.compile(r"[0-9a-f]{40}\Z")
INTEGER_PATTERN = re.compile(r"[1-9][0-9]*\Z")

class ValidationError(Exception):
    """A safe, user-facing validation failure."""


class RejectRedirects(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, fp, code, message, headers, new_url):
        raise urllib.error.HTTPError(
            request.full_url,
            code,
            message,
            headers,
            fp,
        )


@dataclass(frozen=True)
class PullIdentity:
    number: int
    repository_id: int
    head_ref: str
    head_sha: str

@dataclass(frozen=True)
class TestRun:
    run_id: int
    run_number: int
    run_attempt: int

@dataclass(frozen=True)
class ValidationResult:
    pull: PullIdentity
    test_run: TestRun

def _mapping(value: Any, label: str) -> Mapping[str, Any]:
    if not isinstance(value, dict):
        raise ValidationError(f"GitHub returned an invalid {label}")
    return value

def _list(value: Any, label: str) -> list[Any]:
    if not isinstance(value, list):
        raise ValidationError(f"GitHub returned an invalid {label}")
    return value

def _string(value: Any, label: str) -> str:
    if not isinstance(value, str):
        raise ValidationError(f"GitHub returned an invalid {label}")
    return value

def _positive_integer(value: Any, label: str) -> int:
    if type(value) is not int or value <= 0:
        raise ValidationError(f"GitHub returned an invalid {label}")
    return value

def _nested(data: Mapping[str, Any], *keys: str) -> Any:
    value: Any = data
    for key in keys:
        value = _mapping(value, ".".join(keys)).get(key)
    return value

def _parse_pull_number(raw: str) -> int:
    if not INTEGER_PATTERN.fullmatch(raw):
        raise ValidationError("pull-request number must be a positive integer")
    value = int(raw)
    if value > 2_147_483_647:
        raise ValidationError("pull-request number is out of range")
    return value

def _parse_sha(raw: str, label: str) -> str:
    if not SHA_PATTERN.fullmatch(raw):
        raise ValidationError(f"{label} must be a full lowercase commit SHA")
    return raw

def validate_dispatch_context(environment: Mapping[str, str]) -> None:
    expected = {
        "GITHUB_API_URL": API_ROOT,
        "GITHUB_EVENT_NAME": "workflow_dispatch",
        "GITHUB_REPOSITORY": REPOSITORY,
        "GITHUB_REF": f"refs/heads/{BASE_BRANCH}",
        "GITHUB_WORKFLOW_REF": PREVIEW_WORKFLOW_REF,
        "GITHUB_ACTOR": OWNER,
        "GITHUB_TRIGGERING_ACTOR": OWNER,
    }
    for name, wanted in expected.items():
        if environment.get(name) != wanted:
            raise ValidationError(f"trusted dispatch check failed for {name}")
    if not environment.get("GITHUB_TOKEN"):
        raise ValidationError("GITHUB_TOKEN is required")
    if not environment.get("GITHUB_OUTPUT"):
        raise ValidationError("GITHUB_OUTPUT is required")


class GitHubApi:
    def __init__(self, token: str) -> None:
        self._token = token
        self._opener = urllib.request.build_opener(RejectRedirects())

    def get(self, path: str, query: Mapping[str, str] | None = None) -> Any:
        if not path.startswith("/") or ".." in path:
            raise ValidationError("internal GitHub API path is invalid")
        url = f"{API_ROOT}{path}"
        if query:
            url = f"{url}?{urllib.parse.urlencode(query)}"
        request = urllib.request.Request(
            url,
            headers={
                "Accept": "application/vnd.github+json",
                "Authorization": f"Bearer {self._token}",
                "Cache-Control": "no-cache",
                "User-Agent": "moon-service-preview-validator",
                "X-GitHub-Api-Version": API_VERSION,
            },
        )
        try:
            with self._opener.open(request, timeout=30) as response:
                if response.status != 200:
                    raise ValidationError(
                        f"GitHub API returned HTTP {response.status} for {path}"
                    )
                body = response.read(MAX_RESPONSE_BYTES + 1)
        except urllib.error.HTTPError as error:
            code = error.code
            error.close()
            raise ValidationError(
                f"GitHub API returned HTTP {code} for {path}"
            ) from None
        except (urllib.error.URLError, TimeoutError, OSError):
            raise ValidationError(f"GitHub API request failed for {path}") from None
        if len(body) > MAX_RESPONSE_BYTES:
            raise ValidationError(f"GitHub API response was too large for {path}")
        try:
            return json.loads(body.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError):
            raise ValidationError(f"GitHub API returned invalid JSON for {path}") from None

    def collection(
        self, path: str, key: str, query: Mapping[str, str] | None = None
    ) -> list[Any]:
        base_query = dict(query or {})
        collected: list[Any] = []
        expected_total: int | None = None
        page = 1
        while True:
            page_query = {**base_query, "per_page": str(PAGE_SIZE), "page": str(page)}
            payload = _mapping(self.get(path, page_query), f"{key} response")
            total = payload.get("total_count")
            if type(total) is not int or total < 0:
                raise ValidationError(f"GitHub returned an invalid {key} total")
            if total > MAX_COLLECTION_ITEMS:
                raise ValidationError(f"GitHub returned too many {key}")
            if expected_total is None:
                expected_total = total
            elif total != expected_total:
                raise ValidationError(f"GitHub changed the {key} total during pagination")
            items = _list(payload.get(key), key)
            if len(items) > PAGE_SIZE:
                raise ValidationError(f"GitHub returned an oversized {key} page")
            collected.extend(items)
            if len(collected) >= expected_total:
                break
            if not items or page >= (MAX_COLLECTION_ITEMS // PAGE_SIZE):
                raise ValidationError(f"GitHub returned incomplete {key} pagination")
            page += 1
        if len(collected) != expected_total:
            raise ValidationError(f"GitHub returned inconsistent {key} pagination")
        return collected


def _parse_pull(payload: Any, pull_number: int) -> PullIdentity:
    pull = _mapping(payload, "pull request")
    if _positive_integer(pull.get("number"), "pull-request number") != pull_number:
        raise ValidationError("GitHub returned a different pull request")
    if pull.get("state") != "open":
        raise ValidationError("pull request is not open")
    base = _mapping(pull.get("base"), "pull-request base")
    head = _mapping(pull.get("head"), "pull-request head")
    if base.get("ref") != BASE_BRANCH:
        raise ValidationError("pull request does not target main")
    base_repository = _mapping(base.get("repo"), "base repository")
    head_repository = _mapping(head.get("repo"), "head repository")
    if base_repository.get("full_name") != REPOSITORY:
        raise ValidationError("pull-request base repository does not match")
    if head_repository.get("full_name") != REPOSITORY:
        raise ValidationError("fork pull requests are not allowed")
    base_id = _positive_integer(base_repository.get("id"), "base repository ID")
    head_id = _positive_integer(head_repository.get("id"), "head repository ID")
    if base_id != head_id:
        raise ValidationError("pull-request repositories do not match")
    head_ref = _string(head.get("ref"), "head branch")
    if not head_ref or len(head_ref) > 1_024 or any(ord(char) < 32 for char in head_ref):
        raise ValidationError("pull request has an invalid head branch")
    head_sha = _parse_sha(_string(head.get("sha"), "head SHA"), "head SHA")
    return PullIdentity(pull_number, base_id, head_ref, head_sha)


def _pull_request(api: GitHubApi, pull_number: int) -> PullIdentity:
    return _parse_pull(api.get(f"/repos/{REPOSITORY}/pulls/{pull_number}"), pull_number)


def _workflow_id(api: GitHubApi) -> int:
    payload = _mapping(
        api.get(f"/repos/{REPOSITORY}/actions/workflows/{TEST_WORKFLOW_FILE}"),
        "test workflow",
    )
    if payload.get("name") != TEST_WORKFLOW_NAME:
        raise ValidationError("test workflow name does not match")
    if payload.get("path") != TEST_WORKFLOW_PATH:
        raise ValidationError("test workflow path does not match")
    if payload.get("state") != "active":
        raise ValidationError("test workflow is not active")
    return _positive_integer(payload.get("id"), "test workflow ID")


def _validate_run_pull(
    run: Mapping[str, Any], pull: PullIdentity, label: str
) -> None:
    associations = _list(run.get("pull_requests"), f"{label} pull requests")
    if len(associations) != 1:
        raise ValidationError(f"{label} is not bound to exactly one pull request")
    association = _mapping(associations[0], f"{label} pull request")
    if _positive_integer(association.get("number"), f"{label} PR number") != pull.number:
        raise ValidationError(f"{label} belongs to a different pull request")
    head = _mapping(association.get("head"), f"{label} PR head")
    base = _mapping(association.get("base"), f"{label} PR base")
    if head.get("ref") != pull.head_ref or head.get("sha") != pull.head_sha:
        raise ValidationError(f"{label} pull-request head does not match")
    if base.get("ref") != BASE_BRANCH:
        raise ValidationError(f"{label} pull-request base does not match")
    if _nested(head, "repo", "id") != pull.repository_id:
        raise ValidationError(f"{label} head repository does not match")
    if _nested(base, "repo", "id") != pull.repository_id:
        raise ValidationError(f"{label} base repository does not match")


def _latest_test_run(api: GitHubApi, workflow_id: int, pull: PullIdentity) -> TestRun:
    path = f"/repos/{REPOSITORY}/actions/workflows/{workflow_id}/runs"
    runs = api.collection(
        path,
        "workflow_runs",
        {
            "event": "pull_request",
            "branch": pull.head_ref,
            "head_sha": pull.head_sha,
            "exclude_pull_requests": "false",
        },
    )
    if not runs:
        raise ValidationError("no matching pull-request test workflow run exists")
    parsed: list[tuple[int, int, int, Mapping[str, Any]]] = []
    seen_ids: set[int] = set()
    seen_numbers: set[int] = set()
    for item in runs:
        run = _mapping(item, "workflow run")
        run_id = _positive_integer(run.get("id"), "workflow run ID")
        run_number = _positive_integer(run.get("run_number"), "workflow run number")
        run_attempt = _positive_integer(run.get("run_attempt"), "workflow run attempt")
        if run_id in seen_ids or run_number in seen_numbers:
            raise ValidationError("GitHub returned duplicate workflow runs")
        seen_ids.add(run_id)
        seen_numbers.add(run_number)
        if run.get("workflow_id") != workflow_id:
            raise ValidationError("workflow run belongs to a different workflow")
        if run.get("event") != "pull_request":
            raise ValidationError("workflow run has the wrong event")
        if run.get("head_branch") != pull.head_ref or run.get("head_sha") != pull.head_sha:
            raise ValidationError("workflow run head does not match")
        _validate_run_pull(run, pull, "workflow run")
        parsed.append((run_number, run_id, run_attempt, run))
    run_number, run_id, run_attempt, latest = max(parsed, key=lambda item: item[:2])
    if latest.get("status") != "completed" or latest.get("conclusion") != "success":
        raise ValidationError("newest matching workflow run is not successful")
    return TestRun(run_id, run_number, run_attempt)


def _validate_jobs(api: GitHubApi, pull: PullIdentity, run: TestRun) -> None:
    path = (
        f"/repos/{REPOSITORY}/actions/runs/{run.run_id}"
        f"/attempts/{run.run_attempt}/jobs"
    )
    jobs = api.collection(path, "jobs")
    required: dict[str, list[Mapping[str, Any]]] = {name: [] for name in REQUIRED_JOBS}
    seen_ids: set[int] = set()
    for item in jobs:
        job = _mapping(item, "workflow job")
        job_id = _positive_integer(job.get("id"), "workflow job ID")
        if job_id in seen_ids:
            raise ValidationError("GitHub returned duplicate workflow jobs")
        seen_ids.add(job_id)
        name = _string(job.get("name"), "workflow job name")
        if name in required:
            required[name].append(job)
    for name, matches in required.items():
        if len(matches) != 1:
            raise ValidationError(f"required job {name} is missing or duplicated")
        job = matches[0]
        if job.get("run_id") != run.run_id:
            raise ValidationError(f"required job {name} has the wrong run ID")
        if job.get("head_sha") != pull.head_sha or job.get("head_branch") != pull.head_ref:
            raise ValidationError(f"required job {name} has the wrong head")
        if job.get("workflow_name") != TEST_WORKFLOW_NAME:
            raise ValidationError(f"required job {name} has the wrong workflow")
        if job.get("status") != "completed" or job.get("conclusion") != "success":
            raise ValidationError(f"required job {name} is not successful")


def validate_candidate(
    api: GitHubApi, pull_number: int, expected_head_sha: str | None = None
) -> ValidationResult:
    pull = _pull_request(api, pull_number)
    if expected_head_sha is not None and pull.head_sha != expected_head_sha:
        raise ValidationError("pull-request head changed after the build")
    workflow_id = _workflow_id(api)
    test_run = _latest_test_run(api, workflow_id, pull)
    _validate_jobs(api, pull, test_run)
    current_run = _latest_test_run(api, workflow_id, pull)
    if current_run != test_run:
        raise ValidationError("newest test workflow run changed during validation")
    current_pull = _pull_request(api, pull_number)
    if current_pull != pull:
        raise ValidationError("pull request changed during validation")
    return ValidationResult(pull, test_run)


def _write_outputs(path: str, result: ValidationResult) -> None:
    values = {
        "pull_request_number": str(result.pull.number),
        "head_sha": result.pull.head_sha,
        "ci_run_id": str(result.test_run.run_id),
        "ci_run_attempt": str(result.test_run.run_attempt),
        "immutable_tag": f"preview-pr-{result.pull.number}-{result.pull.head_sha}",
    }
    payload = "".join(f"{name}={value}\n" for name, value in values.items()).encode()
    try:
        descriptor = os.open(path, os.O_WRONLY | os.O_APPEND)
        try:
            written = os.write(descriptor, payload)
        finally:
            os.close(descriptor)
    except OSError:
        raise ValidationError("could not write GITHUB_OUTPUT") from None
    if written != len(payload):
        raise ValidationError("could not write complete GITHUB_OUTPUT")


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    validate = commands.add_parser("validate")
    validate.add_argument("--pull-request", required=True)
    revalidate = commands.add_parser("revalidate")
    revalidate.add_argument("--pull-request", required=True)
    revalidate.add_argument("--expected-head-sha", required=True)
    return parser


def main(argv: Sequence[str] | None = None) -> int:
    try:
        arguments = _parser().parse_args(argv)
        validate_dispatch_context(os.environ)
        pull_number = _parse_pull_number(arguments.pull_request)
        expected_sha = None
        if arguments.command == "revalidate":
            expected_sha = _parse_sha(arguments.expected_head_sha, "expected head SHA")
        api = GitHubApi(os.environ["GITHUB_TOKEN"])
        result = validate_candidate(api, pull_number, expected_sha)
        _write_outputs(os.environ["GITHUB_OUTPUT"], result)
        print(
            f"Validated pull request {result.pull.number} at {result.pull.head_sha} "
            f"with test run {result.test_run.run_id} attempt {result.test_run.run_attempt}"
        )
        return 0
    except ValidationError as error:
        print(f"preview validation failed: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
