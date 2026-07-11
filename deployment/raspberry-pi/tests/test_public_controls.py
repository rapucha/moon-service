import importlib.machinery
import importlib.util
import fcntl
import json
import os
from pathlib import Path
import smtplib
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[3]
ROLE_ROOT = ROOT / "deployment" / "raspberry-pi" / "roles" / "moon_service_host"
PUBLIC_SCRIPT = ROLE_ROOT / "files" / "moon-service-public"
NOTIFY_SCRIPT = ROLE_ROOT / "files" / "moon-service-public-notify"
FIREWALL_SCRIPT = ROLE_ROOT / "files" / "moon-service-docker-firewall"
CONTROL_SCRIPT = ROLE_ROOT / "files" / "moon-service-control"


FAKE_SYSTEMCTL = r"""#!/usr/bin/env bash
set -euo pipefail
printf 'systemctl %s\n' "$*" >>"$FAKE_ROOT/commands.log"
operation="$1"
shift
case "$operation" in
  is-active)
    service="$1"
    if [[ "$service" == moon-service-funnel.service \
        && "${FAKE_FUNNEL_QUERY_FAIL:-0}" == 1 ]]; then
      exit 1
    fi
    if [[ "$service" == moon-service-funnel.service \
        && -n "${FAKE_FUNNEL_SERVICE_STATE:-}" ]]; then
      printf '%s\n' "$FAKE_FUNNEL_SERVICE_STATE"
      exit 0
    fi
    if [[ -e "$FAKE_ROOT/active-$service" ]]; then
      printf 'active\n'
      exit 0
    fi
    printf 'inactive\n'
    exit 3
    ;;
  is-enabled)
    service="$1"
    if [[ "$service" == moon-service-funnel.service \
        && "${FAKE_FUNNEL_QUERY_FAIL:-0}" == 1 ]]; then
      exit 1
    fi
    if [[ "$service" == moon-service-funnel.service \
        && -n "${FAKE_FUNNEL_ENABLED_STATE:-}" ]]; then
      printf '%s\n' "$FAKE_FUNNEL_ENABLED_STATE"
      exit 0
    fi
    if [[ -e "$FAKE_ROOT/enabled-$service" ]]; then
      printf 'enabled\n'
      exit 0
    fi
    printf 'disabled\n'
    exit 1
    ;;
  start)
    for service in "$@"; do
      touch "$FAKE_ROOT/active-$service"
    done
    ;;
  stop)
    for service in "$@"; do
      rm -f "$FAKE_ROOT/active-$service"
      if [[ "$service" == tailscaled.service ]]; then
        rm -f "$FAKE_ROOT/funnel-route"
      fi
    done
    ;;
  enable)
    [[ "${1:-}" == --now ]] && shift
    for service in "$@"; do
      if [[ "${FAKE_TIMER_ENABLE_FAIL:-0}" == 1 \
          && "$service" == moon-service-public-watchdog.timer ]]; then
        exit 1
      fi
      touch "$FAKE_ROOT/enabled-$service" "$FAKE_ROOT/active-$service"
      if [[ "$service" == moon-service-funnel.service ]]; then
        touch "$FAKE_ROOT/funnel-route"
      fi
    done
    ;;
  disable)
    [[ "${1:-}" == --now ]] && shift
    for service in "$@"; do
      if [[ "$service" == moon-service-funnel.service ]]; then
        if [[ -e "$FAKE_STATE_DIR/public-ingress-enabled" ]]; then
          printf 'marker=present-at-disable\n' >>"$FAKE_ROOT/commands.log"
        else
          printf 'marker=absent-at-disable\n' >>"$FAKE_ROOT/commands.log"
        fi
        if [[ -e "$MOON_RUNTIME_AUTH_FILE" ]]; then
          printf 'runtime-auth=present-at-disable\n' >>"$FAKE_ROOT/commands.log"
        else
          printf 'runtime-auth=absent-at-disable\n' >>"$FAKE_ROOT/commands.log"
        fi
        [[ "${FAKE_STICKY_FUNNEL:-0}" == 1 ]] || rm -f "$FAKE_ROOT/funnel-route"
      fi
      if [[ "$service" == tailscaled.service ]]; then
        rm -f "$FAKE_ROOT/funnel-route"
      fi
      rm -f "$FAKE_ROOT/enabled-$service" "$FAKE_ROOT/active-$service"
    done
    ;;
  *)
    printf 'unexpected systemctl operation: %s\n' "$operation" >&2
    exit 2
    ;;
esac
"""

FAKE_TAILSCALE = r"""#!/usr/bin/env bash
set -euo pipefail
printf 'tailscale %s\n' "$*" >>"$FAKE_ROOT/commands.log"
if [[ "$1" == status && "$2" == --json ]]; then
  printf '{"BackendState":"Running","Self":{"Online":true,"DNSName":"moon.example.ts.net."}}\n'
  exit 0
fi
if [[ "$1" == funnel && "$2" == status && "$3" == --json ]]; then
  if [[ "${FAKE_FUNNEL_STATUS_FAIL:-0}" == 1 ]]; then
    exit 1
  fi
  if [[ "${FAKE_FUNNEL_UNKNOWN:-0}" == 1 ]]; then
    printf '{"routes":["unknown-future-schema"]}\n'
    exit 0
  fi
  if [[ -e "$FAKE_ROOT/funnel-route" ]]; then
    printf '{"Foreground":{"session":{"TCP":{"443":{"TCPForward":"127.0.0.1:18080","TerminateTLS":"moon.example.ts.net","ProxyProtocol":%s}},"AllowFunnel":{"moon.example.ts.net:443":true}}}}\n' "${FAKE_PROXY_VERSION:-2}"
  else
    printf '{}\n'
  fi
  exit 0
fi
if [[ "$1" == funnel && "${2:-}" == reset ]]; then
  [[ "${FAKE_RESET_FAIL:-0}" == 1 ]] || rm -f "$FAKE_ROOT/funnel-route"
  exit 0
fi
if [[ "$1" == funnel && "${*: -1}" == off ]]; then
  [[ "${FAKE_OFF_FAIL:-0}" == 1 ]] || rm -f "$FAKE_ROOT/funnel-route"
  exit 0
fi
printf 'unexpected tailscale command: %s\n' "$*" >&2
exit 2
"""

FAKE_CURL = r"""#!/usr/bin/env bash
set -euo pipefail
if [[ " $* " == *" --write-out "* ]]; then
  printf '404'
  exit 0
fi
printf '{"status":"ok","revision":"1111111111111111111111111111111111111111"}\n'
"""

FAKE_FIREWALL = r"""#!/usr/bin/env bash
set -euo pipefail
printf 'firewall verify\n' >>"$FAKE_ROOT/commands.log"
[[ "${FAKE_FIREWALL_FAIL:-0}" != 1 ]]
"""

FAKE_JOURNALCTL = r"""#!/usr/bin/env bash
set -euo pipefail
for ((index = 0; index < ${FAKE_REJECTIONS:-0}; index++)); do
  printf 'rate-limited\n'
done
"""

FAKE_NOTIFY = r"""#!/usr/bin/env bash
set -euo pipefail
if [[ "$1" == pending ]]; then
  printf '%s\n' "${FAKE_NOTIFY_PENDING:-0}"
  exit 0
fi
if [[ "$1" == test && "${FAKE_NOTIFY_TEST_FAIL:-0}" == 1 ]]; then
  printf 'notify test\n' >>"$FAKE_ROOT/commands.log"
  exit 75
fi
if [[ "$1" == test && "${FAKE_LOCK_STATE_AFTER_NOTIFY_TEST:-0}" == 1 ]]; then
  printf 'notify test\n' >>"$FAKE_ROOT/commands.log"
  chmod 0500 "$FAKE_STATE_DIR"
  exit 0
fi
printf 'notify %s\n' "$*" >>"$FAKE_ROOT/commands.log"
"""


def load_notifier_module():
    name = "moon_service_public_notify_test_module"
    loader = importlib.machinery.SourceFileLoader(name, str(NOTIFY_SCRIPT))
    spec = importlib.util.spec_from_loader(name, loader)
    module = importlib.util.module_from_spec(spec)
    sys.modules[name] = module
    loader.exec_module(module)
    return module


@unittest.skipIf(
    os.geteuid() == 0,
    "behavioral public-control tests require a non-root runner",
)
class PublicControlTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.config = self.root / "config"
        self.state = self.root / "state"
        self.runtime_auth = self.root / "runtime-authorized"
        self.bin = self.root / "bin"
        self.config.mkdir()
        self.state.mkdir()
        self.bin.mkdir()
        (self.state / "current.env").write_text(
            "MOON_IMAGE_REVISION=" + "1" * 40 + "\n", encoding="utf-8"
        )
        (self.config / "host.env").write_text(
            "\n".join(
                [
                    f"MOON_STATE_DIR={self.state}",
                    "MOON_READY_URL=http://127.0.0.1:8080/readyz",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.write_public_config(smtp_enabled=True)
        for name, body in {
            "systemctl": FAKE_SYSTEMCTL,
            "tailscale": FAKE_TAILSCALE,
            "curl": FAKE_CURL,
            "journalctl": FAKE_JOURNALCTL,
            "notify": FAKE_NOTIFY,
            "firewall": FAKE_FIREWALL,
            "nginx": "#!/usr/bin/env bash\nexit 0\n",
            "sleep": "#!/usr/bin/env bash\nexit 0\n",
        }.items():
            path = self.bin / name
            path.write_text(body, encoding="utf-8")
            path.chmod(0o755)
        for service in (
            "nginx.service",
            "tailscaled.service",
            "moon-service-docker-firewall.service",
        ):
            (self.root / f"active-{service}").touch()
        self.environment = os.environ | {
            "FAKE_ROOT": str(self.root),
            "FAKE_STATE_DIR": str(self.state),
            "MOON_CONFIG_DIR": str(self.config),
            "MOON_RUNTIME_AUTH_FILE": str(self.runtime_auth),
            "MOON_PUBLIC_ALLOW_NON_ROOT": "true",
            "MOON_SYSTEMCTL_BIN": str(self.bin / "systemctl"),
            "MOON_TAILSCALE_BIN": str(self.bin / "tailscale"),
            "MOON_CURL_BIN": str(self.bin / "curl"),
            "MOON_NGINX_BIN": str(self.bin / "nginx"),
            "MOON_JOURNALCTL_BIN": str(self.bin / "journalctl"),
            "MOON_PUBLIC_NOTIFY_BIN": str(self.bin / "notify"),
            "MOON_PUBLIC_FIREWALL_BIN": str(self.bin / "firewall"),
            "MOON_SLEEP_BIN": str(self.bin / "sleep"),
        }

    def tearDown(self):
        self.temporary.cleanup()

    def write_public_config(self, *, smtp_enabled: bool):
        (self.config / "public-ingress.env").write_text(
            "\n".join(
                [
                    "MOON_PUBLIC_PROXY_PORT=18080",
                    "MOON_PUBLIC_HTTPS_PORT=443",
                    "MOON_PUBLIC_REQUEST_RATE_PER_MINUTE=300",
                    "MOON_PUBLIC_API_RATE_PER_MINUTE=1",
                    "MOON_PUBLIC_CLIENT_API_RATE_PER_MINUTE=1",
                    "MOON_PUBLIC_CONNECTION_LIMIT=32",
                    "MOON_PUBLIC_CLIENT_CONNECTION_LIMIT=8",
                    "MOON_PUBLIC_BREAKER_WINDOW_SECONDS=60",
                    "MOON_PUBLIC_BREAKER_REJECTIONS=100",
                    f"MOON_PUBLIC_SMTP_ENABLED={'true' if smtp_enabled else 'false'}",
                    "MOON_PUBLIC_SMTP_ALLOW_PLAINTEXT=true",
                    "MOON_PUBLIC_SMTP_HOST=smtp.provider.test",
                    "MOON_PUBLIC_SMTP_PORT=25",
                    "MOON_PUBLIC_SMTP_SENDER=moon@example.test",
                    "MOON_PUBLIC_SMTP_RECIPIENT=operator@example.test",
                ]
            )
            + "\n",
            encoding="utf-8",
        )

    def run_public(self, mode: str, **environment):
        values = self.environment | {key: str(value) for key, value in environment.items()}
        return subprocess.run(
            ["bash", str(PUBLIC_SCRIPT), mode],
            check=False,
            capture_output=True,
            text=True,
            env=values,
        )

    def test_public_on_requires_notification_and_records_exact_route(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        result = self.run_public("on")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue((self.state / "public-ingress-enabled").exists())
        self.assertTrue(self.runtime_auth.exists())
        state = (self.state / "public-ingress-last.env").read_text()
        self.assertIn("STATE=enabled", state)
        commands = (self.root / "commands.log").read_text()
        self.assertIn("notify test", commands)
        self.assertIn("systemctl enable --now moon-service-funnel.service", commands)
        self.assertIn("notify enqueue public_enabled operator 0", commands)
        self.assertEqual(2, commands.count("notify test\n"))

    def test_public_on_fails_closed_without_smtp_notification(self):
        self.write_public_config(smtp_enabled=False)

        result = self.run_public("on")

        self.assertEqual(78, result.returncode)
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse(self.runtime_auth.exists())
        self.assertIn("without configured operator SMTP", result.stdout)

    def test_public_on_requires_an_immediate_successful_relay_test(self):
        result = self.run_public("on", FAKE_NOTIFY_TEST_FAIL=1)

        self.assertEqual(75, result.returncode)
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("test failed", result.stdout)

    def test_public_on_requires_the_container_smtp_firewall(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)

        result = self.run_public("on", FAKE_FIREWALL_FAIL=1)

        self.assertEqual(69, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("firewall verification failed", result.stdout)

    def test_public_on_reserves_notification_capacity_for_an_incident(self):
        result = self.run_public("on", FAKE_NOTIFY_PENDING=90)

        self.assertEqual(75, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("reserved incident capacity", result.stdout)

    def test_public_on_refuses_when_relay_test_audit_marker_cannot_be_written(self):
        try:
            result = self.run_public("on", FAKE_LOCK_STATE_AFTER_NOTIFY_TEST=1)
        finally:
            self.state.chmod(0o700)

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("audit marker could not be persisted", result.stdout)

    def test_public_on_rejects_a_route_without_exact_proxy_v2_contract(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)

        result = self.run_public("on", FAKE_PROXY_VERSION=1)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("did not expose the exact loopback target", result.stdout)

    def test_public_on_clears_and_rejects_a_preexisting_funnel_route(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        (self.root / "funnel-route").touch()

        result = self.run_public("on")

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertIn("existing Funnel route", result.stdout)

    def test_public_on_rolls_back_if_post_start_commit_fails(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)

        result = self.run_public("on", FAKE_TIMER_ENABLE_FAIL=1)

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.root / "active-moon-service-funnel.service").exists())
        self.assertFalse((self.root / "active-nginx.service").exists())
        self.assertIn("rolling back an incomplete public activation", result.stdout)

    def test_breaker_latches_off_before_disabling_funnel_and_notifies(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        self.assertEqual(0, self.run_public("on").returncode)

        result = self.run_public("check", FAKE_REJECTIONS=100)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        state = (self.state / "public-ingress-last.env").read_text()
        self.assertIn("STATE=disabled", state)
        self.assertIn("REASON=rate_limit", state)
        self.assertIn("REJECTED_REQUESTS=100", state)
        commands = (self.root / "commands.log").read_text()
        self.assertIn("runtime-auth=absent-at-disable", commands)
        self.assertNotIn("runtime-auth=present-at-disable", commands)
        self.assertIn("marker=present-at-disable", commands)
        self.assertIn("notify enqueue circuit_open rate_limit 100", commands)
        self.assertLess(
            commands.index("systemctl stop nginx.service"),
            commands.index("systemctl disable --now moon-service-funnel.service"),
        )

    def test_watchdog_removes_exposure_that_has_no_durable_marker(self):
        (self.root / "active-moon-service-funnel.service").touch()
        (self.root / "funnel-route").touch()

        result = self.run_public("check")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.root / "active-moon-service-funnel.service").exists())
        self.assertIn("without the durable enabled marker", result.stdout)
        commands = (self.root / "commands.log").read_text()
        self.assertIn("notify enqueue circuit_open watchdog_failure 0", commands)

    def test_watchdog_removes_orphan_runtime_authorization(self):
        self.runtime_auth.touch()

        result = self.run_public("check")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(self.runtime_auth.exists())
        self.assertIn("without the durable enabled marker", result.stdout)
        commands = (self.root / "commands.log").read_text()
        self.assertIn(
            "systemctl disable --now moon-service-funnel.service",
            commands,
        )

    def test_watchdog_disables_enabled_unit_without_runtime_authorization(self):
        (self.root / "enabled-moon-service-funnel.service").touch()

        result = self.run_public("check")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(
            (self.root / "enabled-moon-service-funnel.service").exists()
        )
        self.assertIn("without the durable enabled marker", result.stdout)
        commands = (self.root / "commands.log").read_text()
        self.assertIn(
            "systemctl disable --now moon-service-funnel.service",
            commands,
        )
        self.assertIn("notify enqueue circuit_open watchdog_failure 0", commands)

    def test_watchdog_cleans_up_unknown_or_unreadable_funnel_unit_states(self):
        for environment in (
            {
                "FAKE_FUNNEL_SERVICE_STATE": "activating",
                "FAKE_FUNNEL_ENABLED_STATE": "enabled-runtime",
            },
            {"FAKE_FUNNEL_QUERY_FAIL": 1},
        ):
            with self.subTest(environment=environment):
                (self.root / "commands.log").unlink(missing_ok=True)

                result = self.run_public("check", **environment)

                self.assertEqual(
                    0,
                    result.returncode,
                    result.stdout + result.stderr,
                )
                commands = (self.root / "commands.log").read_text()
                self.assertIn(
                    "systemctl disable --now moon-service-funnel.service",
                    commands,
                )

    def test_watchdog_disables_tailscaled_when_funnel_status_is_unavailable(self):
        (self.root / "enabled-tailscaled.service").touch()

        result = self.run_public("check", FAKE_FUNNEL_STATUS_FAIL=1)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "active-tailscaled.service").exists())
        self.assertFalse((self.root / "enabled-tailscaled.service").exists())
        self.assertIn("cannot be verified", result.stdout)
        commands = (self.root / "commands.log").read_text()
        self.assertIn("systemctl disable --now tailscaled.service", commands)
        self.assertIn("notify enqueue circuit_open watchdog_failure 0", commands)

    def test_watchdog_fails_closed_on_unknown_nonempty_funnel_schema(self):
        (self.root / "enabled-tailscaled.service").touch()

        result = self.run_public("check", FAKE_FUNNEL_UNKNOWN=1)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "active-tailscaled.service").exists())
        self.assertFalse((self.root / "enabled-tailscaled.service").exists())
        self.assertIn("nonempty or unrecognized", result.stdout)

    def test_breaker_stops_transport_when_persistent_state_is_not_writable(self):
        self.assertEqual(0, self.run_public("on").returncode)
        self.state.chmod(0o500)
        try:
            result = self.run_public("check", FAKE_REJECTIONS=100)
        finally:
            self.state.chmod(0o700)

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.root / "active-nginx.service").exists())
        self.assertIn("state persistence needs repair", result.stdout)

    def test_watchdog_defers_while_an_authorized_control_holds_the_lock(self):
        self.assertEqual(0, self.run_public("on").returncode)
        lock_path = self.runtime_auth.parent / "public-ingress.lock"
        with lock_path.open("a", encoding="utf-8") as lock_stream:
            fcntl.flock(lock_stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            deferred = self.run_public("check", FAKE_REJECTIONS=100)

        self.assertEqual(0, deferred.returncode, deferred.stdout + deferred.stderr)
        self.assertTrue((self.root / "funnel-route").exists())
        self.assertIn("watchdog check deferred", deferred.stdout)

        completed = self.run_public("check", FAKE_REJECTIONS=100)
        self.assertEqual(0, completed.returncode, completed.stdout + completed.stderr)
        self.assertFalse((self.root / "funnel-route").exists())

    def test_watchdog_latches_off_after_smtp_configuration_drift(self):
        self.assertEqual(0, self.run_public("on").returncode)
        self.write_public_config(smtp_enabled=False)

        result = self.run_public("check")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.state / "public-ingress-enabled").exists())
        self.assertIn("enabled marker disagrees", result.stdout)

    def test_watchdog_latches_off_when_notification_retry_timer_stops(self):
        self.assertEqual(0, self.run_public("on").returncode)
        (self.root / "active-moon-service-public-notify.timer").unlink()

        result = self.run_public("check")

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.state / "public-ingress-enabled").exists())

    def test_unverified_exact_off_uses_reset_fallback(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        self.assertEqual(0, self.run_public("on").returncode)

        result = self.run_public("off", FAKE_STICKY_FUNNEL=1)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "funnel-route").exists())
        commands = (self.root / "commands.log").read_text()
        self.assertIn("tailscale funnel reset", commands)
        self.assertNotIn("tailscale funnel --yes", commands)
        self.assertIn("notify enqueue public_disabled operator 0", commands)

    def test_unverified_reset_disables_tailscaled_durably(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        self.assertEqual(0, self.run_public("on").returncode)

        result = self.run_public(
            "off", FAKE_STICKY_FUNNEL=1, FAKE_RESET_FAIL=1
        )

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse((self.root / "active-tailscaled.service").exists())
        self.assertFalse((self.root / "enabled-tailscaled.service").exists())
        commands = (self.root / "commands.log").read_text()
        self.assertIn("systemctl disable --now tailscaled.service", commands)

    def test_public_off_attempts_network_shutdown_when_state_removal_fails(self):
        self.assertEqual(0, self.run_public("notify-test").returncode)
        self.assertEqual(0, self.run_public("on").returncode)
        marker = self.state / "public-ingress-enabled"
        marker.unlink()
        marker.mkdir()

        result = self.run_public("off")

        self.assertNotEqual(0, result.returncode)
        self.assertFalse(self.runtime_auth.exists())
        self.assertFalse((self.root / "funnel-route").exists())
        self.assertFalse((self.root / "active-moon-service-funnel.service").exists())
        self.assertFalse((self.root / "active-nginx.service").exists())
        self.assertIn("state persistence needs repair", result.stdout)

    def test_status_does_not_print_smtp_configuration(self):
        result = self.run_public("status")

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("public marker: disabled", result.stdout)
        self.assertIn("api=1/min client-api=1/min", result.stdout)
        self.assertNotIn("SMTP", result.stdout)


@unittest.skipIf(
    os.geteuid() == 0,
    "behavioral public-notifier tests require a non-root runner",
)
class PublicNotifierTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.config_dir = self.root / "config"
        self.state_dir = self.root / "state"
        self.config_dir.mkdir()
        self.state_dir.mkdir()
        (self.state_dir / "current.env").write_text(
            "MOON_IMAGE_REVISION=" + "a" * 40 + "\n", encoding="utf-8"
        )
        (self.config_dir / "public-ingress.env").write_text(
            "\n".join(
                [
                    "MOON_PUBLIC_SMTP_ENABLED=true",
                    "MOON_PUBLIC_SMTP_ALLOW_PLAINTEXT=true",
                    "MOON_PUBLIC_SMTP_HOST=smtp.provider.test",
                    "MOON_PUBLIC_SMTP_PORT=25",
                    "MOON_PUBLIC_SMTP_SENDER=moon@example.test",
                    "MOON_PUBLIC_SMTP_RECIPIENT=operator@example.test",
                    "MOON_PUBLIC_SMTP_TIMEOUT_SECONDS=10",
                ]
            )
            + "\n",
            encoding="utf-8",
        )
        self.module = load_notifier_module()
        self.environment = mock.patch.dict(
            os.environ,
            {
                "MOON_CONFIG_DIR": str(self.config_dir),
                "MOON_STATE_DIR": str(self.state_dir),
            },
        )
        self.environment.start()

    def tearDown(self):
        self.environment.stop()
        self.temporary.cleanup()

    def test_plaintext_ip_authorized_delivery_uses_no_auth_or_starttls(self):
        config = self.module.configuration()
        queued = self.module.enqueue(config, "circuit_open", "rate_limit", 123)
        smtp = mock.MagicMock()
        smtp.__enter__.return_value = smtp

        with mock.patch.object(self.module.smtplib, "SMTP", return_value=smtp) as constructor:
            result = self.module.flush(config)

        self.assertEqual(0, result)
        self.assertFalse(queued.exists())
        constructor.assert_called_once_with("smtp.provider.test", 25, timeout=10)
        smtp.ehlo_or_helo_if_needed.assert_called_once_with()
        smtp.send_message.assert_called_once()
        smtp.quit.assert_called_once_with()
        self.assertFalse(hasattr(smtp, "starttls") and smtp.starttls.called)
        self.assertFalse(hasattr(smtp, "login") and smtp.login.called)
        message = smtp.send_message.call_args.args[0]
        self.assertIn("Rate-limit rejections: 123", message.get_content())
        self.assertNotIn("client address:", message.get_content().lower())
        self.assertRegex(message["Message-ID"], r"^<[0-9a-f]{32}@moon-service\.invalid>$")
        self.assertIsNotNone(message["Date"])

    def test_failed_delivery_remains_queued_for_retry(self):
        config = self.module.configuration()
        queued = self.module.enqueue(config, "public_disabled", "operator", 0)

        with mock.patch.object(
            self.module.smtplib,
            "SMTP",
            side_effect=smtplib.SMTPConnectError(421, "unavailable"),
        ):
            result = self.module.flush(config)

        self.assertEqual(75, result)
        self.assertTrue(queued.exists())
        self.assertEqual(1, self.module.pending(config))

    def test_quit_failure_after_data_acceptance_does_not_retry_the_message(self):
        config = self.module.configuration()
        queued = self.module.enqueue(config, "circuit_open", "rate_limit", 100)
        smtp = mock.MagicMock()
        smtp.quit.side_effect = smtplib.SMTPServerDisconnected("quit failed")

        with mock.patch.object(self.module.smtplib, "SMTP", return_value=smtp):
            result = self.module.flush(config)

        self.assertEqual(0, result)
        self.assertFalse(queued.exists())
        smtp.send_message.assert_called_once()
        smtp.close.assert_called_once_with()

    def test_notification_test_is_immediate_and_not_queued(self):
        config = self.module.configuration()
        smtp = mock.MagicMock()
        smtp.__enter__.return_value = smtp

        with mock.patch.object(self.module.smtplib, "SMTP", return_value=smtp):
            result = self.module.test_delivery(config)

        self.assertEqual(0, result)
        smtp.send_message.assert_called_once()
        smtp.quit.assert_called_once_with()
        self.assertEqual("[Moon Service] Operator notification test", smtp.send_message.call_args.args[0]["Subject"])
        self.assertIn("No action is required", smtp.send_message.call_args.args[0].get_content())
        self.assertEqual(0, self.module.pending(config))

    def test_critical_notification_is_sent_before_older_transition_mail(self):
        config = self.module.configuration()
        ordinary = self.module.enqueue(config, "public_enabled", "operator", 0)
        critical = self.module.enqueue(config, "circuit_open", "rate_limit", 100)
        smtp = mock.MagicMock()

        with mock.patch.object(self.module.smtplib, "SMTP", return_value=smtp):
            result = self.module.flush(config, max_deliveries=1)

        self.assertEqual(0, result)
        self.assertFalse(critical.exists())
        self.assertTrue(ordinary.exists())
        self.assertEqual(
            "[Moon Service] Public alpha disabled automatically",
            smtp.send_message.call_args.args[0]["Subject"],
        )

    def test_invalid_queue_entry_is_quarantined_without_blocking_mail(self):
        config = self.module.configuration()
        self.module.ensure_queue(config)
        invalid = config.queue_dir / "00000000T000000Z-invalid.json"
        invalid.write_text("not json\n", encoding="utf-8")
        valid = self.module.enqueue(config, "circuit_open", "rate_limit", 100)
        smtp = mock.MagicMock()

        with mock.patch.object(self.module.smtplib, "SMTP", return_value=smtp):
            result = self.module.flush(config)

        self.assertEqual(0, result)
        self.assertFalse(valid.exists())
        self.assertFalse(invalid.exists())
        self.assertEqual(1, len(list(config.invalid_dir.glob("*.invalid"))))
        smtp.send_message.assert_called_once()

    def test_queue_reserves_capacity_for_circuit_notifications(self):
        config = self.module.configuration()
        with mock.patch.object(self.module, "MAX_NONCRITICAL_QUEUED", 1):
            self.module.enqueue(config, "public_enabled", "operator", 0)
            with self.assertRaises(SystemExit):
                self.module.enqueue(config, "public_disabled", "operator", 0)
            critical = self.module.enqueue(config, "circuit_open", "rate_limit", 100)

        self.assertTrue(critical.exists())

    def test_concurrent_flush_leaves_delivery_to_the_lock_owner(self):
        config = self.module.configuration()
        queued = self.module.enqueue(config, "public_disabled", "operator", 0)
        descriptor = self.module.acquire_delivery_lock(config)
        self.assertIsNotNone(descriptor)

        try:
            result = self.module.flush(config)
        finally:
            self.module.release_delivery_lock(descriptor)

        self.assertEqual(0, result)
        self.assertTrue(queued.exists())

    def test_invalid_event_is_rejected_before_queue_write(self):
        config = self.module.configuration()

        with self.assertRaises(SystemExit):
            self.module.enqueue(config, "visitor_query", "operator", 0)

        self.assertFalse(config.queue_dir.exists())


class PublicUnitContractTest(unittest.TestCase):
    @unittest.skipIf(
        os.geteuid() == 0,
        "behavioral control-wrapper test requires a non-root runner",
    )
    def test_control_preserves_failure_after_emergency_cleanup(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            calls = root / "calls"
            public = root / "public"
            emergency = root / "emergency"
            timeout = root / "timeout"
            public.write_text(
                "#!/usr/bin/env bash\nprintf 'public %s\\n' \"$*\" >>\"$CALLS\"\nexit 42\n",
                encoding="utf-8",
            )
            emergency.write_text(
                "#!/usr/bin/env bash\nprintf 'emergency\\n' >>\"$CALLS\"\nexit 0\n",
                encoding="utf-8",
            )
            timeout.write_text(
                "#!/usr/bin/env bash\n"
                "while [[ $# -gt 0 && \"$1\" == -* ]]; do shift; done\n"
                "[[ \"${1:-}\" =~ ^[0-9]+s$ ]] && shift\n"
                "exec \"$@\"\n",
                encoding="utf-8",
            )
            for executable in (public, emergency, timeout):
                executable.chmod(0o755)

            environment = os.environ | {
                "CALLS": str(calls),
                "MOON_CONTROL_ALLOW_NON_ROOT": "true",
                "MOON_PUBLIC_BIN": str(public),
                "MOON_EMERGENCY_BIN": str(emergency),
                "MOON_TIMEOUT_BIN": str(timeout),
            }
            for operation, public_mode in (
                ("public-on", "on"),
                ("public-off", "off"),
            ):
                with self.subTest(operation=operation):
                    calls.unlink(missing_ok=True)
                    result = subprocess.run(
                        ["bash", str(CONTROL_SCRIPT), operation],
                        check=False,
                        capture_output=True,
                        text=True,
                        env=environment,
                    )

                    self.assertEqual(
                        42,
                        result.returncode,
                        result.stdout + result.stderr,
                    )
                    self.assertEqual(
                        f"public {public_mode}\nemergency\n",
                        calls.read_text(),
                    )

    def test_funnel_is_foreground_and_proxy_protocol_target_is_exact(self):
        unit = (ROLE_ROOT / "templates" / "moon-service-funnel.service.j2").read_text()
        self.assertIn("--proxy-protocol=2", unit)
        self.assertIn("--tls-terminated-tcp={{ moon_service_public_https_port }}", unit)
        self.assertIn("tcp://127.0.0.1:{{ moon_service_public_ingress_port }}", unit)
        self.assertNotIn("--bg", unit)
        self.assertNotIn("ExecStop=", unit)
        self.assertIn(
            "BindsTo=nginx.service tailscaled.service moon-service-docker-firewall.service",
            unit,
        )
        self.assertIn("ConditionPathExists=/run/moon-service/public-authorized", unit)

    def test_container_smtp_block_does_not_affect_host_output(self):
        script = FIREWALL_SCRIPT.read_text(encoding="utf-8")
        self.assertIn("DOCKER-USER", script)
        self.assertIn("-i \"$bridge\"", script)
        self.assertIn("PORTS=25,465,587", script)
        self.assertNotIn("OUTPUT", script)
        self.assertNotIn("XTABLES_LOCKFILE", script)

    def test_systemd_units_share_the_global_xtables_lock_and_runtime_latch_directory(self):
        firewall_unit = (
            ROLE_ROOT / "templates" / "moon-service-docker-firewall.service.j2"
        ).read_text()
        watchdog_unit = (
            ROLE_ROOT / "templates" / "moon-service-public-watchdog.service.j2"
        ).read_text()
        self.assertIn("RuntimeDirectory=moon-service", firewall_unit)
        self.assertIn("RuntimeDirectoryPreserve=yes", firewall_unit)
        self.assertIn("/run/moon-service", firewall_unit)
        self.assertIn("-/run/xtables.lock", firewall_unit)
        self.assertIn("ReadWritePaths=-/run/moon-service", watchdog_unit)
        self.assertIn("ReadWritePaths=-/run/xtables.lock", watchdog_unit)

    def test_watchdog_start_failure_invokes_config_independent_emergency_shutdown(self):
        watchdog_unit = (
            ROLE_ROOT / "templates" / "moon-service-public-watchdog.service.j2"
        ).read_text()
        emergency_unit = (
            ROLE_ROOT / "templates" / "moon-service-public-emergency-off.service.j2"
        ).read_text()
        emergency_script = (
            ROLE_ROOT / "files" / "moon-service-public-emergency-off"
        ).read_text()
        self.assertIn("OnFailure=moon-service-public-emergency-off.service", watchdog_unit)
        self.assertNotIn("EnvironmentFile=", watchdog_unit)
        self.assertIn("moon-service-public-emergency-off", emergency_unit)
        self.assertNotIn("CONFIG_DIR", emergency_script)
        self.assertNotIn("STATE_DIR", emergency_script)
        self.assertIn("stop nginx.service", emergency_script)
        self.assertIn("disable --now tailscaled.service", emergency_script)
        self.assertIn('"$FLOCK_BIN" -w 200 9', emergency_script)

    def test_manual_public_off_has_a_configuration_independent_fallback(self):
        control = (ROLE_ROOT / "files" / "moon-service-control").read_text()
        self.assertIn("PUBLIC_BIN=/usr/local/sbin/moon-service-public", control)
        self.assertIn(
            "EMERGENCY_BIN=/usr/local/sbin/moon-service-public-emergency-off",
            control,
        )
        self.assertIn('"$PUBLIC_BIN" off', control)
        self.assertIn('"$PUBLIC_BIN" on', control)
        self.assertEqual(2, control.count('"$EMERGENCY_BIN" || true'))
        self.assertEqual(2, control.count('exit "$operation_status"'))

    def test_docker_ipv6_is_explicitly_disabled_for_the_firewall_contract(self):
        daemon_config = (ROLE_ROOT / "templates" / "docker-daemon.json.j2").read_text()
        self.assertIn('"ipv6": false', daemon_config)

    def test_ansible_never_enables_funnel(self):
        tasks = "\n".join(
            path.read_text(encoding="utf-8")
            for path in (ROLE_ROOT / "tasks").glob("*.yml")
        )
        self.assertNotRegex(
            tasks,
            r"name:\s*moon-service-funnel\.service\s*\n\s*(?:enabled|state):",
        )

    def test_ansible_latches_funnel_off_after_public_settings_change(self):
        tasks = (ROLE_ROOT / "tasks" / "public-ingress-install.yml").read_text()
        latch_tasks = (ROLE_ROOT / "tasks" / "public-ingress-latch-off.yml").read_text()
        self.assertIn("Immediately latch public ingress off", tasks)
        self.assertIn("_moon_service_nginx_public_definition.changed", tasks)
        self.assertIn("_moon_service_public_ingress_settings.changed", tasks)
        self.assertIn("_moon_service_public_control_files.results", tasks)
        self.assertIn("public-ingress-latch-off.yml", tasks)
        self.assertIn("/usr/local/sbin/moon-service-public", latch_tasks)
        self.assertIn("/usr/local/sbin/moon-service-public-emergency-off", latch_tasks)
        self.assertIn("- off", latch_tasks)

    def test_ansible_latches_off_before_host_mutations_and_after_daemon_start(self):
        main_tasks = (ROLE_ROOT / "tasks" / "main.yml").read_text()
        enable_tasks = (ROLE_ROOT / "tasks" / "public-ingress-enable.yml").read_text()
        self.assertLess(
            main_tasks.index("Enter the fail-closed public maintenance window"),
            main_tasks.index("Validate required local inventory variables"),
        )
        self.assertLess(
            main_tasks.index("Enter the fail-closed public maintenance window"),
            main_tasks.index("Remove Docker's legacy one-line repository definition"),
        )
        self.assertIn("Reassert the fail-closed latch after daemon reconciliation", enable_tasks)
        self.assertLess(
            enable_tasks.index("Reassert the fail-closed latch"),
            enable_tasks.index("Enable and start the loopback-only public proxy"),
        )

    def test_ansible_rejects_every_unmanaged_nginx_listener(self):
        enable_tasks = (ROLE_ROOT / "tasks" / "public-ingress-enable.yml").read_text()
        self.assertIn("Dump the complete installed nginx configuration", enable_tasks)
        self.assertIn("- -T", enable_tasks)
        self.assertIn("regex_findall", enable_tasks)
        self.assertIn("127.0.0.1:", enable_tasks)
        self.assertIn("proxy_protocol default_server", enable_tasks)
        self.assertLess(
            enable_tasks.index("Dump the complete installed nginx configuration"),
            enable_tasks.index("Enable and start the loopback-only public proxy"),
        )


if __name__ == "__main__":
    unittest.main()
