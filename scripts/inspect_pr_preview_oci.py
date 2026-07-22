#!/usr/bin/env python3
"""Strictly inspect a Moon Service pull-request preview OCI archive."""

import argparse
import hashlib
import json
import os
import re
import tarfile
import tempfile
from pathlib import Path, PurePosixPath


SOURCE = "https://github.com/rapucha/moon-service"
OCI_INDEX = "application/vnd.oci.image.index.v1+json"
OCI_MANIFEST = "application/vnd.oci.image.manifest.v1+json"
OCI_CONFIG = "application/vnd.oci.image.config.v1+json"
OCI_LAYER = "application/vnd.oci.image.layer.v1.tar+gzip"
DIGEST_RE = re.compile(r"sha256:[0-9a-f]{64}\Z")
SHA_RE = re.compile(r"[0-9a-f]{40}\Z")
INTEGER_RE = re.compile(r"[1-9][0-9]*\Z")
MAX_ARCHIVE = 2_500_000_000
MAX_MEMBERS = 128
MAX_JSON = 4 * 1024 * 1024
MAX_CONFIG = 2 * 1024 * 1024


def fail(message):
    raise ValueError(message)


def require(condition, message):
    if not condition:
        fail(message)


def object_no_duplicates(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            fail(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def json_bytes(data, label, limit=MAX_JSON):
    require(len(data) <= limit, f"{label} exceeds {limit} bytes")
    try:
        value = json.loads(
            data.decode("utf-8"),
            object_pairs_hook=object_no_duplicates,
            parse_constant=lambda item: fail(f"invalid JSON constant: {item}"),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as error:
        fail(f"invalid {label}: {error}")
    require(isinstance(value, dict), f"{label} must be a JSON object")
    return value


def canonical_json(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def sha256_bytes(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def annotations(value, label):
    valid = isinstance(value, dict) and all(
        isinstance(key, str) and isinstance(item, str)
        for key, item in value.items()
    )
    require(valid, f"{label} annotations must be a string map")
    return value


def descriptor(value, label, media_type, platform=False, allow_annotations=False):
    allowed = {"mediaType", "digest", "size"}
    if platform:
        allowed.add("platform")
    if allow_annotations:
        allowed.add("annotations")
    require(
        isinstance(value, dict) and not set(value) - allowed,
        f"invalid {label} descriptor fields",
    )
    valid_identity = (
        value.get("mediaType") == media_type
        and DIGEST_RE.fullmatch(str(value.get("digest", "")))
    )
    require(valid_identity, f"invalid {label} media type or digest")
    size = value.get("size")
    valid_size = (
        not isinstance(size, bool)
        and isinstance(size, int)
        and 0 <= size <= MAX_ARCHIVE
    )
    require(valid_size, f"invalid {label} size")
    expected_platform = {"architecture": "arm64", "os": "linux"}
    require(
        not platform or value.get("platform") == expected_platform,
        f"{label} platform must be linux/arm64",
    )
    if "annotations" in value:
        annotations(value["annotations"], label)
    return value


def validate_child(manifest_data, get_blob, source, revision):
    manifest = json_bytes(manifest_data, "OCI child manifest")
    allowed = {"schemaVersion", "mediaType", "config", "layers", "annotations"}
    valid_manifest = (
        not set(manifest) - allowed
        and manifest.get("schemaVersion") == 2
        and manifest.get("mediaType") == OCI_MANIFEST
    )
    require(valid_manifest, "invalid OCI child manifest")
    child_annotations = annotations(
        manifest.get("annotations", {}),
        "child manifest",
    )
    require(
        child_annotations.get("org.opencontainers.image.source") == source,
        "child source annotation does not match",
    )
    require(
        child_annotations.get("org.opencontainers.image.revision") == revision,
        "child revision annotation does not match",
    )
    config_descriptor = descriptor(manifest.get("config"), "config", OCI_CONFIG)
    layers = manifest.get("layers")
    require(
        isinstance(layers, list) and 1 <= len(layers) <= 64,
        "child must contain between 1 and 64 layers",
    )
    for number, layer in enumerate(layers):
        descriptor(
            layer,
            f"layer {number}",
            OCI_LAYER,
            allow_annotations=True,
        )

    config_data = get_blob(
        config_descriptor["digest"],
        config_descriptor["size"],
        MAX_CONFIG,
    )
    config = json_bytes(config_data, "OCI image config", MAX_CONFIG)
    require(
        config.get("architecture") == "arm64" and config.get("os") == "linux",
        "image config platform must be linux/arm64",
    )
    runtime = config.get("config")
    require(isinstance(runtime, dict), "image runtime config is missing")
    labels = annotations(runtime.get("Labels", {}), "config labels")
    require(
        labels.get("org.opencontainers.image.source") == source,
        "config source label does not match",
    )
    require(
        labels.get("org.opencontainers.image.revision") == revision,
        "config revision label does not match",
    )
    expected_revision = f"MOON_BUILD_REVISION={revision}"
    environment = runtime.get("Env", [])
    valid_environment = (
        isinstance(environment, list)
        and all(isinstance(item, str) for item in environment)
        and [
            item
            for item in environment
            if item.startswith("MOON_BUILD_REVISION=")
        ]
        == [expected_revision]
    )
    require(
        valid_environment,
        "config must contain one exact MOON_BUILD_REVISION",
    )
    expected_runtime = {
        "User": "10001:10001",
        "Entrypoint": ["java", "-jar", "/app/moon-service.jar"],
        "WorkingDir": "/app",
        "StopSignal": "SIGTERM",
        "ExposedPorts": {"8080/tcp": {}},
    }
    require(
        all(runtime.get(key) == value for key, value in expected_runtime.items()),
        "image runtime contract does not match Moon Service",
    )
    expected_healthcheck = {
        "Test": ["CMD", "/usr/local/bin/moon-service-healthcheck"],
        "Interval": 30_000_000_000,
        "Timeout": 3_000_000_000,
        "StartPeriod": 30_000_000_000,
        "StartInterval": 2_000_000_000,
        "Retries": 3,
    }
    require(
        runtime.get("Healthcheck") == expected_healthcheck,
        "image healthcheck contract does not match",
    )
    rootfs = config.get("rootfs")
    require(
        isinstance(rootfs, dict) and rootfs.get("type") == "layers",
        "invalid config rootfs",
    )
    diff_ids = rootfs.get("diff_ids")
    valid_diff_ids = (
        isinstance(diff_ids, list)
        and len(diff_ids) == len(layers)
        and all(
            isinstance(item, str) and DIGEST_RE.fullmatch(item)
            for item in diff_ids
        )
    )
    require(valid_diff_ids, "config diff IDs do not match the layer count")
    return {
        "config_digest": config_descriptor["digest"],
        "layers": layers,
    }


def inspect_archive(archive, work, revision):
    valid_archive = (
        archive.is_file()
        and not archive.is_symlink()
        and archive.stat().st_size <= MAX_ARCHIVE
    )
    require(valid_archive, "OCI archive must be one bounded regular file")
    allowed_directories = {"blobs", "blobs/sha256"}
    files = {}
    seen = set()
    total = 0
    with tarfile.open(archive, mode="r:") as bundle:
        for number, member in enumerate(bundle, 1):
            require(number <= MAX_MEMBERS, "OCI archive contains too many members")
            name = member.name.rstrip("/")
            path = PurePosixPath(name)
            valid_path = (
                name
                and len(name) <= 200
                and name not in seen
                and not path.is_absolute()
                and "\\" not in name
                and not any(part in {"", ".", ".."} for part in path.parts)
            )
            require(
                valid_path,
                "OCI archive contains an unsafe or duplicate path",
            )
            seen.add(name)
            if member.isdir():
                require(
                    name in allowed_directories,
                    f"unexpected OCI archive directory: {name}",
                )
                continue
            require(
                member.isfile() and not getattr(member, "sparse", None),
                f"OCI archive member is not a plain regular file: {name}",
            )
            is_blob = re.fullmatch(r"blobs/sha256/[0-9a-f]{64}", name)
            require(
                name in {"oci-layout", "index.json"} or is_blob,
                f"unexpected OCI archive file: {name}",
            )
            valid_size = (
                name not in files
                and member.size <= MAX_ARCHIVE
                and total + member.size <= MAX_ARCHIVE
            )
            require(valid_size, "OCI archive has duplicate or oversized content")
            total += member.size
            destination = work / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            source = bundle.extractfile(member)
            require(source is not None, f"cannot read OCI archive member: {name}")
            digest = hashlib.sha256()
            with destination.open("xb") as output:
                while chunk := source.read(1024 * 1024):
                    digest.update(chunk)
                    output.write(chunk)
            require(
                destination.stat().st_size == member.size,
                f"truncated OCI archive member: {name}",
            )
            require(
                not is_blob or digest.hexdigest() == path.name,
                f"blob digest does not match its path: {name}",
            )
            files[name] = destination

    require(
        set(files) >= {"oci-layout", "index.json"},
        "archive is not a true OCI image layout",
    )
    layout = json_bytes(files["oci-layout"].read_bytes(), "oci-layout")
    require(
        layout == {"imageLayoutVersion": "1.0.0"},
        "unsupported OCI layout version",
    )
    index = json_bytes(files["index.json"].read_bytes(), "OCI layout index")
    allowed_index = {"schemaVersion", "mediaType", "manifests", "annotations"}
    valid_index = (
        not set(index) - allowed_index
        and index.get("schemaVersion") == 2
        and index.get("mediaType") == OCI_INDEX
    )
    require(valid_index, "invalid OCI layout index")
    manifests = index.get("manifests")
    require(
        isinstance(manifests, list) and len(manifests) == 1,
        "OCI layout must contain exactly one image",
    )
    child_descriptor = descriptor(
        manifests[0],
        "layout child",
        OCI_MANIFEST,
        platform=True,
        allow_annotations=True,
    )
    blob_paths = {
        "sha256:" + name.rsplit("/", 1)[1]: path
        for name, path in files.items()
        if name.startswith("blobs/")
    }

    def local_blob(digest, size, limit):
        path = blob_paths.get(digest)
        valid_blob = (
            path is not None
            and path.stat().st_size == size
            and size <= limit
        )
        require(valid_blob, f"missing or invalid blob: {digest}")
        return path.read_bytes()

    child_data = local_blob(
        child_descriptor["digest"],
        child_descriptor["size"],
        MAX_JSON,
    )
    child = validate_child(child_data, local_blob, SOURCE, revision)
    for number, layer in enumerate(child["layers"]):
        layer_path = blob_paths.get(layer["digest"])
        require(
            layer_path is not None
            and layer_path.stat().st_size == layer["size"],
            f"layer {number} blob size does not match its descriptor",
        )
    used = {
        child_descriptor["digest"],
        child["config_digest"],
    }
    used.update(layer["digest"] for layer in child["layers"])
    require(
        set(blob_paths) == used,
        "OCI archive contains missing or unreachable blobs",
    )
    return {
        "child_digest": child_descriptor["digest"],
        "child_size": child_descriptor["size"],
        "child_data": child_data,
        "config_digest": child["config_digest"],
        "blob_paths": blob_paths,
    }


def identity_for(image, revision):
    return {
        "schema": 1,
        "source": SOURCE,
        "revision": revision,
        "platform": "linux/arm64",
        "child_digest": image["child_digest"],
        "child_size": image["child_size"],
        "config_digest": image["config_digest"],
    }


def outputs(values):
    path = os.environ.get("GITHUB_OUTPUT")
    if not path:
        return
    with open(path, "a", encoding="utf-8") as output:
        for key, value in values.items():
            output.write(f"{key}={value}\n")


def command_inspect(args):
    with tempfile.TemporaryDirectory(prefix="moon-preview-oci-") as directory:
        image = inspect_archive(Path(args.archive), Path(directory), args.revision)
        identity = identity_for(image, args.revision)
    with open(args.identity, "x", encoding="utf-8") as output:
        json.dump(identity, output, sort_keys=True, separators=(",", ":"))
        output.write("\n")
    outputs(
        {
            "child_digest": identity["child_digest"],
            "config_digest": identity["config_digest"],
        }
    )
    print(f"Validated linux/arm64 OCI child {identity['child_digest']}")


def parse_args():
    parser = argparse.ArgumentParser()
    commands = parser.add_subparsers(dest="command", required=True)
    inspect = commands.add_parser("inspect")
    inspect.add_argument("--archive", required=True)
    inspect.add_argument("--identity", required=True)
    inspect.add_argument("--revision", required=True)
    return parser.parse_args()


def main():
    args = parse_args()
    require(
        SHA_RE.fullmatch(args.revision),
        "revision must be a lowercase 40-character Git SHA",
    )
    command_inspect(args)


if __name__ == "__main__":
    try:
        main()
    except (OSError, tarfile.TarError, ValueError) as error:
        raise SystemExit(f"preview OCI validation failed: {error}") from error
