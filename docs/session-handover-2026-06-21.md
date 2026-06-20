# Session Handover - 2026-06-21

Generated at: 2026-06-21T01:35:17+02:00

This handover captures the current Moon Service state after the backend
opportunity-search seam, query lookup, visible-Moon scoring update, and backend
package cleanup. Unlike the previous local handover, this file is intended to
be committed and pushed.

## Current Branch And Latest Commit

Current branch:

```text
spring-enterprise-ish-refactor
```

Latest commit before this handover:

```text
71aced3 refactor(backend): organize opportunity packages
```

Recent commit context:

```text
71aced3 refactor(backend): organize opportunity packages
99299c3 feat(scoring): rank visible moon opportunities
fa58116 feat(backend): add query opportunity lookup
9e44281 refactor(backend): own opportunity search response
5faf768 cleanup, "preview" spring app deleted
33c4a71 refactor(backend): introduce opportunity search seam
```

## Current Backend Shape

The real backend module is now the only Spring HTTP implementation. The old
`prototypes/spring-preview/` module has been removed from the reactor.

Current endpoints:

```http
GET /api/opportunities?q=Praha
POST /api/opportunities/search
```

`GET /api/opportunities` is the public query-shaped path. It currently uses a
fixture resolver for `Praha`, `Prague`, and `prague-cz`, then delegates to the
same opportunity search engine as the lower-level POST endpoint.

`POST /api/opportunities/search` remains the direct fixture/prototype request
path. It accepts `locationId`, `start`, `forecastHorizonDays`,
`maxMoonAltitudeDegrees`, and `limit`.

Current backend flow:

```text
OpportunitySearchController
  -> OpportunitySearchService
      -> LocationResolver for query lookup
      -> OpportunitySearchDefaults for request-time defaults
      -> OpportunitySearchEngine
          -> PrototypeOpportunitySearchEngine
              -> jvm-scoring-prototype PreviewEvaluator
```

Important backend packages:

```text
dev.moonservice.backend.location
  LocationResolver
  ResolvedLocation
  fixture/FixtureLocationResolver

dev.moonservice.backend.opportunity
  OpportunitySearchService
  OpportunitySearchDefaults

dev.moonservice.backend.opportunity.search
  OpportunityResponse
  OpportunitySearchEngine
  OpportunitySearchRequest
  OpportunitySearchResponse
  OpportunityStatusResponse

dev.moonservice.backend.opportunity.prototype
  PrototypeOpportunitySearchEngine
```

The backend owns its response model. The prototype adapter translates prototype
JSON into backend response records and drops prototype-only fields such as
`prototype`, `ephemerisSource`, and `diagnostics`.

## Scoring Model State

The scoring prototype no longer treats 12 degrees as a hard upper bound for all
opportunities. Low Moon remains best, but visible-Moon opportunities up to 90
degrees can now rank when ambient light, illumination balance, and weather are
good.

Altitude scoring now roughly behaves as:

```text
1 to 6 degrees: best
6 to 12 degrees: still strong
12 to 40 degrees: context Moon
40 to 90 degrees: high-context Moon, weak but not invalid
```

Window kinds now distinguish:

```text
moonrise_low / moonset_low
moonrise_context / moonset_context
moonrise_high_context / moonset_high_context
```

The public query defaults now use `maxMoonAltitudeDegrees = 90.0` and derive the
start date from the resolved location's local date using an injected `Clock`.
The direct POST endpoint can still request a stricter ceiling such as 12
degrees.

Docs updated to match this behavior:

- `docs/scoring-model.md`
- `docs/api-shape.md`
- `docs/mvp-roadmap.md`

## Current Local Working Tree

At handover creation time, the only untracked files were:

```text
?? docs/session-handover-2026-06-20.md
?? moon-service.iml
```

Notes:

- `docs/session-handover-2026-06-20.md` is the previous local handover and was
  not committed in that session.
- `moon-service.iml` is IDE metadata and should remain untracked unless repo
  policy changes.
- This new `docs/session-handover-2026-06-21.md` should be committed and
  pushed as requested.

## Verification Already Run

After the visible-Moon scoring and package cleanup work, these commands passed:

```bash
mvn test
mvn test -pl backend -am
mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.classpathScope=test -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json"
python3 -B scripts/prototype_contract_parity.py
git diff --check
```

The final package cleanup was separately verified with:

```bash
mvn test -pl backend -am
git diff --check
```

## Suggested Next Steps

1. Replace the fixture `LocationResolver` with an Open-Meteo-backed resolver
   behind the existing `dev.moonservice.backend.location` seam.
2. Keep the fixture resolver available for deterministic tests.
3. Decide whether direct `POST /api/opportunities/search` should remain public,
   test-only, or become an internal/prototype endpoint before adding a web UI.
4. Add backend-owned handling for ambiguous query results before live geocoding.
5. Add live weather integration only after the location resolver contract is
   stable; keep provider calls cacheable and privacy boundaries explicit.
6. Decide later whether to delete, archive, or commit the older untracked
   `docs/session-handover-2026-06-20.md`.
