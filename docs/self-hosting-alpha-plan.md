# Self-Hosting Alpha Plan

## Purpose

This plan captures the hosting boundary for a small Moon Service alpha on home
hardware. The current implementation is deliberately smaller than the earlier
k3s design: one Docker Compose application on a dedicated Raspberry Pi 4,
provisioned by Ansible and updated from tested GHCR images. The operational
runbook is [`deployment/raspberry-pi/README.md`](../deployment/raspberry-pi/README.md).

The k3s/NFS sections remain as future design notes. They are not the current
tester-alpha runtime and are explicitly out of scope for infrastructure
milestone [#93](https://github.com/rapucha/moon-service/issues/93).

The current constraint is that SSDs are not available. The assumed node storage
is a 32 GB WD Purple SD card, with roughly 64-80 GB of storage available through
NFS from another server if durable storage is needed later. The Pi should be
treated as replaceable infrastructure with explicit off-card secret recovery.
A database is expected later, but should not be introduced
only to make the first public lookup, RSS, or calendar behavior deployable.

The original hosting boundary was tracked in
[#19](https://github.com/rapucha/moon-service/issues/19). The current delivery
is tracked by [#93](https://github.com/rapucha/moon-service/issues/93). Its host
provisioning and physical-deployment foundations are complete under
[#95](https://github.com/rapucha/moon-service/issues/95) and
[#107](https://github.com/rapucha/moon-service/issues/107); the public edge and
final tester-alpha acceptance are tracked by
[#97](https://github.com/rapucha/moon-service/issues/97).

## Decision Snapshot

- Use home hosting only for alpha or beta, not as the long-term production
  assumption.
- Use Raspberry Pi OS Lite 64-bit based on Debian Trixie and Docker Compose for
  the current alpha; do not introduce k3s yet.
- Pull the tested GHCR multi-architecture image by immutable index digest. Keep
  one current and one previous known-good image and automatically roll back a
  candidate that fails revision-aware readiness.
- Bind the private deployment to loopback by default. A host-local inventory
  opt-in may bind the Pi's primary IPv4 address for direct access from the
  trusted home LAN. Use SSH or the existing MikroTik WireGuard path for
  administration; do not forward a router port.
- Treat a successful image publication and a successful physical deployment as
  separate states. The Pi must report the exact healthy revision and immutable
  digest back to its GitHub Deployment before the main workflow calls the
  deployment successful.
- Install Tailscale without credentials in tracked provisioning. The #97
  implementation installs a fail-closed public edge without enrolling the node
  or enabling Funnel: Tailscale Funnel sends PROXY protocol v2 to nginx on the
  exact `127.0.0.1:18080` listener, and nginx proxies only allowlisted routes to
  the unchanged exact application listener. Public activation remains an
  explicit operator action after enrollment and physical checks.
- Keep one backend instance at first because provider counters and caches are
  currently process-local. nginx owns the first public request, connection,
  body, header, and timeout limits; add backend-owned limiting later only if a
  non-Funnel ingress or product contract requires it.
- Treat public alpha availability as expendable. A root-owned watchdog latches
  Funnel off after excessive limiter rejections or inconsistent Funnel state;
  only an explicit operator command may restore it.
- Use the home ISP's source-IP-authorized plaintext SMTP relay only for bounded
  operator messages from the host. It has no AUTH or STARTTLS and is not product
  email infrastructure. Keep the application container unable to reach SMTP.
- Check the public `/readyz` path independently from the Pi through GitHub
  Actions without consuming Open-Meteo quota. Host SMTP cannot be the only
  outage notification path because it is unavailable when the home WAN is
  down.
- Use the 32 GB SD card for Pi OS, Docker runtime state, two application image
  generations, and bounded local logs. Keep durable application data off it.
- Plan a small NFS-backed storage pool around 64-80 GB for alpha data that must
  survive Pod rescheduling or SD-card replacement.
- Avoid running Postgres on SD cards until there is a clear product need and a
  tested off-card backup/restore routine.
- Keep public RSS/Atom and `.ics` generation stateless or cache-backed at first.
  Add a database when private feeds, saved locations, alert subscriptions,
  durable counters, or durable cache state require it.

## Implemented Tester-Alpha Topology

```text
trusted-LAN browser
  -> exact primary-LAN-IPv4:8080
  -> one Docker Compose Spring Boot container
  -> Open-Meteo geocoding/weather over outbound HTTPS

Internet tester
  -> Tailscale Funnel HTTPS on the stable *.ts.net name
  -> TLS-terminated TCP with PROXY protocol v2
  -> nginx on exact 127.0.0.1:18080
  -> exact GET/HEAD route allowlist plus limits and security headers
  -> the same exact application listener

GitHub-hosted Actions
  -> tested AMD64/ARM64 image in GHCR
  -> serialized main promotion to an immutable index digest
  -> GitHub Deployment records the expected revision and digest

Raspberry Pi systemd timer
  -> resolves GHCR main as desired state without depending on GitHub API
  -> pulls the immutable main digest
  -> readiness/revision check
  -> commit current/previous state or restore current
  -> finds only matching Deployment payloads
  -> outbound HTTPS deployment status to GitHub when available

GitHub-hosted Actions
  -> waits for the matching Pi status with a bounded timeout

GitHub-hosted scheduled uptime check
  -> public Funnel /readyz only
  -> three bounded attempts, status=ok, and a 40-hex revision
```

The home router does not forward application, SSH, Docker, admin, database, or
other Pi ports. The Funnel URL is public ingress, not an invitation-only
authentication boundary. The repository implementation remains latched off
until the operator enrolls Tailscale, configures and tests operator SMTP, runs
the local readiness checks, and deliberately enables Funnel.

The callback path is independent of application ingress: the Pi initiates
outbound HTTPS to GitHub after checking Docker health, the recorded digest, and
the `/readyz` revision locally. LAN failure, Funnel configuration, or a public
DNS name is therefore not required for deployment acknowledgement. GHCR `main`
remains the host's desired-state source, so a GitHub App or Deployments API
outage cannot block an otherwise healthy local update; it instead makes the
external workflow time out without confirmation.

The public path is deliberately separate from the trusted-LAN listener:

```text
trusted LAN client -> exact primary-LAN-IPv4:8080 --------+
                                                          +-> one app container
Tailscale Funnel -> PROXY v2 -> 127.0.0.1:18080 nginx ----+
```

nginx accepts PROXY protocol only on loopback and trusts the supplied client
address only when the immediate peer is `127.0.0.1`. It discards Internet-
supplied forwarding headers before proxying, uses the PROXY address only for
edge per-client limits, and does not send it to Spring Boot. Neither nginx nor
the application binds a public or wildcard host address. Deployment
confirmation continues to use the local application endpoint rather than the
public Funnel URL.

The admin path remains private:

```text
GET /admin/status
```

The backend requires `X-Moon-Admin-Token` when admin routes are enabled, and the
Compose seed configuration leaves `/admin/**` disabled. Independently, nginx
returns `404` for `/admin/**`, `/healthz`, the direct fixture endpoint
`/api/opportunities/search`, raw HTML resource paths, and every unknown route.
The public readiness exception is the provider-free `/readyz` endpoint.

## Phase 0: Prove The Container Boundary

Goal: confirm the backend can run as a single container before adding cluster
complexity.

Implementation work:

- Keep using `backend/Dockerfile` as the package boundary.
- Keep using the opt-in containerized smoke check delivered by
  [#27](https://github.com/rapucha/moon-service/issues/27) after runtime image
  or startup changes.
- Define the required runtime environment variables:
  - `MOON_LOCATION_RESOLVER=open-meteo`
  - `MOON_WEATHER_PROVIDER=open-meteo`
  - `MOON_ADMIN_TOKEN=<secret>`
  - `MOON_BUILD_REVISION=<git-commit-sha>` (embedded by the image build)
  - optional `MOON_PROVIDER_QUOTAS_OPERATIONS_*` values when provider limits are
    known.
- Use unauthenticated `GET /healthz` for process liveness and `GET /readyz` for
  traffic readiness. Both are provider-independent and expose only status plus
  the public source revision.
- Keep the image's built-in readiness check and fixed non-root UID/GID `10001`.
- Send `SIGTERM` and allow more than the configured 30-second graceful-shutdown
  phase before forcing container termination.

Exit criteria:

- A backend image can start with live Open-Meteo configuration.
- A smoke check can call `GET /api/opportunities?q=Zakopane` or another real
  city through the container.
- Docker reports the container healthy from `/readyz`, and the reported
  revision matches the image revision.
- The runtime Java process is non-root and an in-flight request can complete
  during graceful shutdown.
- `/admin/status` works only with the configured admin token.

## Deferred Phase 1: SD-Card-Aware k3s Baseline

Goal: run k3s without pretending the SD card is durable server storage.

Recommended shape:

- Each Pi uses a 32 GB WD Purple SD card as node-local storage.
- One Raspberry Pi acts as the k3s server.
- Other Pis, if used, join as agents only.
- Use k3s's default embedded SQLite datastore for the single-server alpha.
- Do not enable embedded etcd HA on SD cards.
- Use NFS-backed storage from another server for alpha persistent volumes when
  persistence is unavoidable. Treat the NFS server as the durable-storage
  boundary, not the Pi SD cards.
- Keep cluster manifests, deployment notes, and secret recovery steps in Git or
  another off-card location.
- Keep application logs short-retention and reconstructible.
- Back up k3s state and any secrets off-card before calling the alpha reliable.
- Keep only required node ports open on the LAN. Do not expose k3s internals to
  the internet.

Why this is constrained:

- K3s officially recommends SSD storage where possible and warns that embedded
  etcd is write-intensive on Raspberry Pi or ARM devices.
- K3s's default embedded SQLite datastore is only for a single server.
- SD cards can still work for a small alpha if the operational model assumes
  rebuilds from source, manifests, and backups instead of node durability.

Exit criteria:

- A fresh Pi can be rebuilt from documented steps.
- Losing the SD card loses only rebuildable cluster state, not unrecoverable
  product data.
- The cluster has enough memory headroom for k3s, its selected ingress
  components, and one Java backend Pod.

Backup matrix before public alpha:

| State | Back up? | Notes |
| --- | --- | --- |
| Repo revision and image tag | Yes | Needed to rebuild the deployed version. |
| k3s manifests or deployment commands | Yes | Keep outside the SD card. |
| Runtime configuration and secret names | Yes | Do not commit secret values. |
| Admin token source | Yes | Store in the chosen secret manager or offline notes. |
| Tailscale node identity and Funnel configuration | Yes | Needed to restore public routing. |
| NFS export path and server configuration | Yes | Needed to remount alpha persistent volumes. |
| Process-local caches | No | Rebuildable from provider calls. |
| Process-local provider counters | No | Useful live visibility, not durable truth. |
| Disposable logs | No | Keep short retention unless debugging a specific issue. |
| Future Postgres data | Yes | Only after a restore drill exists. |

## Implemented Public Edge: Tailscale Funnel And Loopback nginx

Goal: expose only the public app surface and keep hostile traffic away from the
home network where possible.

Implemented shape:

- Funnel terminates public HTTPS on an allowed Funnel port and forwards
  TLS-terminated TCP with PROXY protocol v2 to `127.0.0.1:18080`.
- nginx has one loopback-only PROXY-protocol listener. It proxies to the exact
  configured application listener, so the trusted LAN path remains unchanged
  and no raw router port forward is required.
- The public surface allows only `GET`/`HEAD` for `/`, `/search`, `/about`,
  `/readyz`, `/api/opportunities`, and the exact static assets required by the
  browser. Feed and calendar routes are not implicitly public; they require a
  deliberate allowlist update when implemented.
- `/admin/**`, `/healthz`, `POST /api/opportunities/search`, raw HTML resource
  paths, unknown routes, and non-`GET`/`HEAD` methods remain outside the public
  contract.
- The edge applies baseline Content Security Policy, framing, referrer,
  permissions, MIME-sniffing, cross-origin, and HSTS headers.

Default ingress controls:

- All public traffic: 300 requests/minute with burst 40, 32 concurrent
  connections globally, and 8 concurrent connections per PROXY-protocol client.
- `/api/opportunities`: 1 request/minute globally with burst 10 and 1
  request/minute per client with burst 3. At the sustained global ceiling, each
  lookup can call geocoding and weather and retry both once while remaining
  below the provider's published 10,000-call daily allowance, conservatively
  treating it as shared and leaving headroom for trusted-LAN/manual work.
- Request bodies are capped at 1 KiB. Header buffers, client/header/body/send
  timeouts, keepalive use, proxy connection/send/read timeouts, and container
  CPU, memory, and PID use are all bounded.
- API request or connection rejection returns HTTP `429`, `Retry-After: 60`, and
  the documented `status: "rate_limited"` JSON shape. Upstream exhaustion still
  maps to `temporarily_unavailable`.
- Ordinary public access is not logged by nginx. Limiter rejections emit only a
  fixed `rate-limited` event without path, query, client address, headers, or
  user agent.
- These limits protect the application, provider quota, and accepted response
  bandwidth; they are not volumetric DDoS protection for the home connection.
  Funnel avoids a router port forward and hides the home's public address, but
  request bytes still reach `tailscaled` before local nginx can reject them.
  The latched breaker therefore takes the alpha offline quickly when rejection
  volume crosses the threshold, which is acceptable for this small test.

Circuit breaker and notification boundary:

- A root-owned watchdog runs every 15 seconds. At 100 limiter rejections in the
  preceding 60 seconds it removes volatile runtime authorization, stops the
  public transport, and only then updates the persistent enable marker/state so
  SD-card failure cannot delay shutdown. It also fails closed when
  enabled state disagrees with the actual Funnel route or it cannot verify the
  route or rejection counters.
- Its lock and volatile authorization use a dedicated `/run` directory, so a
  read-only persistent state path cannot prevent transport shutdown. A minimal
  configuration-independent systemd `OnFailure` action stops nginx/Funnel and
  disables `tailscaled` if the normal watchdog cannot start or parse config.
- Exact Funnel removal is verified. Failure escalates to `tailscale funnel
  reset`, then disabling and stopping `tailscaled` so the public path stays
  closed across reboot. The loopback nginx proxy is stopped first to close
  already-accepted keepalive flows; the trusted-LAN listener and application
  remain independent.
- The breaker stays latched off across service restarts and reboot. There is no
  automatic recovery loop; the operator investigates and uses the constrained
  `public-on` command explicitly.
- Successful exposure also requires volatile authorization in
  `/run/moon-service/public-authorized`, created
  only after activation preflight. Reboot clears it, so Funnel never comes back
  merely because a stale persistent marker or enabled unit survived; the
  operator checks conditions and runs `public-on` again.
- Public activation refuses to proceed unless operator SMTP is completely
  configured, a test message is accepted by the configured relay during that
  activation, and a local nginx/application/Tailscale preflight passes. Relay
  acceptance is not proof of inbox delivery; confirm receipt in physical
  acceptance. Activation also requires enough queue headroom to preserve the
  reserved circuit-open capacity.
- The current ISP relay authorizes the home connection's source IP and provides
  neither AUTH nor STARTTLS. Enabling it requires explicit plaintext opt-in.
  Messages contain only a fixed event/reason, UTC time, rejection count, and
  deployed revision—never request data or client identity. Failed delivery is
  queued root-only and retried; notification failure never prevents shutdown.
- A host firewall rejects SMTP ports from Docker bridges while leaving the
  root-owned host notifier's outbound connection available. The watchdog
  reasserts this rule, Docker IPv6 is explicitly disabled, and the standard
  global xtables lock remains shared with Docker and other firewall tooling.
- Circuit-open mail has reserved queue capacity and priority. Invalid queue
  entries are quarantined. Delivery is at-least-once with a stable `Message-ID`,
  so a process loss after relay acceptance can produce a harmless duplicate.

External availability:

- A scheduled GitHub Actions workflow checks the public `/readyz` every 15
  minutes. It makes up to three bounded HTTPS attempts and requires HTTP `200`,
  `status: "ok"`, and a 40-hex revision.
- The probe never calls geocoding or weather. Repository variable
  `MOON_PUBLIC_BASE_URL` holds only the public HTTPS origin, without credentials,
  a path, query, or fragment. The scheduled job is skipped until that variable
  is configured after deliberate first activation.
- GitHub workflow failure is the off-home outage signal. Host SMTP is for local
  transitions and cannot report a home-WAN outage, so the operator must keep
  GitHub Actions failure notifications enabled.

WAF stance:

- Do not start by tuning a local WAF on the Pis.
- Prefer simple edge bot controls, ingress rate limits, request bounds, and app
  validation first.
- Revisit WAF only if traffic shows repeated exploit attempts or the public
  input surface becomes more complex than city lookup, feeds, and calendar
  exports.

Remaining physical acceptance:

- Enroll the dedicated Pi in Tailscale without committing an auth key.
- Configure and test the operator relay, then deliberately enable Funnel.
- Verify the real public home, About, assets, representative Prague lookup,
  attribution, `/readyz`, security headers, and exact revision.
- Verify blocked routes, `429` behavior, automated breaker shutdown and email,
  external outage detection, preserved LAN access, and manual restoration.

## Phase 3: App-Level Abuse And Provider Protection

Goal: keep Open-Meteo usage, CPU, memory, and home bandwidth bounded.

The backend already has useful alpha primitives:

- Request logs avoid raw query strings by default.
- Runtime geocoding and weather caches are process-local.
- Concurrent identical cache misses share one upstream provider call inside one
  backend process.
- `/admin/status` exposes provider counters, cache stats, and quota windows.

Remaining application work:

- Add application-level rate limiting only if ingress-only limits become
  insufficient for a future non-Funnel path or backend-owned contract.
- Keep the ingress-owned `status: "rate_limited"` response aligned with the
  public API contract.
- Keep Open-Meteo quota limits configurable and visible in `/admin/status`.
- Keep current public rate-limit and breaker settings visible through the
  constrained host status command.
- Preserve the v0 rule that the browser does not call geocoding on every
  keystroke.
- Keep cache TTLs and maximum sizes visible in backend configuration.

Exit criteria:

- Repeated searches for the same city hit caches instead of increasing provider
  counters.
- Provider timeout, retry, and rate-limit counts are visible during a manual
  spike.
- Dependency failure returns `temporarily_unavailable`, not an empty opportunity
  list.

## Phase 4: RSS And Calendar Without A Database

Goal: ship low-friction feed/calendar behavior without forcing persistence too
early.

No database is required for these first cases:

- One-off `.ics` export for a single opportunity.
- Public RSS/Atom feed for a canonical public location.
- Public `.ics` calendar feed for a canonical public location.

The implementation should be deterministic:

- Use stable canonical location IDs.
- Generate stable event/feed IDs from location ID plus opportunity start time.
- Use HTTP cache headers to reduce repeated work.
- Recompute or process-cache public feeds from canonical inputs.
- Avoid private exact-coordinate feed URLs until the privacy and storage model is
  explicit.

Database triggers:

- Saved user locations.
- Private feed tokens or revocation.
- User-specific thresholds/preferences.
- Email alerts or alert delivery history.
- Durable provider counters across restarts.
- Durable/shared cache state across backend instances.
- Precomputed feed snapshots or job history.

Exit criteria:

- Public feeds and exports work for canonical locations without storing personal
  location preferences.
- Event IDs remain stable across backend restarts.
- A later database can replace cache/storage seams without changing public feed
  URLs.

## Phase 5: Future Database Boundary

Goal: make the eventual database boring, not urgent.

Expected future store:

- Postgres with migrations through Flyway or Liquibase.
- App-owned schema, separate from any k3s datastore.
- Logical backups tested before storing personal data or private feed tokens.

Do not use the application database as a shortcut for early deployment:

- Provider counters can remain process-local during a single-process alpha.
- Public feed/calendar outputs can be generated deterministically.
- Rebuildable cache state should not force a durable database.

If Postgres must run on the same SD-card-backed Pi later:

- Treat it as alpha-grade only.
- Store the data directory on the NFS-backed storage pool, not on the Pi SD
  card.
- Store only data that is backed up off-card and can tolerate restore lag.
- Keep write volume low.
- Prefer logical dumps to an off-card destination.
- Test restore before relying on alerts, private feed tokens, or saved
  preferences.
- Do not claim high availability.

If a separate server already owns the NFS storage, the cleaner future shape is
to run Postgres directly on that server and let the backend connect over the
LAN. Running Postgres in k3s with its data directory on NFS is an alpha
tradeoff, not the preferred durable database design.

Better future options when budget allows:

- Move Postgres to a small x86 host, NAS, or managed/free-tier provider.
- Keep k3s cluster state and application data separate.
- Move provider counters and rate limits to a durable/shared store only when
  multi-instance hosting makes process-local state misleading.

## Deferred Deployment Work

Do not add these until a follow-up issue makes them necessary:

- Production Helm chart or Kustomize layout.
- Postgres, Flyway/Liquibase, or database credentials.
- Redis or distributed rate limiting.
- Local WAF rules.
- Multi-node HA control plane.
- GitOps automation.
- Product email alerts or general-purpose alert delivery. The root-only,
  plaintext operator notifier for public-ingress transitions is already part of
  the #97 safety boundary and is not a user-notification system.

## Related Issues

- [#93](https://github.com/rapucha/moon-service/issues/93): current Docker
  Compose tester-alpha infrastructure milestone.
- [#95](https://github.com/rapucha/moon-service/issues/95): Raspberry Pi
  provisioning and health-checked host-pull deployment.
- [#107](https://github.com/rapucha/moon-service/issues/107): reliable timer
  rearming and exact physical-deployment confirmation through GitHub.
- [#97](https://github.com/rapucha/moon-service/issues/97): public HTTPS
  exposure and hardening through Tailscale Funnel.
- [#8](https://github.com/rapucha/moon-service/issues/8): add basic
  provider-call scalability protections.
- [#9](https://github.com/rapucha/moon-service/issues/9): add basic backend
  observability.
- [#16](https://github.com/rapucha/moon-service/issues/16): add public feeds
  and iCalendar exports for real opportunities.
- [#19](https://github.com/rapucha/moon-service/issues/19): choose the alpha
  hosting and backup boundary before deployment-specific work.
- [#27](https://github.com/rapucha/moon-service/issues/27): add a
  containerized backend live smoke test.

## References

- [K3s requirements](https://docs.k3s.io/installation/requirements)
- [K3s cluster datastore](https://docs.k3s.io/datastore)
- [K3s networking services](https://docs.k3s.io/networking/networking-services)
- [Tailscale Funnel](https://tailscale.com/docs/features/tailscale-funnel)
- [Tailscale Funnel CLI](https://tailscale.com/docs/reference/tailscale-cli/funnel)
- [Kubernetes Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/)
- [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Kubernetes NFS volumes](https://kubernetes.io/docs/concepts/storage/volumes/#nfs)
- [OWASP API4:2023 Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
