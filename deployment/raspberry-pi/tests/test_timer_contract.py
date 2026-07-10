from pathlib import Path
import unittest


ROOT = Path(__file__).resolve().parents[3]
ROLE_ROOT = (
    ROOT
    / "deployment"
    / "raspberry-pi"
    / "roles"
    / "moon_service_host"
)


class TimerContractTest(unittest.TestCase):
    def assert_reactivatable_timer(
        self,
        template_name: str,
        initial_trigger: str,
        recurring_trigger: str,
    ) -> None:
        timer = (ROLE_ROOT / "templates" / template_name).read_text()

        self.assertIn(f"OnActiveSec={initial_trigger}", timer)
        self.assertIn(f"OnUnitInactiveSec={recurring_trigger}", timer)
        self.assertNotIn("OnBootSec=", timer)
        self.assertNotIn("OnUnitActiveSec=", timer)

    def test_deploy_timer_rearms_after_same_boot_reactivation(self):
        self.assert_reactivatable_timer(
            "moon-service-deploy.timer.j2",
            "45s",
            "{{ moon_service_poll_interval }}",
        )

    def test_cleanup_timer_rearms_after_same_boot_reactivation(self):
        self.assert_reactivatable_timer(
            "moon-service-cleanup.timer.j2",
            "20min",
            "1d",
        )

    def test_operator_status_reports_monotonic_timer_schedule_and_substate(self):
        control = (ROLE_ROOT / "files" / "moon-service-control").read_text()

        self.assertIn("--property=SubState", control)
        self.assertIn("--property=NextElapseUSecMonotonic", control)
        self.assertNotIn("--property=NextElapseUSecRealtime", control)


if __name__ == "__main__":
    unittest.main()
