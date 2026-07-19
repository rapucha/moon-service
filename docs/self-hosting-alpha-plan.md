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
is tracked by [#93](https://github.com/rapucha/moon-service/issues/93) and its
host child [#95](https://github.com/rapucha/moon-service/issues/95). Reliable
timer rearming and exact GitHub deployment confirmation are follow-up
[#107](https://github.com/rapucha/moon-service/issues/107).

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
- Install Tailscale without enrollment during host provisioning. Enrollment
  and Funnel remain explicit operator actions. The tester-alpha host now uses
  the verified #97 public boundary: HTTPS port `443` proxies only to
  `http://127.0.0.1:8080`, while the exact trusted-LAN listener remains in
  place.
- Keep one backend instance at first because provider counters and caches are
  currently process-local. For the temporary tester alpha, use one shared,
  process-local request boundary inside the Spring application. Do not add
  visitor identity or per-client fairness. Cloudflare remains the later
  production edge.
- Use the fail-closed hosted surface and the Spring-managed limits delivered
  under
  [#118](https://github.com/rapucha/moon-service/issues/118) and
  [#120](https://github.com/rapucha/moon-service/issues/120). These controls
  bound accepted application work, responses, and provider calls after a
  request reaches the Pi; they are not a WAN bandwidth cap.
- Use the 32 GB SD card for Pi OS, Docker runtime state, two application image
  generations, and bounded local logs. Keep durable application data off it.
- Plan a small NFS-backed storage pool around 64-80 GB for alpha data that must
  survive Pod rescheduling or SD-card replacement.
- Avoid running Postgres on SD cards until there is a clear product need and a
  tested off-card backup/restore routine.
- Keep public RSS/Atom and `.ics` generation stateless or cache-backed at first.
  Add a database when private feeds, saved locations, alert subscriptions,
  durable counters, or durable cache state require it.

### Calibration feedback may be lost

Issue [#33](https://github.com/rapucha/moon-service/issues/33) allows optional
calibration feedback to use NFS-backed PostgreSQL before a restore drill because
the owner accepts losing this alpha evidence. Storage remains off by default,
has a configurable limit, and keeps reports until an operator deletes them.

This accepted loss applies only to calibration reports. Any future important or
personal stored data needs its own backup and recovery decision before Moon
Service relies on it.

An unmounted NFS path, unavailable database, or full store may stop feedback.
It must not prevent the application from starting, serving opportunity lookups,
or reporting provider-independent readiness. Database deployment, application
wiring, and controlled activation remain separate GitHub issues under #33.

## Current Tester-Alpha Topology

```text
operator browser
  -> trusted-LAN primary IPv4:8080 when explicitly enabled
     or SSH tunnel over LAN or MikroTik WireGuard with the loopback default
  -> one exact host listener on the Raspberry Pi
  -> one Docker Compose Spring Boot container
  -> Open-Meteo geocoding/weather over outbound HTTPS

public tester browser
  -> Tailscale Funnel HTTPS 443
  -> exact 127.0.0.1:8080 proxy target
  -> the same Docker Compose Spring Boot container

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
```

The home router does not forward application, SSH, Docker, admin, database, or
other Pi ports. Public tester access uses outbound-established Tailscale Funnel
under #97. The Funnel URL is public ingress, not an authentication boundary.

The callback path is independent of application ingress: the Pi initiates
outbound HTTPS to GitHub after checking Docker health, the recorded digest, and
the `/readyz` revision locally. LAN failure, Funnel configuration, or a public
DNS name is therefore not required for deployment acknowledgement. GHCR `main`
remains the host's desired-state source, so a GitHub App or Deployments API
outage cannot block an otherwise healthy local update; it instead makes the
external workflow time out without confirmation.

Issue #97 delivered this public-ingress shape without opening a router port:

```text
trusted LAN client -> exact primary-LAN-IPv4:8080 ----+
                                                       +-> one Compose container
Tailscale Funnel -> exact 127.0.0.1:8080 ------------+
```

The deployment has two exact host listeners: the configured primary LAN IPv4
and loopback. It does not use `0.0.0.0` or a local reverse proxy. Deployment
confirmation continues to use the local endpoint rather than depend on the
public Funnel URL.

The temporary hosted-alpha operator path is:

```text
GET /admin/status
```

The backend requires `X-Moon-Admin-Token`. The Compose seed leaves
`/admin/**` disabled on a fresh host. The active tester-alpha configuration
makes exact `GET`/`HEAD /admin/status` a deliberate exception behind an
explicit 64-hex deployment token so process-local evidence remains available;
every other admin path stays off the public surface.

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
| Calibration feedback | No | Accepted loss described in [Calibration feedback may be lost](#calibration-feedback-may-be-lost); keep it off the SD card. |
| Other future Postgres data | Yes | Only after a restore drill exists. |

## Phase 2: Tailscale Funnel Public Edge

Status: active for the tester alpha since the #123 verification on 2026-07-18.

Goal: expose only a bounded public app surface through an outbound-established
tunnel, without a raw router port forward and without claiming that local
application rejection can stop traffic already carried over the home ISP link.

Implemented shape:

- Tailscale Funnel terminates public HTTPS and reaches the Pi over Tailscale's
  outbound-established connectivity; the home router has no HTTP port forward.
- Funnel proxies directly to `http://127.0.0.1:8080`. The existing exact
  primary-IPv4 listener remains available only to the trusted LAN.
- Public routes are limited to the web app and its static assets, `/search`,
  `/api/opportunities`, provider-independent `/readyz`, and exact
  token-authenticated `/admin/status`.
- The prototype fixture endpoint, `POST /api/opportunities/search`, is blocked.
  Future feed and calendar routes require their own accepted public limits
  before they can join the hosted allowlist.
- Every `/admin/**` route except exact authenticated `GET`/`HEAD /admin/status`
  is blocked before controller handling.
- No raw router port forward is required for HTTP(S) when Funnel is used.

### Tester-Alpha Shared-Uplink Decision

Issue [#118](https://github.com/rapucha/moon-service/issues/118) selects a
best-effort boundary for the temporary alpha:

- Do not add router or host traffic shaping for the couple of expected testers.
- Do not claim a project-selected Mbps ceiling. Tailscale documents a
  non-configurable Funnel bandwidth limit but does not publish a numeric value
  that Moon Service can treat as a guarantee.
- Enforce request admission and search/provider work with Spring-managed
  application components. Values are supplied through Spring configuration;
  this is not a Tomcat valve, connector rate limiter, or proxy policy.
- Share the limits across the deployed instance. Visitor identity, source IP,
  forwarded-client identity, and fairness between testers are unnecessary for
  this alpha.
- Keep Cloudflare as the production edge rather than growing the home-hosted
  alpha into a production ingress design.

The control and observation points are deliberately separate:

| Boundary | Exact point | What it establishes | What it does not establish |
| --- | --- | --- | --- |
| Tailscale Funnel limit | Tailscale-operated Funnel service, outside the home deployment and project configuration | An additional upstream limit exists | A known numeric ceiling or a Moon Service guarantee for household traffic |
| Spring request/work limits | Inside the Spring Boot process on the Pi, after the request has reached the embedded server | Bounds admitted application requests, concurrent opportunity work, bounded responses, and provider calls | An inbound Mbps cap or prevention of bytes already crossing the ISP access link |
| Household-impact observation | A trusted LAN client sharing the same router and ISP connection while test load originates outside the LAN | Shows whether the allowed alpha load materially harms ordinary household latency or usability | An enforcement mechanism or protection from traffic outside the controlled test |

The deployed application values are request and work limits, not external
Funnel restrictions:

- One shared whole-site token bucket refills at 60 requests per minute (one
  token per second) and stores at most 40 tokens. Capacity 40 is the maximum
  initial or accumulated burst; it is not 40 requests in addition to an
  unrelated 60-request allowance.
- At most two opportunity searches execute concurrently.
- A separate provider-backed lookup bucket initially holds ten tokens and
  refills once per minute. It protects Open-Meteo usage and is not a home-uplink
  bandwidth measurement.

Issue [#119](https://github.com/rapucha/moon-service/issues/119) delivered the
disabled-by-default route, method, body, header, admin, and fixture boundary.
Issue [#120](https://github.com/rapucha/moon-service/issues/120) delivered the
Spring implementation, deterministic limiter and concurrency tests, provider
arithmetic, `429` behavior, and Docker-readiness carve-out. Both are merged and
deployed.

Other hosted-surface controls remain narrow:

- Request size limits suitable for query-only endpoints.
- Conservative timeouts so slow clients do not tie up the backend.
- Basic security headers for browser responses.
- Future feed and calendar routes must join an explicit shared bound before
  public exposure; their caching and polling policy remains future work.

### Activation Result And Follow-Up

Issues #119 through #122 were merged and deployed before activation. Issue
[#123](https://github.com/rapucha/moon-service/issues/123) then verified the
focused public surface, security headers, exact authenticated status behavior,
one real provider-backed lookup, and the explicit Funnel off/on recovery path.
The healthy exact handler was left enabled.

The owner moved the controlled outside-household static burst and video-call
observation to nonblocking follow-up
[#158](https://github.com/rapucha/moon-service/issues/158). That issue must
leave Funnel off on degradation, unexpected provider work, missing rejection,
or unrelated exposure. Success restores the previously approved Funnel state.
It does not add an inbound Mbps guarantee.

The exact status, withdrawal, restoration, and re-enrollment commands live in
the [Raspberry Pi runbook](../deployment/raspberry-pi/README.md). Reconsidering
a MikroTik/router cap or host shaping requires a new reviewed issue and explicit
mutation authority; #118 does not authorize either change.

WAF stance:

- Do not start by tuning a local WAF on the Pis.
- Prefer the narrow hosted surface, shared Spring limits, request bounds, and
  application validation for this alpha.
- Revisit WAF only if traffic shows repeated exploit attempts or the public
  input surface becomes more complex than city lookup, feeds, and calendar
  exports.

Exit criteria met for activation:

- During the explicit #123 window, the public URL reaches only the accepted
  hosted surface through the tunnel.
- Missing/wrong admin tokens reveal no status data; every other admin route,
  fixture, SSH, Docker, and unrelated host surface is not publicly reachable.
- Request and provider safeguards are deployed, and the exact Funnel-off path
  was verified without harming LAN health. The separate household-impact
  measurement remains tracked by #158 and is not an activation gate.

## Phase 3: App-Level Abuse And Provider Protection

Status: implemented for the tester alpha.

Goal: keep accepted Open-Meteo usage, application work, and generated traffic
bounded. This phase does not claim to cap inbound WAN traffic.

The backend has these alpha controls:

- Request logs avoid raw query strings by default.
- Runtime geocoding and weather caches are process-local.
- Concurrent identical cache misses share one upstream provider call inside one
  backend process.
- `/admin/status` exposes provider counters, cache stats, and quota windows.
- Shared Spring-managed whole-site, opportunity-concurrency, and provider-backed
  lookup limits enforce the values selected above.
- Rejected product lookups return documented `status: "rate_limited"` with HTTP
  `429`. Whole-site exhaustion can return that bounded response before static or
  hidden-route handling.
- Open-Meteo quota limits remain configurable and visible in `/admin/status`.
- Preserve the v0 rule that the browser does not call geocoding on every
  keystroke.
- Keep cache TTLs and maximum sizes visible in backend configuration.

Exit criteria met:

- Repeated searches for the same city hit caches instead of increasing provider
  counters.
- Deterministic tests prove burst, refill, concurrency, retry, and restart
  behavior without relying on visitor identity.
- Limiter exhaustion does not make Docker health restart an otherwise healthy
  application.
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
- For data other than the calibration feedback covered by the accepted-loss
  decision above, store only data that is backed up off-card and can tolerate
  restore lag.
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
- Additional Postgres, migration, or database-credential work not already
  approved by the calibration-feedback issues under #33.
- Redis or distributed rate limiting.
- Local WAF rules.
- Multi-node HA control plane.
- GitOps automation.
- Alert delivery infrastructure.

## Related Issues

- [#93](https://github.com/rapucha/moon-service/issues/93): current Docker
  Compose tester-alpha infrastructure milestone.
- [#95](https://github.com/rapucha/moon-service/issues/95): Raspberry Pi
  provisioning and health-checked host-pull deployment.
- [#107](https://github.com/rapucha/moon-service/issues/107): reliable timer
  rearming and exact physical-deployment confirmation through GitHub.
- [#97](https://github.com/rapucha/moon-service/issues/97): public HTTPS
  exposure and hardening through Tailscale Funnel.
- [#118](https://github.com/rapucha/moon-service/issues/118): accepted
  tester-alpha shared-uplink protection boundary.
- [#119](https://github.com/rapucha/moon-service/issues/119): fail-closed
  hosted-alpha application surface.
- [#120](https://github.com/rapucha/moon-service/issues/120): shared
  application and provider-use bounds.
- [#121](https://github.com/rapucha/moon-service/issues/121): provider
  attribution and noncommercial-alpha disclosure.
- [#122](https://github.com/rapucha/moon-service/issues/122): inactive direct
  Tailscale Funnel routing preparation.
- [#123](https://github.com/rapucha/moon-service/issues/123): separately
  approved Funnel activation and physical verification.
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
