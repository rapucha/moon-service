# Open-Meteo Geocoding Transport Policy

Metadata
- Title: Add retry and error-classification policy for Open-Meteo geocoding transport
- Owner/Role: backend
- Created: 2026-06-22
- Status: TODO
- Scope: `backend.location.openmeteo`, backend docs/tests

Context
- Problem statement:
  - `JavaHttpOpenMeteoGeocodingTransport` currently sends one request and treats any non-2xx, IO failure, timeout, or interruption as provider unavailable.
  - This is acceptable while the adapter is fixture-tested and not active in Spring runtime, but it is too blunt before live geocoding becomes active.
- Desired outcome:
  - The transport has a small, explicit timeout/retry/error-classification policy suitable for user-triggered geocoding.
  - Tests prove retryable and non-retryable failure behavior without live provider calls.
- Non-goals:
  - Do not make Open-Meteo the active Spring resolver.
  - Do not add broad retry loops, persistence, provider metrics storage, or live tests to the Maven loop.
  - Do not log raw user location queries by default.

Plan
- Steps:
  1. Introduce a small transport result/error model or exception type that distinguishes transient provider failure, rate limit, non-retryable HTTP failure, timeout, interruption, and invalid response body.
  2. Add at most one retry for transient failures: HTTP `429`, `502`, `503`, `504`, connection reset, and timeout.
  3. Avoid retries for HTTP `400`, `401`, `403`, `404`, malformed provider payload, or valid empty results.
  4. Keep resolver mapping semantics unchanged: dependency failures become `temporarily_unavailable`, valid empty `results` becomes `not_found`.
  5. Add fixture/fake-transport tests for retry and classification behavior.
- Risks and mitigations:
  - Retrying user searches can slow the web request -> use one retry only and keep timeout small.
  - Rate-limit handling can accidentally hammer the provider -> honor only short `Retry-After` values and otherwise fail fast.
  - Logs could expose raw location queries -> log provider status/failure kind only.
- Rollback:
  - Revert transport policy changes; the adapter can return to one request plus `temporarily_unavailable` on failure.

Files
- Planned edits:
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/OpenMeteoGeocodingClient.java` - transport policy and classification seam.
  - `backend/src/test/java/dev/moonservice/backend/location/openmeteo/OpenMeteoGeocodingClientTest.java` - retry/error classification tests with fake transport.
  - `docs/geocoding-research.md` and `backend/README.md` - document runtime transport policy once implemented.
- Generated or local-only files:
  - None expected.
- Deferred items:
  - Provider call counters and admin status reporting - belongs with ops/quota visibility.
  - Runtime provider switching - blocked until opportunity search consumes coordinate-backed locations.

Validation
- Commands:
  - `mvn test -pl backend -am` -> backend and scoring-prototype reactor tests pass.
  - `git diff --check` -> no whitespace errors.
- Results:
  - Not started.
- Skipped validation:
  - `live-tests/run_live_geocoding_tests.sh` -> live provider drift check, not part of this transport unit-test task.

Decisions
- Keep retries narrow and bounded - geocoding is user-triggered and should fail fast.
- Preserve current public resolver statuses - transport detail should not leak into the public API before a broader error contract exists.

Handoff
- Current state:
  - Filed as GitHub issue: https://github.com/rapucha/moon-service/issues/2
- Next step:
  - Implement the transport policy before activating Open-Meteo as a Spring `LocationResolver`.
- Watch-outs:
  - Keep normal backend tests network-free.
