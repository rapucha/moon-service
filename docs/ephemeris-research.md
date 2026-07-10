# Ephemeris Research

## Decision

Use Astronomy Engine `2.1.19` for the JVM backend during the MVP and tester
alpha. Its Kotlin/JVM artifact is accepted from JitPack under the explicit
build, integrity, upgrade, and fallback constraints below. This resolves
[#17](https://github.com/rapucha/moon-service/issues/17) without vendoring,
forking, or mirroring the library now.

Repository and docs: <https://github.com/cosinekitty/astronomy>

Rationale:

- Its Kotlin/JVM API is callable from the Java backend and all direct upstream
  types are currently localized in one adapter.
- It calculates apparent horizon-based positions for an observer on Earth, including altitude and azimuth.
- It supports rise and set searches for the Moon, Sun, and planets.
- It exposes Moon phase and illumination data needed by the scoring model.
- It is MIT licensed, which is simple for a private or later public app.
- It is designed to be small and dependency-light.
- The project documents validation against NOVAS, JPL Horizons, and other ephemeris sources, with a target accuracy suitable for amateur astronomy use.

### Build and provenance constraints

- Keep the exact Maven coordinate
  `io.github.cosinekitty:astronomy:2.1.19`. Do not use a branch, snapshot,
  version range, `latest`, or a silently substituted artifact.
- Upstream release tag [`v2.1.19`](https://github.com/cosinekitty/astronomy/releases/tag/v2.1.19)
  resolves to commit `61dc07020aaa6885d2c7f688a4d82beaf6edb9ef`.
  The tag and JitPack artifact are not cryptographically signed. JitPack's
  public build log also shows that its publication command excluded upstream
  tests, so Moon Service's regression and reference validation remain part of
  accepting the binary.
- The independently observed JAR SHA-256 on 2026-07-10 is
  `d2ec1432e2d280e3bff7f776c884260bae64d0bc53c8d117b8e65a3d9cfc6646`.
  The corresponding POM SHA-256 is
  `b998a89e2177d06005e7135ac50eb730ca11cdc28f3961d9984d50b3067573c6`.
  A fresh JitPack download and the existing Maven cache matched. Both trusted
  checksums are committed under `.mvn/checksums`; Maven verifies downloaded and
  already-cached project artifacts against them. The scoring module's
  `validate` phase also re-hashes the resolved JAR and POM and requires exact
  entries for `astronomy.version` in that manifest, independent of the remote
  repository ID. Treat a missing entry or mismatch as a failed dependency
  review, not as an automatic upgrade.
- The artifact is not available from Maven Central. JitPack documents public
  artifacts as immutable after seven days and continues serving an existing
  build if its source tag or repository disappears. This artifact was built
  from the recorded commit on 2023-12-31, so it is frozen under that policy.
- Project Maven configuration enables the group-ID remote-repository filter so
  JitPack may serve only `io.github.cosinekitty` and its subgroups. Trusted
  SHA-256 values independently pin the Astronomy Engine JAR and POM. The POM
  disables JitPack snapshots and requires repository checksum validation to
  succeed as an additional transport check. These controls require Maven 3.9
  or newer, which the root build enforces; the container build and current
  development environment use Maven `3.9.16`.
- The upstream POM requests Kotlin stdlib `1.6.10`; the pinned Spring Boot
  parent currently mediates the packaged backend to Kotlin stdlib `2.3.21`.
  Review the resolved dependency tree whenever the Spring Boot parent or
  Astronomy Engine changes rather than assuming only `astronomy.version`
  controls the runtime.

### Failure and upgrade policy

- JitPack is a build-time dependency only. A cold-resolution outage must fail
  a new build; it must never replace the artifact or bypass tests. Published
  digest-pinned images, the running Pi, and its retained rollback image do not
  contact JitPack at runtime.
- Maven and container-build caches improve availability but are not the source
  of truth. If repeated JitPack outages block cold builds, the checksum changes
  unexpectedly, or stronger offline provenance becomes necessary, open a
  focused follow-up to mirror the verified artifact or vendor the exact
  MIT-licensed source. Do not improvise a different library during an outage.
- Any version upgrade must deliberately update the version, upstream commit,
  observed checksum, and license notice; inspect the resolved Kotlin runtime;
  then rerun backend tests, prototype parity, and the documented JPL reference
  checks before promotion.
- The backend artifact carries Astronomy Engine's full MIT notice at
  `META-INF/LICENSE-Astronomy-Engine.txt`.

### Future client boundary

This is a JVM backend decision, not a mobile-framework decision. A future
installed client should consume the backend's canonical opportunity results by
default and therefore needs no JitPack dependency. This decision does not
approve JitPack for a future Gradle or installed-client build. React Native with
Expo is now the leading cross-platform client candidate to evaluate under
[#109](https://github.com/rapucha/moon-service/issues/109), but it has not been
selected or scaffolded. If an offline client-side ephemeris preview later has
proven user value, evaluate and validate Astronomy Engine's separately
published JavaScript/npm implementation as its own dependency decision.

## Candidate Comparison

### Astronomy Engine

Status: accepted for the JVM backend MVP and tester alpha under the constraints
above.

Source: <https://github.com/cosinekitty/astronomy>

Relevant capabilities:

- `Observer` for latitude, longitude, and elevation.
- `horizon(...)` for topocentric altitude and azimuth.
- `searchRiseSet(...)` for rise/set times.
- `illumination(...)` and Moon phase functions.
- Kotlin/JVM and Java-facing API.

Tradeoffs:

- The Kotlin/JVM artifact is available through JitPack rather than Maven
  Central, so cold builds retain a third-party availability dependency.
- The pinned tag and artifact are unsigned, and JitPack excluded upstream tests
  while building it. Exact pinning, repository filtering, checksum failure,
  Moon Service regression tests, and the documented fallback bound that risk.
- API is astronomy-oriented rather than photography-oriented, so Moon Service still needs its own candidate-window and scoring layer.

### Time4J / Time4A

Status: secondary candidate or cross-check source.

Source: <https://github.com/MenoData/Time4J>

Relevant capabilities:

- Java time library with sun/moon astronomy support.
- Android users are directed to the sister project Time4A.

Tradeoffs:

- LGPL-2.1 license is workable in some cases, but it adds more distribution and compliance considerations than MIT.
- It is a broad date/time/calendar library, so adopting it only for Moon calculations may be heavier than needed.

### Swiss Ephemeris

Status: do not use for MVP.

Source: <https://www.astro.com/swisseph/swephinfo_e.htm>

Relevant capabilities:

- Very high precision ephemeris based on JPL data.
- Long historical/future time coverage.

Tradeoffs:

- Dual licensed under AGPL or a paid professional license.
- The AGPL path is not a good fit for this project unless the whole distribution and service model intentionally adopts AGPL.
- The precision and data footprint are unnecessary for alert-level Moon photography planning.

## Current Adapter Boundary

The current code does not implement a public `EphemerisService` interface, and
issue #17 does not add one only for hypothetical replacement. Direct Astronomy
Engine types and calls are concentrated in `EphemerisSampler`, which emits
Moon Service's own `MoonSample` values. `WindowGenerator.SampleProvider` keeps
window/scoring algorithms testable without upstream types, and the backend HTTP
surface receives product-shaped results through `PreviewEvaluator`.

That localized adapter is sufficient for the MVP. Introduce a formal provider
interface only when a second implementation, a move out of the retained
prototype module, or a concrete production test seam requires it. Preserve the
following project-owned data boundary during that change:

```text
EphemerisService
  input:
    - UTC instant
    - latitude
    - longitude
    - elevation meters

  output:
    - Moon altitude degrees
    - Moon azimuth degrees
    - Moon illumination fraction
    - Moon phase angle or named phase
    - observer-oriented bright-limb tilt at the sampled instant
    - observer-oriented lunar north-pole tilt at the sampled instant
    - next moonrise time
    - next moonset time
    - Sun altitude degrees
    - Sun azimuth degrees
```

This boundary allows replacing the ephemeris library later without changing
scoring, HTTP contracts, or client code.

### Observer-oriented bright-limb tilt

The suggested-time bright-limb direction is derived from the same apparent
topocentric horizontal Moon and Sun positions used elsewhere. Let `hm` and `hs`
be Moon and Sun altitude, and let `Am` and `As` be their azimuths, all in
radians. Project the Sun direction into the tangent plane at the Moon:

```text
right = cos(hs) * sin(As - Am)
up    = sin(hs) * cos(hm) - cos(hs) * sin(hm) * cos(As - Am)
tilt  = normalizeDegrees(toDegrees(atan2(right, up)))
```

The public convention is horizon-aligned and directly renderable: zero degrees
points toward local zenith, 90 degrees points right toward increasing azimuth,
and angles increase clockwise in `[0, 360)`. If both tangent-plane components
are negligible, such as exact conjunction or opposition, the direction is
undefined and the API value is `null`.

### Observer-oriented lunar north-pole tilt

The suggested-time lunar-axis direction uses Astronomy Engine's J2000 lunar
north-pole vector and its corrected topocentric J2000 Moon vector. Rotate both
vectors into the local horizontal frame, where `x` is north, `y` is west, and
`z` is zenith. After normalizing the Moon line-of-sight vector `m`, form a
tangent-plane basis:

```text
zenith = (0, 0, 1)
right  = normalize(cross(m, zenith))
up     = normalize(zenith - dot(zenith, m) * m)

poleRight = dot(lunarNorthPole, right)
poleUp    = dot(lunarNorthPole, up)
tilt      = normalizeDegrees(toDegrees(atan2(poleRight, poleUp)))
```

`right` points toward increasing azimuth even though the horizontal frame uses
a west-positive `y` axis. The public convention matches the bright-limb field:
zero degrees points toward local zenith, 90 degrees points right toward
increasing azimuth, and angles increase clockwise in `[0, 360)`. Non-finite or
degenerate projections produce `null`.

This value describes only the lunar north rotational pole direction on the
observer's screen. It does not include libration or provide the sub-observer
lunar longitude and latitude needed to shift texture sampling across the disk.
The rigid pole projection is geometric and airless even though reported Moon
and Sun altitudes use normal refraction; differential refraction across the
lunar disk would be a small distortion rather than a single rotation angle.
Do not use Astronomy Engine's prime-meridian `spin` value for this field: doing
so would incorrectly make the Earth-facing texture spin through each month.

## Validation Source

Use NASA/JPL Horizons as the primary reference for validation.

API docs: <https://ssd-api.jpl.nasa.gov/doc/horizons.html>

Useful Horizons behavior:

- `EPHEM_TYPE=OBSERVER` provides observer ephemerides.
- `CENTER='coord'` plus `SITE_COORD` can represent an arbitrary latitude/longitude/elevation.
- `QUANTITIES` can request observer quantities such as apparent coordinates and azimuth/elevation.
- `STEP_SIZE` supports fixed time steps and rise/transit/set event output modes for topocentric observers.
- Horizons URL parameters that contain spaces or comma-separated values must keep the single-quoted value syntax shown in the official API examples, such as `QUANTITIES='4,10'`, `SITE_COORD='14.4378,50.0755,0.250'`, and `START_TIME='2026-Jun-29 18:00'`.
- For city-level Moon Service validation, compare Astronomy Engine `SearchRiseSet` against Horizons `STEP_SIZE='1m GEO'`, not `TVH`. `TVH` includes true visual horizon dip from site altitude; city elevation is observer elevation above sea level, not height above a local flat horizon.

Use a second source, such as Time4J/Time4A or a reputable public astronomy calculator, only to sanity-check that the JPL query was configured correctly.

## Validation Cases

Use UTC internally. Local time is listed only to make manual review easier.

### Case 1: Prague Near Full Moon

Purpose: validate normal mid-latitude Moon altitude, azimuth, illumination, and rise/set behavior near the main target use case.

- Location: Prague, Czech Republic.
- Coordinates: 50.0755 N, 14.4378 E.
- Elevation: 250 m.
- Time window: 2026-06-29 18:00 UTC to 2026-06-30 04:00 UTC.
- Local time: Europe/Prague.
- Expected reference: JPL Horizons observer table for Moon with azimuth/elevation and rise/set markers.

### Case 2: Low Moon Window In Western Europe

Purpose: validate low-altitude filtering around a practical horizon opportunity.

- Location: Amsterdam, Netherlands.
- Coordinates: 52.3676 N, 4.9041 E.
- Elevation: 0 m.
- Time window: 2026-07-29 18:00 UTC to 2026-07-30 04:00 UTC.
- Local time: Europe/Amsterdam.
- Expected reference: JPL Horizons observer table for Moon sampled every 10 minutes, plus interpolated crossing through 0 to 12 degrees altitude.

### Case 3: Southern Hemisphere Regression Case

Purpose: prevent north-hemisphere assumptions in azimuth, rise/set, and scoring.

- Location: Wellington, New Zealand.
- Coordinates: 41.2924 S, 174.7787 E.
- Elevation: 0 m.
- Time window: 2026-08-28 05:00 UTC to 2026-08-28 17:00 UTC.
- Local time: Pacific/Auckland.
- Expected reference: JPL Horizons observer table for Moon with azimuth/elevation and rise/set markers.

## Acceptance Tolerances

For the first implementation spike:

- Moon altitude should be within 0.25 degrees of the reference for sampled instants.
- Moon azimuth should be within 0.25 degrees of the reference for sampled instants.
- Moonrise and moonset should be within 2 minutes of the reference.
- Moon illumination fraction should be within 0.02 of the reference or equivalent trusted source.

These tolerances are tighter than the product needs, but loose enough to avoid wasting time on harmless differences in refraction settings, elevation, and timescale handling.

## Validation Spike Results

Date run: 2026-06-13 UTC.

Method:

- Used Astronomy Engine's Python implementation as a temporary local proxy for the same library family.
- Used JPL Horizons `EPHEM_TYPE='OBSERVER'`, Moon target `COMMAND='301'`, `APPARENT='REFRACTED'`, `ANG_FORMAT='DEG'`, `QUANTITIES='4,10'`, and 10-minute samples for altitude, azimuth, and illuminated fraction.
- Used Astronomy Engine equator-of-date topocentric coordinates with aberration enabled, then `Horizon(..., Refraction.JplHorizons)` for sampled altitude/azimuth comparison.
- Used Horizons `STEP_SIZE='1m GEO'` plus `R_T_S_ONLY='YES'` for rise/set comparison against Astronomy Engine `SearchRiseSet`.

Observed maximum differences:

```text
Prague, 61 samples
  max altitude delta: 0.000486 degrees
  max azimuth delta:  0.000718 degrees
  max illum delta:    0.060654 percentage points
  rise delta:         0.568 minutes
  set delta:          0.788 minutes

Amsterdam, 61 samples
  max altitude delta: 0.000705 degrees
  max azimuth delta:  0.000975 degrees
  max illum delta:    0.044290 percentage points
  rise delta:         0.261 minutes
  set delta:          no set event inside the validation window

Wellington, 73 samples
  max altitude delta: 0.001107 degrees
  max azimuth delta:  0.001607 degrees
  max illum delta:    0.033735 percentage points
  rise delta:         0.667 minutes
  set delta:          no set event inside the validation window
```

Conclusion:

- Astronomy Engine is accurate enough for the first thin scoring prototype.
- The sampled altitude and azimuth differences are far below the 0.25 degree tolerance.
- Rise/set differences are below the 2 minute tolerance when Horizons `GEO` mode is used.
- Illumination differences are far below the 0.02 fraction tolerance. The table above reports percentage points; the worst case is about `0.000607` as a 0 to 1 fraction.
- Do not use Horizons `TVH` mode for city-level validation unless intentionally modeling height above a visible local horizon. In Prague, `TVH` shifted rise/set by roughly 4 to 5 minutes compared with Astronomy Engine's default city-level rise/set behavior.

## Implementation Notes For Later

- Normalize all calculations to UTC instants.
- Store latitude and longitude as decimal degrees.
- Include elevation when known, but allow `0 m` as a default.
- Decide whether altitude means apparent refracted altitude or geometric altitude, and keep that choice consistent.
- For alert scoring, apparent refracted altitude is likely more user-relevant near the horizon.
- Never mix local civil time into core calculations except for display.
