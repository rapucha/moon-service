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
Moon Service's own `MoonSample` values and a project-owned lunar angular-radius
value in degrees. It also emits project-owned `LunarEclipseShadowSample`
values for the lunar-eclipse API. `WindowGenerator.SampleProvider` keeps
window/scoring algorithms testable without upstream types. The fixture-only
backend path receives product-shaped results through `PreviewEvaluator`.
Resolved-location and typed preference searches call `OpportunityService`,
format the ordinary result through `ResponseFormatter`, and keep preference
metadata typed.

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
    - topocentric apparent lunar angular radius degrees
    - Moon illumination fraction
    - Moon phase angle or named phase
    - observer-oriented bright-limb tilt at the sampled instant
    - observer-oriented lunar north-pole tilt at the sampled instant
    - next moonrise time
    - next moonset time
    - Sun altitude degrees
    - Sun azimuth degrees
    - eclipse-shadow center right/up in Moon radii
    - eclipse umbra and penumbra radii in Moon radii
```

This boundary allows replacing the ephemeris library later without changing
scoring, HTTP contracts, or client code.

### Topocentric apparent lunar angular radius

`EphemerisSampler` obtains the observer-relative Moon distance in astronomical
units from the `Equatorial.dist` value returned by Astronomy Engine's
topocentric Moon calculation. It calculates the angular radius from that
distance and the Moon's physical mean radius, then returns only the result in
degrees.

Use `1,737.4 km` as the physical mean radius, following the
[JPL planetary-satellite physical parameters](https://ssd.jpl.nasa.gov/sats/phys_par/sep.html).
Keep the upstream distance type and calculation inside `EphemerisSampler`.

### Lunar-eclipse shadow geometry

Keep Astronomy Engine's public lunar-eclipse search authoritative for phase
contacts, subtype, maximum, and peak obscuration. For drawable geometry at one
instant, `EphemerisSampler` uses only the supported public `geoVector`,
`geoMoon`, `equator`, and rotation APIs in version 2.1.19. Do not call the
library's Kotlin-internal shadow or disc-overlap functions.

Let `s` be the corrected geocentric Sun vector, `m` the geocentric Moon vector,
and `d = -s` the Earth-shadow direction. All vectors are EQJ and use AU:

```text
u      = dot(d, m) / dot(d, d)
offset = u*d - m
```

`offset` points from the Moon center to the Earth-shadow axis. Use the named
constants `695,700.0 km` for the Sun radius, `6,371.0 km + 88.0 km` for the
effective eclipse radius of Earth, and the existing `1,737.4 km` Moon mean
radius. At the Moon's shadow plane:

```text
umbra     = earthRadius - u*(sunRadius - earthRadius)
penumbra  = earthRadius + u*(sunRadius + earthRadius)
```

The cone stays geocentric so the physical eclipse does not change by observer.
Rotate `offset` and the corrected topocentric Moon direction into the existing
horizontal frame only to express the result on the observer's screen. Project
onto the same viewer-right and local-zenith basis used for lunar pole
orientation. Divide offsets and radii by the Moon mean radius. Positive right
points toward increasing azimuth on screen; positive up points toward local
zenith. Atmospheric refraction affects the reported Moon altitude, not the
shadow offset.

Fixed tests compare the derived center distance with the required
penumbra/Moon, umbra/Moon, and totality internal tangencies at known contacts.
The allowed error is `0.01` Moon radii. This calculation is a narrow current
adapter capability, not a provider interface or fallback.

### Replacing the implementation

The shadow calculation uses library-neutral geometry on public Sun and Moon
vectors. A replacement does not need to reproduce Astronomy Engine's internal
functions. It must provide vectors in one documented frame and unit, preserve
the geocentric cone, and map its results into Moon Service's project-owned
samples. This makes the current public-API implementation easier to replace
than a dependency on Astronomy Engine's Kotlin internals.

There are two practical implementation shapes:

1. An in-process JVM library can replace the Astronomy Engine calls inside the
   adapter. This keeps one process and avoids network failure modes. Introduce
   a formal `EphemerisService` interface only when the replacement is ready to
   run beside the current implementation or another concrete caller needs it.
2. A Python service can own an implementation such as Skyfield and expose a
   private REST API to the Spring backend. A full search operation should
   accept the location and search interval and return Moon Service's event
   facts and project-owned samples, not Skyfield objects. If the service also
   offers a sampling operation, that operation should accept several instants
   at once. Both forms avoid one network call for every sampled instant.

[Skyfield's lunar-eclipse routine](https://rhodesmill.org/skyfield/almanac.html#lunar-eclipses)
finds eclipse maxima and types and reports shadow dimensions at maximum. Its
ordinary position APIs can provide the Sun, Moon, and observer vectors needed
for the same screen projection. It does not return phase contacts or
viewer-oriented geometry at arbitrary instants, so it is not a drop-in
replacement for the current search and sampler. A complete replacement must
still produce and validate Moon Service's phase contacts, per-instant shadow
samples, local visibility, and pole orientation.

Skyfield's eclipse helper also owns its own shadow-enlargement and body-radius
constants, as shown in its
[official source](https://github.com/skyfielders/python-skyfield/blob/master/skyfield/eclipselib.py).
Using its returned radii directly would rebaseline Moon Service's geometry. A
compatibility-focused replacement should keep the project constants and cone
formula around Skyfield vectors. An intentional model change needs separate
approval and new tolerances. After cutover, Astronomy Engine and Skyfield must
not remain competing authorities for one event.

A Skyfield container also owns data and runtime concerns that do not exist in
the current single-JVM deployment. Skyfield reads a JPL `.bsp` planetary
ephemeris and uses time-scale data for UTC and Earth-rotation conversions. Its
[loader can download missing files](https://rhodesmill.org/skyfield/files.html),
but a reproducible service should instead build with pinned, checksummed files
and start without downloading data. The image must also pin Python, Skyfield,
[NumPy](https://rhodesmill.org/skyfield/installation.html), the ephemeris
kernel, and [time data](https://rhodesmill.org/skyfield/time.html) as one tested
set. Lunar pole orientation needs the additional lunar frame and orientation
files described in Skyfield's
[planetary reference-frame documentation](https://rhodesmill.org/skyfield/planetary.html#observing-a-moon-location).
Pin their coverage and checksums too. A future issue must define readiness,
resource limits, timeouts, deployment, update cadence, and failure behavior
before this service becomes authoritative.

The adapter must translate conventions explicitly. Skyfield documents a
[north/east/zenith horizontal frame](https://rhodesmill.org/skyfield/coordinates.html#altitude-and-azimuth-horizonal-coordinates),
while the current Astronomy Engine path is north/west/zenith. Skyfield also leaves
[atmospheric refraction](https://rhodesmill.org/skyfield/positions.html#adjusting-altitude-for-atmospheric-refraction)
off unless the caller requests it. Preserve Moon Service's right/up signs and
normal-refraction policy unless an approved contract change says otherwise.

Use an offline comparison for either replacement:

1. Freeze the current contact, maximum, altitude/azimuth, pole-orientation, and
   shadow-geometry fixtures.
2. Run both implementations over those fixtures and the documented geographic
   validation cases.
3. Explain differences in contact times, subtype, obscuration, local
   visibility, and screen coordinates. Decide whether changed maximum times
   may change stable event IDs.
4. Cut over only after the replacement satisfies explicit tolerances. Do not
   add a production fallback merely to keep both implementations available.

Running Skyfield as an internal Moon Service container would not disclose a
location to a new third party, but it would add a required runtime component
and internal transmission of the location. An externally operated astronomy
service would also change the provider and privacy model. Either deployment
needs its own approved issue; this document does not add the service.

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
