from contextlib import redirect_stderr, redirect_stdout
from copy import deepcopy
from datetime import datetime, timezone
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import unittest


ROOT = Path(__file__).resolve().parents[3]
DEPLOYMENT_PATH = ROOT / "deployment" / "raspberry-pi"
VERSIONS_PATH = DEPLOYMENT_PATH / "preview_package_versions.py"
HELPER_PATH = DEPLOYMENT_PATH / "preview_package_retention.py"


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    spec.loader.exec_module(module)
    return module


VERSIONS = load_module("preview_package_versions", VERSIONS_PATH)
HELPER = load_module("preview_package_retention", HELPER_PATH)

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
    }


def page_stream(*pages):
    return "\n".join(json.dumps(page) for page in pages)


def parsed(*records):
    return VERSIONS.parse_page_stream(page_stream(list(records)))


class ScriptedRunner:
    def __init__(self, steps):
        self.steps = list(steps)
        self.calls = []

    def __call__(self, argv, **options):
        self.calls.append((tuple(argv), options))
        if not self.steps:
            raise AssertionError(f"unexpected command: {argv}")
        check, returncode, stdout, stderr = self.steps.pop(0)
        check(tuple(argv), options)
        return subprocess.CompletedProcess(
            argv,
            returncode,
            stdout,
            stderr,
        )

    def assert_complete(self, test):
        test.assertEqual([], self.steps)


def exact_command(expected, *, capture=True):
    def check(argv, options):
        if argv != tuple(expected):
            raise AssertionError(f"expected {expected!r}, got {argv!r}")
        if options.get("text") is not True or options.get("check") is not False:
            raise AssertionError(f"unexpected runner options: {options!r}")
        if capture:
            if (
                options.get("stdout") is not subprocess.PIPE
                or options.get("stderr") is not subprocess.PIPE
            ):
                raise AssertionError(f"output was not captured: {options!r}")
        elif "stdout" in options or "stderr" in options:
            raise AssertionError(f"build output was captured: {options!r}")

    return check


def list_command():
    return exact_command(
        ["gh", "api", "--paginate", HELPER.ACTIVE_VERSIONS_PATH]
    )


def get_command(version_id):
    return exact_command(
        ["gh", "api", f"{HELPER.PACKAGE_VERSIONS_PATH}/{version_id}"]
    )


def delete_command(version_id):
    return exact_command(
        [
            "gh",
            "api",
            "--method",
            "DELETE",
            f"{HELPER.PACKAGE_VERSIONS_PATH}/{version_id}",
        ]
    )


class PreviewPackageVersionsTest(unittest.TestCase):
    def test_pages_schema_and_tag_order_are_normalized_fail_closed(self):
        active = version(90, 0, ["preview"], ACTIVE_DIGEST)
        old = version(1, VERSIONS.AGE_SECONDS + 1, [preview_tag()])
        result = VERSIONS.parse_page_stream(
            page_stream([active], [], [old])
        )
        self.assertEqual([90, 1], [item.id for item in result])

        reordered = version(
            2,
            0,
            [preview_tag(2, "2"), preview_tag(2, "1")],
        )
        normalized = VERSIONS.validate_version(reordered)
        self.assertEqual(tuple(sorted(reordered["metadata"]["container"]["tags"])),
                         normalized.tags)

        malformed_streams = (
            "",
            "{}",
            json.dumps([active]) + " trailing",
            json.dumps([active]) + json.dumps({"not": "a page"}),
            page_stream([active], [active]),
        )
        for stream in malformed_streams:
            with self.subTest(stream=stream):
                with self.assertRaises(VERSIONS.SelectionError):
                    VERSIONS.parse_page_stream(stream)

        invalid = deepcopy(active)
        invalid["metadata"]["container"]["tags"] = ["preview", "preview"]
        with self.assertRaisesRegex(
            VERSIONS.SelectionError,
            "invalid package-version schema",
        ):
            VERSIONS.validate_version(invalid)

    def test_selection_keeps_active_newest_nine_and_inclusive_boundary(self):
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        tied_old = [
            version(item, VERSIONS.AGE_SECONDS + 1, [preview_tag(item)])
            for item in range(1, 12)
        ]
        boundary = version(
            50,
            VERSIONS.AGE_SECONDS,
            [preview_tag(50)],
        )
        filtered = version(
            51,
            VERSIONS.AGE_SECONDS + 1,
            [preview_tag(51), "main"],
        )
        plan = VERSIONS.build_plan(
            parsed(active, *tied_old, boundary, filtered),
            ACTIVE_DIGEST,
            NOW,
        )

        self.assertEqual(99, plan["active_version_id"])
        self.assertEqual([3, 2, 1], plan["candidate_ids"])
        self.assertTrue({99, 50, *range(4, 12)}.issubset(plan["keep_ids"]))
        self.assertNotIn(51, plan["candidate_ids"])

    def test_active_identity_plan_and_fresh_records_are_revalidated(self):
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        candidate = version(
            1,
            VERSIONS.AGE_SECONDS + 10,
            [preview_tag(1), preview_tag(1, "2")],
        )
        kept = [
            version(
                item,
                VERSIONS.AGE_SECONDS + 10,
                [preview_tag(item)],
            )
            for item in range(2, 11)
        ]
        plan = VERSIONS.build_plan(
            parsed(active, candidate, *kept),
            ACTIVE_DIGEST,
            NOW,
        )
        reordered = deepcopy(candidate)
        reordered["metadata"]["container"]["tags"].reverse()
        self.assertEqual(
            1,
            VERSIONS.revalidate_candidate(
                plan,
                1,
                VERSIONS.validate_version(active),
                VERSIONS.validate_version(reordered),
            ),
        )

        tampered = deepcopy(plan)
        tampered["candidate_ids"] = [True]
        with self.assertRaisesRegex(VERSIONS.SelectionError, "invalid plan"):
            VERSIONS.validate_plan(tampered)

        changed = deepcopy(candidate)
        changed["created_at"] = created_at(VERSIONS.AGE_SECONDS + 11)
        with self.assertRaisesRegex(VERSIONS.SelectionError, "candidate changed"):
            VERSIONS.revalidate_candidate(
                plan,
                1,
                VERSIONS.validate_version(active),
                VERSIONS.validate_version(changed),
            )

        duplicate_active = parsed(
            active,
            version(100, 0, ["preview"], ACTIVE_DIGEST),
        )
        with self.assertRaisesRegex(
            VERSIONS.SelectionError,
            "active preview identity is invalid",
        ):
            VERSIONS.build_plan(duplicate_active, ACTIVE_DIGEST, NOW)

    def test_probe_creation_and_absence_require_exact_identity(self):
        probe_tag = "preview-package-capability-probe-100-1"
        active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        old = version(1, VERSIONS.AGE_SECONDS + 1, [preview_tag()])
        probe = version(200, 0, [probe_tag], PROBE_DIGEST)
        before = parsed(active, old)
        after = parsed(active, old, probe)

        self.assertTrue(
            VERSIONS.prove_probe_absent(
                before,
                ACTIVE_DIGEST,
                probe_tag,
            )
        )
        self.assertEqual(
            200,
            VERSIONS.prove_probe_created(
                before,
                after,
                ACTIVE_DIGEST,
                probe_tag,
                PROBE_DIGEST,
                VERSIONS.validate_version(probe),
            ),
        )

        with self.assertRaisesRegex(VERSIONS.SelectionError, "probe tag is present"):
            VERSIONS.prove_probe_absent(after, ACTIVE_DIGEST, probe_tag)

        extra = version(201, 0, ["other"], "sha256:" + ("c" * 64))
        with self.assertRaisesRegex(
            VERSIONS.SelectionError,
            "probe creation identity is invalid",
        ):
            VERSIONS.prove_probe_created(
                before,
                parsed(active, old, probe, extra),
                ACTIVE_DIGEST,
                probe_tag,
                PROBE_DIGEST,
            )


class PreviewPackageRetentionTest(unittest.TestCase):
    def setUp(self):
        self.active = version(99, 0, ["preview"], ACTIVE_DIGEST)
        self.candidate = version(
            1,
            VERSIONS.AGE_SECONDS + 1,
            [preview_tag(1)],
        )
        self.kept = [
            version(
                item,
                VERSIONS.AGE_SECONDS + 1,
                [preview_tag(item)],
            )
            for item in range(2, 11)
        ]
        self.snapshot = page_stream(
            [self.active, self.candidate],
            self.kept,
        )

    def test_routes_and_repository_match_the_fixed_approved_contract(self):
        self.assertEqual(
            "/users/rapucha/packages/container/moon-service/versions",
            HELPER.PACKAGE_VERSIONS_PATH,
        )
        self.assertEqual(
            "/users/rapucha/packages/container/moon-service/versions"
            "?state=active&per_page=100",
            HELPER.ACTIVE_VERSIONS_PATH,
        )
        self.assertEqual(
            "ghcr.io/rapucha/moon-service",
            HELPER.IMAGE_REPOSITORY,
        )

    def test_retain_lists_revalidates_and_deletes_only_the_approved_id(self):
        runner = ScriptedRunner([
            (list_command(), 0, self.snapshot, ""),
            (get_command(99), 0, json.dumps(self.active), ""),
            (get_command(1), 0, json.dumps(self.candidate), ""),
            (delete_command(1), 0, "", ""),
        ])

        self.assertEqual(
            (1,),
            HELPER._retain(
                ACTIVE_DIGEST,
                runner=runner,
                now_epoch=NOW,
            ),
        )
        runner.assert_complete(self)

    def test_retain_stops_before_delete_when_fresh_candidate_changed(self):
        changed = deepcopy(self.candidate)
        changed["metadata"]["container"]["tags"].append("main")
        runner = ScriptedRunner([
            (list_command(), 0, self.snapshot, ""),
            (get_command(99), 0, json.dumps(self.active), ""),
            (get_command(1), 0, json.dumps(changed), ""),
        ])

        with self.assertRaisesRegex(VERSIONS.SelectionError, "candidate changed"):
            HELPER._retain(
                ACTIVE_DIGEST,
                runner=runner,
                now_epoch=NOW,
            )
        runner.assert_complete(self)
        self.assertFalse(
            any("--method" in argv for argv, _ in runner.calls)
        )

    def test_retain_rejects_invalid_digest_before_running_commands(self):
        runner = ScriptedRunner([])
        with self.assertRaisesRegex(HELPER.OperationError, "invalid expected digest"):
            HELPER._retain("not-a-digest", runner=runner, now_epoch=NOW)
        self.assertEqual([], runner.calls)

    def test_probe_runs_the_exact_disposable_lifecycle(self):
        probe_tag = "preview-package-capability-probe-100-2"
        probe = version(200, 0, [probe_tag], PROBE_DIGEST)
        before = page_stream([self.active, self.candidate])
        after = page_stream([self.active, self.candidate, probe])

        def build_check(argv, options):
            self.assertEqual(
                ("docker", "buildx", "build"),
                argv[:3],
            )
            self.assertEqual("linux/arm64", argv[argv.index("--platform") + 1])
            self.assertIn("--push", argv)
            self.assertIn("--provenance=false", argv)
            self.assertIn("--sbom=false", argv)
            self.assertIn(
                f"BASE_IMAGE={HELPER.IMAGE_REPOSITORY}@{ACTIVE_DIGEST}",
                argv,
            )
            self.assertIn(f"{HELPER.PROBE_LABEL}={probe_tag}", argv)
            self.assertIn(
                f"{HELPER.IMAGE_REPOSITORY}:{probe_tag}",
                argv,
            )
            dockerfile = Path(argv[argv.index("--file") + 1])
            self.assertEqual(
                "ARG BASE_IMAGE\nFROM ${BASE_IMAGE}\n",
                dockerfile.read_text(encoding="utf-8"),
            )
            self.assertNotIn("stdout", options)
            self.assertNotIn("stderr", options)

        probe_ref = f"{HELPER.IMAGE_REPOSITORY}:{probe_tag}"
        inspect = [
            "docker",
            "buildx",
            "imagetools",
            "inspect",
            probe_ref,
            "--format",
            "{{json .Manifest}}",
        ]
        runner = ScriptedRunner([
            (list_command(), 0, before, ""),
            (build_check, 0, None, None),
            (
                exact_command(inspect),
                0,
                json.dumps({"digest": PROBE_DIGEST}),
                "",
            ),
            (list_command(), 0, after, ""),
            (get_command(200), 0, json.dumps(probe), ""),
            (delete_command(200), 0, "", ""),
            (list_command(), 0, before, ""),
        ])

        self.assertEqual(
            200,
            HELPER._probe(
                ACTIVE_DIGEST,
                "100",
                "2",
                runner=runner,
            ),
        )
        runner.assert_complete(self)

    def test_probe_stops_before_delete_when_creation_is_ambiguous(self):
        probe_tag = "preview-package-capability-probe-100-2"
        probe = version(200, 0, [probe_tag], PROBE_DIGEST)
        extra = version(201, 0, ["other"], "sha256:" + ("c" * 64))
        before = page_stream([self.active])
        after = page_stream([self.active, probe, extra])

        def build_check(argv, options):
            self.assertEqual(("docker", "buildx", "build"), argv[:3])

        probe_ref = f"{HELPER.IMAGE_REPOSITORY}:{probe_tag}"
        inspect = [
            "docker",
            "buildx",
            "imagetools",
            "inspect",
            probe_ref,
            "--format",
            "{{json .Manifest}}",
        ]
        runner = ScriptedRunner([
            (list_command(), 0, before, ""),
            (build_check, 0, None, None),
            (
                exact_command(inspect),
                0,
                json.dumps({"digest": PROBE_DIGEST}),
                "",
            ),
            (list_command(), 0, after, ""),
        ])

        with self.assertRaisesRegex(
            VERSIONS.SelectionError,
            "probe creation identity is invalid",
        ):
            HELPER._probe(
                ACTIVE_DIGEST,
                "100",
                "2",
                runner=runner,
            )
        runner.assert_complete(self)
        self.assertFalse(
            any("--method" in argv for argv, _ in runner.calls)
        )

    def test_cli_is_narrow_and_does_not_echo_command_output(self):
        for command in ("retain", "probe"):
            result = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    str(HELPER_PATH),
                    command,
                    "--help",
                ],
                check=False,
                capture_output=True,
                text=True,
            )
            with self.subTest(command=command):
                self.assertEqual(0, result.returncode)
                self.assertIn("--expected-digest", result.stdout)
                for old_flag in (
                    "--snapshot",
                    "--plan",
                    "--candidate-id",
                    "--probe-tag",
                ):
                    self.assertNotIn(old_flag, result.stdout)

        runner = ScriptedRunner([
            (
                list_command(),
                1,
                "SENSITIVE RESPONSE",
                "SENSITIVE FAILURE",
            ),
        ])
        stdout = io.StringIO()
        stderr = io.StringIO()
        args = HELPER._parser().parse_args(
            ["retain", "--expected-digest", ACTIVE_DIGEST]
        )
        with redirect_stdout(stdout), redirect_stderr(stderr):
            status = HELPER._execute(
                args,
                environment={},
                runner=runner,
                now_epoch=NOW,
            )
        self.assertEqual(2, status)
        self.assertEqual("", stdout.getvalue())
        self.assertIn("gh api failed", stderr.getvalue())
        self.assertNotIn("SENSITIVE", stderr.getvalue())
        runner.assert_complete(self)


if __name__ == "__main__":
    unittest.main()
