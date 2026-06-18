# Moon Service

Moon Service is an early-stage discovery and alert tool for photographers. The goal is to identify upcoming Moon photography opportunities near a selected city or location, with emphasis on a low Moon, useful ambient light, and promising weather.

The project is currently documentation-led with narrow prototypes under
`prototypes/`. Production backend, Android, database, and deployment
scaffolding should wait until the MVP contracts and prototype boundaries are
proven.

## MVP Direction

The favored first product is a zero-install web flow:

- Enter a city or location.
- Resolve ambiguous locations when needed.
- Show ranked Moon photography opportunities.
- Provide shareable result pages.
- Provide Atom/RSS feeds and `.ics` exports.
- Avoid mandatory accounts, cookies, saved server-side user profiles, and email alerts in v0.

Email alerts, native Android, saved personal preferences, terrain horizon modeling, and exact landmark alignment are deferred.

## Current Decisions

- Web-first MVP with a small backend later.
- Open-Meteo Geocoding as the first geocoding provider candidate.
- Raw Unicode location input, with curated alias/transliteration fallback for known provider gaps.
- Open-Meteo Weather as the first weather provider candidate.
- Astronomy Engine as the first ephemeris candidate for the thin scoring prototype.
- Browser `localStorage` may hold recent searches; the backend should not permanently store user locations in v0.

## Repository Map

- `AGENTS.md`: durable instructions for future coding-agent sessions.
- `docs/product-notes.md`: product stance, MVP scope, privacy posture.
- `docs/architecture.md`: architecture options and current web-first backend direction.
- `docs/api-shape.md`: first web/API contract.
- `docs/scoring-model.md`: v0 opportunity scoring model.
- `docs/ephemeris-research.md`: ephemeris decision and validation notes.
- `docs/weather-provider-research.md`: weather provider decision and validation notes.
- `docs/geocoding-research.md`: geocoding provider decision and validation notes.
- `docs/mvp-roadmap.md`: milestone plan and next steps.
- `scripts/geocoding_contract_spike.py`: retained Python spike for checking the v0 geocoding contract.
- `scripts/scoring_contract_spike.py`: retained Python spike for checking the v0 scoring contract with fixture data.
- `scripts/real_data_scoring_spike.py`: retained Python spike that combines live JPL Horizons ephemeris samples with live Open-Meteo weather.
- `prototypes/jvm-ephemeris/`: source-file JVM prototype using Astronomy Engine for Moon/Sun samples, low-Moon candidate windows, and fixture-weather scoring.
- `prototypes/jvm-scoring/`: minimal Maven JVM prototype with natural low-Moon windows, fixture weather scoring, and fixture tests.
- `prototypes/spring-preview/`: thin Spring Boot HTTP contract harness around the Maven scoring prototype.

## Geocoding Contract Spike

Run the repeatable fixture-based check:

```bash
python3 -B scripts/geocoding_contract_spike.py
```

Run selected queries:

```bash
python3 -B scripts/geocoding_contract_spike.py Prague 東京 Å
```

Optionally revalidate against live Open-Meteo Geocoding:

```bash
python3 -B scripts/geocoding_contract_spike.py --live --lang ja 東京 京都 大阪
```

The default fixture mode is intended for stable local checks. Live mode is for detecting provider behavior changes.

## Scoring Contract Spike

Run the fixture-based scoring check:

```bash
python3 -B scripts/scoring_contract_spike.py
```

Run with a different minimum score:

```bash
python3 -B scripts/scoring_contract_spike.py --min-score 80
```

This script does not calculate real ephemeris or fetch live weather. It uses fixed Moon, Sun, and weather samples to exercise hard filters, score components, exposure-balance hints, ranking, explanation text, and API-shaped output.

## Real-Data Scoring Spike

Run the live-provider scoring check:

```bash
python3 -B scripts/real_data_scoring_spike.py
```

Useful shorter run while iterating:

```bash
python3 -B scripts/real_data_scoring_spike.py --forecast-days 2 --limit 3
```

This script calls NASA/JPL Horizons for Moon/Sun observer samples and Open-Meteo for hourly forecast data, then reuses the scoring functions from `scripts/scoring_contract_spike.py`. It is a prototype harness, not the final ephemeris or backend integration.

## JVM Ephemeris And Scoring Prototype

Run the Astronomy Engine source-file prototype after fetching its jars into
`/tmp` as documented in `prototypes/jvm-ephemeris/README.md`:

```bash
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar \
  prototypes/jvm-ephemeris/MoonWindowPrototype.java \
  --location prague-cz --start 2026-06-29 --days 7 --step-minutes 30 --min-score 50 --limit 5
```

This prototype is the intended bridge away from the JPL-based Python validation
spike. It includes fixture-weather scoring, but does not include Spring Boot,
persistence, live weather calls, feeds, or calendar generation.

## Maven JVM Scoring Prototype

Run the Maven-based prototype tests:

```bash
cd prototypes/jvm-scoring
mvn test
```

Run the Maven prototype CLI:

```bash
cd prototypes/jvm-scoring
mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype \
  -Dexec.args="--request fixtures/prague-preview-request.json"
```

The equivalent explicit-flag form is:

```bash
cd prototypes/jvm-scoring
mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java \
  -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype \
  -Dexec.args="--location prague-cz --start 2026-06-29 --days 7 --max-altitude 12 --limit 5"
```

The request fixture shape is:

```json
{
  "locationId": "prague-cz",
  "start": "2026-06-29",
  "forecastHorizonDays": 7,
  "maxMoonAltitudeDegrees": 12,
  "limit": 5
}
```

This is still a prototype under `prototypes/`, not backend scaffolding. It uses
Astronomy Engine via JitPack and fixed fixture weather only. The `start` value
is interpreted as a local date in the fixture location timezone.

## Prototype Contract Parity

After running `mvn test` once so dependencies are available, compare the
retained Python scoring spike, the source-file JVM prototype, and the Maven JVM
prototype:

```bash
python3 -B scripts/prototype_contract_parity.py
```

The Python spike and source-file JVM prototype are retained historical
references, so exact opportunity times and scores differ intentionally. The
Maven JVM prototype is the active natural-window contract target.

## Spring Preview Prototype

Run the Spring HTTP contract harness tests:

```bash
(cd prototypes/jvm-scoring && mvn install)
(cd prototypes/spring-preview && mvn test)
```

Run the local prototype endpoint:

```bash
(cd prototypes/jvm-scoring && mvn install)
(cd prototypes/spring-preview && mvn spring-boot:run)
```

The only endpoint is `POST /api/preview`, using the same request shape as
`prototypes/jvm-scoring/fixtures/prague-preview-request.json`. This remains a
prototype: no geocoding, live weather, persistence, accounts, feeds, calendar
generation, Docker, or deployment config. The Spring harness depends on the
`jvm-scoring-prototype` Maven artifact and calls its public
`PreviewEvaluator` facade.

## Verification

For documentation-only changes:

```bash
git diff --check
```

For the current Python spike:

```bash
python3 -B scripts/geocoding_contract_spike.py
python3 -B -m py_compile scripts/geocoding_contract_spike.py
python3 -B scripts/scoring_contract_spike.py
python3 -B -m py_compile scripts/scoring_contract_spike.py
python3 -B scripts/real_data_scoring_spike.py --forecast-days 2 --limit 3
python3 -B -m py_compile scripts/real_data_scoring_spike.py
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar prototypes/jvm-ephemeris/MoonWindowPrototype.java --location prague-cz --start 2026-06-29 --days 7 --step-minutes 30 --min-score 50 --limit 5
(cd prototypes/jvm-scoring && mvn test)
(cd prototypes/jvm-scoring && mvn -q org.codehaus.mojo:exec-maven-plugin:3.3.0:java -Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype -Dexec.args="--request fixtures/prague-preview-request.json")
python3 -B scripts/prototype_contract_parity.py
(cd prototypes/jvm-scoring && mvn install)
(cd prototypes/spring-preview && mvn test)
```
