# Raspberry Pi Deployment

This directory implements the Raspberry Pi host from
[#95](https://github.com/rapucha/moon-service/issues/95), exact deployment
acknowledgement from [#107](https://github.com/rapucha/moon-service/issues/107),
and the tester-alpha public boundary from
[#97](https://github.com/rapucha/moon-service/issues/97). The Pi pulls an
already-tested ARM64 image from GHCR; it never builds Java or container images.

The application keeps one exact loopback or trusted-LAN listener. A separate
nginx proxy listens only on `127.0.0.1:18080`, accepts Tailscale's PROXY
protocol, and enforces the public route, rate, connection, request, timeout,
header, and logging boundaries. Tailscale Funnel is an explicit, reversible
operator action after provisioning. No router port is opened.

## What provisioning installs

The Ansible role:

- accepts only Debian 13 Trixie on ARM64;
- persists Raspberry Pi's supported `cgroup_enable=memory` kernel argument and
  performs a controlled reboot when the active kernel still lacks that
  controller;
- installs Docker Engine, Buildx, and the Compose plugin from Docker's official
  Debian ARM64 repository;
- installs Tailscale from its official Trixie repository, starts `tailscaled`,
  and deliberately leaves the host unenrolled and Funnel disabled;
- installs nginx without its wildcard port-80 site, then requires the exact
  loopback public-proxy listener before completing provisioning;
- applies per-client and aggregate ingress limits plus a latched automatic
  circuit breaker for distributed or sustained abuse;
- queues minimal operator incident mail through an explicitly accepted
  plaintext, IP-authorized ISP SMTP relay and blocks Docker containers from
  using SMTP ports;
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
10. A Tailscale tailnet where the bootstrap administrator can enroll this host,
    enable HTTPS/Funnel for the node, and remove a stale replacement node during
    SD-card recovery. Do not put a reusable Tailscale auth key in inventory.
11. For local circuit-breaker notification, the ISP SMTP hostname, port,
    envelope sender, and one operator recipient. This deployment deliberately
    supports the current IP-authorized relay with neither AUTH nor STARTTLS;
    notification contents therefore contain no request or client data.
12. After the public URL exists, set repository variable
    `MOON_PUBLIC_BASE_URL` to its HTTPS origin so the independent scheduled
    `/readyz` check can detect a home-path outage.

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

Configure the operator incident relay only as one complete block in ignored
`inventory.yml`:

```yaml
moon_service_public_smtp_enabled: true
moon_service_public_smtp_allow_plaintext: true
moon_service_public_smtp_host: smtp.provider.example
moon_service_public_smtp_port: 25
moon_service_public_smtp_sender: moon-service@example.test
moon_service_public_smtp_recipient: operator@example.test
```

The explicit plaintext flag records the accepted ISP boundary; the notifier
never attempts AUTH or STARTTLS. Ansible rejects partial configuration and
renders the envelope into root-only `public-ingress.env`. It is operational
mail to one maintainer, not the deferred product email-alert feature. The app
container does not receive these values. SMTP sender/recipient and the fixed
operational metadata can be observed or modified in transit. Treat mail as a
prompt to inspect local status, never as authoritative state.

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

Provisioning installs the package and starts `tailscaled`, but deliberately
leaves the host unenrolled and Funnel off. Do not add a reusable Tailscale auth
key to inventory. Existing MikroTik WireGuard remains the independent remote-
administration path.

The implemented traffic shape is:

```text
trusted LAN -> exact primary-LAN-IPv4:8080 -> Spring Boot container
Internet -> Funnel HTTPS/PROXY v2 -> 127.0.0.1:18080 nginx -> same LAN listener
```

nginx trusts PROXY client identity only from a loopback connection made by
`tailscaled`. It uses that source address in an in-memory per-client limit, but
clears `Forwarded`, `X-Forwarded-For`, and `X-Real-IP` before proxying. Ordinary
access logging is disabled. A rate rejection emits only the fixed
`rate-limited` journal event used by the watchdog—never a query, URI, client
address, header, or user agent.

The exact tester-alpha public surface is `/`, `/search`, `/about`, `/readyz`,
`GET /api/opportunities`, and the assets those pages currently load. nginx
returns an edge response for every other route. In particular, it never proxies
`/admin/**`, fixture-only `POST /api/opportunities/search`, `/healthz`, raw HTML
files, actuator-style paths, or newly added routes until the allowlist changes
deliberately. The Spring admin token remains useful on the trusted LAN but
cannot make an admin route public.

Current edge defaults are 300 accepted requests/minute aggregate, 1 API
request/minute aggregate with burst 10, 1 API request/minute per PROXY client
with burst 3, 32 total connections, and 8 connections per client. At the
sustained aggregate ceiling, a lookup can make both geocoding and weather calls
and retry each once while remaining below the provider's published 10,000-call
daily allowance, conservatively treating it as shared and leaving headroom for
trusted-LAN/manual work; caches normally reduce calls further.
Request/body/header sizes and client/upstream timeouts are bounded. A strict
CSP, frame denial, no-sniff, referrer, permissions, cross-origin, and HSTS
headers are added even on error responses. One hundred limiter rejections in a
rolling 60-second window trigger the latched circuit breaker; tune these values
through inventory only after observing real tester traffic and provider
counters.

This protects the app, provider quota, and accepted response bandwidth; it is
not volumetric DDoS protection for the home connection. Funnel avoids a router
port forward and hides the home's public address, but request bytes still reach
`tailscaled` before nginx can reject them locally. The automated response is
therefore deliberately availability-sacrificing: once rejection volume crosses
the threshold, the tester alpha is taken offline and stays off for inspection.

The GitHub deployment callback remains outbound and local-health-based. Funnel
state therefore cannot make a healthy image deployment appear successful or
force a rollback.

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
tailnet. Funnel does not publish reporter files or credentials.

### First enrollment and public activation

Provisioning is safe to run before Tailscale login: nginx and both watchdog
timers start locally, while `moon-service-funnel.service` remains disabled and
has no public listener. As the bootstrap administrator, enroll the dedicated
host interactively and follow the browser URL printed by Tailscale:

```bash
sudo tailscale up --hostname=moon-service
sudo tailscale status
```

Do not continue until `tailscale status --json` reports the node online with a
DNS name, the LAN revision is healthy, nginx configuration passes, and the
operator SMTP block is installed through Ansible. `public-notify-test` is an
optional standalone diagnostic; `public-on` always repeats a live relay test
before it can activate the exact foreground Funnel service:

```bash
sudo moon-service-control public-notify-test
sudo moon-service-control public-on
sudo moon-service-control public-status
sudo tailscale funnel status
```

`public-on` refuses to proceed without configured incident mail accepted by the
relay during that activation and reserved queue capacity for a later critical
incident. It checks the LAN app, the PROXY-protocol nginx path, Tailscale
enrollment, and the exact Funnel target before recording success. It then
queues a restoration/activation notice. The public command
uses TLS-terminated TCP plus PROXY protocol v2 on Funnel port 443; the
foreground systemd session is the lifetime of exposure.

Record the resulting `https://...ts.net` origin in repository variable
`MOON_PUBLIC_BASE_URL`, without a path or trailing credentials. The scheduled
`Check public uptime` workflow calls only `/readyz`, makes three bounded
attempts, and validates `status: ok` plus a 40-hex revision. It never consumes
weather or geocoding quota. GitHub's workflow failure is the independent
off-home outage signal; the ISP relay cannot report a home WAN failure. The
scheduled job remains skipped while the repository variable is absent, so it
does not report an outage before deliberate first activation.

### Public kill switch and operator mail

The manual kill switch is:

```bash
sudo moon-service-control public-off
```

It removes volatile runtime authorization first and stops the loopback nginx
proxy, which immediately closes already-accepted HTTP keepalive flows. It then
disables and stops the foreground Funnel unit. Only after those shutdown
attempts does it update SD-backed marker/transition state, so slow or read-only
persistent storage cannot delay the kill switch. Closing the original
foreground CLI session
is what removes its session-scoped route; a separate
`tailscale funnel ... off` command cannot address that opaque foreground
session. The control verifies that no Funnel route remains. On this dedicated
node it falls back to `tailscale funnel reset`; if exposure still cannot be
disproved, it disables and stops `tailscaled` so a reboot cannot restore unknown
configuration. Docker, the trusted-LAN application listener, the deployment
timer, and outbound GitHub reporting remain available. `public-on` validates
and starts nginx again. A reboot may also start its loopback-only listener, but
an ordinary Ansible rerun does not re-enable a tripped Funnel. Recovery is the
explicit `public-on` command after investigation.

If malformed configuration prevents the normal manual `public-off` path from
starting, the constrained wrapper automatically invokes the same
configuration-independent emergency shutdown used by watchdog `OnFailure`.

The watchdog checks the fixed nginx limiter events every 15 seconds. At 100
rejections within 60 seconds it invokes the same kill path once and queues a
`circuit_open` notification after shutdown verification. A watchdog state or
Funnel-state disagreement, stale SMTP preflight, stopped notification timer, or
failed container-SMTP firewall reconciliation also fails closed. There is no
traffic-based automatic restart.

The watchdog lock and volatile authorization live under `/run/moon-service`,
not on the SD-backed state path. If persistent state becomes read-only, shutdown
still removes runtime authorization and stops nginx/Funnel before reporting the
state-write failure. If malformed configuration, a missing executable, or a
systemd sandbox/start error prevents the watchdog command from running, its
configuration-independent `OnFailure` unit stops nginx, resets Funnel, and
disables `tailscaled` as the final availability-sacrificing fallback.
Checks defer successfully while an operator control transaction owns that lock;
an emergency shutdown waits for the transaction before acting, so it cannot race
an in-progress activation and then be silently undone.

The current ISP relay authenticates only by the home's public source IP and
offers neither AUTH nor STARTTLS. The accepted plaintext message contains only
event/reason categories, UTC time, rejection count, deployed revision, and
fixed recovery commands. It contains no query, request body, client address, or
user agent. Failed delivery stays in a root-only queue and retries every five
minutes; it never delays or reverses shutdown. A DOCKER-USER rule rejects
container traffic to ports 25, 465, 587, and any separately configured relay
port while leaving host-originated notification traffic available. The
watchdog reasserts that rule on every pass. Docker IPv6 networking is explicitly
disabled; equivalent IPv6 bridge rules are still applied when that chain is
available. The Funnel service is bound to the firewall service, so a Docker or
firewall stop removes volatile authorization and stops public exposure.

Relay acceptance does not prove inbox delivery. Circuit-open messages have
reserved queue capacity and priority over routine transition mail, malformed
queue entries are quarantined, and every retry keeps a stable `Message-ID`.
Delivery is at-least-once: a process loss after SMTP acceptance but before queue
removal can produce a duplicate. Confirm receipt during physical acceptance and
verify every incident against `public-status`.

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

The public watchdog and notification timers must also be active even while
Funnel is disabled:

```bash
systemctl show moon-service-public-watchdog.timer moon-service-public-notify.timer \
  --property=ActiveState --property=NextElapseUSecMonotonic
sudo moon-service-control public-status
ss -ltn 'sport = :18080'
```

The only `:18080` listener must be `127.0.0.1`; port 80 must have no listener.
Before activation, public status must show a disabled marker, inactive Funnel
route, and active nginx/watchdog state.

`ActiveState=active` together with `NextElapseUSecMonotonic=infinity` is a
broken schedule, not a healthy timer. After an issue #107 provisioning run, the
deploy timer must be scheduled even when it was stopped and restarted in the
same boot.

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

After public activation, verify the intended path from a machine outside the
home LAN, replacing the origin exactly once:

```bash
PUBLIC_ORIGIN=https://REPLACE_WITH_FUNNEL_HOST.ts.net
curl --fail --silent --show-error "$PUBLIC_ORIGIN/"
curl --fail --silent --show-error "$PUBLIC_ORIGIN/readyz"
curl --fail --silent --show-error "$PUBLIC_ORIGIN/api/opportunities?q=Prague"
curl --head "$PUBLIC_ORIGIN/"
curl --include "$PUBLIC_ORIGIN/admin/status"
curl --include --request POST "$PUBLIC_ORIGIN/api/opportunities/search"
```

Home, assets, readiness, and the Prague lookup must succeed. The two private
routes must be rejected at nginx without reaching Spring, even if a valid admin
token is supplied. Inspect the home response for CSP, HSTS, frame denial,
no-sniff, referrer, permissions, and cross-origin headers. Confirm SSH, Docker,
port 8080, port 18080, and unrelated host services are not reachable through
the Funnel hostname.

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
2. Run provisioning once and observe the immediate catch-up deployment when it
   repairs scheduling; otherwise wait for the first timer attempt.
3. Verify `status`, `/readyz`, the exact digest/revision, ARM64 platform,
   runtime UID/GID, and exact memory limit before making a live opportunity
   request.
4. Run the same playbook again. Review the recap and investigate unexpected
   changes; the host and application configuration should remain intact.
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
9. Configure incident SMTP, run `public-notify-test`, enroll Tailscale, announce
   the public state change, and run `public-on`. Verify the exact allowed and
   denied routes above, including visible Open-Meteo/GeoNames attribution. Both
   the standalone test and `public-on` preflight must be accepted by the relay;
   confirm at least one message actually reaches the operator inbox.
10. Send a controlled repeated burst through the public API. Confirm nginx
    returns `429` before provider use grows materially, the watchdog disables
    Funnel after the configured rejection threshold, one SMTP notice arrives,
    and LAN readiness remains healthy. Reboot once while latched off and verify
    it stays off; restore explicitly with `public-on`.
11. Set `MOON_PUBLIC_BASE_URL`, run the uptime workflow manually, then disable
    Funnel long enough to prove a scheduled check fails externally. Restore the
    alpha and confirm the next check succeeds.
12. Publish a later healthy `main` revision and reconfirm that public ingress
    serves that revision while the exact GitHub Deployment callback still
    completes independently.

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
- `last-github-report.env`: latest callback result, status, exact identity, and
  matching Deployment IDs, without tokens or private-key material;
- `automatic-updates-held`: present after a manual rollback;
- `memory-limit-remediation`: prevents automation from being re-enabled after
  an interrupted or failed host memory-limit repair;
- `host-configuration-remediation`: prevents automation from being re-enabled
  after an interrupted or failed Compose/listener reconciliation;
- `public-ingress-enabled`: present only while explicit public activation is
  fully committed for the current boot; the volatile
  `/run/moon-service/public-authorized` file is also required to start Funnel,
  and the breaker removes both before network shutdown;
- `public-ingress-last.env`: latest enabled/disabled transition, fixed reason,
  UTC time, and limiter rejection count;
- `public-notification-tested`: hash and UTC time proving the current SMTP
  envelope's latest direct test was accepted by the configured relay;
- `public-notifications/`: root-only bounded incident messages waiting for the
  IP-authorized SMTP relay;
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
sudo moon-service-control public-status
sudo moon-service-control public-on
sudo moon-service-control public-off
sudo moon-service-control public-notify-test
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
- `public-status` reports the latch, Funnel/nginx/watchdog and notification-timer
  state, active limit values, pending-notification count, and last transition
  without revealing the SMTP envelope or visitor data.
- `public-off` is idempotent and persistently removes public exposure while
  leaving LAN service and deployment automation running.
- `public-on` performs all readiness/Tailscale/notification preflights and is
  the only normal way to restore a tripped Funnel.
- `public-notify-test` sends one harmless message directly as a standalone relay
  diagnostic. It does not queue the test and does not replace the live test that
  `public-on` performs for every activation.

Timer update/cleanup runs skip successfully when another operation holds the
deployment lock. Interactive restart/rollback/resume uses the deployment lock;
public operations use a separate root-owned ingress lock. They wait for their
respective lock and return nonzero rather than report a skipped operator action
as successful.

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

Funnel deliberately does not restart automatically after a reboot. Its unit
requires `/run/moon-service/public-authorized`, which only `public-on` creates
after live preflight and which reboot always clears. The persistent marker alone
cannot authorize exposure; the watchdog reconciles any stale marker back to the
disabled state. nginx, the watchdog, and notification retry timer still start;
they expose only a loopback listener. After checking status and WAN/provider
conditions, restore the tester edge explicitly with `public-on`.

### Public ingress or notification failure

Run `public-status` first. If state is inconsistent or hostile traffic is still
arriving, run `public-off`; it is safe when already disabled. Inspect
`journalctl -u moon-service-funnel.service`,
`journalctl -u moon-service-public-watchdog.service`, and
`journalctl -u moon-service-public-notify.service`. Correct inventory and rerun
Ansible for nginx, limit, or SMTP-envelope drift. `public-notify-test` can
diagnose the relay separately; `public-on` performs its own mandatory live test.

Treat every Ansible provisioning run as a public maintenance window. If the
control is already installed, the role latches ingress off before any host
mutation; it repeats the latch after changed public files and again after daemon
reconciliation. This prevents a failed or interrupted run from leaving Funnel
live under a partially changed rate, breaker, route, port, unit, or SMTP
contract. Verify the converged settings and restore explicitly with
`public-on`, which sends a fresh relay test.

If the ISP changes relay behavior or the source-IP authorization fails, queued
mail remains local and public activation refuses when SMTP is disabled. A WAN
outage may prevent both Funnel and SMTP; the external GitHub uptime failure is
the independent signal. Do not weaken the no-AUTH/no-STARTTLS configuration by
silently sending visitor details over plaintext.

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
off-host stores, and allow the Pi to pull the current published digest. Restore
the SMTP inventory block, but leave Funnel off. Remove or rename the stale
Tailscale device deliberately, enroll the replacement, verify the resulting
DNS name, and update `MOON_PUBLIC_BASE_URL` plus tester instructions if that
name changed. Run all preflights and the notification test before `public-on`.
Record the previously deployed digest off-host if reproducing that exact
version may matter. Process-local caches and provider counters restart empty by
design.

## Local verification of repository changes

The deployment transition tests use fake Docker/registry/readiness commands;
they do not contact GHCR or a Pi:

```bash
python3 -m unittest discover -s deployment/raspberry-pi/tests -v
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-deploy
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-control
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-public
bash -n deployment/raspberry-pi/roles/moon_service_host/files/moon-service-docker-firewall
git diff --check
```

When Ansible is available, also run:

```bash
(cd deployment/raspberry-pi && ansible-playbook site.yml --syntax-check)
(cd deployment/raspberry-pi && ansible-playbook \
  --inventory localhost, --connection local tests/memory-cgroup.runtime.yml)
(cd deployment/raspberry-pi && ansible-playbook \
  --inventory localhost, --connection local tests/bind-address.runtime.yml)
(cd deployment/raspberry-pi && ansible-playbook \
  --inventory localhost, --connection local tests/public-ingress.runtime.yml)
```

Do not run the playbook against the physical Pi merely as a repository test.
The first live run installs packages, writes host configuration, enables
services/timers, and may pull/start the application.

## Package-source references

- [Install Docker Engine on Debian](https://docs.docker.com/engine/install/debian/)
- [Tailscale packages for Debian Trixie](https://pkgs.tailscale.com/stable/#debian-trixie)
- [Tailscale Funnel](https://tailscale.com/docs/features/tailscale-funnel)
- [`tailscale funnel` CLI](https://tailscale.com/docs/reference/tailscale-cli/funnel)
- [nginx request limiting](https://nginx.org/en/docs/http/ngx_http_limit_req_module.html)
- [nginx PROXY/real client address](https://nginx.org/en/docs/http/ngx_http_realip_module.html)
- [nginx conditional syslog access logging](https://nginx.org/en/docs/http/ngx_http_log_module.html)
- [Docker local logging driver](https://docs.docker.com/engine/logging/drivers/local/)
- [Raspberry Pi kernel command line](https://www.raspberrypi.com/documentation/computers/config_txt.html#kernel-command-line-cmdlinetxt)
- [Raspberry Pi memory-cgroup override](https://github.com/raspberrypi/linux/issues/6980#issuecomment-3149752155)
- [Docker resource constraints](https://docs.docker.com/engine/containers/resource_constraints/)
- [Authenticate to GitHub Container Registry](https://docs.github.com/en/packages/working-with-a-github-packages-registry/working-with-the-container-registry)
