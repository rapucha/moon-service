#!/usr/bin/env python3

import argparse
import calendar
from dataclasses import dataclass
from datetime import datetime, timezone
from decimal import Decimal
import json
from pathlib import Path
import re
import sys


AGE_SECONDS = 691200
NEWEST_OTHER_COUNT = 9
PLAN_SCHEMA = 1

_DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}")
_PREVIEW_TAG_RE = re.compile(r"preview-pr-[1-9][0-9]*-[0-9a-f]{40}")
_CREATED_AT_RE = re.compile(
    r"(?P<year>[0-9]{4})-(?P<month>[0-9]{2})-(?P<day>[0-9]{2})"
    r"T(?P<hour>[0-9]{2}):(?P<minute>[0-9]{2}):"
    r"(?P<second>[0-9]{2})(?P<fraction>\.[0-9]+)?Z"
)


class SelectionError(ValueError):
    pass


def _is_positive_id(value):
    return type(value) is int and value > 0


@dataclass(frozen=True)
class Version:
    id: int
    name: str
    created_at: str
    tags: tuple[str, ...]
    created_epoch: Decimal

    def snapshot(self):
        return {
            "id": self.id,
            "name": self.name,
            "created_at": self.created_at,
            "tags": list(self.tags),
        }


def _require_digest(value, message):
    if not isinstance(value, str) or _DIGEST_RE.fullmatch(value) is None:
        raise SelectionError(message)


def _require_nonnegative_epoch(value):
    if type(value) is not int or value < 0:
        raise SelectionError("invalid UTC epoch")


def _created_epoch(value):
    if not isinstance(value, str):
        raise SelectionError("invalid package-version schema")
    match = _CREATED_AT_RE.fullmatch(value)
    if match is None:
        raise SelectionError("invalid package-version schema")
    parts = {name: int(match.group(name)) for name in (
        "year", "month", "day", "hour", "minute", "second"
    )}
    try:
        created = datetime(**parts, tzinfo=timezone.utc)
    except ValueError as error:
        raise SelectionError("invalid package-version schema") from error
    epoch = Decimal(calendar.timegm(created.utctimetuple()))
    fraction = match.group("fraction")
    return epoch if fraction is None else epoch + Decimal(fraction)


def validate_version(record):
    if not isinstance(record, dict):
        raise SelectionError("invalid package-version schema")
    version_id = record.get("id")
    if not _is_positive_id(version_id):
        raise SelectionError("invalid package-version schema")
    name = record.get("name")
    _require_digest(name, "invalid package-version schema")
    created_at = record.get("created_at")
    created_epoch = _created_epoch(created_at)
    metadata = record.get("metadata")
    container = metadata.get("container") if isinstance(metadata, dict) else None
    tags = container.get("tags") if isinstance(container, dict) else None
    if (
        not isinstance(tags, list)
        or any(not isinstance(tag, str) for tag in tags)
        or len(tags) != len(set(tags))
    ):
        raise SelectionError("invalid package-version schema")
    return Version(version_id, name, created_at, tuple(sorted(tags)), created_epoch)


def parse_page_stream(stream):
    if not isinstance(stream, str):
        raise SelectionError("invalid paginated JSON")
    decoder = json.JSONDecoder()
    position = 0
    page_count = 0
    versions = []
    seen_ids = set()
    while True:
        while position < len(stream) and stream[position].isspace():
            position += 1
        if position == len(stream):
            break
        try:
            page, position = decoder.raw_decode(stream, position)
        except json.JSONDecodeError as error:
            raise SelectionError("invalid paginated JSON") from error
        if not isinstance(page, list):
            raise SelectionError("paginated input must contain page arrays")
        page_count += 1
        for record in page:
            version = validate_version(record)
            if version.id in seen_ids:
                raise SelectionError("duplicate package-version ID")
            seen_ids.add(version.id)
            versions.append(version)
    if page_count == 0:
        raise SelectionError("paginated input is empty")
    return tuple(versions)


def parse_record(stream):
    try:
        record = json.loads(stream)
    except (json.JSONDecodeError, TypeError) as error:
        raise SelectionError("invalid record JSON") from error
    return validate_version(record)


def _is_preview_only(version):
    return bool(version.tags) and all(
        _PREVIEW_TAG_RE.fullmatch(tag) is not None for tag in version.tags
    )


def _active_version(versions, expected_digest):
    active = [version for version in versions if "preview" in version.tags]
    if len(active) != 1 or active[0].name != expected_digest:
        raise SelectionError("active preview identity is invalid")
    return active[0]


def build_plan(versions, expected_digest, now_epoch):
    _require_digest(expected_digest, "invalid expected digest")
    _require_nonnegative_epoch(now_epoch)
    versions = tuple(versions)
    ids = [version.id for version in versions]
    if len(ids) != len(set(ids)):
        raise SelectionError("duplicate package-version ID")
    active = _active_version(versions, expected_digest)
    ordered = sorted(
        (
            version
            for version in versions
            if version.id != active.id and _is_preview_only(version)
        ),
        key=lambda version: (version.created_epoch, version.id),
        reverse=True,
    )
    boundary = Decimal(now_epoch - AGE_SECONDS)
    keep_ids = {active.id}
    keep_ids.update(version.id for version in ordered[:NEWEST_OTHER_COUNT])
    keep_ids.update(
        version.id
        for version in versions
        if version.created_epoch >= boundary
    )
    candidates = [
        version.id
        for version in ordered
        if version.id not in keep_ids and version.created_epoch < boundary
    ]
    return {
        "schema": PLAN_SCHEMA,
        "now_epoch": now_epoch,
        "expected_digest": expected_digest,
        "active_version_id": active.id,
        "keep_ids": sorted(keep_ids),
        "candidate_ids": candidates,
        "versions": [
            version.snapshot()
            for version in sorted(versions, key=lambda version: version.id)
        ],
    }


def _version_from_snapshot(snapshot):
    if not isinstance(snapshot, dict) or set(snapshot) != {
        "id", "name", "created_at", "tags"
    }:
        raise SelectionError("invalid plan")
    return validate_version({
        "id": snapshot["id"],
        "name": snapshot["name"],
        "created_at": snapshot["created_at"],
        "metadata": {"container": {"tags": snapshot["tags"]}},
    })


def validate_plan(plan):
    if not isinstance(plan, dict) or set(plan) != {
        "schema",
        "now_epoch",
        "expected_digest",
        "active_version_id",
        "keep_ids",
        "candidate_ids",
        "versions",
    }:
        raise SelectionError("invalid plan")
    if type(plan["schema"]) is not int or plan["schema"] != PLAN_SCHEMA:
        raise SelectionError("invalid plan")
    if not isinstance(plan["versions"], list):
        raise SelectionError("invalid plan")
    versions = tuple(
        _version_from_snapshot(snapshot) for snapshot in plan["versions"]
    )
    expected = build_plan(
        versions, plan["expected_digest"], plan["now_epoch"]
    )
    if json.dumps(plan, sort_keys=True) != json.dumps(expected, sort_keys=True):
        raise SelectionError("invalid plan")
    return versions


def parse_plan(stream):
    try:
        plan = json.loads(stream)
    except (json.JSONDecodeError, TypeError) as error:
        raise SelectionError("invalid plan JSON") from error
    validate_plan(plan)
    return plan


def revalidate_candidate(plan, candidate_id, active_record, candidate_record):
    versions = validate_plan(plan)
    if not _is_positive_id(candidate_id):
        raise SelectionError("invalid candidate ID")
    if candidate_id not in plan["candidate_ids"]:
        raise SelectionError("candidate is not approved")
    if candidate_id in plan["keep_ids"] or candidate_id == plan["active_version_id"]:
        raise SelectionError("candidate is not approved")
    snapshots = {version.id: version for version in versions}
    active_snapshot = snapshots[plan["active_version_id"]]
    candidate_snapshot = snapshots[candidate_id]
    if active_record.snapshot() != active_snapshot.snapshot():
        raise SelectionError("active preview changed")
    if candidate_record.snapshot() != candidate_snapshot.snapshot():
        raise SelectionError("candidate changed")
    _active_version((active_record,), plan["expected_digest"])
    boundary = Decimal(plan["now_epoch"] - AGE_SECONDS)
    if (
        not _is_preview_only(candidate_record)
        or candidate_record.created_epoch >= boundary
    ):
        raise SelectionError("candidate is no longer eligible")
    return candidate_id


def _require_probe_tag(probe_tag):
    if not isinstance(probe_tag, str) or not probe_tag or probe_tag == "preview":
        raise SelectionError("invalid probe tag")
    return probe_tag


def prove_probe_created(
    before,
    after,
    expected_active_digest,
    probe_tag,
    expected_probe_digest,
    probe_record=None,
):
    _require_digest(expected_active_digest, "invalid expected active digest")
    _require_digest(expected_probe_digest, "invalid expected probe digest")
    if expected_probe_digest == expected_active_digest:
        raise SelectionError("probe digest is not distinct")
    probe_tag = _require_probe_tag(probe_tag)
    before = tuple(before)
    after = tuple(after)
    _active_version(before, expected_active_digest)
    _active_version(after, expected_active_digest)
    if any(probe_tag in version.tags for version in before):
        raise SelectionError("probe tag existed before creation")
    before_ids = {version.id for version in before}
    new_versions = [version for version in after if version.id not in before_ids]
    if len(new_versions) != 1:
        raise SelectionError("probe creation identity is invalid")
    created = new_versions[0]
    tagged = [version for version in after if probe_tag in version.tags]
    if (
        tagged != [created]
        or created.name != expected_probe_digest
        or created.tags != (probe_tag,)
    ):
        raise SelectionError("probe creation identity is invalid")
    if probe_record is not None and probe_record.snapshot() != created.snapshot():
        raise SelectionError("probe record changed")
    return created.id


def prove_probe_absent(
    versions, expected_active_digest, probe_tag, probe_version_id=None
):
    _require_digest(expected_active_digest, "invalid expected active digest")
    probe_tag = _require_probe_tag(probe_tag)
    versions = tuple(versions)
    _active_version(versions, expected_active_digest)
    if any(probe_tag in version.tags for version in versions):
        raise SelectionError("probe tag is present")
    if probe_version_id is not None:
        if not _is_positive_id(probe_version_id):
            raise SelectionError("invalid probe version ID")
        if any(version.id == probe_version_id for version in versions):
            raise SelectionError("probe version is present")
    return True


def _read_text(path):
    if path == "-":
        return sys.stdin.read()
    return Path(path).read_text(encoding="utf-8")


def _positive_id(value, message):
    if not isinstance(value, str) or re.fullmatch(r"[1-9][0-9]*", value) is None:
        raise SelectionError(message)
    return int(value)


def _epoch_argument(value):
    if not isinstance(value, str) or re.fullmatch(r"[0-9]+", value) is None:
        raise SelectionError("invalid UTC epoch")
    return int(value)


def _argument(command, name, help_text, optional=False):
    command.add_argument(name, required=not optional, help=help_text)


def _parser():
    parser = argparse.ArgumentParser(
        description="Select and verify conservative preview package cleanup."
    )
    commands = parser.add_subparsers(dest="command", required=True)

    plan = commands.add_parser(
        "plan", help="Validate a complete package snapshot and create a plan."
    )
    _argument(plan, "--snapshot", "Sequential JSON page arrays from gh api --paginate.")
    _argument(plan, "--expected-digest", "Verified digest the active preview must use.")
    _argument(plan, "--now-epoch", "One nonnegative UTC epoch for the cleanup run.")

    revalidate = commands.add_parser(
        "revalidate", help="Revalidate one planned candidate before deletion."
    )
    _argument(revalidate, "--plan", "Plan JSON emitted by the plan command.")
    _argument(revalidate, "--active-record", "Fresh GET JSON for the active version.")
    _argument(revalidate, "--candidate-record", "Fresh GET JSON for the candidate.")
    _argument(revalidate, "--candidate-id", "Positive ID selected from the plan.")

    created = commands.add_parser(
        "probe-created", help="Prove exact disposable probe creation identity."
    )
    _argument(created, "--before-snapshot", "Complete pre-publication snapshot.")
    _argument(created, "--after-snapshot", "Complete post-publication snapshot.")
    _argument(created, "--expected-active-digest", "Preview digest that must stay active.")
    _argument(created, "--probe-tag", "Unique disposable probe tag.")
    _argument(created, "--expected-probe-digest", "Verified disposable probe digest.")
    _argument(created, "--probe-record", "Optional fresh GET JSON for the probe.", True)

    absent = commands.add_parser(
        "probe-absent", help="Prove a probe tag and optional exact ID are absent."
    )
    _argument(absent, "--snapshot", "Complete paginated package snapshot.")
    _argument(absent, "--expected-active-digest", "Preview digest that must stay active.")
    _argument(absent, "--probe-tag", "Unique disposable probe tag.")
    _argument(absent, "--probe-version-id", "Positive probe ID that must be absent.", True)
    return parser


def _write_json(value):
    json.dump(value, sys.stdout, sort_keys=True, separators=(",", ":"))
    sys.stdout.write("\n")


def main(argv=None):
    args = _parser().parse_args(argv)
    try:
        if args.command == "plan":
            versions = parse_page_stream(_read_text(args.snapshot))
            result = build_plan(
                versions,
                args.expected_digest,
                _epoch_argument(args.now_epoch),
            )
        elif args.command == "revalidate":
            candidate_id = _positive_id(
                args.candidate_id, "invalid candidate ID"
            )
            result = {"candidate_id": revalidate_candidate(
                parse_plan(_read_text(args.plan)),
                candidate_id,
                parse_record(_read_text(args.active_record)),
                parse_record(_read_text(args.candidate_record)),
            )}
        elif args.command == "probe-created":
            probe_record = (
                parse_record(_read_text(args.probe_record))
                if args.probe_record else None
            )
            result = {"probe_version_id": prove_probe_created(
                parse_page_stream(_read_text(args.before_snapshot)),
                parse_page_stream(_read_text(args.after_snapshot)),
                args.expected_active_digest,
                args.probe_tag,
                args.expected_probe_digest,
                probe_record,
            )}
        else:
            probe_version_id = (
                _positive_id(
                    args.probe_version_id, "invalid probe version ID"
                )
                if args.probe_version_id else None
            )
            prove_probe_absent(
                parse_page_stream(_read_text(args.snapshot)),
                args.expected_active_digest,
                args.probe_tag,
                probe_version_id,
            )
            result = {"absent": True}
    except SelectionError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    except (OSError, UnicodeError):
        print("error: cannot read input", file=sys.stderr)
        return 2
    _write_json(result)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
