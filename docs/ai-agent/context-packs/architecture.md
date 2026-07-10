# Context Pack - Architecture

Purpose
- Provide agents with the minimum architecture context needed before changing
  module boundaries or product behavior.

Canonical Human Docs
- `docs/architecture.md`
- `docs/product-notes.md`
- `docs/mvp-roadmap.md`
- `docs/api-shape.md`

Current Shape
- Product: web-first Moon photography opportunity discovery.
- First user flow: enter a city/location and see upcoming useful Moon windows.
- Active implementation:
  - `backend/`: first real Spring Boot backend spine, currently fixture-backed.
  - `prototypes/jvm-scoring/`: active scoring and ephemeris prototype.
  - `prototypes/jvm-ephemeris/`: older source-file ephemeris prototype.
  - `scripts/`: Python spikes and parity checks.
- Current non-goals:
  - Mandatory accounts.
  - Production database.
  - Installed client scaffolding. React Native with Expo is a leading candidate,
    but the client-platform decision remains open in GitHub issue `#109`.
  - Deployment scaffolding.
  - Email alerts.
  - Automated social posting.

Architectural Direction
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

Boundaries
- Product/API truth belongs in human-owned docs.
- Executable backend behavior belongs in backend tests.
- Scoring rules should remain independently testable.
- Provider-specific details should be behind interfaces once live providers are
  introduced.
- Fixture-backed providers are acceptable while migrating prototypes into the
  backend.

Before Architecture Changes
- Identify whether the change is:
  - A new module.
  - A new dependency/provider.
  - A public API change.
  - A storage/privacy change.
- Use the matching checklist.

Validation
- Backend changes:
  - `mvn test -pl backend -am`
- Scoring/window changes:
  - `(cd prototypes/jvm-scoring && mvn test)`
  - `python3 -B scripts/prototype_contract_parity.py`
- Always:
  - `git diff --check`
