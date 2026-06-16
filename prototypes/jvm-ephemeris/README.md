# JVM Ephemeris And Scoring Prototype

This is a script-level JVM prototype for the next Moon Service implementation
step. It is not backend, Android, database, or deployment scaffolding.

The goal is to prove that the intended production ephemeris library path can
produce the Moon/Sun facts needed by the scoring model, then apply the retained
v0 scoring contract using fixture weather:

- Moon apparent altitude and azimuth.
- Moon illumination percentage.
- Sun apparent altitude and light bucket.
- Contiguous low-Moon candidate windows.
- Component scores and `minScore` rejection.
- Exposure-balance labels that match the scoring spike vocabulary.
- API-shaped opportunity output.

## Dependency

Astronomy Engine is used through the Kotlin/JVM artifact documented upstream:

```text
io.github.cosinekitty:astronomy:2.1.19
```

For this prototype, do not vendor jars into the repo. Fetch them into `/tmp`
when running locally:

```bash
curl -fL -o /tmp/astronomy-2.1.19.jar \
  https://jitpack.io/io/github/cosinekitty/astronomy/2.1.19/astronomy-2.1.19.jar
curl -fL -o /tmp/kotlin-stdlib-jdk8-1.6.10.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.6.10/kotlin-stdlib-jdk8-1.6.10.jar
curl -fL -o /tmp/kotlin-stdlib-jdk7-1.6.10.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.6.10/kotlin-stdlib-jdk7-1.6.10.jar
curl -fL -o /tmp/kotlin-stdlib-1.6.10.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib/1.6.10/kotlin-stdlib-1.6.10.jar
curl -fL -o /tmp/kotlin-stdlib-common-1.6.10.jar \
  https://repo1.maven.org/maven2/org/jetbrains/kotlin/kotlin-stdlib-common/1.6.10/kotlin-stdlib-common-1.6.10.jar
```

## Run

The prototype is written as a Java source-file launcher program so it can stay
small while the production project structure is still intentionally undecided:

```bash
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar \
  prototypes/jvm-ephemeris/MoonWindowPrototype.java \
  --location prague-cz \
  --start 2026-06-29 \
  --days 7 \
  --step-minutes 5 \
  --min-score 50 \
  --limit 5
```

Default behavior uses the Prague fixture shared with the retained Python
scoring spikes and fixed partly-cloudy fixture weather.

## Boundary

This prototype intentionally does not include:

- HTTP routes.
- Spring Boot.
- Weather provider calls. Weather is currently a fixed fixture.
- Persistence.
- Feed or calendar generation.
- Production package structure.

After this contract-shape pass, the next narrow step is to decide whether to
keep iterating in this source-file prototype or create a minimal Maven project
before adding fixture tests.

## Contract Shape

The JVM prototype intentionally mirrors the retained Python scoring contract for
the core response fields:

- Top-level `status`, `location`, `forecastHorizonDays`, `opportunities`,
  `rejected`, and `messages`.
- Opportunity `id`, time window, `localTimeZone`, `score`, `confidence`,
  `components`, `moon`, `sun`, `weather`, `exposureBalance`, `reason`, and
  `links`.
- Location `kind`, `id`, `displayName`, coordinates, elevation, timezone, and
  country code.

The JVM output also includes prototype-only diagnostics such as sample counts,
source notes, and the generated search interval. These are useful while the
prototype is still a local harness and do not have to become public API fields.
