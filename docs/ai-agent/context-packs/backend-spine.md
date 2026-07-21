# Context Pack - Backend Spine

Purpose
- Guide work in the current Spring Boot backend and apply its approved
  dependency directions while the MVP grows.

Current Backend Scope
- Module: `backend/`
- Framework: Spring Boot.
- Primary API endpoints:
  - `GET /api/opportunities?q=Praha`
  - `POST /api/opportunities/search`
  - `GET /api/calibration-feedback/v1/capability`
  - `POST /api/calibration-feedback/v1/submissions`
- The [HTTP route inventory](../../http-route-inventory.md) is the canonical
  list of controller routes and exposure rules.
- Anonymous query lookup uses Open-Meteo geocoding and weather adapters behind
  backend-owned resolver and provider seams. The direct POST endpoint retains
  its deterministic prototype fixture path. Unit tests use saved responses and
  fakes rather than live provider calls.
- Opportunity scoring still depends on the `jvm-scoring-prototype` Maven
  module and several of its internal types.
- Optional PostgreSQL, Hikari, and Flyway persistence exists only for bounded
  calibration feedback. It is disabled by default and isolated from lookup,
  liveness, and readiness.

Design Intent
- Keep the backend small and explicit.
- Preserve public opportunity and feedback contracts while replacing internal
  boundaries deliberately.
- Keep live provider adapters behind backend-owned ports.
- Keep each provider testable with fakes and fixtures.

Dependency Directions
- Treat the [architecture map](../../architecture.md#backend-package-ownership-and-dependency-directions)
  as the source of truth.
- `backend.web` is an outer adapter. Application, domain, provider, and
  persistence responsibilities must not depend on its filters or controllers.
- Provider adapters depend on backend-owned ports, never the reverse.
- Spring configuration is the composition root and may wire interfaces to
  concrete implementations.
- The Maven direction is `backend` to `jvm-scoring-prototype`; the prototype
  must not depend on `backend`.
- Current package cycles and broader prototype imports are transitional, not
  approved directions. Do not add enforcement tooling without separate issue
  authority.

Recommended Growth Sequence
1. Keep live geocoding and weather behind their current backend-owned seams.
2. Reduce prototype coupling only through reviewed migration work; do not hide
   it behind new speculative abstractions.
3. Keep calibration-feedback persistence optional and privacy-bounded.
4. Add Atom/RSS and `.ics` exports after the opportunity contract is stable.

Guardrails
- Do not broaden calibration persistence or make lookup depend on it during
  endpoint or module cleanup.
- Do not call live provider APIs from unit tests.
- Do not introduce accounts or cookies for opportunity search.
- Do not move shared application or provider behavior into HTTP adapters.
- Do not silently change public response meanings; update docs and tests.
- Keep error responses conventional:
  - `400` for invalid request.
  - `429` for application rate limit.
  - `503` for dependency unavailability.
  - `200` for product states such as no opportunities or ambiguous location.

Validation
- Normal backend loop:
  - `mvn test -pl backend -am`
- If scoring/window generation changed:
  - `(cd prototypes/jvm-scoring && mvn test)`
  - `python3 -B scripts/prototype_contract_parity.py`

Review Questions
- Does each dependency follow the architecture map, including the outer-web
  and provider-port directions?
- Did the change move any privacy boundary?
- Did it add to a transitional cycle or broaden prototype coupling?
- Does endpoint behavior match `docs/api-shape.md`, or is the divergence
  intentional and documented?
- Can the module be tested without network access?
