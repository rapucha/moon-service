# Ephemeris Research

## Decision

Use Astronomy Engine as the first ephemeris candidate for Moon Service. It has passed the first validation spike for the thin scoring prototype.

Repository and docs: <https://github.com/cosinekitty/astronomy>

Rationale:

- It has Kotlin/JVM support with Java examples, so it can run in Android Kotlin and a future JVM backend.
- It calculates apparent horizon-based positions for an observer on Earth, including altitude and azimuth.
- It supports rise and set searches for the Moon, Sun, and planets.
- It exposes Moon phase and illumination data needed by the scoring model.
- It is MIT licensed, which is simple for a private or later public app.
- It is designed to be small and dependency-light.
- The project documents validation against NOVAS, JPL Horizons, and other ephemeris sources, with a target accuracy suitable for amateur astronomy use.

Remaining caveat:

- The Kotlin/JVM package is distributed through JitPack in the upstream README, not Maven Central. Before committing to it permanently, verify that this is acceptable for Android and backend builds, or vendor/fork only if there is a strong reason.
  Tracked by [#17](https://github.com/rapucha/moon-service/issues/17).

## Candidate Comparison

### Astronomy Engine

Status: recommended first candidate.

Source: <https://github.com/cosinekitty/astronomy>

Relevant capabilities:

- `Observer` for latitude, longitude, and elevation.
- `horizon(...)` for topocentric altitude and azimuth.
- `searchRiseSet(...)` for rise/set times.
- `illumination(...)` and Moon phase functions.
- Kotlin/JVM and Java-facing API.

Tradeoffs:

- JitPack dependency source needs build-policy review, tracked by
  [#17](https://github.com/rapucha/moon-service/issues/17).
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

## Recommended Abstraction

When implementation starts, keep project code independent of the library API:

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

This boundary allows replacing the ephemeris library later without changing scoring or UI code.

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
