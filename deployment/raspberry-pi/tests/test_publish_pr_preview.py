import copy
import hashlib
import importlib.util
import io
import json
import os
from pathlib import Path
import re
import sys
import tarfile
import tempfile
from types import SimpleNamespace
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW = ROOT / ".github" / "workflows" / "publish-pr-preview.yml"
REVISION = "1" * 40
OTHER_REVISION = "2" * 40
PR_NUMBER = 199


def load_script(name, relative):
    spec = importlib.util.spec_from_file_location(name, ROOT / relative)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


OCI = load_script("inspect_pr_preview_oci", "scripts/inspect_pr_preview_oci.py")
REGISTRY = load_script("pr_preview_registry", "scripts/pr_preview_registry.py")
PUBLISH = load_script(
    "publish_pr_preview_oci",
    "scripts/publish_pr_preview_oci.py",
)


def digest(data):
    return "sha256:" + hashlib.sha256(data).hexdigest()


def tar_file(bundle, name, data):
    info = tarfile.TarInfo(name)
    info.size = len(data)
    info.mode = 0o644
    bundle.addfile(info, io.BytesIO(data))


def make_archive(
    directory,
    revision=REVISION,
    *,
    architecture="arm64",
    manifest_source=OCI.SOURCE,
    manifest_revision=None,
    label_source=OCI.SOURCE,
    label_revision=None,
    environment_revision=None,
    layer=b"preview-layer",
    layer_size=None,
    extra_image=False,
):
    manifest_revision = manifest_revision or revision
    label_revision = label_revision or revision
    environment_revision = environment_revision or revision
    layer_digest = digest(layer)
    config = {
        "architecture": architecture,
        "os": "linux",
        "config": {
            "Labels": {
                "org.opencontainers.image.source": label_source,
                "org.opencontainers.image.revision": label_revision,
            },
            "Env": [f"MOON_BUILD_REVISION={environment_revision}"],
            "User": "10001:10001",
            "Entrypoint": ["java", "-jar", "/app/moon-service.jar"],
            "WorkingDir": "/app",
            "StopSignal": "SIGTERM",
            "ExposedPorts": {"8080/tcp": {}},
            "Healthcheck": {
                "Test": ["CMD", "/usr/local/bin/moon-service-healthcheck"],
                "Interval": 30_000_000_000,
                "Timeout": 3_000_000_000,
                "StartPeriod": 30_000_000_000,
                "StartInterval": 2_000_000_000,
                "Retries": 3,
            },
        },
        "rootfs": {"type": "layers", "diff_ids": [digest(b"diff-id")]},
    }
    config_data = OCI.canonical_json(config)
    config_digest = digest(config_data)
    manifest = {
        "schemaVersion": 2,
        "mediaType": OCI.OCI_MANIFEST,
        "config": {
            "mediaType": OCI.OCI_CONFIG,
            "digest": config_digest,
            "size": len(config_data),
        },
        "layers": [
            {
                "mediaType": OCI.OCI_LAYER,
                "digest": layer_digest,
                "size": len(layer) if layer_size is None else layer_size,
            }
        ],
        "annotations": {
            "org.opencontainers.image.source": manifest_source,
            "org.opencontainers.image.revision": manifest_revision,
        },
    }
    manifest_data = OCI.canonical_json(manifest)
    manifest_digest = digest(manifest_data)
    descriptor = {
        "mediaType": OCI.OCI_MANIFEST,
        "digest": manifest_digest,
        "size": len(manifest_data),
        "platform": {"architecture": architecture, "os": "linux"},
    }
    index = {
        "schemaVersion": 2,
        "mediaType": OCI.OCI_INDEX,
        "manifests": [descriptor, copy.deepcopy(descriptor)]
        if extra_image
        else [descriptor],
    }
    archive = directory / "preview-image.oci.tar"
    with tarfile.open(archive, "w") as bundle:
        tar_file(bundle, "oci-layout", b'{"imageLayoutVersion":"1.0.0"}')
        tar_file(bundle, "index.json", OCI.canonical_json(index))
        tar_file(bundle, f"blobs/sha256/{manifest_digest[7:]}", manifest_data)
        tar_file(bundle, f"blobs/sha256/{config_digest[7:]}", config_data)
        tar_file(bundle, f"blobs/sha256/{layer_digest[7:]}", layer)
    return archive


class MemoryRegistry:
    def __init__(self):
        self.manifests = {}
        self.blobs = {}
        self.puts = []

    def manifest(self, reference, missing=False):
        stored = self.manifests.get(reference)
        if stored is None:
            if missing:
                return None
            raise ValueError(f"missing manifest: {reference}")
        body, media_type = stored
        return body, media_type, digest(body)

    def blob(self, blob_digest, size, limit):
        return self.blobs[blob_digest]

    def has_blob(self, blob_digest, size):
        return blob_digest in self.blobs and len(self.blobs[blob_digest]) == size

    def upload_blob(self, blob_digest, path):
        self.blobs[blob_digest] = path.read_bytes()

    def put_manifest(self, reference, media_type, body):
        self.puts.append(reference)
        self.manifests[reference] = (body, media_type)


def make_publish_args(
    archive,
    identity,
    *,
    revision=REVISION,
    run_id=900,
    run_number=90,
    run_attempt=1,
    expected_index_digest=None,
):
    return SimpleNamespace(
        archive=str(archive) if archive else None,
        identity=str(identity) if identity else None,
        expected_index_digest=expected_index_digest,
        pr_number=PR_NUMBER,
        revision=revision,
        run_id=run_id,
        run_number=run_number,
        run_attempt=run_attempt,
    )


def prepare_handoff(directory, revision=REVISION, **archive_options):
    archive = make_archive(directory, revision, **archive_options)
    identity = directory / "expected-identity.json"
    with mock.patch.dict(os.environ, {}, clear=True):
        OCI.command_inspect(
            SimpleNamespace(
                archive=str(archive),
                identity=str(identity),
                revision=revision,
            )
        )
    return archive, identity


def parse_output(path):
    result = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        key, value = line.split("=", 1)
        result[key] = value
    return result


def run_publish(registry, args, directory):
    output = directory / f"output-{len(list(directory.iterdir()))}.txt"
    summary = directory / f"summary-{len(list(directory.iterdir()))}.md"
    with mock.patch.object(REGISTRY, "Registry", return_value=registry), mock.patch.dict(
        os.environ,
        {"GITHUB_OUTPUT": str(output), "GITHUB_STEP_SUMMARY": str(summary)},
        clear=True,
    ):
        PUBLISH.command_publish(args)
    return parse_output(output), summary.read_text(encoding="utf-8")


def job_block(workflow, name):
    lines = workflow.splitlines(keepends=True)
    start = lines.index(f"  {name}:\n")
    end = len(lines)
    for number in range(start + 1, len(lines)):
        if re.fullmatch(r"  [a-z0-9-]+:\n", lines[number]):
            end = number
            break
    return "".join(lines[start:end])


class PreviewOciPublicationTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def directory(self, name):
        path = self.root / name
        path.mkdir()
        return path

    def test_archive_requires_one_arm64_image_with_exact_identity(self):
        archive, identity = prepare_handoff(self.root)
        value = json.loads(identity.read_text(encoding="utf-8"))
        self.assertEqual("linux/arm64", value["platform"])
        self.assertEqual(OCI.SOURCE, value["source"])
        self.assertEqual(REVISION, value["revision"])

        cases = (
            {"architecture": "amd64"},
            {"manifest_source": "https://example.invalid/source"},
            {"manifest_revision": OTHER_REVISION},
            {"label_source": "https://example.invalid/source"},
            {"label_revision": OTHER_REVISION},
            {"environment_revision": OTHER_REVISION},
            {"layer_size": len(b"preview-layer") + 1},
            {"extra_image": True},
        )
        for number, options in enumerate(cases):
            with self.subTest(options):
                root = self.directory(f"invalid-{number}")
                archive = make_archive(root, **options)
                with self.assertRaises(ValueError):
                    OCI.inspect_archive(archive, root / "extract", REVISION)

        unsafe = self.root / "unsafe.oci.tar"
        with tarfile.open(unsafe, "w") as bundle:
            tar_file(bundle, "../outside", b"unsafe")
        with self.assertRaisesRegex(ValueError, "unsafe or duplicate path"):
            OCI.inspect_archive(unsafe, self.root / "unsafe-extract", REVISION)

    def test_first_publish_creates_only_preview_tags_and_manual_handoff(self):
        archive, identity = prepare_handoff(self.root)
        registry = MemoryRegistry()
        values, summary = run_publish(
            registry,
            make_publish_args(archive, identity, run_id=900, run_attempt=3),
            self.root,
        )
        tag = f"preview-pr-{PR_NUMBER}-{REVISION}"
        tagged = REGISTRY.remote_index(registry, tag, REVISION, PR_NUMBER)
        channel = REGISTRY.remote_index(registry, REGISTRY.CHANNEL)

        self.assertEqual(tagged["digest"], channel["digest"])
        self.assertEqual(
            [tag, REGISTRY.CHANNEL],
            [item for item in registry.puts if not item.startswith("sha256:")],
        )
        self.assertEqual("created", values["publication"])
        self.assertEqual("advanced", values["channel_action"])
        self.assertEqual(tag, values["immutable_tag"])
        self.assertEqual(tagged["digest"], values["index_digest"])
        self.assertEqual("90", values["producer_run_number"])
        self.assertIn(f"Pull request: `#{PR_NUMBER}`", summary)
        self.assertIn(f"Full revision: `{REVISION}`", summary)
        self.assertIn(
            f"{REGISTRY.REGISTRY}/{REGISTRY.REPOSITORY}:{tag}",
            summary,
        )
        self.assertIn(tagged["digest"], summary)

    def test_exact_immutable_reuses_probed_digest_without_a_write(self):
        archive, identity = prepare_handoff(self.root)
        registry = MemoryRegistry()
        first, _ = run_publish(
            registry, make_publish_args(archive, identity, run_id=900), self.root
        )
        registry.puts.clear()
        values, _ = run_publish(
            registry,
            make_publish_args(
                None,
                None,
                run_id=901,
                expected_index_digest=first["index_digest"],
            ),
            self.root,
        )

        self.assertEqual([], registry.puts)
        self.assertEqual("reused", values["publication"])
        self.assertEqual("unchanged", values["channel_action"])
        self.assertEqual("900", values["producer_run_id"])

    def test_reuse_rejects_a_changed_probed_digest(self):
        first_dir = self.directory("first")
        archive, identity = prepare_handoff(first_dir)
        registry = MemoryRegistry()
        values, _ = run_publish(
            registry, make_publish_args(archive, identity), first_dir
        )
        registry.puts.clear()
        with self.assertRaisesRegex(ValueError, "changed after"):
            run_publish(
                registry,
                make_publish_args(
                    None,
                    None,
                    expected_index_digest="sha256:" + "f" * 64,
                ),
                self.root,
            )
        self.assertEqual([], registry.puts)

    def test_existing_immutable_rejects_a_different_archive(self):
        first_dir = self.directory("first-archive")
        archive, identity = prepare_handoff(first_dir)
        registry = MemoryRegistry()
        run_publish(registry, make_publish_args(archive, identity), first_dir)

        different_dir = self.directory("different-archive")
        archive, identity = prepare_handoff(different_dir, layer=b"different")
        registry.puts.clear()
        with self.assertRaisesRegex(ValueError, "does not match the archive"):
            run_publish(
                registry,
                make_publish_args(archive, identity, run_id=901),
                different_dir,
            )
        self.assertEqual([], registry.puts)

    def test_older_candidate_cannot_replace_a_newer_channel(self):
        old_dir = self.directory("old")
        old_archive, old_identity = prepare_handoff(old_dir)
        registry = MemoryRegistry()
        old, _ = run_publish(
            registry,
            make_publish_args(
                old_archive,
                old_identity,
                run_id=999,
                run_number=90,
            ),
            old_dir,
        )

        new_dir = self.directory("new")
        new_archive, new_identity = prepare_handoff(
            new_dir, OTHER_REVISION, layer=b"newer"
        )
        newer, _ = run_publish(
            registry,
            make_publish_args(
                new_archive,
                new_identity,
                revision=OTHER_REVISION,
                run_id=100,
                run_number=91,
            ),
            new_dir,
        )
        registry.puts.clear()
        result, _ = run_publish(
            registry,
            make_publish_args(
                None,
                None,
                run_id=902,
                expected_index_digest=old["index_digest"],
            ),
            self.root,
        )
        channel = REGISTRY.remote_index(registry, REGISTRY.CHANNEL)

        self.assertEqual("kept-newer", result["channel_action"])
        self.assertEqual(newer["index_digest"], channel["digest"])
        self.assertEqual([], registry.puts)

    def test_registry_rejects_unreserved_tag_writes(self):
        registry = object.__new__(REGISTRY.Registry)
        registry.allowed_tags = {
            REGISTRY.CHANNEL,
            f"preview-pr-{PR_NUMBER}-{REVISION}",
        }
        registry.request = mock.Mock()
        for reference in ("main", REVISION, "preview-pr-other"):
            with self.subTest(reference), self.assertRaisesRegex(
                ValueError, "unreserved tag"
            ):
                registry.put_manifest(reference, OCI.OCI_INDEX, b"{}")
        registry.request.assert_not_called()

class PreviewWorkflowContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW.read_text(encoding="utf-8")
        cls.validate = job_block(cls.workflow, "validate-request")
        cls.build = job_block(cls.workflow, "build-preview")
        cls.revalidate = job_block(cls.workflow, "revalidate-request")
        cls.publish = job_block(cls.workflow, "publish-preview")

    def test_dispatch_and_external_actions_are_exactly_trusted(self):
        dispatch = self.workflow[: self.workflow.index("\npermissions:")]
        self.assertIn("  workflow_dispatch:\n", dispatch)
        self.assertNotIn("\n  pull_request:", dispatch)
        self.assertNotIn("push:", dispatch)
        self.assertEqual(1, dispatch.count("      pull_request:"))
        self.assertNotIn("      ref:", dispatch)
        self.assertNotIn("      sha:", dispatch)
        for expected in (
            '[[ "$REF" == "refs/heads/main" ]]',
            '[[ "$ACTOR" == "rapucha" ]]',
            '[[ "$TRIGGERING_ACTOR" == "rapucha" ]]',
            "publish-pr-preview.yml@refs/heads/main",
        ):
            self.assertIn(expected, self.validate)
        self.assertLess(
            self.validate.index("Reject an untrusted dispatch"),
            self.validate.index("Check out trusted workflow code"),
        )
        pins = re.findall(r"uses:\s+[^@\s]+@([^\s#]+)", self.workflow)
        self.assertTrue(pins)
        self.assertTrue(all(re.fullmatch(r"[0-9a-f]{40}", pin) for pin in pins))

    def test_only_read_only_build_executes_pull_request_source(self):
        self.assertEqual(1, self.workflow.count("path: source"))
        self.assertIn("path: source", self.build)
        self.assertIn("ref: ${{ needs.validate-request.outputs.head_sha }}", self.build)
        self.assertIn("permissions:\n      contents: read", self.build)
        self.assertNotIn("packages: write", self.build)
        self.assertNotIn("packages: write", self.validate)
        self.assertNotIn("packages: write", self.revalidate)
        self.assertEqual(1, self.workflow.count("packages: write"))
        self.assertIn("packages: write", self.publish)
        self.assertIn("actions: read", self.publish)
        self.assertIn("pull-requests: read", self.publish)
        self.assertNotIn("path: source", self.publish)
        self.assertNotIn("docker ", self.publish)
        self.assertNotIn("smoke_container_image", self.publish)
        self.assertNotIn("secrets.", self.workflow)
        self.assertNotIn("actions/cache@", self.workflow)
        self.assertNotIn("cache-from", self.workflow)
        self.assertNotIn("cache-to", self.workflow)
        self.assertIn("cache-image: false", self.build)
        self.assertIn("cache-binary: false", self.build)
        checkout_count = self.workflow.count("uses: actions/checkout@")
        self.assertEqual(checkout_count, self.workflow.count("persist-credentials: false"))
        self.assertEqual(4, self.workflow.count("ref: ${{ github.workflow_sha }}"))

    def test_handoff_revalidation_reuse_and_channel_wiring_are_exact(self):
        self.assertEqual(
            1,
            self.workflow.count("inspect_pr_preview_oci.py inspect"),
        )
        self.assertIn("publish_pr_preview_oci.py probe", self.validate)
        self.assertIn("publish_pr_preview_oci.py publish", self.publish)
        self.assertIn("retention-days: 1", self.build)
        artifact_name_value = (
            "preview-pr-${{ needs.validate-request.outputs.pull_request_number }}-"
            "${{ needs.validate-request.outputs.head_sha }}-${{ github.run_id }}-"
            "${{ github.run_attempt }}"
        )
        self.assertEqual(1, self.workflow.count(artifact_name_value))
        self.assertIn(
            "artifact_name: ${{ steps.handoff.outputs.artifact_name }}",
            self.build,
        )
        self.assertIn("id: handoff", self.build)
        self.assertIn(
            "name: ${{ steps.handoff.outputs.artifact_name }}",
            self.build,
        )
        self.assertIn(
            "name: ${{ needs.build-preview.outputs.artifact_name }}",
            self.publish,
        )
        self.assertIn("preview-image.oci.tar", self.build)
        self.assertIn("expected-identity.json", self.build)
        self.assertIn("Prepare an empty handoff directory", self.publish)
        self.assertIn("find \"$HANDOFF_DIR\" -mindepth 1", self.publish)
        self.assertIn("- revalidate-request", self.publish)
        self.assertIn("group: moon-service-preview-channel", self.publish)
        self.assertIn("queue: max", self.publish)
        self.assertIn("cancel-in-progress: false", self.publish)
        self.assertIn('--run-number "$GITHUB_RUN_NUMBER"', self.publish)
        self.assertIn("Revalidate immediately before publication", self.publish)
        self.assertIn("publish_pr_preview.py revalidate", self.publish)
        self.assertLess(
            self.publish.index("Download the exact OCI handoff"),
            self.publish.index("Revalidate immediately before publication"),
        )
        self.assertLess(
            self.publish.index("Revalidate immediately before publication"),
            self.publish.index("Publish or reuse the immutable preview"),
        )
        self.assertIn(
            "existing: ${{ steps.existing.outputs.existing }}", self.validate
        )
        self.assertIn(
            "EXPECTED_EXISTING_DIGEST: "
            "${{ needs.validate-request.outputs.existing_digest }}",
            self.publish,
        )
        self.assertIn(
            'arguments+=(--expected-index-digest "$EXPECTED_EXISTING_DIGEST")',
            self.publish,
        )

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            with mock.patch.object(
                REGISTRY, "Registry", return_value=MemoryRegistry()
            ), mock.patch.dict(
                os.environ, {"GITHUB_OUTPUT": str(output)}, clear=True
            ):
                PUBLISH.command_probe(
                    SimpleNamespace(pr_number=PR_NUMBER, revision=REVISION)
                )
            values = parse_output(output)
        self.assertEqual("false", values["existing"])


if __name__ == "__main__":
    unittest.main()
