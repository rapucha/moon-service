from pathlib import Path
import os
import re
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]
DEPLOYMENT = ROOT / "deployment" / "raspberry-pi"
PLAYBOOK = DEPLOYMENT / "preview-runtime.yml"
PRODUCTION_PLAYBOOK = DEPLOYMENT / "site.yml"
INVENTORY = DEPLOYMENT / "inventory.example.yml"
ROLE = DEPLOYMENT / "roles" / "moon_service_preview_runtime"
DEFAULTS = ROLE / "defaults" / "main.yml"
TASKS = ROLE / "tasks" / "main.yml"
COMPOSE = ROLE / "templates" / "compose.yml.j2"
APPLICATION_ENV = ROLE / "templates" / "application.env.j2"
CONTROL = ROLE / "files" / "moon-service-preview-control"

POSTGRES_DIGEST = (
    "sha256:3a82e1f56c8f0f5616a11103ac3d47e632c3938698946a7ad26da0df1334744a"
)


def shell_function(script: str, name: str) -> str:
    match = re.search(
        rf"(?ms)^{re.escape(name)}\(\) \{{\n(.*?)^\}}\n",
        script,
    )
    if match is None:
        raise AssertionError(f"missing shell function: {name}")
    return match.group(1)


class PreviewRuntimeContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playbook = PLAYBOOK.read_text(encoding="utf-8")
        cls.production_playbook = PRODUCTION_PLAYBOOK.read_text(encoding="utf-8")
        cls.inventory = INVENTORY.read_text(encoding="utf-8")
        cls.defaults = DEFAULTS.read_text(encoding="utf-8")
        cls.tasks = TASKS.read_text(encoding="utf-8")
        cls.compose = COMPOSE.read_text(encoding="utf-8")
        cls.application_env = APPLICATION_ENV.read_text(encoding="utf-8")
        cls.control = CONTROL.read_text(encoding="utf-8")
        cls.app_service = cls.compose[
            cls.compose.index("  app:\n") : cls.compose.index("  postgres:\n")
        ]
        cls.postgres_service = cls.compose[
            cls.compose.index("  postgres:\n") : cls.compose.index("\nnetworks:\n")
        ]

    def function(self, name: str) -> str:
        return shell_function(self.control, name)

    def assert_ordered(self, text: str, *values: str) -> None:
        positions = []
        for value in values:
            self.assertIn(value, text)
            positions.append(text.index(value))
        self.assertEqual(sorted(positions), positions)

    def test_runtime_provisioning_is_separate_disabled_and_asset_only(self):
        self.assertIn("moon_service_preview_runtime", self.playbook)
        self.assertIn("moon_service_preview_runtime_enabled | bool", self.playbook)
        self.assertRegex(
            self.defaults,
            r"(?m)^moon_service_preview_runtime_enabled:\s*false\s*$",
        )
        self.assertNotIn("moon_service_preview_runtime", self.production_playbook)
        for forbidden in (
            "systemd_service:",
            "timer",
            "docker compose up",
            "moon-service-preview-control start",
        ):
            self.assertNotIn(forbidden, self.tasks)

    def test_role_installs_fixed_root_only_source_free_assets(self):
        expected_assets = {
            "/etc/moon-service-preview/application.env": '"0600"',
            "/opt/moon-service-preview/compose.yml": '"0600"',
            "/usr/local/sbin/moon-service-preview-control": '"0700"',
        }
        for path, mode in expected_assets.items():
            self.assertIn(f"dest: {path}", self.tasks)
            path_at = self.tasks.index(f"dest: {path}")
            asset = self.tasks[path_at : path_at + 180]
            self.assertIn("owner: root", asset)
            self.assertIn("group: root", asset)
            self.assertIn(f"mode: {mode}", asset)
        self.assertGreaterEqual(self.tasks.count("no_log: true"), 3)
        self.assertGreaterEqual(self.tasks.count("diff: false"), 3)
        combined = self.tasks + self.compose + self.application_env
        self.assertNotRegex(combined, r"(?:10|192\.168|172\.(?:1[6-9]|2[0-9]|3[01]))\.")
        for secret_name in ("admin-token", "database-password"):
            self.assertNotIn(secret_name, self.application_env)

    def test_inventory_example_is_disabled_and_uses_no_runtime_identity(self):
        self.assertIn("moon_service_preview_runtime_enabled: false", self.inventory)
        self.assertNotRegex(
            self.inventory,
            r"(?m)^\s*moon_service_preview_(?:image_digest|image_revision|pull_request):",
        )
        self.assertIn("192.0.2.38", self.inventory)
        self.assertNotRegex(
            self.inventory,
            r"(?:10|192\.168|172\.(?:1[6-9]|2[0-9]|3[01]))\.",
        )

    def test_compose_uses_only_fixed_preview_names_and_candidate_digest(self):
        for value in (
            "name: moon-service-preview",
            "container_name: moon-service-preview-app",
            "container_name: moon-service-preview-postgres",
            'ghcr.io/rapucha/moon-service@${MOON_PREVIEW_IMAGE_DIGEST}',
        ):
            self.assertIn(value, self.compose)
        self.assertNotIn("moon-service-control", self.compose)
        self.assertNotIn("moon-service-deploy", self.compose)
        self.assertNotIn("/var/lib/moon-service/", self.compose)
        self.assertNotIn("/etc/moon-service/", self.compose)
        self.assertNotIn("/opt/moon-service/", self.compose)

    def test_application_listener_and_database_port_are_isolated(self):
        self.assertIn(
            '"{{ moon_service_bind_address }}:8081:8080"',
            self.app_service,
        )
        self.assertNotIn("0.0.0.0:", self.app_service)
        self.assertNotIn("127.0.0.1:", self.app_service)
        self.assertNotIn("ports:", self.postgres_service)
        self.assertNotIn("expose:", self.postgres_service)

    def test_application_has_outbound_and_private_database_networks(self):
        self.assertRegex(
            self.app_service,
            r"(?ms)networks:\s*\n\s*- outbound\s*\n\s*- database",
        )
        self.assertRegex(
            self.postgres_service,
            r"(?ms)networks:\s*\n\s*- database",
        )
        self.assertNotIn("- outbound", self.postgres_service)
        self.assertRegex(
            self.compose,
            r"(?ms)^networks:\n\s+outbound: \{\}\n\s+database:\n\s+internal: true",
        )

    def test_only_postgres_receives_the_fixed_nfs_subtree(self):
        self.assertNotIn("volumes:", self.app_service)
        self.assertNotIn("/mnt/moon-service-preview", self.app_service)
        self.assertNotIn("/var/run/docker.sock", self.app_service)
        for value in (
            "source: /mnt/moon-service-preview/postgres",
            "target: /var/lib/postgresql",
            "create_host_path: false",
        ):
            self.assertIn(value, self.postgres_service)

    def test_container_users_resources_and_security_are_exact(self):
        contracts = (
            (self.app_service, '"10001:10001"', "768m", '"0.5"'),
            (self.postgres_service, '"999:999"', "384m", '"0.25"'),
        )
        for service, user, memory, cpus in contracts:
            with self.subTest(user=user):
                for value in (
                    f"user: {user}",
                    'restart: "no"',
                    "read_only: true",
                    "cap_drop:\n      - ALL",
                    "no-new-privileges:true",
                    "pids_limit: 128",
                    f"mem_limit: {memory}",
                    f"cpus: {cpus}",
                    "driver: local",
                    'max-size: "10m"',
                    'max-file: "2"',
                    "size=",
                ):
                    self.assertIn(value, service)

    def test_postgres_image_data_layout_and_password_are_pinned(self):
        self.assertIn(f"postgres:18.4-trixie@{POSTGRES_DIGEST}", self.postgres_service)
        self.assertIn("POSTGRES_DB: moon_service_preview", self.postgres_service)
        self.assertIn("POSTGRES_USER: moon_service_preview", self.postgres_service)
        self.assertIn(
            "POSTGRES_PASSWORD_FILE: /run/secrets/postgres.password",
            self.postgres_service,
        )
        self.assertIn("target: /var/lib/postgresql", self.postgres_service)
        self.assertNotIn("PGDATA:", self.postgres_service)

    def test_credentials_are_runtime_secrets_not_rendered_values(self):
        for value in (
            "environment: MOON_PREVIEW_ADMIN_TOKEN",
            "environment: MOON_PREVIEW_DATABASE_PASSWORD",
            "target: moon.admin.token",
            "target: moon.feedback.persistence.password",
            "target: postgres.password",
        ):
            self.assertIn(value, self.compose)
        self.assertNotIn("${MOON_PREVIEW_ADMIN_TOKEN}", self.compose)
        self.assertNotIn("${MOON_PREVIEW_DATABASE_PASSWORD}", self.compose)
        self.assertNotIn("POSTGRES_PASSWORD:", self.compose)
        secret_writer = self.function("write_secret")
        self.assertIn('mktemp "${target%/*}/.secret.', secret_writer)
        self.assertNotIn("CONFIG_DIR", secret_writer)

    def test_application_environment_matches_preview_contract(self):
        expected = {
            "MOON_LOCATION_RESOLVER": "open-meteo",
            "MOON_WEATHER_PROVIDER": "open-meteo",
            "MOON_HOSTED_ALPHA_ENABLED": "true",
            "MOON_FEEDBACK_ENABLED": "true",
            "MOON_FEEDBACK_PERSISTENCE_ENABLED": "true",
            "MOON_FEEDBACK_PERSISTENCE_JDBC_URL": (
                "jdbc:postgresql://postgres:5432/moon_service_preview"
            ),
            "MOON_FEEDBACK_PERSISTENCE_USERNAME": "moon_service_preview",
            "MOON_FEEDBACK_PERSISTENCE_CAPACITY": "100",
            "MOON_RESOURCE_LIMITS_WHOLE_SITE_CAPACITY": "20",
            "MOON_RESOURCE_LIMITS_WHOLE_SITE_REFILL_INTERVAL": "PT2S",
            "MOON_RESOURCE_LIMITS_PROVIDER_LOOKUP_CAPACITY": "5",
            "MOON_RESOURCE_LIMITS_PROVIDER_LOOKUP_REFILL_INTERVAL": "PT5M",
            "MOON_RESOURCE_LIMITS_OPPORTUNITY_CONCURRENCY": "1",
            "SPRING_CONFIG_IMPORT": "configtree:/run/secrets/",
            "SERVER_PORT": "8080",
            "MOON_HEALTHCHECK_HOST": "127.0.0.1",
        }
        actual = dict(
            line.split("=", 1)
            for line in self.application_env.splitlines()
            if line and not line.startswith("#")
        )
        self.assertEqual(expected, actual)

    def test_helper_accepts_only_explicit_valid_preview_identity(self):
        start = self.function("start_action")
        for value in (
            r"^[1-9][0-9]*$",
            r"^[0-9a-f]{40}$",
            r"^sha256:[0-9a-f]{64}$",
        ):
            self.assertIn(value, start)
        self.assertIn("start <pull-request> <revision> <digest>", self.control)
        self.assertIn("[[ $# -eq 4 ]]", self.control)
        for forbidden in ("workflow_dispatch", "gh api", "gh pr", "GITHUB_TOKEN"):
            self.assertNotIn(forbidden, self.control)

    def test_candidate_is_pulled_and_verified_without_custom_protocol(self):
        image = self.function("image_is_valid")
        for value in (
            'reference="ghcr.io/rapucha/moon-service@$digest"',
            '/usr/bin/docker pull "$reference"',
            '/usr/bin/docker image inspect "$reference"',
            '.Architecture == "arm64"',
            '.Os == "linux"',
            'org.opencontainers.image.source',
            'org.opencontainers.image.revision',
            'MOON_BUILD_REVISION=',
        ):
            self.assertIn(value, image)
        for forbidden in (
            "buildx",
            "imagetools",
            "manifest inspect",
            "oras",
            "skopeo",
            "urllib",
            "type=oci",
            "expected-identity.json",
        ):
            self.assertNotIn(forbidden, self.control)

    def test_nfs_preflight_is_exact_and_has_no_local_fallback(self):
        preflight = self.function("nfs_preflight")
        for value in (
            "/etc/fstab",
            '/usr/bin/stat --format=%d "$MOUNT_POINT/."',
            '--mountpoint "$MOUNT_POINT"',
            "--types nfs,nfs4",
            "SOURCE,TARGET,FSTYPE,OPTIONS",
            'option_present "$options" vers=4.1',
            'option_present "$options" proto=tcp',
            'option_present "$options" rw',
            'option_present "$options" hard',
            '! option_present "$options" ro',
            '! option_present "$options" soft',
            '/usr/bin/test -d "$DATA_DIR" -a ! -L "$DATA_DIR"',
            '/usr/bin/realpath -e -- "$DATA_DIR"',
            "/usr/bin/stat -c '%u:%g' \"$DATA_DIR\"",
            "--reuid=999 --regid=999",
            "--clear-groups",
            'mktemp "$DATA_DIR/.moon-preview-probe.',
            '/usr/bin/rm -- "$probe"',
        ):
            self.assertIn(value, preflight)
        self.assertNotIn("mkdir", preflight)
        self.assertNotIn("mount ", preflight)
        self.assert_ordered(
            preflight,
            '/usr/bin/stat --format=%d "$MOUNT_POINT/."',
            '/usr/bin/findmnt --json --all --mountpoint "$MOUNT_POINT"',
        )

    def test_candidate_validation_precedes_stopping_current_preview(self):
        start = self.function("start_action")
        self.assert_ordered(
            start,
            'existing_pull="$(state_value',
            '"another pull request owns the slot',
            'image_is_valid "$revision" "$digest"',
            'compose "$existing_state" down --remove-orphans',
        )

    def test_cross_pull_request_replacement_requires_separate_purge(self):
        start = self.function("start_action")
        self.assertIn(
            '"another pull request owns the slot; stop and purge it first"',
            start,
        )
        self.assertIn(
            '"unowned preview state requires local inspection and confirmed purge"',
            start,
        )
        self.assertIn("|| containers_exist", start)
        self.assertNotIn("purge_action", start)
        for forbidden in ("rollback", "previous.env", "replacement-phase"):
            self.assertNotIn(forbidden, self.control.lower())

    def test_initial_database_failures_start_degraded_and_fail_closed(self):
        start = self.function("start_action")
        self.assertIn("if ! nfs_preflight; then", start)
        self.assertIn('start_degraded_app "$DEGRADED_STATE" "$revision"', start)
        self.assertGreaterEqual(start.count("return 1"), 2)
        self.assertIn('compose "$DEGRADED_STATE" stop postgres', start)
        self.assertIn(
            '[[ "$had_current" == true ]] || data_has_entries || /usr/bin/rm -f "$PURGE_REQUIRED"',
            start,
        )
        self.assertIn("database remains running after cleanup", start)
        database_failure = start[start.index('if ! compose "$DEGRADED_STATE" up') :]
        self.assert_ordered(
            database_failure,
            '[[ "$had_current" == true ]] || mark_purge_required',
            'compose "$DEGRADED_STATE" stop postgres',
            "database remains running after cleanup",
            'data_has_entries || /usr/bin/rm -f "$PURGE_REQUIRED"',
            'start_degraded_app "$DEGRADED_STATE" "$revision"',
        )
        degraded = self.function("start_degraded_app")
        self.assertIn("up --detach --no-deps --pull never app", degraded)
        self.assertIn(
            'app_ready "$revision" && feedback_has_availability unavailable',
            degraded,
        )
        self.assertNotIn("postgres", degraded)

    def test_failure_cleanup_is_required_and_verified(self):
        cleanup = self.function("remove_preview")
        self.assertIn("down --remove-orphans", cleanup)
        self.assertIn("|| die 69", cleanup)
        self.assertIn("! containers_exist || die 69", cleanup)
        start = self.function("start_action")
        self.assertEqual(3, start.count('remove_preview "$DEGRADED_STATE"'))
        self.assertNotIn("down --remove-orphans >/dev/null 2>&1 || true", start)

    def test_successful_identity_is_written_only_after_application_ready(self):
        start = self.function("start_action")
        self.assert_ordered(
            start,
            'compose "$DEGRADED_STATE" up --detach --no-deps postgres',
            'compose "$DEGRADED_STATE" up --detach --no-deps --pull never app',
            'app_ready "$revision"',
            "feedback_has_availability available",
            'write_state "$CURRENT_STATE"',
        )
        capability = self.function("feedback_has_availability")
        self.assertIn('local expected="$1"', capability)
        self.assertIn(".submissionAvailability == $expected", capability)
        state = self.function("write_state")
        self.assertIn("MOON_PREVIEW_IMAGE_DIGEST", state)
        self.assertIn("MOON_PREVIEW_IMAGE_REVISION", state)
        self.assertIn("MOON_PREVIEW_PULL_REQUEST", state)
        for forbidden in ("PASSWORD", "TOKEN", "SOURCE", "MOUNT"):
            self.assertNotIn(forbidden, state)

    def test_status_is_source_free_and_reports_only_nonsecret_identity(self):
        status = self.function("status_action")
        for value in (
            "status=purge-required",
            "status=stopped",
            "status=running",
            "status=degraded",
            "status=invalid",
            "pull_request=%s",
            "revision=%s",
            "digest=%s",
        ):
            self.assertIn(value, status)
        for forbidden in (
            "ADMIN_TOKEN",
            "DATABASE_PASSWORD",
            "expected_source",
            "MOUNT_POINT",
            "DATA_DIR",
        ):
            self.assertNotIn(forbidden, status)
        degraded = status[status.index('elif valid_state "$DEGRADED_STATE"') :]
        self.assert_ordered(
            degraded,
            'elif valid_state "$DEGRADED_STATE"',
            "status=stopped",
            'if wait_healthy "$APP_CONTAINER" 0',
            "status=degraded",
        )

    def test_stop_retains_data_secrets_and_identity(self):
        stop = self.function("stop_action")
        self.assertIn("down --remove-orphans", stop)
        for forbidden in (
            "/usr/bin/rm",
            "DATA_DIR",
            "ADMIN_TOKEN_FILE",
            "DATABASE_PASSWORD_FILE",
            "CURRENT_STATE",
            "DEGRADED_STATE",
        ):
            self.assertNotIn(forbidden, stop)

    def test_purge_is_confirmed_stopped_locked_and_preserves_subtree(self):
        purge = self.function("purge_action")
        self.assert_ordered(
            purge,
            "containers_exist && die",
            "nfs_preflight || die",
            "/usr/bin/find",
            'data_has_entries && die',
            '/usr/bin/rm -f "$ADMIN_TOKEN_FILE"',
        )
        for value in (
            "--reuid=999 --regid=999 --clear-groups",
            '"$DATA_DIR" -mindepth 1 -maxdepth 1',
            "-exec /usr/bin/rm -rf -- '{}' +",
            '"$CURRENT_STATE" "$DEGRADED_STATE" "$PURGE_REQUIRED"',
            '/usr/bin/flock -w 30',
            '[[ $# -eq 2 && "$2" == --confirm ]]',
        ):
            self.assertIn(value, purge + self.control)
        self.assertNotIn("rmdir", purge)
        self.assertNotIn('rm -rf -- "$DATA_DIR"', purge)

    def test_manual_actions_have_explicit_bounded_waits(self):
        for value in (
            "timeout --signal=TERM --kill-after=5s 180s",
            "wait_healthy \"$APP_CONTAINER\" 180",
            "wait_healthy moon-service-preview-postgres 90",
            "/usr/bin/timeout 20s /usr/bin/docker info",
            "/usr/bin/timeout 20s /usr/bin/docker inspect",
            "/usr/bin/timeout 10s",
            "/usr/bin/timeout 60s",
            "--max-time 3",
            "/usr/bin/flock -w 30",
        ):
            self.assertIn(value, self.control)

    def test_no_automation_registry_protocol_or_production_assets_are_added(self):
        combined = self.playbook + self.tasks + self.compose + self.control
        for forbidden in (
            "systemctl enable",
            ".timer",
            "OnBootSec",
            "docker run",
            "docker tag",
            "docker push",
            "/usr/local/sbin/moon-service-control",
            "/usr/local/sbin/moon-service-deploy",
            "/etc/moon-service/application.env",
            "/opt/moon-service/compose.yml",
        ):
            self.assertNotIn(forbidden, combined)

    def test_rendered_compose_is_accepted_when_docker_compose_is_available(self):
        docker = shutil.which("docker")
        if docker is None:
            self.skipTest("Docker Compose is not installed")
        version = subprocess.run(
            [docker, "compose", "version"],
            capture_output=True,
            check=False,
            text=True,
        )
        if version.returncode != 0:
            self.skipTest("Docker Compose plugin is not installed")
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            application_env = root / "application.env"
            application_env.write_text(self.application_env, encoding="utf-8")
            rendered = self.compose.replace(
                "{{ moon_service_bind_address }}",
                "192.0.2.10",
            ).replace(
                "/etc/moon-service-preview/application.env",
                str(application_env),
            )
            compose_file = root / "compose.yml"
            compose_file.write_text(rendered, encoding="utf-8")
            environment = os.environ.copy()
            environment.update(
                {
                    "MOON_PREVIEW_ADMIN_TOKEN": "a" * 64,
                    "MOON_PREVIEW_DATABASE_PASSWORD": "b" * 64,
                    "MOON_PREVIEW_IMAGE_DIGEST": "sha256:" + "c" * 64,
                    "MOON_PREVIEW_IMAGE_REVISION": "d" * 40,
                    "MOON_PREVIEW_PULL_REQUEST": "200",
                }
            )
            result = subprocess.run(
                [docker, "compose", "--file", str(compose_file), "config", "--quiet"],
                capture_output=True,
                check=False,
                text=True,
                env=environment,
            )
            self.assertEqual(0, result.returncode, result.stderr)


if __name__ == "__main__":
    unittest.main()
