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
host child [#95](https://github.com/rapucha/moon-service/issues/95).

## Decision Snapshot

- Use home hosting only for alpha or beta, not as the long-term production
  assumption.
- Use Raspberry Pi OS Lite 64-bit based on Debian Trixie and Docker Compose for
  the current alpha; do not introduce k3s yet.
- Pull the tested GHCR multi-architecture image by immutable index digest. Keep
  one current and one previous known-good image and automatically roll back a
  candidate that fails revision-aware readiness.
- Bind the private deployment to loopback. Use SSH over the LAN or the existing
  MikroTik WireGuard path for administration; do not forward a router port.
- Install Tailscale without enrollment during host provisioning. Issue
  [#97](https://github.com/rapucha/moon-service/issues/97) owns the later
  Tailscale Funnel public HTTPS boundary.
- Keep one backend instance at first because provider counters and caches are
  currently process-local. Public request rate limiting is planned for the edge
  or ingress first, with app-level rate limiting added when the product response
  contract needs backend-owned `429` JSON.
- Add edge and ingress rate limits before any public alpha.
- Use the 32 GB SD card for Pi OS, Docker runtime state, two application image
  generations, and bounded local logs. Keep durable application data off it.
- Plan a small NFS-backed storage pool around 64-80 GB for alpha data that must
  survive Pod rescheduling or SD-card replacement.
- Avoid running Postgres on SD cards until there is a clear product need and a
  tested off-card backup/restore routine.
- Keep public RSS/Atom and `.ics` generation stateless or cache-backed at first.
  Add a database when private feeds, saved locations, alert subscriptions,
  durable counters, or durable cache state require it.

## Current Private Alpha Topology

```text
operator browser
  -> SSH tunnel over LAN or MikroTik WireGuard
  -> 127.0.0.1:8080 on the Raspberry Pi
  -> one Docker Compose Spring Boot container
  -> Open-Meteo geocoding/weather over outbound HTTPS

GitHub-hosted Actions
  -> tested AMD64/ARM64 image in GHCR
  -> Pi systemd timer resolves main to an immutable index digest
  -> readiness/revision check
  -> commit current/previous state or restore current
```

The home router does not forward application, SSH, Docker, admin, database, or
other Pi ports. Public tester access is a later outbound Tailscale Funnel under
#97; the Funnel URL is public ingress, not an authentication boundary.

The admin path remains private:

```text
GET /admin/status
```

The backend already requires `X-Moon-Admin-Token` when admin routes are enabled.
The Compose seed configuration leaves `/admin/**` disabled. If an operator
adds a host-local token, the route remains private during #95. Issue #97 must
also keep `/admin/**` off the public surface; the backend token is a minimum
application boundary, not permission to publish the route.

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
- The cluster has enough memory headroom for k3s, Traefik, cloudflared, and one
  Java backend Pod.

Backup matrix before public alpha:

| State | Back up? | Notes |
| --- | --- | --- |
| Repo revision and image tag | Yes | Needed to rebuild the deployed version. |
| k3s manifests or deployment commands | Yes | Keep outside the SD card. |
| Runtime configuration and secret names | Yes | Do not commit secret values. |
| Admin token source | Yes | Store in the chosen secret manager or offline notes. |
| Cloudflare Tunnel and access configuration | Yes | Needed to restore public routing. |
| NFS export path and server configuration | Yes | Needed to remount alpha persistent volumes. |
| Process-local caches | No | Rebuildable from provider calls. |
| Process-local provider counters | No | Useful live visibility, not durable truth. |
| Disposable logs | No | Keep short retention unless debugging a specific issue. |
| Future Postgres data | Yes | Only after a restore drill exists. |

## Deferred Phase 2: Public Edge And Reverse Proxy

Goal: expose only the public app surface and keep hostile traffic away from the
home network where possible.

Recommended shape:

- Cloudflare DNS and TLS terminate public HTTPS.
- Cloudflare Tunnel connects outbound from the home network or cluster.
- Traefik remains the in-cluster reverse proxy and ingress router.
- Public routes are limited to the web app, `/search`, `/api/opportunities`,
  future public feed routes, and future public calendar export routes.
- The current prototype fixture endpoint, `POST /api/opportunities/search`,
  must be explicitly blocked, protected, or retired before public alpha unless
  a follow-up decision keeps it public.
- `/admin/**` is private at both the edge and backend token layer.
- No raw router port forward is required for HTTP(S) when the tunnel is used.

Ingress controls to plan:

- Request size limits suitable for query-only endpoints.
- Conservative timeouts so slow clients do not tie up the backend.
- Per-IP or per-forwarded-client rate limits on `/api/opportunities`.
- Stricter limits for one-character lookups and future feed/calendar refresh
  routes if they become abuse targets.
- Basic security headers for browser responses.
- Forwarded-client-IP handling that matches the chosen edge path. Rate limits
  are only useful if Traefik sees the correct client identity or the edge applies
  the limit first.

Initial alpha rate-limit targets, to tune after real traffic:

- `/api/opportunities`: about 30 requests per minute per client, with a small
  burst allowance for manual repeated searches.
- One-character or otherwise high-ambiguity lookups: about 10 requests per
  minute per client.
- Future feed and calendar routes: favor HTTP caching and low per-client refresh
  rates, because calendars and feed readers can poll repeatedly without a human
  actively using the site.
- Upstream provider exhaustion still maps to `temporarily_unavailable`; client
  overuse maps to `rate_limited` with HTTP `429` once backend-owned app-level
  limiting exists. Edge or ingress rate limiting is an earlier safety control
  and may not produce the final product JSON shape.

WAF stance:

- Do not start by tuning a local WAF on the Pis.
- Prefer simple edge bot controls, ingress rate limits, request bounds, and app
  validation first.
- Revisit WAF only if traffic shows repeated exploit attempts or the public
  input surface becomes more complex than city lookup, feeds, and calendar
  exports.

Exit criteria:

- The public domain reaches the backend through the tunnel.
- The Kubernetes API, node ports, admin path, and any future database are not
  publicly reachable.
- A burst of repeated public API calls receives `429` before provider quotas are
  at risk.

## Phase 3: App-Level Abuse And Provider Protection

Goal: keep Open-Meteo usage, CPU, memory, and home bandwidth bounded.

The backend already has useful alpha primitives:

- Request logs avoid raw query strings by default.
- Runtime geocoding and weather caches are process-local.
- Concurrent identical cache misses share one upstream provider call inside one
  backend process.
- `/admin/status` exposes provider counters, cache stats, and quota windows.

Remaining application work:

- Add explicit application-level rate limiting when ingress-only limits are not
  enough for correct product responses.
- Return documented `status: "rate_limited"` with HTTP `429`.
- Keep Open-Meteo quota limits configurable and visible in `/admin/status`.
- Add operator-visible current public rate-limit settings.
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
- Alert delivery infrastructure.

## Related Issues

- [#93](https://github.com/rapucha/moon-service/issues/93): current Docker
  Compose tester-alpha infrastructure milestone.
- [#95](https://github.com/rapucha/moon-service/issues/95): Raspberry Pi
  provisioning and health-checked host-pull deployment.
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
- [Cloudflare Tunnel on Kubernetes](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/deployment-guides/kubernetes/)
- [Kubernetes Ingress](https://kubernetes.io/docs/concepts/services-networking/ingress/)
- [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)
- [Kubernetes NFS volumes](https://kubernetes.io/docs/concepts/storage/volumes/#nfs)
- [Traefik RateLimit middleware](https://doc.traefik.io/traefik/reference/routing-configuration/http/middlewares/ratelimit/)
- [OWASP API4:2023 Unrestricted Resource Consumption](https://owasp.org/API-Security/editions/2023/en/0xa4-unrestricted-resource-consumption/)
