# Raspberry Pi Deployment

This directory implements issue
[#95](https://github.com/rapucha/moon-service/issues/95): a reproducible,
single-instance Docker Compose host on Raspberry Pi OS Lite 64-bit (Debian 13
Trixie). The Pi pulls the already-tested ARM64 image from GHCR. It never builds
Java or container images. Follow-up
[#107](https://github.com/rapucha/moon-service/issues/107) makes timer rearming
reliable and adds exact physical-deployment acknowledgement to GitHub.

This deployment publishes the same application instance on the host's exact
primary IPv4 address for a trusted home LAN and on `127.0.0.1:8080` for
Tailscale Funnel. The role opens no router port and does not manage Tailscale
enrollment or Funnel state. Those remain explicit operator actions. The
tester-alpha Funnel was activated and verified under
[#123](https://github.com/rapucha/moon-service/issues/123) as the final step of
[#97](https://github.com/rapucha/moon-service/issues/97).

## What provisioning installs

The Ansible role:

- accepts only Debian 13 Trixie on ARM64;
- persists Raspberry Pi's supported `cgroup_enable=memory` kernel argument and
  performs a controlled reboot when the active kernel still lacks that
  controller;
- installs Docker Engine, Buildx, and the Compose plugin from Docker's official
  Debian ARM64 repository;
- installs Tailscale from its official Trixie repository, starts `tailscaled`,
  and deliberately leaves the host unenrolled;
- creates root-owned configuration, Compose, and deployment-state directories;
- requires ignored host inventory to select that host's active primary IPv4
  address, then publishes exactly that trusted-LAN listener plus loopback;
- runs the application as the image's non-root UID/GID `10001` with no Linux
  capabilities, a read-only root filesystem, bounded memory/CPU/PIDs, and a
  small temporary filesystem;
- caps Docker logs and the system journal for a 32 GB SD card;
- polls the tested GHCR `main` image every two minutes and starts the recorded
  digest on boot;
- keeps a current and previous known-good digest, verifies `/readyz` and its Git
  revision, rolls back unhealthy candidates, and serializes operations with
  `flock`;
- when a repository-only GitHub App is configured, reports the exact healthy
  revision and immutable digest to the matching GitHub Deployment over outbound
  HTTPS;
- creates a dedicated SSH-key-only operator with fixed `sudo` commands and no
  Docker, sudo, or other supplementary group membership.

Docker access is root-equivalent. The automatic updater therefore runs as root.
An existing bootstrap administrator remains responsible for Ansible and
exceptional host recovery. Daily operations use a separate account created by
the role: its password is locked, its primary group is `moon-service-ops`, and
its supplementary groups are converged empty. Repository files, Compose files,
scripts, and state remain root-owned.

## Prerequisites

Prepare these before the controlled physical-host run:

1. Raspberry Pi 4 with Raspberry Pi OS Lite 64-bit based on Debian 13 Trixie.
2. A stable power supply and a 32 GB or larger SD card.
3. An existing bootstrap SSH administrator with key-based access and `sudo`;
   use `--ask-become-pass` if sudo requires a password. This account is not the
   daily Moon Service operator.
4. One local OpenSSH public-key file for the dedicated operator. Keep its
   absolute controller path in ignored inventory; the role installs that key
   and creates the operator account.
5. The Pi's LAN hostname or address, reachable from the Ansible controller.
   WireGuard is unnecessary while controller and Pi share the LAN.
6. Correct system time and outbound DNS/HTTPS access to Debian, Docker,
   Tailscale, GHCR, the GitHub API, and Open-Meteo endpoints.
7. Ansible Core 2.21.1 on the controller, matching the CI pin in
   `ci-requirements.txt`. No Ansible software is installed on the Pi; the Pi
   only needs its default Python 3 and SSH server.
8. A GHCR visibility decision:
   - a public package needs no registry credential;
   - a private package needs a separate read-only package token installed only
     in root's Docker credential store on the Pi.
9. For exact deployment acknowledgement, a deployment-reporter GitHub App
   installed only on `rapucha/moon-service`, with webhooks disabled and only
   repository metadata read plus Deployments read/write. Record its App ID and
   installation ID, and keep its generated PEM private key in a controller file
   outside the repository. Set repository Actions variable
   `MOON_PI_DEPLOYMENT_REPORTER_LOGIN` to the App bot login in the form
   `<app-slug>[bot]`; success from any other actor is rejected.

Do not put the host address, SSH key path, GHCR token, Tailscale key, admin
token, GitHub App private key or path, or another private-network identifier in
a tracked file.

## Configure local inventory

From this directory, create the ignored inventory:

```bash
cp inventory.example.yml inventory.yml
chmod 0600 inventory.yml
```

Replace all placeholders in `inventory.yml`:

- `ansible_user` is the existing bootstrap administrator used for provisioning.
- `moon_service_operator_user` is a distinct account the role creates; keep the
  default `moonops` unless it conflicts locally. Alternate names must start
  with `moonops` so the role cannot accidentally reconstrain a conventional
  administrator or system account.
- `moon_service_operator_public_key_file` is the absolute controller path to
  exactly one OpenSSH public key for that account.
- The optional GitHub App values are kept only in this ignored inventory.
  Configure all three together, quote the numeric IDs, and use an absolute
  controller path to the PEM private key:

```yaml
moon_service_github_app_id: "REPLACE_WITH_APP_ID"
moon_service_github_app_installation_id: "REPLACE_WITH_INSTALLATION_ID"
moon_service_github_app_private_key_file: /absolute/controller/path/to/private-key.pem
```

Protect the controller PEM with mode `0600`. When all three values are present,
the role copies it to `/etc/moon-service/github-app-private-key.pem` as
`root:root` mode `0600` and renders only the two IDs, fixed
repository/task/environment values, and that host path into `host.env`. It
never stores the PEM contents or an installation token in inventory or
`host.env`. Omit all three only when external acknowledgement is deliberately
disabled; a partial configuration must fail provisioning.

Set the Pi's active primary IPv4 address in ignored `inventory.yml`:

```yaml
moon_service_bind_address: REPLACE_WITH_PI_LAN_ADDRESS
```

The role fails closed if this value is absent, loopback, wildcard, a secondary
address, or IPv6. Keep the address stable with a DHCP reservation or static
network configuration; it should normally match the address used by
`ansible_host`. Compose publishes the same container port on this exact address
and on `127.0.0.1`, without a wildcard listener. This does not configure a
firewall, router forwarding, Tailscale enrollment, Funnel, TLS, or public
access.

Do not reuse an existing administrator as the operator: the role deliberately
locks the operator password, sets its primary group, and removes all
supplementary groups. If the bootstrap private key needs an explicit path, add
`ansible_ssh_private_key_file` only to this ignored file or local SSH
configuration.

Verify connectivity without changing the Pi:

```bash
ansible all -m ping
ansible-playbook site.yml --syntax-check
```

Inspect the proposed host facts before provisioning:

```bash
ansible all -m setup -a 'filter=ansible_distribution*'
ansible all -m setup -a 'filter=ansible_architecture'
```

The expected values are Debian, major version `13`, release `trixie`, and
architecture `aarch64`.

### Optional pull-request preview NFS storage

Issue [#198](https://github.com/rapucha/moon-service/issues/198) provides a
separate, disabled playbook for the preview database's NFS mount. It is not
included by `site.yml` and does not change the production Compose service,
listeners, timers, application data, or deployment state. The fixed mount point
is `/mnt/moon-service-preview`.

Configure the real NFS server address and export only through the ignored
`inventory.yml`, which must have mode `0600`. The role necessarily writes that
source to root-owned `/etc/fstab` and exposes it in the Pi's kernel mount table
and root-local systemd diagnostics. Keep those values and outputs off GitHub.
The tracked example uses an IPv4 documentation address and a placeholder
export. Enable the role only after the NAS is configured and all four
statements below are true:

- the NAS client allowlist includes the Pi's intended private IPv4;
- root squashing is enabled for the export;
- the export uses synchronous writes;
- the Pi-to-NAS path stays on the trusted LAN and is not routed publicly.

Record those checks in the private inventory without copying the endpoint into
an issue, pull request, CI output, or shared verification log:

```yaml
moon_service_preview_storage_enabled: true
moon_service_preview_storage_server: REPLACE_WITH_PRIVATE_NAS_IPV4
moon_service_preview_storage_export: REPLACE_WITH_NFS_EXPORT_PATH
moon_service_preview_storage_confirm_client_allowlist: true
moon_service_preview_storage_confirm_root_squash: true
moon_service_preview_storage_confirm_sync: true
moon_service_preview_storage_confirm_trusted_lan: true
```

From `deployment/raspberry-pi`, check and apply only the focused playbook:

```bash
ansible-playbook preview-storage.yml --syntax-check
ansible-playbook --inventory localhost, --connection local \
  tests/preview-storage-validation.runtime.yml
ansible-playbook preview-storage.yml --diff
```

Add `--ask-become-pass` to the apply command when the bootstrap account needs
it. The role installs the NFS client without allowing package installation
to start services, rejects a pre-existing active `rpcbind`, and masks both the
service and socket. It manages an NFS 4.1 TCP hard mount through a systemd
automount. Access mounts it on demand. After first access, the NFS layer may
stay mounted until an explicit unmount or shutdown; the persistent automount
keeps boot independent of NAS availability and remains ready across reboot.

Run the following only on the Pi. Treat the whole diagnostic block as private
local output because mount and systemd status can reveal the NFS source. Do not
paste its output into public evidence:

```bash
sudo findmnt --target /mnt/moon-service-preview \
  --output TARGET,SOURCE,FSTYPE,OPTIONS
systemctl status 'mnt-moon\x2dservice\x2dpreview.automount'
systemctl status 'mnt-moon\x2dservice\x2dpreview.mount'
systemctl show 'mnt-moon\x2dservice\x2dpreview.mount' \
  --property=ReadWriteOnly --property=TimeoutUSec
systemctl is-enabled rpcbind.service rpcbind.socket
systemctl is-active rpcbind.service rpcbind.socket
sudo ss -H -lntup '( sport = :111 )'
```

`findmnt` must report the exact configured source and target, NFS or NFS4,
`vers=4.1`, `proto=tcp`, `rw`, `hard`, `nosuid`, `nodev`, and `noexec`, with no
`ro` or `soft`. The root-owned fstab entry also holds `_netdev`, `nofail`,
`x-systemd.rw-only`, `x-systemd.automount`, and the 30-second mount timeout;
these systemd properties are not kernel mount flags. The generated mount unit
must report `ReadWriteOnly=yes` and `TimeoutUSec=30s`.
Both `rpcbind` units must be masked and inactive, and the `ss` command must
print no TCP or UDP port 111 listener on IPv4 or IPv6. Continued NFS attachment
after first access is expected and does not fail validation. Run the apply
command again; an unchanged host must finish with `changed=0` as the
idempotence check.

The role fails closed on an unconfirmed prerequisite, invalid endpoint, wrong
existing mount, nonempty local mount-point directory, or active portmapper.
Correct the inventory or NAS policy and rerun the focused playbook. Before
moving unexpected local data or changing a mount used by another service,
inspect it and preserve it for recovery; do not force the role past the check.
If `rpcbind` is already active, identify its owner and dependencies before
stopping it. Leave preview storage disabled when another required service needs
the portmapper.

This step proves only the live host mount and records the operator's four NAS
and trusted-LAN confirmations. It cannot prove NAS ACLs, routing,
`root_squash`, or synchronous server writes. It also does not prove that the
preview PostgreSQL UID can write its required database subtree; the preview
runtime issue owns that separate preparation and writeability test.

### Optional pull-request preview runtime

Issue [#200](https://github.com/rapucha/moon-service/issues/200) adds a second
disabled playbook for one manual pull-request preview. `site.yml` does not
include it, and it starts no boot service or timer. The preview listens only on
the primary LAN address at port `8081`; PostgreSQL has no host port.

First have the NAS operator create `/mnt/moon-service-preview/postgres` as
numeric owner `999:999` under the existing `root_squash` export. Preview data
is disposable and may include tester notes, city-level locations, opportunity
context, and timestamps. There is no backup or recovery promise. Keep the NFS
endpoint, credentials, feedback, locations, and host diagnostics off GitHub.

After storage is active, set `moon_service_preview_runtime_enabled: true` only
in ignored inventory, then install the source-free assets and control helper:

```bash
ansible-playbook preview-runtime.yml --syntax-check
ansible-playbook preview-runtime.yml --diff
```

This renders no credential or candidate and starts nothing. For a CI-green
same-repository PR, the owner manually dispatches `publish-pr-preview` and uses
its PR number, full revision, and digest. Require four CPUs and 4 GiB memory:

```bash
nproc
grep -E '^(MemTotal|MemAvailable|SwapTotal|SwapFree):' /proc/meminfo
sudo moon-service-control revision
sudo moon-service-preview-control start \
  REPLACE_WITH_PR_NUMBER REPLACE_WITH_40_CHARACTER_REVISION \
  sha256:REPLACE_WITH_64_HEXADECIMAL_CHARACTERS
sudo moon-service-preview-control status
```

`start` pulls only the fixed Moon Service repository at that digest, validates
ARM64 and revision identity, checks the exact NFS mount and a bounded
UID/GID-`999:999` create/remove probe, and then starts both containers. It makes
root-only credentials once. A same-PR update retains credentials and data; a
different PR is refused while identity or data remains.

Verify the exact revision, representative lookup, and hosted-alpha surface
from the trusted LAN:

```bash
curl --fail 'http://REPLACE_WITH_PI_LAN_ADDRESS:8081/readyz'
curl --fail \
  'http://REPLACE_WITH_PI_LAN_ADDRESS:8081/api/opportunities?q=REPLACE_WITH_TEST_CITY'
curl --fail \
  'http://REPLACE_WITH_PI_LAN_ADDRESS:8081/api/calibration-feedback/v1/capability'
curl --silent --output /dev/null --write-out '%{http_code}\n' \
  'http://REPLACE_WITH_PI_LAN_ADDRESS:8081/healthz'
```

Require the requested revision, a successful lookup, enabled/available
feedback, and a `404` from `/healthz`. As the bootstrap administrator, use
`docker inspect` to confirm the Compose users, limits, read-only filesystems,
capabilities, privilege setting, tmpfs, logs, networks, exact application
binding, and absent PostgreSQL host binding.

For the database-loss check, stop only PostgreSQL. The application must stay
running and usable, feedback must become unavailable, and `/healthz` stay `404`:

```bash
sudo docker stop moon-service-preview-postgres
```

Restart with the same control-helper `start` command. Before a same-PR update,
record a disposable database marker and feedback row; verify both remain
afterward. A failed candidate leaves the preview stopped with data and secrets.

`stop` removes preview containers and networks but retains identity, secrets,
and data. Replacing it with another pull request requires three explicit
commands. `purge` is destructive and cannot be undone:

```bash
sudo moon-service-preview-control stop
sudo moon-service-preview-control purge --confirm
```

Then use `start` with the other PR. Purge rechecks the mount and path, empties
but never removes the prepared subtree, and removes only preview credentials
and state. If NFS or initial PostgreSQL startup fails, the application starts
without persistence, status reports `degraded`, and `start` returns nonzero.
Partial first initialization records `purge-required`; stop, repair, and purge.

During the controlled simultaneous production/preview run, repeat production
readiness and representative requests. Require 512 MiB `MemAvailable`, no swap
increase or OOM kill, and the configured quotas. Stop the preview afterward;
issue #201 owns later polling, boot restore, expiry, replacement, and cleanup.

## Provision the Pi

The first production-provisioning mutation is intentionally a coordinated
operator step:

```bash
ansible-playbook site.yml --diff
```

If sudo needs a password:

```bash
ansible-playbook site.yml --diff --ask-become-pass
```

The role is idempotent. Re-running it ensures required packages are present,
updates managed files, preserves deployment state, and does not overwrite
`/etc/moon-service/application.env`. Docker may restart when its daemon
configuration changes; `live-restore` keeps an already-running container alive
during that restart.

Before changing an existing Compose definition or listener, provisioning
records a host-remediation marker, stops automatic timers, disables boot
deployment, and drains the shared deployment lock. It then recreates the exact
recorded digest and verifies readiness plus the exact requested host binding.
A failure keeps the marker and automation pause in place so a later playbook
run must retry the same reconciliation before timers can resume.

Raspberry Pi kernels disable the memory cgroup by default. The role appends
only `cgroup_enable=memory` to the existing single line in
`/boot/firmware/cmdline.txt`; do not add the unrecognized `cgroup_memory=1`
argument. When the argument is not active, provisioning pauses both Moon
Service timers, disables boot deployment, waits for any in-flight deployment
lock, and reboots once. Ansible reconnects automatically, requires the cgroup
v2 `memory` controller, and re-enables automation only after Docker reports
memory-limit support. Expect a short SSH and application interruption on that
corrective run.

If a recorded deployment already exists with a discarded or incorrect limit,
the role force-recreates that exact digest and waits for its existing readiness
contract. A failed reboot, controller check, recreation, or exact-limit check
leaves automatic deployment disabled and retains a remediation marker so a
later playbook rerun must repeat readiness and exact-limit checks before
automation can resume.

The deploy timer starts after provisioning. A missing registry credential or
temporary network failure is safe: the updater records a deferred attempt and
keeps the recorded local image running.

Both timers use `OnActiveSec` activation-relative first triggers. This matters
because provisioning deliberately stops them during listener or memory-limit
reconciliation and may start them again long after boot: the deploy timer must
receive a new trigger approximately 45 seconds after activation, and the
cleanup timer approximately 20 minutes after activation. An active timer with
no finite next monotonic trigger is not converged host state.

When provisioning repairs a changed or already-elapsed deployment schedule, it
also starts `moon-service-deploy.service` once immediately. This closes the
missed-publication gap without waiting for the newly rearmed timer's first
deadline; subsequent discovery remains timer-driven.

### Private GHCR package only

Create a personal access token (classic) with only `read:packages`; GitHub
Packages does not currently accept fine-grained personal access tokens for this
login. The token owner must also have read access to the package and its linked
repository. Open an interactive SSH session as the bootstrap administrator,
then send the token to Docker on standard input; do not put it in inventory or
a command argument:

```bash
ssh REPLACE_WITH_BOOTSTRAP_ADMIN@REPLACE_WITH_SSH_HOST
read -rsp 'GHCR read token: ' GHCR_TOKEN
printf '%s' "$GHCR_TOKEN" \
  | sudo docker login ghcr.io --username rapucha --password-stdin
unset GHCR_TOKEN
exit
```

This writes root's Docker credential file on the Pi. Docker stores registry
credentials in that host-local file unless a credential helper is configured,
so keep `/root/.docker/config.json` root-only and rotate the token deliberately.
For a public GHCR package, verify an anonymous pull instead and do not create a
long-lived host credential.

### Prepared hosted-alpha configuration

The seeded environment enables live Open-Meteo geocoding and weather and
explicitly sets `MOON_HOSTED_ALPHA_ENABLED=false`. The file is created only
when absent, so provisioning does not overwrite an existing Pi's host-only
configuration or token.

The active tester alpha uses the hosted surface. On a fresh or rebuilt host,
create a high-entropy token and edit the host-only file as the bootstrap
administrator before restoring Funnel:

```bash
openssl rand -hex 32
sudoedit /etc/moon-service/application.env
sudo moon-service-control restart
```

Set `MOON_HOSTED_ALPHA_ENABLED=true` and add
`MOON_ADMIN_TOKEN=<generated-value>` locally. Store the token in the chosen
off-host secret store; never commit it, print it in verification evidence, or
pass it in a URL. Existing hosts require this manual update because Ansible
deliberately does not overwrite `application.env`. A fresh host receives the
disabled seed. Keep Funnel off until the hosted configuration is enabled and
the local readiness check passes.

### Tailscale boundary

Provisioning installs the package and enables `tailscaled` only. Do not add a
Tailscale auth key to inventory. Tailnet enrollment and Funnel configuration
are explicit bootstrap-administrator actions. Existing MikroTik WireGuard
remains the remote-administration path and does not need any software on this
Pi.

The prepared deployment publishes the same container on exactly two host
addresses: the configured primary LAN IPv4 and `127.0.0.1`, both using the
configured host port. The exact-set validator rejects missing, wildcard,
secondary, IPv6, wrong-port, and additional bindings. LAN access therefore
continues without a router forwarding rule, while Tailscale receives the only
HTTP proxy target it supports: explicit loopback. The GitHub deployment
callback remains outbound and local-health-based, so this ingress preparation
does not alter its trust boundary.

Current Tailscale documentation requires MagicDNS, tailnet HTTPS, a Funnel
`nodeAttrs` permission, and public HTTPS port `443`, `8443`, or `10000`.
Interactive enrollment or Funnel setup can open a browser consent flow. Follow
only URLs printed by the CLI. Do not create or store an auth key in inventory.

Check the current state without changing it:

```bash
sudo tailscale status
sudo tailscale funnel status
```

The expected tester-alpha state is an online tailnet node and one Funnel HTTPS
listener on port `443` with one proxy target:

```text
https://<node-name>.<tailnet>.ts.net:443 -> http://127.0.0.1:8080
```

On first enrollment or deliberate re-enrollment, use the interactive login,
then restore only that exact handler:

```bash
sudo tailscale up
sudo tailscale status
sudo tailscale funnel --bg --https=443 http://127.0.0.1:8080
sudo tailscale funnel status
```

`--bg` makes Funnel persist across daemon restarts and reboots. To withdraw
public access without changing LAN access or logging the node out:

```bash
sudo tailscale funnel --https=443 off
sudo tailscale funnel status
```

The off status must show no active public Funnel. Keep Funnel off after an
unexpected route, unhealthy service, or failed verification. Check local
readiness before restoring the exact handler:

```bash
sudo moon-service-control revision
sudo tailscale funnel --bg --https=443 http://127.0.0.1:8080
sudo tailscale funnel status
```

If the listener cannot be removed cleanly, keep the application private and
use `sudo tailscale funnel reset` before rechecking status. Reserve
`sudo tailscale logout` followed by a fresh interactive `sudo tailscale up` for
deliberate re-enrollment recovery; logout is not a routine off switch.

Funnel targets only `http://127.0.0.1:8080`; it does not publish the LAN
listener, SSH, Docker's socket/API, or another host service. No router HTTP
forward is needed. Issue #123 verified those negative boundaries, one real
lookup, and the off/on recovery path. Follow-up
[#158](https://github.com/rapucha/moon-service/issues/158) owns the postponed
household-uplink and video-call measurement. It is not an activation gate.

### GitHub deployment reporter boundary

GitHub Actions creates a Deployment containing the exact revision and immutable
multi-platform digest selected by serialized `main` promotion. The Pi's normal
update cycle still resolves GHCR `main` as its independent desired-state source,
deploys that immutable digest, then finds and updates only Deployment payloads
whose repository, revision, and digest match the local result. A GitHub App or
Deployments API outage therefore cannot block a healthy application update.

The deployment-reporter GitHub App must be installed only on
`rapucha/moon-service`, with repository metadata read and Deployments
read/write and with its webhook disabled. Ignored inventory contains the App
ID, installation ID, and absolute controller PEM path; the role installs the
key root-only at `/etc/moon-service/github-app-private-key.pem`. The deployer
mints short-lived installation tokens as needed; do not store a long-lived
installation token or pass App credentials into the application container.

The reporter is not an ingress service. It initiates HTTPS to GitHub and does
not require the app to be reachable from GitHub, the public Internet, or a
tailnet. Later Funnel configuration must not publish reporter files or
credentials.

## Host verification

After provisioning, open a new session as the dedicated operator and check the
deployment:

```bash
ssh moonops@REPLACE_WITH_SSH_HOST
sudo moon-service-control status
sudo moon-service-control revision
```

Confirm that both active timers have finite future monotonic triggers:

```bash
systemctl show moon-service-deploy.timer \
  --property=ActiveState --property=NextElapseUSecMonotonic
systemctl show moon-service-cleanup.timer \
  --property=ActiveState --property=NextElapseUSecMonotonic
systemctl list-timers moon-service-deploy.timer moon-service-cleanup.timer
```

`ActiveState=active` together with `NextElapseUSecMonotonic=infinity` is a
broken schedule, not a healthy timer. After an issue #107 provisioning run, the
deploy timer must be scheduled even when it was stopped and restarted in the
same boot.

Verify the loopback target through SSH without changing the router:

```bash
ssh -L 8080:127.0.0.1:8080 moonops@REPLACE_WITH_SSH_HOST
```

Then, from the controller:

```bash
curl --fail http://127.0.0.1:8080/readyz
curl --fail 'http://127.0.0.1:8080/api/opportunities?q=Prague'
```

Then access the same instance directly over the trusted LAN:

```bash
curl --fail http://REPLACE_WITH_PI_LAN_ADDRESS:8080/readyz
curl --fail 'http://REPLACE_WITH_PI_LAN_ADDRESS:8080/api/opportunities?q=Prague'
```

Both `/readyz` responses must report the same revision. Docker must publish
exactly the configured primary IPv4 address and `127.0.0.1`, never `0.0.0.0`,
an IPv6 address, or an additional port; the home router must continue to have
no forwarding rule for this port. Inspect the exact set as the bootstrap
administrator:

```bash
sudo docker inspect moon-service \
  --format '{{json .HostConfig.PortBindings}}'
```

The `8080/tcp` entry must contain only the configured LAN address and
`127.0.0.1`, each with host port `8080`. Array order is not significant.

The first response must report `status: ok` and the same 40-character Git
revision shown in `current.env`. The opportunity request is the deliberate live
provider validation; readiness itself never contacts Open-Meteo.

For the one-time native ARM64/runtime-identity check, use the bootstrap
administrator because the daily operator intentionally has no arbitrary Docker
access:

```bash
grep -w memory /sys/fs/cgroup/cgroup.controllers
sudo docker info --format 'memory-limit={{.MemoryLimit}}'
sudo docker inspect moon-service \
  --format 'platform={{.Platform}} image={{.Config.Image}} user={{.Config.User}} health={{.State.Health.Status}} memory={{.HostConfig.Memory}}'
```

Expected runtime user: `10001:10001`. The image reference must contain the
recorded multi-architecture index digest; Docker selects its ARM64 child on the
Pi. Docker must report `memory-limit=true`, and the configured `1536m` limit
must render as exactly `1610612736` bytes. Use the cgroup v2 controller file,
not the legacy `/proc/cgroups`, for this acceptance check.

### Controlled live-validation sequence

For the first physical run, stop after any failed step and keep the Pi within
its exact trusted-LAN-plus-loopback boundary:

1. Run the fact and SSH preflight commands before the playbook.
2. Run provisioning once and observe the immediate catch-up deployment when it
   repairs scheduling; otherwise wait for the first timer attempt.
3. Verify `status`, `/readyz`, the exact digest/revision, ARM64 platform,
   runtime UID/GID, and exact memory limit before making a live opportunity
   request.
4. Run the same playbook again. Review the recap and investigate unexpected
   changes. The host and application configuration should remain intact.
   Tailscale enrollment and operator-managed Funnel state must remain unchanged.
5. Reboot the Pi, then verify the recorded digest starts and becomes ready
   without GHCR being part of the startup path.
6. After a later healthy `main` publication, confirm the next timer cycle starts
   the bounded deployment, moves the old identity to `previous.env`, and posts
   success for the exact revision and digest before the GitHub workflow's
   confirmation timeout.
7. Use the constrained manual `rollback`, verify the previous revision, and
   confirm `status` reports that updates are held. Use `resume` only after the
   drill.
8. During a short controlled registry/network outage, trigger the deploy
   service and verify the local current revision remains ready. Restore network
   access immediately afterward.

The deterministic tests exercise an unhealthy candidate and automatic rollback
without deliberately publishing a broken `main` image. Do not create a broken
production candidate solely for the physical drill.

## Automatic deployment contract

`moon-service-deploy.timer` starts an update approximately every two minutes.
Each attempt:

1. obtains `/var/lib/moon-service/deploy.lock` without waiting;
2. reconciles the recorded current digest before doing network work, which also
   repairs a deployment interrupted between container replacement and state
   commit;
3. inspects `ghcr.io/rapucha/moon-service:main` and verifies the immutable index
   digest, source/revision annotations, and ARM64 manifest;
4. skips the pull when that digest is already current or previously rejected;
5. pulls `repository@sha256:...`, never the moving tag as deployment identity;
6. replaces the one Compose container and waits at most 90 seconds for both
   Docker health and `/readyz` to report the expected Git revision;
7. commits current/previous state only after readiness succeeds;
8. posts success to the matching GitHub Deployment only after the recorded
   state, running image, Docker health, and `/readyz` revision all match;
9. restores the unchanged current state and reports failure when the candidate
   fails.

The systemd attempt has a ten-minute outer timeout, leaving bounded room for a
slow ARM64 pull plus both the candidate and rollback readiness windows. If
systemd must terminate an exceptionally stuck attempt, candidate state is never
committed as current; the next timer run reconciles the recorded known-good
digest and Compose configuration before contacting GHCR.

### Timer scheduling and rearming

The `OnActiveSec` initial timer deadlines are relative to timer activation, not
machine boot. Stopping and starting a timer during an Ansible reconciliation
therefore gives it a fresh finite deadline in the current boot. After the first
run, `OnUnitInactiveSec` schedules the next deploy attempt approximately two
minutes after the service becomes inactive and cleanup approximately one day
after its service becomes inactive.

Use the host-verification commands above after provisioning, timer-unit changes,
or remediation. If an enabled timer is active but has no finite next trigger,
inspect its unit and journal, then rerun the playbook to converge the managed
unit. A bootstrap administrator may deliberately restart the two timers after
inspection, but that is recovery rather than a substitute for the managed
activation-relative schedule.

When Ansible detects a changed deploy-timer definition, host remediation, or an
elapsed deploy timer, it starts one immediate catch-up deployment after
rearming and validating both timers. This is why applying issue #107 should
pick up a `main` image that was published while the old timer was stuck.

### Exact GitHub deployment confirmation

Image publication, `main` tag promotion, and physical deployment are separate
states. The GitHub workflow records the exact promotion result in environment
`raspberry-pi`, task `deploy:raspberry-pi`, with payload schema version `1` and
the expected image repository, revision, digest, deployment-reporter bot login,
workflow run ID, workflow run attempt, and workflow run URL. It posts `queued`
and waits up to 15 minutes for the Pi. The Pi may report success only when all
of these match that Deployment payload:

- `current.env` revision and immutable index digest;
- the running container's image reference and revision/digest labels;
- Docker health;
- `GET /readyz` status and 40-character revision.

Reporting merely that the process is up is insufficient. A status for another
revision or digest cannot satisfy the waiting workflow. A newer successful
publication may supersede an older pending Deployment, but an older workflow
must never make the Pi downgrade a healthy newer version merely to become
green.

The reporter should publish `in_progress` when it begins the matching attempt,
`success` only after the checks above, and `failure` when that candidate was
tried but could not become healthy. The Actions waiter treats `inactive` as a
superseded Deployment and fails because that exact request was not confirmed;
only `success` makes confirmation green. Requests for the same revision and
digest remain eligible for the same Pi success rather than superseding one
another. Because Deployment statuses are append-only, a success from the
configured App remains authoritative if a racing newer promotion appends
`inactive` immediately afterward: the success proves the exact older revision
really ran before supersession. A success from any other GitHub identity fails
the waiter. If GitHub is temporarily unreachable, keep the locally verified
healthy deployment and retry reporting on a later timer cycle; a callback
transport failure is not a reason to roll back a good image. The bounded GitHub
Actions waiter still fails when no exact success arrives, making the missing
acknowledgement visible outside the Pi.

Each reporter invocation has a 20-second outer timeout plus a five-second kill
grace. GitHub slowness, duplicate matching requests, or a wedged TLS request can
delay but cannot consume the deploy service's ten-minute budget or prevent the
local GHCR update. A timed-out report remains nonfatal and the next timer cycle
retries it; matching deployments are processed newest first.

A 15-minute waiter timeout does not post a terminal `error` status. A Pi success
that arrives late therefore remains authoritative, and a confirm-only workflow
rerun can validate the stored creation attempt and accept it.

### Separate host-configuration convergence

The green `raspberry-pi` application Deployment does not claim that the Ansible
role was applied. The first producer-enabled workflow queues the current host;
later jobs queue only for validated role or fingerprint-helper changes. The
older push/GHCR baseline prevents a replaced promotion from hiding such a
change. Manual provisioning completes the separate host-configuration request.

The fingerprint covers the path-framed bytes of every Git-tracked file under
`deployment/raspberry-pi/roles/moon_service_host/`, sorted by repository-relative
path. Its versioned SHA-256 framing excludes ignored inventory, controller keys,
addresses, and all other private inputs. Reproduce the working-tree identity
from the repository root with:

```bash
python3 deployment/raspberry-pi/host-contract-fingerprint.py
```

Provisioning calculates the same identity on the controller. Only after all
role tasks and final exact memory, listener, readiness, and finite-timer checks
succeed does it atomically replace `applied-host-configuration.env` and ask the
existing repository-scoped GitHub App to report success for the newest exact
matching queued request. An interruption before the final assertions retains
the previous applied identity. After persistence, reporting is independent: an
API failure retains the newly applied local identity and leaves the GitHub
request retryable by a later idempotent playbook run.

This is point-in-time evidence, not monitoring: GitHub Actions does not execute
Ansible, reach the Pi inbound, or learn private inventory. Host jobs may overlap
to avoid replacement of a pending signal, so reruns can create duplicate queued
records. Provisioning completes the newest exact match; application-only ranges
after activation create no request.

A failed candidate is recorded in `rejected.env`. Later timer runs do not
reintroduce two-minute outages by retrying the same bad digest. A newly
published digest is tried normally. After correcting a host-only problem, use
`resume` to clear the rejection and retry immediately.

Every new rejection also records `MOON_REJECTION_STAGE` and `MOON_REJECTED_AT`.
`compose` means Compose startup failed; `readiness` covers container health,
`/readyz`, its JSON check, and the final post-identity readiness recheck; and
`identity` covers recorded state, the immutable image reference, or required
labels after initial readiness. The timestamp is UTC `YYYY-MM-DDTHH:MM:SSZ`.
Inspect both fields before `resume`. Legacy rejected-state files remain valid.

If GHCR or the network is unavailable, discovery/pull is deferred only after
the last recorded local digest is reconciled. The running service does not
depend on registry availability.

State is under `/var/lib/moon-service`:

- `current.env`: active known-good digest and revision;
- `previous.env`: one rollback generation;
- `rejected.env`: quarantined image identity, verification stage, and UTC time;
- `last-result.env`: latest update/control outcome;
- `last-github-report.env`: latest callback result, status, exact identity, and
  matching Deployment IDs, without tokens or private-key material;
- `applied-host-configuration.env`: last fingerprint recorded only after full
  Ansible convergence;
- `last-host-github-report.env`: latest non-secret host-convergence callback
  result and matching Deployment IDs;
- `automatic-updates-held`: present after a manual rollback;
- `memory-limit-remediation`: prevents automation from being re-enabled after
  an interrupted or failed host memory-limit repair;
- `host-configuration-remediation`: prevents automation from being re-enabled
  after an interrupted or failed Compose/listener reconciliation;
- `last-cleanup.env`: latest daily image-cleanup result.

## Constrained operations

Members of `moon-service-ops` may run exactly these commands:

```bash
sudo moon-service-control status
sudo moon-service-control revision
sudo moon-service-control logs
sudo moon-service-control restart
sudo moon-service-control rollback
sudo moon-service-control resume
```

- `status` shows container/timer state, incomplete host remediation, plus
  current, previous, rejected, last-result, and latest GitHub-report identities.
  It does not print application or GitHub App secrets.
- `revision` returns provider-independent `/readyz` JSON.
- `logs` returns the latest 200 timestamped container lines.
- `restart` recreates only the recorded current digest and rechecks readiness.
- `rollback` starts and verifies the previous known-good digest, swaps current
  and previous, and places automatic updates on hold. This prevents the next
  poll from immediately undoing an intentional rollback.
- `resume` clears the manual hold and any rejected candidate, then immediately
  runs normal discovery. Investigate before using it.

Timer update/cleanup runs skip successfully when another operation holds the
deployment lock. Interactive restart/rollback/resume waits up to 30 seconds and
returns a nonzero error if the lock remains busy; it never reports a skipped
operator action as successful.

The dedicated operator has no Docker or sudo group and cannot pass arbitrary
arguments through these wrappers. The bootstrap administrator can still use
Docker and systemd directly for provisioning and exceptional recovery; do not
use that account for routine status/log/restart/rollback work.

## Storage and logs

Compose and Docker use the `local` logging driver with three 10 MB files. The
system journal is capped at 256 MB persistent and 64 MB volatile storage, with
a seven-day retention ceiling. These journald limits apply to the dedicated Pi,
not just Moon Service.

The deployer retains current and previous Moon Service digest references.
`moon-service-cleanup.timer` runs daily, removes older repository digest
references, and prunes dangling layers older than seven days. It does not run
`docker system prune` and does not remove volumes; this deployment has no
application volume or database.

Monitor SD-card headroom periodically:

```bash
df -h /
sudo docker system df
journalctl --disk-usage
```

## Host package maintenance

The role uses package state `present`; a provisioning rerun does not silently
upgrade Docker, Tailscale, or the operating system. In a planned maintenance
window, the bootstrap administrator should review and apply Trixie updates,
then re-run provisioning and the host verification:

```bash
sudo apt update
apt list --upgradable
sudo apt upgrade
sudo reboot
```

Do not perform an unattended distribution-release upgrade: the role fails
closed outside Debian 13/Trixie. Application revisions follow the separate
digest-pinned automatic deployment path and do not depend on host package
upgrades.

## Recovery

### Power loss or reboot

Docker is enabled at boot and Compose uses `restart: unless-stopped`.
`moon-service-deploy.service` is also enabled at boot. It first reconciles
`current.env` from the local image store, then checks GHCR. If the current image
cannot become ready, it tries `previous.env`, records the failed current digest
as rejected, and continues on the previous revision.

### Bad image

Automatic candidate verification failure restores current and records the
candidate in `rejected.env`. Inspect its rejection stage and time, `status`, and
`logs`. Do not use `resume` until the failure was host-specific or a corrected
image has been published. A corrected new digest needs no operator action.

### Bad local configuration

Fix `/etc/moon-service/application.env`, then run `restart`. If the same image
was rejected solely because of that configuration, run `resume` after the
restart/configuration check. Re-running Ansible intentionally does not replace
the local file.

### Funnel or public-route failure

Withdraw Funnel first with `sudo tailscale funnel --https=443 off`, then require
`sudo tailscale funnel status` to show no public handler. Confirm local service
health with `sudo moon-service-control revision` and inspect only the needed
bounded logs. Restore the exact HTTPS-to-loopback handler only after the cause
is understood and the hosted service is healthy. Do not use `tailscale logout`
as an incident switch.

### Corrupt deployment state

The updater fails closed when `current.env` exists but has an invalid
repository, digest, or revision. A full administrator should copy
`/var/lib/moon-service` off-host for diagnosis, compare the container image and
GHCR identity, then repair or remove only the corrupt state. The updater will
not silently turn a moving tag into current state.

### SD-card replacement

There is no durable application data in this deployment. Flash a fresh
supported image, restore SSH/sudo access, rerun Ansible from the controller,
restore the admin token and optional private GHCR credential from their
off-host stores, and allow the Pi to pull the current published digest. Verify
local readiness, interactively re-enroll Tailscale if needed, and restore only
the exact HTTPS `443` to `http://127.0.0.1:8080` Funnel handler. Record the
previously deployed digest off-host if reproducing that exact version may
matter. Process-local caches and provider counters restart empty by design.

## Local verification of repository changes

The deployment transition tests use fake Docker/registry/readiness commands;
they do not contact GHCR or a Pi:

```bash
python3 -m unittest discover -s deployment/raspberry-pi/tests -v
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-deploy
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-control
git diff --check
```

When Ansible is available, also run:

```bash
(cd deployment/raspberry-pi && ansible-playbook site.yml --syntax-check)
(cd deployment/raspberry-pi && ansible-playbook \
  --inventory localhost, --connection local tests/memory-cgroup.runtime.yml)
```

Do not run the playbook against the physical Pi merely as a repository test.
The first live run installs packages, writes host configuration, enables
services/timers, and may pull/start the application.

## Package-source references

- [Install Docker Engine on Debian](https://docs.docker.com/engine/install/debian/)
- [Tailscale packages for Debian Trixie](https://pkgs.tailscale.com/stable/#debian-trixie)
- [Tailscale Funnel requirements](https://tailscale.com/docs/features/tailscale-funnel)
- [Tailscale Funnel CLI](https://tailscale.com/docs/reference/tailscale-cli/funnel)
- [Tailscale interactive enrollment CLI](https://tailscale.com/docs/reference/tailscale-cli/up)
- [Docker local logging driver](https://docs.docker.com/engine/logging/drivers/local/)
- [Raspberry Pi kernel command line](https://www.raspberrypi.com/documentation/computers/config_txt.html#kernel-command-line-cmdlinetxt)
- [Raspberry Pi memory-cgroup override](https://github.com/raspberrypi/linux/issues/6980#issuecomment-3149752155)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)
- [Authenticate to GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
