#!/usr/bin/env python3
"""Probe and publish Moon Service pull-request preview images in GHCR."""

import argparse
import os
import tarfile
import tempfile
from pathlib import Path

import inspect_pr_preview_oci as oci
import pr_preview_registry as registry


def immutable_tag(pr_number, revision):
    return f"preview-pr-{pr_number}-{revision}"


def outputs(values):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def summary(lines):
    path = os.environ.get("GITHUB_STEP_SUMMARY")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as output:
        output.write("\n".join(lines) + "\n")


def command_probe(args):
    client = registry.Registry()
    tag = immutable_tag(args.pr_number, args.revision)
    image = registry.remote_index(
        client,
        tag,
        args.revision,
        args.pr_number,
        missing=True,
    )
    values = {"existing": str(image is not None).lower()}
    if image:
        values.update(
            {
                "index_digest": image["digest"],
                "child_digest": image["child_digest"],
                "producer_run_id": image["run_id"],
                "producer_run_attempt": image["run_attempt"],
            }
        )
    outputs(values)
    status = "exists and is valid" if image else "does not exist"
    print(f"Immutable preview {tag} {status}")


def load_local_image(args, directory):
    if not args.archive:
        return None
    local = oci.inspect_archive(Path(args.archive), Path(directory), args.revision)
    identity_path = Path(args.identity)
    oci.require(
        identity_path.is_file()
        and not identity_path.is_symlink()
        and identity_path.stat().st_size <= 64 * 1024,
        "expected identity must be one bounded regular file",
    )
    expected = oci.json_bytes(
        identity_path.read_bytes(),
        "expected identity",
        64 * 1024,
    )
    oci.require(
        expected == oci.identity_for(local, args.revision),
        "expected identity does not match the validated archive and trusted arguments",
    )
    return local


def same_image(remote, local):
    return (
        remote["child_digest"],
        remote["child_size"],
        remote["config_digest"],
    ) == (
        local["child_digest"],
        local["child_size"],
        local["config_digest"],
    )


def create_index(args, local):
    return oci.canonical_json(
        {
            "schemaVersion": 2,
            "mediaType": oci.OCI_INDEX,
            "manifests": [
                {
                    "mediaType": oci.OCI_MANIFEST,
                    "digest": local["child_digest"],
                    "size": local["child_size"],
                    "platform": {"architecture": "arm64", "os": "linux"},
                }
            ],
            "annotations": {
                "org.opencontainers.image.source": oci.SOURCE,
                "org.opencontainers.image.revision": args.revision,
                registry.PR_ANNOTATION: str(args.pr_number),
                registry.RUN_ANNOTATION: str(args.run_id),
                registry.RUN_NUMBER_ANNOTATION: str(args.run_number),
                registry.ATTEMPT_ANNOTATION: str(args.run_attempt),
            },
        }
    )


def publish_local(client, tag, args, local):
    for digest, path in local["blob_paths"].items():
        if digest != local["child_digest"]:
            client.upload_blob(digest, path)
    child = client.manifest(local["child_digest"], missing=True)
    if child:
        oci.require(
            child[0] == local["child_data"],
            "registry already contains a different child manifest",
        )
    else:
        client.put_manifest(
            local["child_digest"],
            oci.OCI_MANIFEST,
            local["child_data"],
        )
    raced = registry.remote_index(
        client,
        tag,
        args.revision,
        args.pr_number,
        missing=True,
    )
    if raced:
        oci.require(
            same_image(raced, local),
            "immutable preview appeared with a different image",
        )
        return raced, "reused"
    body = create_index(args, local)
    client.put_manifest(tag, oci.OCI_INDEX, body)
    candidate = registry.remote_index(
        client,
        tag,
        args.revision,
        args.pr_number,
    )
    oci.require(
        candidate["digest"] == oci.sha256_bytes(body),
        "immutable preview digest changed after publication",
    )
    return candidate, "created"


def candidate_image(client, tag, args, existing, local):
    if existing:
        if local:
            oci.require(
                same_image(existing, local),
                "existing immutable preview does not match the archive",
            )
        return existing, "reused"
    oci.require(
        local is not None,
        "immutable preview disappeared after probe; an archive is required",
    )
    return publish_local(client, tag, args, local)


def advance_channel(client, candidate):
    current = registry.remote_index(client, registry.CHANNEL, missing=True)
    candidate_order = (candidate["run_number"], candidate["run_attempt"])
    current_order = None
    if current:
        current_order = (current["run_number"], current["run_attempt"])
    if current and current_order > candidate_order:
        return "kept-newer"
    if current and current_order == candidate_order:
        oci.require(
            current["digest"] == candidate["digest"],
            "preview channel has a different digest for the same producer",
        )
        return "unchanged"
    client.put_manifest(registry.CHANNEL, oci.OCI_INDEX, candidate["raw"])
    promoted = registry.remote_index(client, registry.CHANNEL)
    oci.require(
        promoted["digest"] == candidate["digest"],
        "preview channel did not retain the candidate digest",
    )
    return "advanced"


def write_handoff(args, tag, candidate, publication, channel_action):
    outputs(
        {
            "immutable_tag": tag,
            "index_digest": candidate["digest"],
            "child_digest": candidate["child_digest"],
            "producer_run_id": candidate["run_id"],
            "producer_run_number": candidate["run_number"],
            "producer_run_attempt": candidate["run_attempt"],
            "publication": publication,
            "channel_action": channel_action,
        }
    )
    summary(
        [
            "## Pull-request preview image",
            "",
            f"- Pull request: `#{args.pr_number}`",
            f"- Full revision: `{args.revision}`",
            (
                f"- Immutable reference: `{registry.REGISTRY}/"
                f"{registry.REPOSITORY}:{tag}`"
            ),
            f"- Immutable index digest: `{candidate['digest']}`",
            f"- Child: `{candidate['child_digest']}` (`linux/arm64`)",
            (
                f"- First producer: workflow run `{candidate['run_number']}` "
                f"(ID `{candidate['run_id']}`), "
                f"attempt `{candidate['run_attempt']}`"
            ),
            (
                f"- Publication: `{publication}`; preview channel: "
                f"`{channel_action}`"
            ),
        ]
    )


def command_publish(args):
    archive_supplied = bool(args.archive or args.identity)
    oci.require(
        not archive_supplied or (args.archive and args.identity),
        "archive and expected identity must be supplied together",
    )
    tag = immutable_tag(args.pr_number, args.revision)
    client = registry.Registry(
        push=True,
        allowed_tags={tag, registry.CHANNEL},
    )
    existing = registry.remote_index(
        client,
        tag,
        args.revision,
        args.pr_number,
        missing=True,
    )
    if args.expected_index_digest:
        oci.require(
            existing is not None
            and existing["digest"] == args.expected_index_digest,
            "immutable preview changed after the trusted probe",
        )
    oci.require(
        archive_supplied or args.expected_index_digest,
        "publication requires an archive or the probed immutable digest",
    )
    with tempfile.TemporaryDirectory(prefix="moon-preview-publish-") as directory:
        local = load_local_image(args, directory)
        candidate, publication = candidate_image(
            client,
            tag,
            args,
            existing,
            local,
        )
    channel_action = advance_channel(client, candidate)
    write_handoff(args, tag, candidate, publication, channel_action)
    print(
        f"{publication.capitalize()} {tag}@{candidate['digest']}; "
        f"preview channel {channel_action}"
    )


def parse_args():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    probe = commands.add_parser("probe")
    probe.add_argument("--pr-number", type=int, required=True)
    probe.add_argument("--revision", required=True)
    publish = commands.add_parser("publish")
    publish.add_argument("--archive")
    publish.add_argument("--identity")
    publish.add_argument("--expected-index-digest")
    publish.add_argument("--pr-number", type=int, required=True)
    publish.add_argument("--run-id", type=int, required=True)
    publish.add_argument("--run-number", type=int, required=True)
    publish.add_argument("--run-attempt", type=int, required=True)
    publish.add_argument("--revision", required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    oci.require(
        oci.SHA_RE.fullmatch(args.revision),
        "revision must be a lowercase 40-character Git SHA",
    )
    args.pr_number = registry.positive(args.pr_number, "PR number")
    if args.command == "publish":
        args.run_id = registry.positive(args.run_id, "run ID")
        args.run_number = registry.positive(args.run_number, "run number")
        args.run_attempt = registry.positive(args.run_attempt, "run attempt")
        oci.require(
            not args.expected_index_digest
            or oci.DIGEST_RE.fullmatch(args.expected_index_digest),
            "expected index digest is invalid",
        )
        oci.require(
            not (args.archive and args.expected_index_digest),
            "archive and probed index digest are mutually exclusive",
        )
    {"probe": command_probe, "publish": command_publish}[args.command](args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, tarfile.TarError, ValueError) as error:
        raise SystemExit(f"preview OCI publication failed: {error}") from error
