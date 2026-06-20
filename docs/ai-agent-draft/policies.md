# AI Contribution Policies Draft

Purpose
- Define transferable guardrails for AI-assisted work on Moon Service.
- Keep changes safe, minimal, verifiable, and aligned with the MVP direction.

Scope Discipline
- Keep early implementation centered on a small web-first backend.
- Do not add production database, deployment, Android, account, email alert, or
  live provider infrastructure unless the user explicitly asks for that step.
- Do not permanently store user locations or preferences unless the privacy
  model and product value are documented first.
- Keep prototypes useful as validation references until backend equivalents
  exist.

Core Principles
- Minimal diffs: avoid unrelated cleanup and broad formatting churn.
- Additive first: prefer adding narrow behavior over deleting or replacing
  working prototypes.
- Behavior preservation: refactors should have parity tests or a clear manual
  validation story.
- Explicit tradeoffs: document why a dependency, provider, or boundary is being
  introduced.
- Validation evidence: report exact commands and outcomes.

Security and Privacy
- Do not commit secrets, tokens, API keys, local credentials, or private URLs.
- Do not log raw user location queries by default.
- Treat city/location input as hostile text.
- Escape user/provider display values before HTML rendering.
- Keep provider call counters and rate-limit behavior visible once live
  providers are introduced.

Provider and Network Guardrails
- Prefer provider abstractions before binding product logic to one service.
- Cache geocoding and weather responses before relying on repeated upstream
  calls.
- Treat upstream quota exhaustion or timeouts as dependency failures, not empty
  opportunity lists.
- Use retry policies only for transient failures; do not retry invalid requests.

Testing Expectations
- Add or update focused tests for behavior changes.
- Prefer deterministic tests with fixtures and fakes.
- Avoid real network calls in normal unit tests.
- Use integration or live-provider checks only when clearly labeled and
  opt-in.
- Validate error paths, not only happy paths.

Escalate Before Proceeding
- Public API contract changes that break existing fields, status meanings, or
  routes.
- New persistence of locations, preferences, contact details, or device
  identifiers.
- Cross-module refactors that move ownership boundaries.
- New external services, paid providers, or provider terms/privacy assumptions.
- Security-sensitive behavior: auth, rate limiting, secrets, consent,
  unsubscribe, deletion, retention.
- CI/build failures that cannot be reproduced or fixed with narrow local
  changes.

Commit and PR Hygiene
- Keep commits focused by concern.
- Use conventional commit style when making commits:
  - `feat(backend): add preview provider seam`
  - `fix(scoring): handle empty weather fixture`
  - `docs(agent): add context packs`
- PR or change summary should include:
  - Summary
  - Key files changed
  - Risks and mitigations
  - Validation commands and outcomes
  - Follow-ups or deferred work

Rollback Practice
- Keep changes small enough to revert cleanly.
- For risky changes, state the revert path or feature toggle plan.
- Avoid combining irreversible data/storage changes with unrelated refactors.
