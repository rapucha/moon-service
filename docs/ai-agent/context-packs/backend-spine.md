# Context Pack - Backend Spine

Purpose
- Guide work in the real backend module while it grows from fixture-backed
  opportunity search into the MVP service.

Current Backend Scope
- Module: `backend/`
- Framework: Spring Boot.
- Current endpoint: `POST /api/opportunities/search`
- Current dependency: `jvm-scoring-prototype`
- Current provider behavior: fixture-backed Prague request only.

Design Intent
- Keep the backend small and explicit.
- Preserve the opportunity search contract while replacing fixture dependencies
  one at a time.
- Introduce seams before live provider integrations:
  - Geocoding provider.
  - Weather provider.
  - Ephemeris service.
  - Opportunity scorer.
  - Feed/calendar renderer.
- Keep each provider testable with fakes and fixtures.

Recommended Growth Sequence
1. Preserve the current fixture-backed opportunity search tests.
2. Add backend-owned response DTOs when the endpoint stops returning
   raw prototype JSON.
3. Add provider interfaces with fixture implementations.
4. Add query-based `GET /api/opportunities?q=...` once geocoding is represented.
5. Add cache interfaces before live weather/geocoding calls become normal.
6. Add Atom/RSS and `.ics` exports after the opportunity contract is stable.
7. Add persistence only when cache durability, result IDs, saved alerts, or
   similar product needs require it.

Guardrails
- Do not add database or migrations during endpoint/module cleanup.
- Do not call live provider APIs from unit tests.
- Do not introduce accounts or cookies for opportunity search.
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
- If Spring preview prototype is intentionally kept aligned:
  - `mvn test -pl prototypes/spring-preview -am`

Review Questions
- Is this still fixture-backed, and is that obvious?
- Did the change move any privacy boundary?
- Did it create a provider dependency without a seam?
- Does endpoint behavior match `docs/api-shape.md`, or is the divergence
  intentional and documented?
- Can the module be tested without network access?
