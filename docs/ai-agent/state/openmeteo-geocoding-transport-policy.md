# Open-Meteo Geocoding Transport Policy

Metadata
- Title: Add retry and error-classification policy for Open-Meteo geocoding transport
- Owner/Role: backend
- Created: 2026-06-22
- Status: DONE
- Scope: `backend.location.openmeteo`, backend docs/tests

Context
- Problem statement:
  - The original Open-Meteo geocoding transport sent one request with raw JDK `HttpClient` use and treated any non-2xx, IO failure, timeout, or interruption as provider unavailable.
  - That was acceptable while the adapter was fixture-tested and not active in Spring runtime, but it was too blunt before live geocoding becomes active.
- Desired outcome:
  - The transport uses Spring `RestClient` for HTTP and Spring `RetryTemplate` for bounded retry, with a small explicit timeout/retry/error-classification policy suitable for user-triggered geocoding.
  - Tests prove retryable and non-retryable failure behavior without live provider calls.
- Non-goals:
  - Do not make Open-Meteo the active Spring resolver.
  - Do not add broad retry loops, persistence, provider metrics storage, or live tests to the Maven loop.
  - Do not log raw user location queries by default.

Plan
- Steps:
  1. Introduce a small typed provider exception model that distinguishes transient provider failure, rate limit, non-retryable HTTP failure, timeout, interruption, and invalid response body.
  2. Add at most one retry for transient failures: HTTP `429`, `502`, `503`, `504`, IO failure, and timeout.
  3. Avoid retries for HTTP `400`, `401`, `403`, `404`, malformed provider payload, or valid empty results.
  4. Keep resolver mapping semantics unchanged: dependency failures become `temporarily_unavailable`, valid empty `results` becomes `not_found`.
  5. Add fixture/fake-transport tests plus no-network `RestClient` mock-server tests for retry and classification behavior.
- Risks and mitigations:
  - Retrying user searches can slow the web request -> use one retry only and keep timeout small.
  - Rate-limit handling can accidentally hammer the provider -> honor only short `Retry-After` values and otherwise fail fast.
  - Logs could expose raw location queries -> log provider status/failure kind only.
- Rollback:
  - Revert transport policy changes; the adapter can return to one request plus `temporarily_unavailable` on failure.

Files
- Planned edits:
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/OpenMeteoGeocodingClient.java` - resolver/client mapping.
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/RestClientOpenMeteoGeocodingTransport.java` - Spring HTTP transport.
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/RetryingOpenMeteoGeocodingTransport.java` - Spring `RetryTemplate` decorator.
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/OpenMeteoGeocodingRetryPolicy.java` - narrow provider retry policy and short `Retry-After` backoff.
  - `backend/src/main/java/dev/moonservice/backend/location/openmeteo/OpenMeteoGeocodingTransportException.java` - transport failure classification.
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
  - Focused Open-Meteo geocoding tests passed on 2026-06-23 after refactoring to Spring `RestClient` plus Spring `RetryTemplate`.
- Skipped validation:
  - `live-tests/run_live_geocoding_tests.sh` -> live provider drift check, not part of this transport unit-test task.

Decisions
- Keep retries narrow and bounded - geocoding is user-triggered and should fail fast.
- Align with Spring retry mechanics: typed provider exceptions are the retry signal.
- Preserve current public resolver statuses - transport detail should not leak into the public API before a broader error contract exists.

Handoff
- Current state:
  - Implemented locally for GitHub issue: https://github.com/rapucha/moon-service/issues/2
- Next step:
  - Keep Open-Meteo inactive as the Spring `LocationResolver` until opportunity search can consume coordinate-backed locations.
- Watch-outs:
  - Keep normal backend tests network-free.
