# Context Pack - Test Authoring

Purpose
- Guide focused, deterministic tests for Moon Service backend and prototypes.

When To Use
- Adding behavior.
- Fixing bugs.
- Creating seams for refactors.
- Locking down API contract behavior.
- Proving scoring changes.

Test Strategy
- Prefer behavior-focused tests over implementation-detail assertions.
- Keep unit tests deterministic and fixture-backed.
- Avoid real network calls in default test suites.
- Use live-provider checks only as explicit, opt-in validation.
- Validate edge cases and error paths, not just the happy path.

Backend Testing
- Use Spring HTTP tests when verifying controller mapping, serialization, and
  error responses.
- Use plain unit tests for scoring, provider adapters, validation, and cache
  decisions where possible.
- Keep app-context tests targeted and fast.

Scoring and Ephemeris Testing
- Preserve fixture tests for natural low-Moon windows.
- Cover:
  - Window boundary behavior.
  - Max Moon altitude filters.
  - Weather scoring aggregation.
  - Ranking and limits.
  - Invalid request validation.
- Treat ephemeris/provider differences as documented behavior, not accidental
  test looseness.

Provider Testing
- For geocoding and weather providers:
  - Unit test request construction and response mapping with fixtures.
  - Test failure modes: timeout, quota/rate limit, malformed provider response,
    empty result, ambiguous result.
  - Do not call live providers in unit tests.

Command Loop
- Backend:
  - `mvn test -pl backend -am`
- Scoring:
  - `(cd prototypes/jvm-scoring && mvn test)`
- Parity:
  - `python3 -B scripts/prototype_contract_parity.py`
- Targeted Maven tests:
  - `mvn -Dtest='*SomeClass*' test -pl <module> -am`

Test Quality Checklist
- Assertions are specific.
- Test names describe behavior.
- Fixtures are understandable and minimal.
- Timezones and dates are explicit.
- No sleeps, random network calls, or dependency on current weather.
- Failure messages point to the broken contract or rule.
