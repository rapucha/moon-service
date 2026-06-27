# JVM Scoring Prototype

This is the smallest Maven-based JVM prototype for Moon Service ephemeris and
scoring. It ports the source-file reference in `../jvm-ephemeris/` into small
testable Java classes, but it is still only a prototype harness under
`prototypes/`.

It intentionally does not include Spring Boot, HTTP routes, persistence,
Docker, Android, deployment code, live weather integration, feeds, or calendar
generation.

## What It Exercises

- Astronomy Engine Moon/Sun sampling through JitPack.
- Natural low-Moon candidate window generation.
- Fixture weather scoring.
- Component scoring and `limit`-based top-result selection.
- Exposure-balance labels and explanation text.
- API-shaped JSON output with `status`, `location`, `forecastHorizonDays`,
  `opportunities`, `rejected`, `messages`, and prototype diagnostics.

## Run Tests

```bash
mvn test
```

## Run The Functional Fixture Harness

```bash
mvn -q test-compile org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.classpathScope=test \
  -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype \
  -Dexec.args="--request fixtures/prague-preview-request.json"
```

The runner lives under `src/test` because it is a functional fixture harness,
not a production command-line surface. The `start` date is interpreted as a
local date in the fixture location's timezone. Maven resolves
`io.github.cosinekitty:astronomy:2.1.19` from JitPack; do not vendor dependency
jars into this repo.

The request fixture is intentionally explicit:

```json
{
  "locationId": "prague-cz",
  "start": "2026-06-29",
  "forecastHorizonDays": 7,
  "maxMoonAltitudeDegrees": 12,
  "limit": 5
}
```

The public integration surface for sibling prototypes is
`dev.moonservice.scoringprototype.PreviewEvaluator`. Other classes stay
package-private unless tests need direct access inside this module.

## Current Boundary

This Maven prototype is the next step after the source-file JVM harness. The
retained Python scripts, especially `../../scripts/scoring_contract_spike.py`,
remain proof/reference harnesses for the scoring contract.
