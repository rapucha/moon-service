from pathlib import Path
import re
import unittest


ROOT = Path(__file__).resolve().parents[3]
DEPLOYMENT = ROOT / "deployment" / "raspberry-pi"
PLAYBOOK = DEPLOYMENT / "preview-storage.yml"
PRODUCTION_PLAYBOOK = DEPLOYMENT / "site.yml"
INVENTORY = DEPLOYMENT / "inventory.example.yml"
ROLE = DEPLOYMENT / "roles" / "moon_service_preview_storage"
DEFAULTS = ROLE / "defaults" / "main.yml"
TASKS = ROLE / "tasks" / "main.yml"
INVENTORY_VALIDATION = ROLE / "tasks" / "validate-inventory.yml"
MOUNT_VALIDATION = ROLE / "tasks" / "validate-current-mount.yml"
RUNTIME_VALIDATION = DEPLOYMENT / "tests" / "preview-storage-validation.runtime.yml"

TARGET = "/mnt/moon-service-preview"
OPTIONS = (
    "vers=4.1,proto=tcp,rw,hard,_netdev,nofail,nosuid,nodev,noexec,"
    "x-systemd.rw-only,x-systemd.automount,x-systemd.idle-timeout=600,"
    "x-systemd.mount-timeout=30s"
)
CONFIRMATIONS = (
    "client_allowlist",
    "root_squash",
    "sync",
    "trusted_lan",
)


def task_blocks(text: str) -> list[str]:
    starts = list(re.finditer(r"(?m)^[ \t]*- name:[ \t]", text))
    return [
        text[match.start() : starts[index + 1].start() if index + 1 < len(starts) else None]
        for index, match in enumerate(starts)
    ]


class PreviewStorageContractTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.playbook = PLAYBOOK.read_text(encoding="utf-8")
        cls.production_playbook = PRODUCTION_PLAYBOOK.read_text(encoding="utf-8")
        cls.inventory = INVENTORY.read_text(encoding="utf-8")
        cls.defaults = DEFAULTS.read_text(encoding="utf-8")
        cls.main_tasks = TASKS.read_text(encoding="utf-8")
        cls.inventory_validation = INVENTORY_VALIDATION.read_text(encoding="utf-8")
        cls.mount_validation = MOUNT_VALIDATION.read_text(encoding="utf-8")
        cls.runtime_validation = RUNTIME_VALIDATION.read_text(encoding="utf-8")
        cls.tasks = cls.main_tasks.replace(
            "- name: Validate preview-storage inventory before host mutation\n"
            "  ansible.builtin.include_tasks: validate-inventory.yml\n",
            cls.inventory_validation,
        ).replace(
            "- name: Validate the current preview-storage mount state\n"
            "  ansible.builtin.include_tasks: validate-current-mount.yml\n",
            cls.mount_validation,
        )

    def assert_before(self, earlier: str, later: str) -> None:
        self.assertIn(earlier, self.tasks)
        self.assertIn(later, self.tasks)
        self.assertLess(self.tasks.index(earlier), self.tasks.index(later))

    def task(self, name: str) -> str:
        matches = [
            block
            for block in task_blocks(self.tasks)
            if block.startswith(f"- name: {name}\n")
        ]
        self.assertEqual(1, len(matches), name)
        return matches[0]

    def test_focused_playbook_is_separate_from_production(self):
        self.assertIn("moon_service_preview_storage", self.playbook)
        self.assertIn("moon_service_preview_storage_enabled | bool", self.playbook)
        self.assertNotIn("moon_service_preview_storage", self.production_playbook)

    def test_operator_inputs_are_disabled_and_empty_by_default(self):
        self.assertRegex(
            self.defaults,
            r"(?m)^moon_service_preview_storage_enabled:\s*false\s*$",
        )
        for name in ("server", "export"):
            self.assertRegex(
                self.defaults,
                rf"(?m)^moon_service_preview_storage_{name}:\s*['\"]{{2}}\s*$",
            )
        for suffix in CONFIRMATIONS:
            self.assertRegex(
                self.defaults,
                rf"(?m)^moon_service_preview_storage_confirm_{suffix}:\s*false\s*$",
            )

    def test_tracked_inventory_uses_a_rejected_documentation_endpoint(self):
        match = re.search(
            r"(?m)^\s*moon_service_preview_storage_server:\s*['\"]?"
            r"(192\.0\.2\.[0-9]+|198\.51\.100\.[0-9]+|203\.0\.113\.[0-9]+)",
            self.inventory,
        )
        self.assertIsNotNone(match)
        self.assertIn(
            "moon_service_preview_storage_export: REPLACE_WITH_NFS_EXPORT_PATH",
            self.inventory,
        )

    def test_server_and_export_validation_rejects_malformed_input(self):
        validation = self.task("Validate private preview-storage inventory")
        for value in (
            "moon_service_preview_storage_server is string",
            "moon_service_preview_storage_export is string",
            "25[0-5]",
            "^(?:10\\.|192\\.168\\.|172\\.",
            "^/[A-Za-z0-9_./-]+\\Z",
            "endswith('/')",
            "'//' not in moon_service_preview_storage_export",
            "'.' not in moon_service_preview_storage_export.split('/')",
            "'..' not in moon_service_preview_storage_export.split('/')",
            "'/example/moon-service-preview'",
            "'REPLACE' not in moon_service_preview_storage_export",
        ):
            self.assertIn(value, validation)
        self.assertIn("no_log: true", validation)
        self.assertIn("diff: false", validation)

    def test_all_private_confirmations_precede_host_mutation(self):
        mutation_markers = (
            "ansible.builtin.apt:",
            "ansible.builtin.file:",
            "ansible.builtin.lineinfile:",
            "ansible.builtin.systemd_service:",
        )
        first_mutation = min(self.tasks.index(marker) for marker in mutation_markers)
        for suffix in CONFIRMATIONS:
            variable = f"moon_service_preview_storage_confirm_{suffix} is sameas true"
            self.assertIn(variable, self.tasks)
            self.assertLess(self.tasks.index(variable), first_mutation)

    def test_rpcbind_is_rejected_before_apt_and_remains_unavailable(self):
        install = "Install only the NFS client package without service auto-start"
        self.assert_before("Inspect rpcbind unit state before mutation", install)
        self.assert_before("Refuse an existing rpcbind unit use", install)
        unit_check = self.task("Inspect rpcbind unit state before mutation")
        self.assertIn("--property=LoadState", unit_check)
        self.assertIn("--property=ActiveState", unit_check)
        unit_refusal = self.task("Refuse an existing rpcbind unit use")
        for state in (
            "ActiveState=inactive",
            "ActiveState=failed",
            "LoadState=loaded",
            "LoadState=masked",
            "LoadState=not-found",
        ):
            self.assertIn(state, unit_refusal)
        for unsafe_state in ("active", "activating", "deactivating", "maintenance"):
            self.assertNotIn(f"ActiveState={unsafe_state}' in", unit_refusal)
        apt = self.task(install)
        self.assertIn("name: nfs-common", apt)
        self.assertIn("policy_rc_d: 101", apt)
        for unit in ("rpcbind.service", "rpcbind.socket"):
            self.assertIn(unit, self.task("Mask rpcbind before installing the NFS client"))
            self.assertIn(unit, self.task("Keep rpcbind stopped, disabled, and masked"))
        self.assertIn("masked: true", self.tasks)
        for name in (
            "Check for a port 111 listener before mutation",
            "Check for a final port 111 listener",
        ):
            check = self.task(name)
            self.assertIn("/usr/bin/ss -H -lntu sport = :111", check)
            self.assertIn("changed_when: false", check)
        self.assertIn("port_111_before.stdout | trim | length) == 0", self.tasks)
        self.assertIn("port_111_after.stdout | trim | length) == 0", self.tasks)

    def test_target_and_fstab_conflicts_fail_before_mount_mutation(self):
        self.assertIn("--mountpoint /mnt/moon-service-preview", self.tasks)
        local_check = self.task("Check for local data at an unmounted preview-storage target")
        for argument in ("-mindepth 1", "-maxdepth 1", "-print -quit"):
            self.assertIn(argument, local_check)
        self.assertIn("src: /etc/fstab", self.tasks)
        first_mutation = min(
            self.tasks.index(marker)
            for marker in (
                "ansible.builtin.apt:",
                "ansible.builtin.file:",
                "ansible.builtin.lineinfile:",
                "ansible.builtin.systemd_service:",
            )
        )
        for check in (
            "Refuse an unexpected preview-storage fstab entry",
            "Refuse an unsafe current mount",
            "Refuse a nonempty or unsafe unmounted target",
        ):
            self.assertIn(check, self.tasks)
            self.assertLess(self.tasks.index(check), first_mutation)
        fstab_check = self.task("Refuse an unexpected preview-storage fstab entry")
        self.assertIn("fstab_target_lines | length == 0", fstab_check)
        self.assertIn("fstab_target_lines | length == 1", fstab_check)
        self.assertIn("exact_fstab_present", fstab_check)

    def test_mount_target_and_options_are_fixed(self):
        self.assertIn(TARGET, self.tasks)
        self.assertIn(OPTIONS, self.tasks)
        self.assertNotRegex(self.defaults, r"(?m)^moon_service_preview_storage_(?:target|options):")
        self.assertNotRegex(OPTIONS, r"(?:^|,)(?:ro|soft)(?:,|$)")

    def test_source_bearing_tasks_suppress_logs_and_diffs(self):
        names = (
            "Validate private preview-storage inventory",
            "Record the fixed preview-storage contract",
            "Record the exact preview-storage fstab line",
            "Read fstab before mutation",
            "Inspect the current exact mount point",
            "Configure the exact preview-storage fstab entry",
            "Start the preview-storage automount",
            "Trigger the preview-storage automount",
            "Inspect the active preview-storage NFS mount",
            "Read fstab after provisioning",
        )
        for name in names:
            block = self.task(name)
            self.assertIn("no_log: true", block, name)
            self.assertIn("diff: false", block, name)

    def test_automount_can_be_retriggered_after_an_idle_unmount(self):
        self.assertIn("mnt-moon\\x2dservice\\x2dpreview.automount", self.tasks)
        self.assertIn("selectattr('fstype', 'equalto', 'autofs')", self.tasks)
        self.assertIn("_moon_service_preview_storage_automount_before.rc == 0", self.tasks)
        self.assertIn("state: started", self.task("Start the preview-storage automount"))
        trigger = self.task("Trigger the preview-storage automount")
        self.assertIn("ansible.builtin.command:", trigger)
        self.assertIn(TARGET, trigger)
        self.assertIn("changed_when: false", trigger)
        self.assertNotIn("findmnt --target", self.tasks)

    def test_live_mount_validation_is_exact_and_fail_closed(self):
        inspection = self.task("Inspect the active preview-storage NFS mount")
        self.assertIn("--mountpoint /mnt/moon-service-preview", inspection)
        self.assertIn("--types nfs,nfs4", inspection)
        self.assertIn("SOURCE,TARGET,FSTYPE,OPTIONS", inspection)
        validation = self.task("Require the exact live preview-storage contract")
        for value in (
            "final_source_matches",
            "final_target_matches",
            "final_fstab_matches",
            "['nfs', 'nfs4']",
            "vers=4.1",
            "proto=tcp",
            "rw",
            "hard",
            "nosuid",
            "nodev",
            "noexec",
            "ro",
            "soft",
        ):
            self.assertIn(value, validation)
        self.assertIn("automount_after.rc == 0", validation)

    def test_generated_systemd_policy_is_verified_without_source_output(self):
        mount_policy = self.task("Inspect the generated preview mount policy")
        self.assertIn("--property=ReadWriteOnly", mount_policy)
        self.assertIn("--property=TimeoutUSec", mount_policy)
        self.assertNotIn("--property=What", mount_policy)
        automount_policy = self.task("Inspect the generated preview automount policy")
        self.assertIn("--property=TimeoutIdleUSec", automount_policy)
        self.assertNotIn("--property=What", automount_policy)
        validation = self.task("Require the exact live preview-storage contract")
        for value in (
            "ReadWriteOnly=yes",
            "TimeoutUSec=30s",
            "TimeoutIdleUSec=10min",
        ):
            self.assertIn(value, validation)

    def test_runtime_validation_exercises_inputs_and_mount_classification(self):
        for tasks_from in ("validate-inventory", "validate-current-mount"):
            self.assertIn(f"tasks_from: {tasks_from}", self.runtime_validation)
        for evidence in (
            "documentation_endpoint_rejected",
            "malformed_export_rejected",
            "missing_confirmation_rejected",
            "foreign_mount_rejected",
            "unowned_autofs_rejected",
        ):
            self.assertIn(evidence, self.runtime_validation)
        for mutating_module in (
            "ansible.builtin.apt:",
            "ansible.builtin.file:",
            "ansible.builtin.lineinfile:",
            "ansible.builtin.systemd_service:",
            "ansible.builtin.command:",
        ):
            self.assertNotIn(mutating_module, self.runtime_validation)

    def test_mutation_uses_convergent_builtin_modules(self):
        for module in (
            "ansible.builtin.apt:",
            "ansible.builtin.file:",
            "ansible.builtin.lineinfile:",
            "ansible.builtin.systemd_service:",
        ):
            self.assertIn(module, self.tasks)
        self.assertNotIn("ansible.builtin.shell:", self.tasks)
        self.assertNotIn("ansible.posix", self.tasks)
        for block in task_blocks(self.tasks):
            if "ansible.builtin.command:" in block:
                self.assertIn("changed_when: false", block, block.splitlines()[0])


if __name__ == "__main__":
    unittest.main()
