# JVM Ephemeris Prototype

This is a script-level JVM prototype for the next Moon Service implementation
step. It is not backend, Android, database, or deployment scaffolding.

The goal is to prove that the intended production ephemeris library path can
produce the Moon/Sun facts needed by the scoring model:

- Moon apparent altitude and azimuth.
- Moon illumination percentage.
- Sun apparent altitude and light bucket.
- Contiguous low-Moon candidate windows.
- Exposure-balance labels that match the scoring spike vocabulary.

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

This repo currently has a Java runtime but no Maven, Gradle, Kotlin compiler,
or `javac`, so the prototype is written as a Java source-file launcher program:

```bash
java -cp /tmp/astronomy-2.1.19.jar:/tmp/kotlin-stdlib-jdk8-1.6.10.jar:/tmp/kotlin-stdlib-jdk7-1.6.10.jar:/tmp/kotlin-stdlib-1.6.10.jar:/tmp/kotlin-stdlib-common-1.6.10.jar \
  prototypes/jvm-ephemeris/MoonWindowPrototype.java \
  --location prague-cz \
  --start 2026-06-29 \
  --days 7 \
  --step-minutes 30 \
  --limit 5
```

Default behavior uses the Prague fixture shared with the retained Python
scoring spikes.

## Boundary

This prototype intentionally does not include:

- HTTP routes.
- Spring Boot.
- Weather provider calls.
- Persistence.
- Feed or calendar generation.
- Production package structure.

Once the output is compared with the retained JPL-based spike, the next narrow
step is to port the fixture scoring rules into JVM code and keep weather as
fixtures until the scoring contract is stable.
