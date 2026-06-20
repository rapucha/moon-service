# Context Pack - Architecture

Purpose
- Give agents a concise architecture map before changing module boundaries or
  product behavior.

Current Shape
- Product: web-first Moon photography opportunity discovery.
- First user flow: enter a city/location and see upcoming useful Moon windows.
- Current implementation state:
  - `backend/`: first real Spring Boot backend spine, currently fixture-backed.
  - `prototypes/jvm-scoring/`: active scoring and ephemeris prototype.
  - `prototypes/spring-preview/`: HTTP contract harness retained as reference.
  - `prototypes/jvm-ephemeris/`: older source-file ephemeris prototype.
  - `scripts/`: Python spikes and parity checks.
- Current non-goals:
  - Mandatory accounts.
  - Production database.
  - Android app.
  - Deployment scaffolding.
  - Email alerts.
  - Automated social posting.

Key Architectural Direction
- Small backend owns:
  - Geocoding.
  - Weather provider integration.
  - Weather and geocoding caching.
  - Candidate Moon opportunity generation.
  - Scoring.
  - Shareable result pages.
  - Atom/RSS and `.ics` exports.
- Browser may hold recent anonymous searches in localStorage.
- Backend should not permanently store locations in v0 unless a later feature
  requires it and the privacy model is updated.

Important Boundaries
- Public API contract belongs in docs and backend tests.
- Scoring rules should remain independently testable.
- Provider-specific details should be behind interfaces once live providers are
  introduced.
- Fixture-backed providers are acceptable while migrating prototypes into the
  backend.

Risks
- Promoting prototype code too directly can bake in fixture assumptions.
- Adding persistence too early creates privacy and migration work before user
  value is proven.
- Query/location handling can become a privacy and abuse boundary quickly.
- Weather and geocoding provider behavior can change; cache and validation
  rules should be explicit.

Before Changing Architecture
- Read:
  - `docs/architecture.md`
  - `docs/api-shape.md`
  - `docs/product-notes.md`
  - `docs/mvp-roadmap.md`
- Identify whether the change is:
  - A new module.
  - A new dependency/provider.
  - A public API change.
  - A storage/privacy change.
- Use the matching checklist.

Validation
- Backend changes:
  - `mvn test -pl backend -am`
- Prototype parity changes:
  - `(cd prototypes/jvm-scoring && mvn test)`
  - `python3 -B scripts/prototype_contract_parity.py`
- Always:
  - `git diff --check`
