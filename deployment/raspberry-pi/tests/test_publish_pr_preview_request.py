import copy
import importlib.util
from pathlib import Path
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
SCRIPT = ROOT / "scripts" / "publish_pr_preview.py"
SPEC = importlib.util.spec_from_file_location("publish_pr_preview_request", SCRIPT)
assert SPEC and SPEC.loader
REQUEST = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = REQUEST
SPEC.loader.exec_module(REQUEST)

REVISION = "1" * 40
OTHER_REVISION = "2" * 40
PR_NUMBER = 199
REPOSITORY_ID = 77
HEAD_REF = "issue-199-preview-image-publication"
WORKFLOW_ID = 41


def pull_payload(revision=REVISION):
    repository = {"id": REPOSITORY_ID, "full_name": REQUEST.REPOSITORY}
    return {
        "number": PR_NUMBER,
        "state": "open",
        "base": {"ref": REQUEST.BASE_BRANCH, "repo": copy.deepcopy(repository)},
        "head": {
            "ref": HEAD_REF,
            "sha": revision,
            "repo": copy.deepcopy(repository),
        },
    }


def run_payload(
    run_id=501,
    run_number=17,
    run_attempt=2,
    status="completed",
    conclusion="success",
):
    association = {
        "number": PR_NUMBER,
        "head": {
            "ref": HEAD_REF,
            "sha": REVISION,
            "repo": {"id": REPOSITORY_ID},
        },
        "base": {
            "ref": REQUEST.BASE_BRANCH,
            "repo": {"id": REPOSITORY_ID},
        },
    }
    return {
        "id": run_id,
        "run_number": run_number,
        "run_attempt": run_attempt,
        "workflow_id": WORKFLOW_ID,
        "event": "pull_request",
        "head_branch": HEAD_REF,
        "head_sha": REVISION,
        "status": status,
        "conclusion": conclusion,
        "pull_requests": [association],
    }


def job_payload(name, job_id, run_id=501):
    return {
        "id": job_id,
        "run_id": run_id,
        "name": name,
        "head_sha": REVISION,
        "head_branch": HEAD_REF,
        "workflow_name": REQUEST.TEST_WORKFLOW_NAME,
        "status": "completed",
        "conclusion": "success",
    }


def valid_jobs(run_id=501):
    return [
        job_payload(name, 700 + number, run_id)
        for number, name in enumerate(REQUEST.REQUIRED_JOBS)
    ]


def changed(value, path, replacement):
    result = copy.deepcopy(value)
    target = result
    for key in path[:-1]:
        target = target[key]
    target[path[-1]] = replacement
    return result


class FakeGitHubApi:
    def __init__(self, pulls=None, runs=None, jobs=None):
        self.pulls = pulls or [pull_payload()]
        self.runs = runs or [[run_payload()]]
        self.jobs = jobs or valid_jobs()
        self.pull_index = 0
        self.run_index = 0
        self.calls = []

    @staticmethod
    def next_value(values, index):
        return copy.deepcopy(values[min(index, len(values) - 1)])

    def get(self, path, query=None):
        self.calls.append(("get", path, query))
        if path.endswith(f"/pulls/{PR_NUMBER}"):
            value = self.next_value(self.pulls, self.pull_index)
            self.pull_index += 1
            return value
        if path.endswith(f"/actions/workflows/{REQUEST.TEST_WORKFLOW_FILE}"):
            return {
                "id": WORKFLOW_ID,
                "name": REQUEST.TEST_WORKFLOW_NAME,
                "path": REQUEST.TEST_WORKFLOW_PATH,
                "state": "active",
            }
        raise AssertionError(f"unexpected API GET: {path}")

    def collection(self, path, key, query=None):
        self.calls.append(("collection", path, query))
        if key == "workflow_runs":
            value = self.next_value(self.runs, self.run_index)
            self.run_index += 1
            return value
        if key == "jobs":
            return copy.deepcopy(self.jobs)
        raise AssertionError(f"unexpected API collection: {path}")


class PreviewRequestValidationTest(unittest.TestCase):
    def test_github_api_rejects_redirects_before_reusing_authorization(self):
        with mock.patch.object(REQUEST.urllib.request, "build_opener") as build:
            REQUEST.GitHubApi("test-token")
        redirect_handler = build.call_args.args[0]
        self.assertIsInstance(redirect_handler, REQUEST.RejectRedirects)

        request = REQUEST.urllib.request.Request(
            f"{REQUEST.API_ROOT}/repos/{REQUEST.REPOSITORY}",
            headers={"Authorization": "Bearer test-token"},
        )
        with self.assertRaises(REQUEST.urllib.error.HTTPError) as caught:
            redirect_handler.redirect_request(
                request,
                None,
                302,
                "Found",
                {"Location": "http://example.invalid/steal"},
                "http://example.invalid/steal",
            )
        self.assertEqual(request.full_url, caught.exception.url)

    def test_dispatch_requires_exact_owner_main_and_workflow_ref(self):
        environment = {
            "GITHUB_API_URL": REQUEST.API_ROOT,
            "GITHUB_EVENT_NAME": "workflow_dispatch",
            "GITHUB_REPOSITORY": REQUEST.REPOSITORY,
            "GITHUB_REF": "refs/heads/main",
            "GITHUB_WORKFLOW_REF": REQUEST.PREVIEW_WORKFLOW_REF,
            "GITHUB_ACTOR": REQUEST.OWNER,
            "GITHUB_TRIGGERING_ACTOR": REQUEST.OWNER,
            "GITHUB_TOKEN": "test-token",
            "GITHUB_OUTPUT": "/tmp/test-output",
        }
        REQUEST.validate_dispatch_context(environment)
        for name in environment:
            invalid = dict(environment)
            invalid[name] = ""
            with self.subTest(name), self.assertRaises(REQUEST.ValidationError):
                REQUEST.validate_dispatch_context(invalid)

    def test_valid_candidate_selects_exact_latest_attempt(self):
        older = run_payload(run_id=500, run_number=16, run_attempt=1)
        api = FakeGitHubApi(runs=[[older, run_payload()], [older, run_payload()]])
        result = REQUEST.validate_candidate(api, PR_NUMBER)

        self.assertEqual(501, result.test_run.run_id)
        self.assertEqual(2, result.test_run.run_attempt)
        run_calls = [
            call
            for call in api.calls
            if call[0] == "collection" and call[1].endswith("/runs")
        ]
        self.assertEqual(2, len(run_calls))
        self.assertEqual(
            {
                "event": "pull_request",
                "branch": HEAD_REF,
                "head_sha": REVISION,
                "exclude_pull_requests": "false",
            },
            run_calls[0][2],
        )
        self.assertTrue(
            any("/runs/501/attempts/2/jobs" in call[1] for call in api.calls)
        )
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "output"
            output.touch()
            REQUEST._write_outputs(str(output), result)
            values = dict(
                line.split("=", 1)
                for line in output.read_text(encoding="utf-8").splitlines()
            )
        self.assertEqual(str(PR_NUMBER), values["pull_request_number"])
        self.assertEqual(REVISION, values["head_sha"])
        self.assertEqual(f"preview-pr-{PR_NUMBER}-{REVISION}", values["immutable_tag"])

    def test_rejects_closed_wrong_base_fork_and_invalid_head(self):
        cases = (
            ("closed", ("state",), "closed"),
            ("wrong base", ("base", "ref"), "develop"),
            ("fork", ("head", "repo", "full_name"), "someone/moon-service"),
            ("repository ID", ("head", "repo", "id"), REPOSITORY_ID + 1),
            ("head SHA", ("head", "sha"), "abc"),
        )
        for label, path, replacement in cases:
            payload = changed(pull_payload(), path, replacement)
            with self.subTest(label), self.assertRaises(REQUEST.ValidationError):
                REQUEST._parse_pull(payload, PR_NUMBER)
        with self.assertRaisesRegex(REQUEST.ValidationError, "head changed"):
            REQUEST.validate_candidate(FakeGitHubApi(), PR_NUMBER, OTHER_REVISION)

    def test_newest_matching_run_must_be_completed_success(self):
        pull = REQUEST._parse_pull(pull_payload(), PR_NUMBER)
        old_success = run_payload(run_id=500, run_number=16, run_attempt=1)
        cases = (
            ([], "no matching"),
            (
                [old_success, run_payload(status="in_progress", conclusion=None)],
                "not successful",
            ),
        )
        for runs, message in cases:
            with self.subTest(message), self.assertRaisesRegex(
                REQUEST.ValidationError, message
            ):
                REQUEST._latest_test_run(
                    FakeGitHubApi(runs=[runs]), WORKFLOW_ID, pull
                )

    def test_required_jobs_are_unique_exact_and_successful(self):
        pull = REQUEST._parse_pull(pull_payload(), PR_NUMBER)
        run = REQUEST.TestRun(501, 17, 2)
        cases = [
            ("missing", valid_jobs()[1:]),
            (
                "duplicated",
                valid_jobs() + [job_payload(REQUEST.REQUIRED_JOBS[0], 999)],
            ),
        ]
        mutations = (
            ("pending", "status", "in_progress"),
            ("failed", "conclusion", "failure"),
            ("wrong head", "head_sha", OTHER_REVISION),
            ("wrong workflow", "workflow_name", "Other workflow"),
        )
        for label, field, replacement in mutations:
            cases.append((label, changed(valid_jobs(), (0, field), replacement)))
        for label, jobs in cases:
            with self.subTest(label), self.assertRaises(REQUEST.ValidationError):
                REQUEST._validate_jobs(FakeGitHubApi(jobs=jobs), pull, run)

    def test_rejects_pull_or_latest_run_changed_during_validation(self):
        changed_pull = pull_payload()
        changed_pull["head"]["sha"] = OTHER_REVISION
        with self.assertRaisesRegex(REQUEST.ValidationError, "pull request changed"):
            REQUEST.validate_candidate(
                FakeGitHubApi(pulls=[pull_payload(), changed_pull]), PR_NUMBER
            )

        newer = run_payload(run_id=502, run_number=18, run_attempt=1)
        with self.assertRaisesRegex(REQUEST.ValidationError, "run changed"):
            REQUEST.validate_candidate(
                FakeGitHubApi(runs=[[run_payload()], [run_payload(), newer]]),
                PR_NUMBER,
            )


if __name__ == "__main__":
    unittest.main()
