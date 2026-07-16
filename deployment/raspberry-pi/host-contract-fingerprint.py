#!/usr/bin/env python3
"""Print the deterministic identity of tracked Moon Service host-role inputs."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import subprocess


ROLE_PATH = "deployment/raspberry-pi/roles/moon_service_host"
DOMAIN = b"moon-service-host-contract-v1\0"


def git(root: Path, *arguments: str) -> bytes:
    return subprocess.run(
        ["git", "-C", str(root), *arguments],
        check=True,
        capture_output=True,
    ).stdout


def repository_root() -> Path:
    return Path(git(Path.cwd(), "rev-parse", "--show-toplevel").decode().strip())


def working_tree_entries(root: Path) -> list[tuple[str, bytes]]:
    paths = sorted(
        item.decode()
        for item in git(root, "ls-files", "-z", "--", ROLE_PATH).split(b"\0")
        if item
    )
    entries = []
    for relative in paths:
        path = root / relative
        content = os.readlink(path).encode() if path.is_symlink() else path.read_bytes()
        entries.append((relative, content))
    return entries


def revision_entries(root: Path, revision: str) -> list[tuple[str, bytes]]:
    commit = git(root, "rev-parse", "--verify", f"{revision}^{{commit}}").decode().strip()
    paths = sorted(
        item.decode()
        for item in git(
            root, "ls-tree", "-r", "-z", "--name-only", commit, "--", ROLE_PATH
        ).split(b"\0")
        if item
    )
    return [(path, git(root, "show", f"{commit}:{path}")) for path in paths]


def fingerprint(entries: list[tuple[str, bytes]]) -> str:
    if not entries:
        raise ValueError("host contract contains no tracked role inputs")
    digest = hashlib.sha256(DOMAIN)
    for relative, content in entries:
        encoded_path = relative.encode()
        digest.update(len(encoded_path).to_bytes(8, "big"))
        digest.update(encoded_path)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return f"sha256:{digest.hexdigest()}"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--revision")
    arguments = parser.parse_args()
    root = repository_root()
    entries = (
        revision_entries(root, arguments.revision)
        if arguments.revision
        else working_tree_entries(root)
    )
    print(fingerprint(entries))


if __name__ == "__main__":
    main()
