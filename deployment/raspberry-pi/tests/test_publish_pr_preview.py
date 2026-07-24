from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "publish-pr-preview.yml"
SELECTOR_PATH = ROOT / "scripts" / "select_preview_package_versions.py"

SELECTOR_SPEC = importlib.util.spec_from_file_location(
    "select_preview_package_versions", SELECTOR_PATH
)
SELECTOR = importlib.util.module_from_spec(SELECTOR_SPEC)
sys.modules[SELECTOR_SPEC.name] = SELECTOR
SELECTOR_SPEC.loader.exec_module(SELECTOR)

NOW = 1_800_000_000
ACTIVE_DIGEST = "sha256:" + ("a" * 64)
PROBE_DIGEST = "sha256:" + ("b" * 64)


def preview_tag(number=7, character="1"):
    return f"preview-pr-{number}-{character * 40}"


def created_at(age_seconds):
    instant = datetime.fromtimestamp(NOW - age_seconds, timezone.utc)
    return instant.strftime("%Y-%m-%dT%H:%M:%SZ")


def version(version_id, age_seconds, tags, digest=None):
    return {
        "id": version_id,
        "name": digest or ("sha256:" + f"{version_id:064x}"),
        "created_at": created_at(age_seconds),
        "metadata": {"container": {"tags": list(tags)}},
        "html_url": "https://example.invalid/package-version",
    }


def page_stream(*pages):
    return "\n".join(json.dumps(page) for page in pages)


def parsed(*records):
    return SELECTOR.parse_page_stream(page_stream(list(records)))


class PublishPullRequestPreviewWorkflowTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.workflow = WORKFLOW_PATH.read_text(encoding="utf-8")
        cls.build, cls.publish = cls._job_blocks(cls.workflow)

    @staticmethod
    def _job_blocks(workflow):
        build_start = workflow.index("  build-preview:\n")
        publish_start = workflow.index("  publish-preview:\n")
        return workflow[build_start:publish_start], workflow[publish_start:]

    def test_owner_dispatch_and_current_pull_request_are_validated_twice(self):
        self.assertIn("workflow_dispatch:", self.workflow)
        self.assertIn('[[ "$REF" == "refs/heads/main" ]]', self.build)
        self.assertIn('[[ "$ACTOR" == "rapucha" ]]', self.build)
        self.assertIn('[[ "$TRIGGERING_ACTOR" == "rapucha" ]]', self.build)
        self.assertEqual(2, self.workflow.count('gh pr view "$PULL_REQUEST"'))
        self.assertEqual(2, self.workflow.count('.state == "OPEN"'))
        self.assertEqual(2, self.workflow.count('.baseRefName == "main"'))
        self.assertEqual(2, self.workflow.count(".isCrossRepository == false"))
        self.assertIn(".headRefOid == $head", self.publish)
        for check in ("Backend tests", "Frontend tests", "Deployment tests"):
            self.assertEqual(2, self.workflow.count(f'.name == "{check}"'))
        self.assertEqual(2, self.workflow.count('.conclusion == "SUCCESS"'))

    def test_only_publisher_can_write_packages(self):
        self.assertEqual(1, self.workflow.count("packages: write"))
        self.assertNotIn("packages: write", self.build)
        self.assertIn("packages: write", self.publish)
        self.assertNotIn("docker run", self.publish)

    def test_arm64_image_is_smoked_then_handed_off_as_docker_archive(self):
        for required in (
            "docker/setup-qemu-action@",
            "docker/setup-buildx-action@",
            "--platform linux/arm64",
            "--load",
            'trusted/scripts/smoke_container_image.sh "$LOCAL_IMAGE" "$REVISION"',
            'docker save --output "$ARCHIVE_DIR/preview-image.tar"',
            "actions/upload-artifact@",
            "retention-days: 1",
        ):
            self.assertIn(required, self.build)
        self.assertIn("artifact_name: ${{ steps.archive.outputs.artifact_name }}", self.build)
        self.assertIn("name: ${{ needs.build-preview.outputs.artifact_name }}", self.publish)

    def test_publisher_uses_standard_docker_commands_and_reports_one_digest(self):
        for required in (
            'docker load --input "$RUNNER_TEMP/moon-preview-archive/preview-image.tar"',
            ".[0].Architecture == \"arm64\"",
            "docker/login-action@",
            'docker tag "$LOCAL_IMAGE" "$REVISION_REF"',
            'docker push "$REVISION_REF"',
            'docker tag "$LOCAL_IMAGE" "$PREVIEW_REF"',
            'docker push "$PREVIEW_REF"',
            "docker buildx imagetools inspect",
            '[[ "$preview_digest" == "$revision_digest" ]]',
            'echo "- Digest: \\`$revision_digest\\`"',
        ):
            self.assertIn(required, self.publish)

    def test_preview_pull_request_label_is_built_and_revalidated(self):
        label = "dev.moonservice.preview.pull-request"
        self.assertIn(f'--label "{label}=$PULL_REQUEST"', self.build)
        self.assertIn(
            f'.[0].Config.Labels["{label}"] == $pull_request',
            self.publish,
        )
        self.assertIn(
            "PULL_REQUEST: ${{ needs.build-preview.outputs.pull_request_number }}",
            self.publish,
        )

    def test_retention_uses_complete_fixed_endpoint_after_digest_verification(self):
        endpoint = (
            "/users/rapucha/packages/container/moon-service/versions"
            "?state=active&per_page=100"
        )
        digest_check = self.publish.index(
            '[[ "$preview_digest" == "$revision_digest" ]]'
        )
        retention = self.publish.index(
            "- name: Retain bounded preview package versions"
        )
        self.assertLess(digest_check, retention)
        self.assertGreaterEqual(self.publish.count(f'"{endpoint}"'), 4)
        self.assertGreaterEqual(self.publish.count("gh api --paginate"), 4)
        self.assertNotIn("/orgs/", self.publish)

    def test_retention_revalidates_and_deletes_only_returned_candidate_id(self):
        retention = self.publish[
            self.publish.index("- name: Retain bounded preview package versions"):
            self.publish.index("- name: Probe package list get and delete capability")
        ]
        self.assertIn("select_preview_package_versions.py plan", retention)
        self.assertIn("select_preview_package_versions.py revalidate", retention)
        self.assertIn('gh api "$PACKAGE_VERSIONS_PATH/$active_id"', retention)
        self.assertIn('gh api "$PACKAGE_VERSIONS_PATH/$candidate_id"', retention)
        self.assertIn('[[ "$delete_id" == "$candidate_id" ]]', retention)
        self.assertIn(
            'gh api --method DELETE "$PACKAGE_VERSIONS_PATH/$delete_id"',
            retention,
        )
        self.assertNotIn(
            'gh api --method DELETE "$PACKAGE_VERSIONS_PATH/$candidate_id"',
            retention,
        )

    def test_capability_probe_defaults_off_and_proves_exact_lifecycle(self):
        self.assertIn("package_capability_probe:", self.workflow)
        probe_input = self.workflow[
            self.workflow.index("      package_capability_probe:"):
            self.workflow.index("\n\npermissions:")
        ]
        self.assertIn("default: false", probe_input)
        probe = self.publish[
            self.publish.index("- name: Probe package list get and delete capability"):
        ]
        self.assertIn("if: ${{ inputs.package_capability_probe }}", probe)
        self.assertEqual(2, probe.count("select_preview_package_versions.py probe-created"))
        self.assertEqual(2, probe.count("select_preview_package_versions.py probe-absent"))
        self.assertIn("--probe-record", probe)
        self.assertIn('gh api "$PACKAGE_VERSIONS_PATH/$probe_id"', probe)
        self.assertIn('[[ "$delete_id" == "$probe_id" ]]', probe)
        self.assertIn("--probe-version-id \"$delete_id\"", probe)

    def test_workflow_serializes_complete_dispatches(self):
        pre_jobs = self.workflow[: self.workflow.index("jobs:\n")]
        self.assertIn("group: moon-service-pr-preview", pre_jobs)
        self.assertIn("queue: max", pre_jobs)
        self.assertIn("cancel-in-progress: false", pre_jobs)
        self.assertNotIn("concurrency:", self.build)
        self.assertNotIn("concurrency:", self.publish)

    def test_external_actions_are_commit_pinned(self):
        action_lines = [
            line.strip()
            for line in self.workflow.splitlines()
            if line.strip().startswith("uses:")
        ]
        self.assertGreaterEqual(len(action_lines), 7)
        for line in action_lines:
            with self.subTest(line=line):
                self.assertRegex(line, r"^uses: [^@\s]+@[0-9a-f]{40}(?:\s+#.*)?$")

    def test_custom_oci_and_registry_protocols_are_absent(self):
        forbidden = (
            "inspect_pr_preview_oci.py",
            "pr_preview_registry.py",
            "publish_pr_preview.py",
            "publish_pr_preview_oci.py",
            "type=oci",
            "expected-identity.json",
            "urllib.request",
            "ghcr.io/v2/",
            "skopeo",
            "oras ",
        )
        for value in forbidden:
            self.assertNotIn(value, self.workflow)
        for name in forbidden[:4]:
            self.assertFalse((ROOT / "scripts" / name).exists())


class PreviewPackageSelectorTest(unittest.TestCase):
    def test_complete_sequential_pages_are_parsed_and_ids_are_unique(self):
        active = version(90, 0, ["preview"], ACTIVE_DIGEST)
        old = version(1, SELECTOR.AGE_SECONDS + 1, [preview_tag()])
        versions = SELECTOR.parse_page_stream(
            page_stream([active], [], [old])
        )
        self.assertEqual([90, 1], [item.id for item in versions])

        malformed = (
            "",
            "{}",
            json.dumps([active]) + " trailing",
            json.dumps([active]) + json.dumps({"not": "a page"}),
        )
        for stream in malformed:
            with self.subTest(stream=stream):
                with self.assertRaises(SELECTOR.SelectionError):
                    SELECTOR.parse_page_stream(stream)

        with self.assertRaisesRegex(
            SELECTOR.SelectionError, "duplicate package-version ID"
        ):
            SELECTOR.parse_page_stream(page_stream([active], [active]))

    def test_package_version_schema_fails_closed(self):
        valid = version(1, 0, [preview_tag()])
        fractional = deepcopy(valid)
        fractional["created_at"] = fractional["created_at"].replace(
            "Z", ".123456789Z"
        )
        self.assertEqual(1, SELECTOR.validate_version(fractional).id)

        invalid = []
        for key, value in (
            ("id", 0),
            ("id", True),
            ("name", "sha256:" + ("A" * 64)),
            ("created_at", "2027-01-15T08:00:00+00:00"),
            ("created_at", "2027-02-30T08:00:00Z"),
        ):
            record = deepcopy(valid)
            record[key] = value
            invalid.append(record)
        for tags in (
            [preview_tag(), preview_tag()],
            [7],
            "not-an-array",
        ):
            record = deepcopy(valid)
            record["metadata"]["container"]["tags"] = tags
            invalid.append(record)
        missing_container = deepcopy(valid)
        missing_container["metadata"] = {}
        invalid.append(missing_container)

        for record in invalid:
            with self.subTest(record=record):
                with self.assertRaisesRegex(
                    SELECTOR.SelectionError, "invalid package-version schema"
                ):
                    SELECTOR.validate_version(record)

    def test_active_and_exactly_nine_other_newest_versions_are_kept(self):
        active = version(
            99, 0, ["preview", preview_tag(99)], ACTIVE_DIGEST
        )
        others = [
            version(item, SELECTOR.AGE_SECONDS + 100, [preview_tag(item)])
            for item in range(1, 12)
        ]
        plan = SELECTOR.build_plan(parsed(active, *others), ACTIVE_DIGEST, NOW)

        self.assertEqual(99, plan["active_version_id"])
        self.assertEqual([2, 1], plan["candidate_ids"])
        self.assertEqual(
            {99, *range(3, 12)},
            set(plan["keep_ids"]),
        )

    def test_age_boundary_and_exact_preview_tag_grammar_filter_candidates(self):
        active = version(999, 0, ["preview"], ACTIVE_DIGEST)
        nine_newer = [
            version(
                100 + item,
                SELECTOR.AGE_SECONDS - 100,
                [preview_tag(100 + item)],
            )
            for item in range(9)
        ]
        boundary = version(
            50, SELECTOR.AGE_SECONDS, [preview_tag(50)]
        )
        eligible = version(
            31,
            SELECTOR.AGE_SECONDS + 1,
            [preview_tag(31), preview_tag(31, "2")],
        )
        filtered = (
            version(30, SELECTOR.AGE_SECONDS + 1, []),
            version(29, SELECTOR.AGE_SECONDS + 1, ["main"]),
            version(
                28,
                SELECTOR.AGE_SECONDS + 1,
                [preview_tag(28), "main"],
            ),
            version(
                27,
                SELECTOR.AGE_SECONDS + 1,
                ["preview-pr-0-" + ("1" * 40)],
            ),
            version(
                26,
                SELECTOR.AGE_SECONDS + 1,
                ["preview-pr-26-" + ("A" * 40)],
            ),
            version(25, SELECTOR.AGE_SECONDS + 1, ["1" * 40]),
        )
        plan = SELECTOR.build_plan(
            parsed(active, *nine_newer, boundary, eligible, *filtered),
            ACTIVE_DIGEST,
            NOW,
        )

        self.assertIn(50, plan["keep_ids"])
        self.assertEqual([31], plan["candidate_ids"])
        self.assertTrue(set(range(100, 109)).issubset(plan["keep_ids"]))
        self.assertTrue(set(item["id"] for item in filtered).isdisjoint(
            plan["candidate_ids"]
        ))

    def test_active_preview_must_be_unique_and_match_dispatch_digest(self):
        active = version(10, 0, ["preview"], ACTIVE_DIGEST)
        old = version(1, SELECTOR.AGE_SECONDS + 1, [preview_tag()])
        cases = (
            parsed(old),
            parsed(active, version(11, 0, ["preview"], ACTIVE_DIGEST)),
            parsed(version(10, 0, ["preview"], PROBE_DIGEST), old),
        )
        for versions in cases:
            with self.subTest(ids=[item.id for item in versions]):
                with self.assertRaisesRegex(
                    SELECTOR.SelectionError,
                    "active preview identity is invalid",
                ):
                    SELECTOR.build_plan(versions, ACTIVE_DIGEST, NOW)

    def test_revalidation_matches_snapshot_and_refuses_keep_set_ids(self):
        active = version(
            99, 0, ["preview", preview_tag(99)], ACTIVE_DIGEST
        )
        candidate = version(
            1,
            SELECTOR.AGE_SECONDS + 10,
            [preview_tag(1), preview_tag(1, "2")],
        )
        kept = [
            version(
                item,
                SELECTOR.AGE_SECONDS + 10,
                [preview_tag(item)],
            )
            for item in range(2, 11)
        ]
        plan = SELECTOR.build_plan(
            parsed(active, candidate, *kept), ACTIVE_DIGEST, NOW
        )
        active_record = SELECTOR.validate_version(active)
        reordered_candidate = deepcopy(candidate)
        reordered_candidate["metadata"]["container"]["tags"].reverse()
        candidate_record = SELECTOR.validate_version(reordered_candidate)
        self.assertEqual(
            1,
            SELECTOR.revalidate_candidate(
                plan, 1, active_record, candidate_record
            ),
        )

        changed_records = []
        changed_active = deepcopy(active)
        changed_active["name"] = PROBE_DIGEST
        changed_records.append((
            SELECTOR.validate_version(changed_active),
            candidate_record,
        ))
        changed_candidate = deepcopy(candidate)
        changed_candidate["created_at"] = created_at(
            SELECTOR.AGE_SECONDS + 11
        )
        changed_records.append((
            active_record,
            SELECTOR.validate_version(changed_candidate),
        ))
        changed_tags = deepcopy(candidate)
        changed_tags["metadata"]["container"]["tags"].append(preview_tag(1, "3"))
        changed_records.append((
            active_record,
            SELECTOR.validate_version(changed_tags),
        ))
        for fresh_active, fresh_candidate in changed_records:
            with self.subTest(
                active=fresh_active.snapshot(),
                candidate=fresh_candidate.snapshot(),
            ):
                with self.assertRaises(SELECTOR.SelectionError):
                    SELECTOR.revalidate_candidate(
                        plan, 1, fresh_active, fresh_candidate
                    )

        with self.assertRaisesRegex(
            SELECTOR.SelectionError, "candidate is not approved"
        ):
            SELECTOR.revalidate_candidate(
                plan,
                10,
                active_record,
                SELECTOR.validate_version(kept[-1]),
            )

    def test_plan_rejects_type_or_selection_tampering(self):
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        others = [
            version(item, SELECTOR.AGE_SECONDS + 1, [preview_tag(item)])
            for item in range(1, 11)
        ]
        plan = SELECTOR.build_plan(parsed(active, *others), ACTIVE_DIGEST, NOW)
        tampered = deepcopy(plan)
        tampered["candidate_ids"] = [True]
        with self.assertRaisesRegex(SELECTOR.SelectionError, "invalid plan"):
            SELECTOR.validate_plan(tampered)
        tampered = deepcopy(plan)
        tampered["keep_ids"].remove(10)
        tampered["candidate_ids"].insert(0, 10)
        with self.assertRaisesRegex(SELECTOR.SelectionError, "invalid plan"):
            SELECTOR.validate_plan(tampered)

    def test_probe_creation_get_and_absence_are_exact(self):
        probe_tag = "preview-package-capability-probe-100-1"
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        old = version(1, SELECTOR.AGE_SECONDS + 1, [preview_tag()])
        probe = version(200, 0, [probe_tag], PROBE_DIGEST)
        before = parsed(active, old)
        after = parsed(active, old, probe)
        probe_record = SELECTOR.validate_version(probe)

        self.assertTrue(SELECTOR.prove_probe_absent(
            before, ACTIVE_DIGEST, probe_tag
        ))
        self.assertEqual(
            200,
            SELECTOR.prove_probe_created(
                before,
                after,
                ACTIVE_DIGEST,
                probe_tag,
                PROBE_DIGEST,
                probe_record,
            ),
        )
        self.assertTrue(SELECTOR.prove_probe_absent(
            before, ACTIVE_DIGEST, probe_tag, 200
        ))

        wrong_record = deepcopy(probe)
        wrong_record["created_at"] = created_at(1)
        failures = (
            (parsed(active, old, probe), after, None),
            (before, parsed(active, old, probe, version(
                201, 0, ["other-probe"], "sha256:" + ("c" * 64)
            )), None),
            (before, parsed(active, old, version(
                200, 0, [probe_tag, "extra"], PROBE_DIGEST
            )), None),
            (
                before,
                after,
                SELECTOR.validate_version(wrong_record),
            ),
        )
        for before_case, after_case, record_case in failures:
            with self.subTest(
                before=[item.id for item in before_case],
                after=[item.id for item in after_case],
            ):
                with self.assertRaises(SELECTOR.SelectionError):
                    SELECTOR.prove_probe_created(
                        before_case,
                        after_case,
                        ACTIVE_DIGEST,
                        probe_tag,
                        PROBE_DIGEST,
                        record_case,
                    )
        with self.assertRaisesRegex(SELECTOR.SelectionError, "probe tag is present"):
            SELECTOR.prove_probe_absent(after, ACTIVE_DIGEST, probe_tag)
        with self.assertRaisesRegex(
            SELECTOR.SelectionError, "probe version is present"
        ):
            SELECTOR.prove_probe_absent(
                parsed(active, old, version(200, 0, ["other"], PROBE_DIGEST)),
                ACTIVE_DIGEST,
                probe_tag,
                200,
            )

    def test_cli_modes_use_files_and_emit_only_validated_json(self):
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        candidate = version(
            1, SELECTOR.AGE_SECONDS + 1, [preview_tag(1)]
        )
        kept = [
            version(
                item,
                SELECTOR.AGE_SECONDS + 1,
                [preview_tag(item)],
            )
            for item in range(2, 11)
        ]
        probe_tag = "preview-package-capability-probe-100-1"
        probe = version(200, 0, [probe_tag], PROBE_DIGEST)

        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)

            def write(name, content):
                path = directory / name
                path.write_text(content, encoding="utf-8")
                return path

            def run(*arguments):
                return subprocess.run(
                    [sys.executable, "-B", str(SELECTOR_PATH), *map(str, arguments)],
                    check=False,
                    capture_output=True,
                    text=True,
                )

            before = page_stream([active, candidate], kept)
            snapshot_path = write("snapshot.json", before)
            result = run(
                "plan",
                "--snapshot", snapshot_path,
                "--expected-digest", ACTIVE_DIGEST,
                "--now-epoch", NOW,
            )
            self.assertEqual("", result.stderr)
            self.assertEqual(0, result.returncode)
            plan = json.loads(result.stdout)
            self.assertEqual([1], plan["candidate_ids"])
            plan_path = write("plan.json", result.stdout)
            active_path = write("active.json", json.dumps(active))
            candidate_path = write("candidate.json", json.dumps(candidate))
            result = run(
                "revalidate",
                "--plan", plan_path,
                "--active-record", active_path,
                "--candidate-record", candidate_path,
                "--candidate-id", 1,
            )
            self.assertEqual({"candidate_id": 1}, json.loads(result.stdout))

            result = run(
                "probe-absent",
                "--snapshot", snapshot_path,
                "--expected-active-digest", ACTIVE_DIGEST,
                "--probe-tag", probe_tag,
            )
            self.assertEqual({"absent": True}, json.loads(result.stdout))
            after_path = write(
                "after.json", page_stream([active, candidate], kept + [probe])
            )
            probe_path = write("probe.json", json.dumps(probe))
            result = run(
                "probe-created",
                "--before-snapshot", snapshot_path,
                "--after-snapshot", after_path,
                "--expected-active-digest", ACTIVE_DIGEST,
                "--probe-tag", probe_tag,
                "--expected-probe-digest", PROBE_DIGEST,
                "--probe-record", probe_path,
            )
            self.assertEqual({"probe_version_id": 200}, json.loads(result.stdout))
            result = run(
                "probe-absent",
                "--snapshot", snapshot_path,
                "--expected-active-digest", ACTIVE_DIGEST,
                "--probe-tag", probe_tag,
                "--probe-version-id", 200,
            )
            self.assertEqual({"absent": True}, json.loads(result.stdout))

            invalid = write("invalid.json", "[SENSITIVE INVALID INPUT]")
            result = run(
                "plan",
                "--snapshot", invalid,
                "--expected-digest", ACTIVE_DIGEST,
                "--now-epoch", NOW,
            )
            self.assertEqual("", result.stdout)
            self.assertEqual(2, result.returncode)
            self.assertIn("error:", result.stderr)
            self.assertNotIn("SENSITIVE", result.stderr)
            self.assertNotIn(str(invalid), result.stderr)

    def test_cli_help_documents_every_workflow_flag(self):
        expected = {
            "plan": ("--snapshot", "--expected-digest", "--now-epoch"),
            "revalidate": (
                "--plan", "--active-record", "--candidate-record", "--candidate-id"
            ),
            "probe-created": (
                "--before-snapshot", "--after-snapshot",
                "--expected-active-digest", "--probe-tag",
                "--expected-probe-digest", "--probe-record",
            ),
            "probe-absent": (
                "--snapshot", "--expected-active-digest",
                "--probe-tag", "--probe-version-id",
            ),
        }
        for command, flags in expected.items():
            result = subprocess.run(
                [sys.executable, "-B", str(SELECTOR_PATH), command, "--help"],
                check=False,
                capture_output=True,
                text=True,
            )
            with self.subTest(command=command):
                self.assertEqual(0, result.returncode)
                for flag in flags:
                    self.assertIn(flag, result.stdout)


if __name__ == "__main__":
    unittest.main()
