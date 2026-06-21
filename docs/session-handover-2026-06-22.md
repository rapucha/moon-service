# Session Handover - 2026-06-22

Generated at: 2026-06-22T00:59:07+02:00

This handover captures the Moon Service state after adding the backend location
provider resolution seam, ambiguous-location response support, and manual
Open-Meteo geocoding drift checks.

## Current Branch And Latest Commit

Current branch:

```text
spring-enterprise-ish-refactor
```

Latest pushed commit:

```text
f44afe3 feat(backend): add location provider resolution seam
```

Recent commit context:

```text
f44afe3 feat(backend): add location provider resolution seam
9a8dd2e docs: add session handover 2026-06-21
71aced3 refactor(backend): organize opportunity packages
99299c3 feat(scoring): rank visible moon opportunities
fa58116 feat(backend): add query opportunity lookup
```

## Current Backend Shape

Current endpoints:

```http
GET /api/opportunities?q=Praha
POST /api/opportunities/search
```

`GET /api/opportunities` is the query-shaped public lookup path. It currently
uses the fixture-backed `LocationResolver`, then delegates resolved locations to
the same opportunity search engine as the lower-level POST endpoint.

`POST /api/opportunities/search` remains the direct fixture/prototype request
path. It accepts `locationId`, `start`, `forecastHorizonDays`,
`maxMoonAltitudeDegrees`, and `limit`.

Current backend flow:

```text
OpportunitySearchController
  -> OpportunitySearchService
      -> LocationResolver for query lookup
          -> LocationResolution
              resolved / ambiguous / not_found / temporarily_unavailable
      -> OpportunitySearchDefaults for request-time defaults
      -> OpportunitySearchEngine
          -> PrototypeOpportunitySearchEngine
              -> jvm-scoring-prototype PreviewEvaluator
```

Important backend packages:

```text
dev.moonservice.backend.location
  LocationQuery
  LocationResolution
  LocationResolver
  ResolvedLocation
  fixture/FixtureLocationResolver

dev.moonservice.backend.opportunity
  OpportunitySearchService
  OpportunitySearchDefaults

dev.moonservice.backend.opportunity.search
  LocationCandidatesResponse
  OpportunityResponse
  OpportunitySearchEngine
  OpportunitySearchRequest
  OpportunitySearchResponse
  OpportunityStatusResponse

dev.moonservice.backend.opportunity.prototype
  PrototypeOpportunitySearchEngine
```

The fixture resolver is now a provider-style adapter. It can return:

- resolved Prague fixtures for `Praha`, `Prague`, and `prague-cz`
- `ambiguous_location` fixture candidates for `Springfield`
- `location_not_found` for unknown fixture queries

The resolution model can also represent `temporarily_unavailable`; the
controller maps that status to HTTP `503`. The fixture resolver does not
currently produce that state.

Spring configuration still hardcodes `FixtureLocationResolver` as the active
`LocationResolver`. This is intentional until an Open-Meteo implementation
exists. Add runtime provider switching when there are at least two real choices
to switch between.

## Live Provider Checks

Manual live checks now live under:

```text
live-tests/
```

Run the Open-Meteo geocoding drift check explicitly:

```bash
live-tests/run_live_geocoding_tests.sh
```

The script creates or reuses `live-tests/.venv`, installs `pytest` and
`pytest-html`, runs the `live_geocoding` pytest tests with
`MOON_SERVICE_RUN_LIVE_TESTS=1`, and writes:

```text
live-tests/reports/openmeteo-geocoding.html
```

These tests are provider drift checks, not backend unit tests. They verify broad
Open-Meteo assumptions such as Prague ambiguity, known native-script misses
like `東京` and `서울`, and one-character query behavior. Do not run them in the
ordinary Maven loop.

## Current Local Working Tree

At handover creation time, tracked files are clean relative to the pushed
branch except for this new handover file before it is committed. Untracked local
files were:

```text
?? moon-service.iml
?? prototypes/spring-preview/
```

Notes:

- `moon-service.iml` is IDE metadata and should remain untracked unless repo
  policy changes.
- `prototypes/spring-preview/` appears to contain generated/IDE residue from
  the removed Spring preview prototype, not source. It should not be added
  without inspecting it again.

## Verification Already Run

After the provider seam and live-test harness work, these commands passed:

```bash
mvn test -pl backend -am
bash -n live-tests/run_live_geocoding_tests.sh
python3 -m py_compile live-tests/test_openmeteo_geocoding.py
git diff --check
```

The live Open-Meteo pytest suite was intentionally not run during implementation
because it installs dependencies and calls the external provider.

## Next Session Plan

In the next session, implement the Open-Meteo geocoding adapter behind the
existing backend location seam:

1. Add `backend.location.openmeteo` package.
2. Create an `OpenMeteoGeocodingClient` that builds the request and parses
   provider JSON.
3. Map provider results into `LocationResolution`.
4. Keep `FixtureLocationResolver` as the default Spring bean for now, unless
   explicitly adding configuration to switch providers.
5. Add saved JSON fixtures for:
   - `Praha` resolved
   - `Prague` ambiguous
   - native-script miss like `東京`
   - malformed/empty provider response
6. Add adapter tests using fixtures only.
7. Update docs with the provider-adapter boundary and the command for manual
   live drift checks.

Keep normal tests network-free. Do not add caching, persistence, accounts,
Android, deployment, weather integration, or live provider calls in unit tests
as part of this step.
