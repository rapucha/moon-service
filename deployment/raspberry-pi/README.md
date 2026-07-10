# Raspberry Pi Deployment

This directory implements issue
[#95](https://github.com/rapucha/moon-service/issues/95): a reproducible,
single-instance Docker Compose host on Raspberry Pi OS Lite 64-bit (Debian 13
Trixie). The Pi pulls the already-tested ARM64 image from GHCR. It never builds
Java or container images.

This is the private deployment layer. It binds the application to
`127.0.0.1:8080` by default and supports an explicit primary-IPv4 listener for
a trusted home LAN. It opens no router port, does not enroll Tailscale, and
does not configure Funnel. Public exposure remains issue
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
- keeps the listener on loopback unless ignored host inventory explicitly
  selects that host's active primary IPv4 address;
- runs the application as the image's non-root UID/GID `10001` with no Linux
  capabilities, a read-only root filesystem, bounded memory/CPU/PIDs, and a
  small temporary filesystem;
- caps Docker logs and the system journal for a 32 GB SD card;
- polls the tested GHCR `main` image every two minutes and starts the recorded
  digest on boot;
- keeps a current and previous known-good digest, verifies `/readyz` and its Git
  revision, rolls back unhealthy candidates, and serializes operations with
  `flock`;
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
   Tailscale, GHCR, and Open-Meteo endpoints.
7. Ansible Core 2.21.1 on the controller, matching the CI pin in
   `ci-requirements.txt`. No Ansible software is installed on the Pi; the Pi
   only needs its default Python 3 and SSH server.
8. A GHCR visibility decision:
   - a public package needs no registry credential;
   - a private package needs a separate read-only package token installed only
     in root's Docker credential store on the Pi.

Do not put the host address, SSH key path, GHCR token, Tailscale key, admin
token, or another private-network identifier in a tracked file.

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

The safe listener default is `127.0.0.1`. For direct access from a trusted LAN,
add the Pi's active primary IPv4 address to ignored `inventory.yml`:

```yaml
moon_service_bind_address: REPLACE_WITH_PI_LAN_ADDRESS
```

The role rejects wildcard and secondary-interface listeners. Keep the address
stable with a DHCP reservation or static network configuration; it should
normally match the address used by `ansible_host`. This opt-in changes only
the Docker host binding. It does not configure a firewall, router forwarding,
Tailscale, Funnel, TLS, or public access.

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

## Provision the Pi

The first actual host mutation is intentionally a coordinated operator step:

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

### Optional admin endpoint

The seeded environment enables live Open-Meteo geocoding and weather. It leaves
`/admin/**` disabled. To enable it privately, create a high-entropy token and
edit the host-only file as the bootstrap administrator:

```bash
openssl rand -hex 32
sudoedit /etc/moon-service/application.env
sudo moon-service-control restart
```

Add `MOON_ADMIN_TOKEN=<generated-value>` locally. Store the token in the chosen
off-host secret store; never commit it or pass it in a URL. Provisioning does
not overwrite this file on later runs.

### Tailscale boundary

Provisioning installs the package and enables `tailscaled` only. Do not add a
Tailscale auth key to inventory. Tailnet enrollment and Funnel configuration
are explicit bootstrap-administrator actions for #97. Existing MikroTik
WireGuard remains the remote-administration path and does not need any software
on this Pi.

## Host verification

After provisioning, open a new session as the dedicated operator and check the
deployment:

```bash
ssh moonops@REPLACE_WITH_SSH_HOST
sudo moon-service-control status
sudo moon-service-control revision
```

With the default loopback listener, inspect it through SSH without changing
the router:

```bash
ssh -L 8080:127.0.0.1:8080 moonops@REPLACE_WITH_SSH_HOST
```

Then, from the controller:

```bash
curl --fail http://127.0.0.1:8080/readyz
curl --fail 'http://127.0.0.1:8080/api/opportunities?q=Prague'
```

With the trusted-LAN inventory override, access the Pi directly instead:

```bash
curl --fail http://REPLACE_WITH_PI_LAN_ADDRESS:8080/readyz
curl --fail 'http://REPLACE_WITH_PI_LAN_ADDRESS:8080/api/opportunities?q=Prague'
```

Docker must publish exactly the configured primary IPv4 address, never
`0.0.0.0`; the home router must continue to have no forwarding rule for this
port.

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
its configured loopback or trusted-LAN boundary:

1. Run the fact and SSH preflight commands before the playbook.
2. Run provisioning once and wait for the first timer attempt.
3. Verify `status`, `/readyz`, the exact digest/revision, ARM64 platform,
   runtime UID/GID, and exact memory limit before making a live opportunity
   request.
4. Run the same playbook again. Review the recap and investigate unexpected
   changes; the host and application configuration should remain intact.
5. Reboot the Pi, then verify the recorded digest starts and becomes ready
   without GHCR being part of the startup path.
6. After a later healthy `main` publication, confirm the Pi advances within
   five minutes and moves the old identity to `previous.env`.
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
8. restores the unchanged current state when the candidate fails.

The systemd attempt has a ten-minute outer timeout, leaving bounded room for a
slow ARM64 pull plus both the candidate and rollback readiness windows. If
systemd must terminate an exceptionally stuck attempt, candidate state is never
committed as current; the next timer run reconciles the recorded known-good
digest and Compose configuration before contacting GHCR.

A failed candidate is recorded in `rejected.env`. Later timer runs do not
reintroduce two-minute outages by retrying the same bad digest. A newly
published digest is tried normally. After correcting a host-only problem, use
`resume` to clear the rejection and retry immediately.

If GHCR or the network is unavailable, discovery/pull is deferred only after
the last recorded local digest is reconciled. The running service does not
depend on registry availability.

State is under `/var/lib/moon-service`:

- `current.env`: active known-good digest and revision;
- `previous.env`: one rollback generation;
- `rejected.env`: candidate that failed readiness;
- `last-result.env`: latest update/control outcome;
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

- `status` shows container/timer state, incomplete host remediation,
  plus current, previous, rejected, and last-result identities. It does not
  print application secrets.
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

Automatic readiness failure restores current and records the candidate in
`rejected.env`. Inspect `status` and `logs`. Do not use `resume` until the
failure was host-specific or a corrected image has been published. A corrected
new digest needs no operator action.

### Bad local configuration

Fix `/etc/moon-service/application.env`, then run `restart`. If the same image
was rejected solely because of that configuration, run `resume` after the
restart/configuration check. Re-running Ansible intentionally does not replace
the local file.

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
off-host stores, and allow the Pi to pull the current published digest. Record
the previously deployed digest off-host if reproducing that exact version may
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
- [Docker local logging driver](https://docs.docker.com/engine/logging/drivers/local/)
- [Raspberry Pi kernel command line](https://www.raspberrypi.com/documentation/computers/config_txt.html#kernel-command-line-cmdlinetxt)
- [Raspberry Pi memory-cgroup override](https://github.com/raspberrypi/linux/issues/6980#issuecomment-3149752155)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)
- [Authenticate to GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
