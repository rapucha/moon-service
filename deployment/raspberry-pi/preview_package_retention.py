#!/usr/bin/env python3
"""Run the bounded GHCR preview retention and capability-probe workflows.

This deployment helper owns command execution and temporary files. It delegates
all package-record validation, selection, and proof decisions to
``preview_package_versions``. The two CLI commands accept only a verified active
digest; package routes, the image repository, and probe identity rules are fixed
here so the workflow cannot broaden their scope through arguments.
"""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import time

import preview_package_versions


PACKAGE_VERSIONS_PATH = (
    "/users/rapucha/packages/container/moon-service/versions"
)
ACTIVE_VERSIONS_PATH = f"{PACKAGE_VERSIONS_PATH}?state=active&per_page=100"
IMAGE_REPOSITORY = "ghcr.io/rapucha/moon-service"
PROBE_LABEL = "dev.moonservice.preview.package-capability-probe"

_DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}")
_POSITIVE_RE = re.compile(r"[1-9][0-9]*")


class OperationError(RuntimeError):
    """Report a command or local operation that could not complete safely."""


def _command_label(argv):
    """Return a concise command family without paths, tags, or digests."""
    if argv[:2] == ["gh", "api"]:
        return "gh api"
    if argv[:3] == ["docker", "buildx", "build"]:
        return "docker buildx build"
    if argv[:4] == ["docker", "buildx", "imagetools", "inspect"]:
        return "docker buildx imagetools inspect"
    return "external command"


def _run(runner, argv, *, capture):
    """Run one argv command and hide captured response content on failure."""
    options = {"text": True, "check": False}
    if capture:
        options.update(stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    try:
        result = runner(argv, **options)
    except (OSError, subprocess.SubprocessError) as error:
        raise OperationError(
            f"{_command_label(argv)} could not run"
        ) from error
    if result.returncode != 0:
        raise OperationError(f"{_command_label(argv)} failed")
    if not capture:
        return ""
    if not isinstance(result.stdout, str):
        raise OperationError(f"{_command_label(argv)} returned no output")
    return result.stdout


def _list_versions(runner):
    """List and validate every active package-version page."""
    output = _run(
        runner,
        ["gh", "api", "--paginate", ACTIVE_VERSIONS_PATH],
        capture=True,
    )
    return preview_package_versions.parse_page_stream(output)


def _positive(value, field):
    """Convert an exact positive decimal value or reject it."""
    text = str(value) if type(value) is int else value
    if not isinstance(text, str) or _POSITIVE_RE.fullmatch(text) is None:
        raise OperationError(f"invalid {field}")
    return int(text)


def _expected_digest(value):
    """Require the verified active digest before running any command."""
    if not isinstance(value, str) or _DIGEST_RE.fullmatch(value) is None:
        raise OperationError("invalid expected digest")
    return value


def _get_version(runner, version_id):
    """Fetch and validate one exact positive package-version ID."""
    safe_id = _positive(version_id, "package-version ID")
    output = _run(
        runner,
        ["gh", "api", f"{PACKAGE_VERSIONS_PATH}/{safe_id}"],
        capture=True,
    )
    return preview_package_versions.parse_record(output)


def _delete_version(runner, version_id):
    """Delete only one validated positive package-version ID."""
    safe_id = _positive(version_id, "package-version ID")
    _run(
        runner,
        [
            "gh",
            "api",
            "--method",
            "DELETE",
            f"{PACKAGE_VERSIONS_PATH}/{safe_id}",
        ],
        capture=True,
    )


def _manifest_digest(output):
    """Read one exact lowercase SHA-256 digest from inspect JSON."""
    try:
        manifest = json.loads(output)
    except (json.JSONDecodeError, TypeError) as error:
        raise OperationError("invalid probe manifest JSON") from error
    digest = manifest.get("digest") if isinstance(manifest, dict) else None
    if not isinstance(digest, str) or _DIGEST_RE.fullmatch(digest) is None:
        raise OperationError("invalid probe manifest digest")
    return digest


def _probe_tag(run_id, run_attempt):
    """Derive the unique disposable tag from positive workflow identifiers."""
    safe_run_id = _positive(run_id, "GITHUB_RUN_ID")
    safe_attempt = _positive(run_attempt, "GITHUB_RUN_ATTEMPT")
    return f"preview-package-capability-probe-{safe_run_id}-{safe_attempt}"


def _retain(expected_digest, *, runner, now_epoch):
    """Plan once, revalidate each candidate freshly, and delete exact IDs."""
    safe_digest = _expected_digest(expected_digest)
    if type(now_epoch) is not int or now_epoch < 0:
        raise OperationError("invalid retention epoch")
    snapshot = _list_versions(runner)
    plan = preview_package_versions.build_plan(
        snapshot,
        safe_digest,
        now_epoch,
    )
    active_id = _positive(
        plan["active_version_id"],
        "active package-version ID",
    )
    deleted = []
    for candidate_id in plan["candidate_ids"]:
        safe_candidate_id = _positive(
            candidate_id,
            "candidate package-version ID",
        )
        active_record = _get_version(runner, active_id)
        candidate_record = _get_version(runner, safe_candidate_id)
        delete_id = preview_package_versions.revalidate_candidate(
            plan,
            safe_candidate_id,
            active_record,
            candidate_record,
        )
        if _positive(delete_id, "approved deletion ID") != safe_candidate_id:
            raise OperationError("approved deletion ID changed")
        _delete_version(runner, delete_id)
        deleted.append(delete_id)
    return tuple(deleted)


def _build_probe(runner, context, expected_digest, probe_tag):
    """Build and push one labeled ARM64 image while streaming build output."""
    dockerfile = Path(context, "Dockerfile")
    dockerfile.write_text(
        "ARG BASE_IMAGE\nFROM ${BASE_IMAGE}\n",
        encoding="utf-8",
    )
    probe_ref = f"{IMAGE_REPOSITORY}:{probe_tag}"
    _run(
        runner,
        [
            "docker",
            "buildx",
            "build",
            "--file",
            str(dockerfile),
            "--platform",
            "linux/arm64",
            "--push",
            "--provenance=false",
            "--sbom=false",
            "--build-arg",
            f"BASE_IMAGE={IMAGE_REPOSITORY}@{expected_digest}",
            "--label",
            f"{PROBE_LABEL}={probe_tag}",
            "--tag",
            probe_ref,
            str(context),
        ],
        capture=False,
    )
    return probe_ref


def _inspect_probe(runner, probe_ref, expected_digest):
    """Inspect the pushed probe and require a distinct immutable digest."""
    output = _run(
        runner,
        [
            "docker",
            "buildx",
            "imagetools",
            "inspect",
            probe_ref,
            "--format",
            "{{json .Manifest}}",
        ],
        capture=True,
    )
    probe_digest = _manifest_digest(output)
    if probe_digest == expected_digest:
        raise OperationError("probe digest is not distinct")
    return probe_digest


def _probe(
    expected_digest,
    run_id,
    run_attempt,
    *,
    runner,
):
    """Create, prove, fetch, delete, and prove absence of one probe version."""
    safe_digest = _expected_digest(expected_digest)
    tag = _probe_tag(run_id, run_attempt)
    before = _list_versions(runner)
    preview_package_versions.prove_probe_absent(
        before,
        safe_digest,
        tag,
    )
    try:
        with tempfile.TemporaryDirectory(
            prefix="moon-preview-package-probe-"
        ) as context:
            probe_ref = _build_probe(
                runner,
                context,
                safe_digest,
                tag,
            )
    except OSError as error:
        raise OperationError("probe build context failed") from error
    probe_digest = _inspect_probe(runner, probe_ref, safe_digest)
    after_create = _list_versions(runner)
    probe_id = preview_package_versions.prove_probe_created(
        before,
        after_create,
        safe_digest,
        tag,
        probe_digest,
    )
    if probe_id in {version.id for version in before}:
        raise OperationError("probe selected a pre-existing version")
    probe_record = _get_version(runner, probe_id)
    delete_id = preview_package_versions.prove_probe_created(
        before,
        after_create,
        safe_digest,
        tag,
        probe_digest,
        probe_record=probe_record,
    )
    if _positive(delete_id, "approved probe deletion ID") != probe_id:
        raise OperationError("approved probe deletion ID changed")
    _delete_version(runner, delete_id)
    after_delete = _list_versions(runner)
    preview_package_versions.prove_probe_absent(
        after_delete,
        safe_digest,
        tag,
        probe_version_id=delete_id,
    )
    return delete_id


def _parser():
    """Build the narrow retention and probe command-line interface."""
    parser = argparse.ArgumentParser(
        description="Manage bounded Moon Service preview package versions."
    )
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("retain", "probe"):
        command = commands.add_parser(name)
        command.add_argument(
            "--expected-digest",
            required=True,
            help="Verified digest used by the active preview tag.",
        )
    return parser


def _execute(args, *, environment, runner, now_epoch):
    """Execute parsed CLI state through explicit private dependencies."""
    try:
        if args.command == "retain":
            deleted = _retain(
                args.expected_digest,
                runner=runner,
                now_epoch=now_epoch,
            )
            print(f"Deleted {len(deleted)} expired preview version(s).")
        else:
            deleted_id = _probe(
                args.expected_digest,
                environment.get("GITHUB_RUN_ID"),
                environment.get("GITHUB_RUN_ATTEMPT"),
                runner=runner,
            )
            print(f"Deleted disposable probe version {deleted_id}.")
    except (
        OperationError,
        preview_package_versions.SelectionError,
        subprocess.SubprocessError,
    ) as error:
        print(f"Preview package operation failed: {error}", file=sys.stderr)
        return 2
    except OSError:
        print(
            "Preview package operation failed: local file operation failed",
            file=sys.stderr,
        )
        return 2
    return 0


def main(argv=None):
    """Parse the public CLI and run it with the real process dependencies."""
    args = _parser().parse_args(argv)
    now_epoch = int(time.time()) if args.command == "retain" else None
    return _execute(
        args,
        environment=os.environ,
        runner=subprocess.run,
        now_epoch=now_epoch,
    )


if __name__ == "__main__":
    sys.exit(main())
