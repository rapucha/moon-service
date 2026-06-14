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
- Low-Moon candidate window generation.
- Fixture weather scoring.
- Hard filters and component scores from the retained scoring contract.
- Exposure-balance labels and explanation text.
- API-shaped JSON output with `status`, `location`, `forecastHorizonDays`,
  `opportunities`, `rejected`, `messages`, and prototype diagnostics.

## Run Tests

```bash
mvn test
```

## Run The Prototype

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=dev.moonservice.prototype.MoonScoringPrototype \
  -Dexec.args="--request fixtures/prague-preview-request.json"
```

The equivalent explicit-flag form is:

```bash
mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=dev.moonservice.prototype.MoonScoringPrototype \
  -Dexec.args="--location prague-cz --start 2026-06-29 --days 7 --step-minutes 30 --min-score 50 --limit 5"
```

Default behavior uses the Prague fixture and fixed partly-cloudy fixture
weather. Maven resolves `io.github.cosinekitty:astronomy:2.1.19` from JitPack;
do not vendor dependency jars into this repo.

The request fixture is intentionally small:

```json
{
  "locationId": "prague-cz",
  "start": "2026-06-29",
  "forecastHorizonDays": 7,
  "stepMinutes": 30,
  "maxMoonAltitudeDegrees": 12,
  "minScore": 50,
  "limit": 5
}
```

## Current Boundary

This Maven prototype is the next step after the source-file JVM harness. The
retained Python scripts, especially `../../scripts/scoring_contract_spike.py`,
remain proof/reference harnesses for the scoring contract.
