from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
WORKFLOW_PATH = ROOT / ".github" / "workflows" / "publish-pr-preview.yml"
HELPER_PATH = (
    "trusted/deployment/raspberry-pi/preview_package_retention.py"
)


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
        self.assertIn(
            "artifact_name: ${{ steps.archive.outputs.artifact_name }}",
            self.build,
        )
        self.assertIn(
            "name: ${{ needs.build-preview.outputs.artifact_name }}",
            self.publish,
        )

    def test_publisher_uses_standard_docker_commands_and_reports_one_digest(self):
        for required in (
            'docker load --input "$RUNNER_TEMP/moon-preview-archive/preview-image.tar"',
            '.[0].Architecture == "arm64"',
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

    def test_package_helper_runs_only_after_digest_verification(self):
        digest_check = self.publish.index(
            '[[ "$preview_digest" == "$revision_digest" ]]'
        )
        retention = self.publish.index(
            "- name: Retain bounded preview package versions"
        )
        probe = self.publish.index(
            "- name: Probe package list get and delete capability"
        )
        self.assertLess(digest_check, retention)
        self.assertLess(retention, probe)
        self.assertEqual(2, self.publish.count(HELPER_PATH))

    def test_workflow_delegates_package_orchestration_to_trusted_helper(self):
        lifecycle = self.publish[
            self.publish.index("- name: Retain bounded preview package versions"):
        ]
        self.assertIn(f"{HELPER_PATH} retain", lifecycle)
        self.assertIn(f"{HELPER_PATH} probe", lifecycle)
        for inline_detail in (
            "gh api --paginate",
            "gh api --method DELETE",
            "PACKAGE_VERSIONS_PATH",
            "select_preview_package_versions.py",
            "preview-package-capability-probe-$GITHUB_RUN_ID",
        ):
            self.assertNotIn(inline_detail, lifecycle)

    def test_capability_probe_defaults_off(self):
        self.assertIn("package_capability_probe:", self.workflow)
        probe_input = self.workflow[
            self.workflow.index("      package_capability_probe:"):
            self.workflow.index("\n\npermissions:")
        ]
        self.assertIn("default: false", probe_input)
        probe = self.publish[
            self.publish.index(
                "- name: Probe package list get and delete capability"
            ):
        ]
        self.assertIn("if: ${{ inputs.package_capability_probe }}", probe)
        self.assertEqual(1, probe.count(f"{HELPER_PATH} probe"))

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
                self.assertRegex(
                    line,
                    r"^uses: [^@\s]+@[0-9a-f]{40}(?:\s+#.*)?$",
                )

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
        self.assertFalse(
            (ROOT / "scripts" / "select_preview_package_versions.py").exists()
        )


if __name__ == "__main__":
    unittest.main()
