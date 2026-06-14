# Scoring Model

## Goal

The scoring model should rank upcoming Moon photography opportunities for a saved location. A strong opportunity has the Moon low enough to compose with foreground subjects, enough ambient light to expose the scene, and weather that is not obviously hostile.

The first model should be explainable. Users should see why an alert fired.

The core photographic problem is exposure balance. The Moon is much brighter than most foreground subjects and can blow out if the scene is exposed like a normal landscape. Moon Service should therefore favor windows where the Moon is visible but the sky and foreground still have enough Sun-driven ambient light that a photographer can preserve Moon detail and still keep useful scene detail.

## Candidate Window

Generate candidate windows around Moon visibility events:

- Moonrise.
- Moonset.
- Low-altitude passes near the horizon.
- Full, gibbous, quarter, and crescent Moon windows when the Moon is low enough and the light/weather context is useful.

Initial search horizon should match the selected weather provider's reliable forecast range. If the provider only gives useful confidence for a few days, do not pretend to predict beyond that.

## Initial Inputs

Moon geometry:

- Altitude.
- Azimuth.
- Illumination or phase.
- Rise/set timing.
- Observer elevation when available.

Sun and light:

- Sun altitude.
- Daylight, golden hour, civil twilight, nautical twilight, or night bucket.
- Relative timing between Sun state and Moon position.
- Exposure-balance context: whether ambient light is likely sufficient for foreground detail while keeping Moon highlights under control.

Weather:

- Cloud cover.
- Low, mid, and high cloud cover when available.
- Precipitation probability.
- Precipitation amount.
- Visibility.
- Forecast confidence if available.
- Optional condition summary, such as clear, partly cloudy, fog, rain, snow, or overcast.

User preferences:

- Location.
- Alert lead time.
- Optional preferred window type, such as low full Moon, crescent, twilight, or daylight Moon.
- Optional minimum score threshold.

## V0 Hard Filters

Reject opportunities when:

- Moon is below the horizon.
- Moon altitude is too high for the low-Moon use case.
- Weather is overcast or precipitation risk is high.
- Visibility is below the selected threshold.
- Forecast confidence is too low to produce an alert, if the provider exposes confidence.

Suggested starting thresholds:

- Moon altitude: 0 to 12 degrees.
- Precipitation probability: below 30 percent.
- Visibility: provider-specific, but reject fog/very poor visibility.
- Cloud cover: reject very high cloud cover, but do not reject partial clouds automatically.

These numbers are placeholders. Validate them against real examples before treating them as product behavior.

## Observer Elevation And Horizon Obstruction

Distinguish two concepts:

- Observer elevation: the location's height above sea level. Ephemeris calculations can use this for small parallax/refraction corrections.
- Horizon obstruction: terrain, hills, buildings, or trees that raise the effective horizon in a specific azimuth direction.

Observer elevation is safe to include in V0 when the geocoder provides it. It does not solve hilly terrain visibility.

Terrain horizon should be deferred until the product supports exact shooting positions. City-level lookup is too vague for terrain obstruction because "Prague" can mean very different viewpoints. Once exact positions exist, a later terrain model can estimate:

```text
observer location + azimuth -> terrain horizon altitude
```

Then visibility can be checked with:

```text
moon_visible = moon_altitude > terrain_horizon_altitude + margin
```

V0 should phrase low-horizon opportunities cautiously:

```text
Local hills, buildings, or trees may affect exact visibility near the horizon.
```

## V0 Score Components

Use an additive score from 0 to 100.

Suggested starting weights:

- Moon altitude fit: 30 points.
- Sun/light fit: 25 points.
- Moon illumination fit: 15 points.
- Weather fit: 25 points.
- Forecast confidence: 5 points.

Moon altitude fit:

- Best near 1 to 6 degrees.
- Still useful from 6 to 12 degrees.
- Penalize below 0 degrees or too close to the horizon if terrain/obstruction is unknown.

Sun/light fit:

- Favor golden hour and civil twilight.
- Allow daylight when Moon contrast is plausible and the Moon is visible enough to photograph.
- Penalize full night for the MVP's exposure-balance goal, while keeping it available for later night-photo modes.
- Treat nautical twilight and night cautiously: they may still work, but foreground detail is harder to retain without blending, artificial light, silhouette intent, or high dynamic range technique.

Moon illumination fit:

- Favor full or near-full Moon when all else is equal.
- Do not reject crescent Moon opportunities solely because illumination is low.
- Low crescent windows can still be useful when the Moon is close to the horizon, the sky is clear enough, and ambient light supports the intended photograph.

Exposure-balance explanation:

- Surface Sun altitude and Moon illumination together. A thin crescent in golden hour may be easy to balance; a bright full Moon in deep night may require exposing for the Moon and losing foreground detail.
- Avoid implying that the system knows the user's exact exposure settings. Camera dynamic range, lens, focal length, haze, atmospheric extinction, and post-processing choices matter.
- Prefer wording such as "ambient light should help preserve foreground detail" over exact exposure promises.

Weather fit:

- Clear sky is good.
- Partial or textured cloud can be excellent.
- Overcast, fog, rain, snow, and poor visibility should be rejected or heavily penalized.

Forecast confidence:

- Use confidence to reduce alert urgency.
- If confidence is not available, expose a neutral confidence state rather than fabricating precision.

## Alert Explanation

Each scored opportunity should include a short explanation:

```text
Moon 4.2 degrees above the east horizon near civil twilight. Forecast is partly cloudy with low precipitation risk.
```

Include the raw facts needed for a photographer to make a decision:

- Date/time range.
- Moon altitude and azimuth.
- Moon illumination.
- Sun state.
- Weather summary.
- Score or confidence label.
- Exposure-balance hint, especially when the Moon is very bright, very thin, or the Sun is below civil twilight.

## Known Limitations

V0 ignores:

- Terrain horizon elevation and local obstruction.
- Buildings, trees, skylines, and local obstructions.
- Exact subject alignment.
- Shooting position versus subject position.
- Lens focal length and field of view.
- Forecast model disagreement unless the provider exposes it.

These are acceptable limitations for an alert-first MVP, but the UI should avoid claiming exact composition guidance.

## Research Needed

- Validate the recommended ephemeris candidate in `docs/ephemeris-research.md` against JPL Horizons before using it in product scoring.
- Validate the recommended weather provider in `docs/weather-provider-research.md` against local forecast examples and map its fields into the model.
- Collect real sample days for known good and bad Moon photography conditions.
- Tune thresholds from examples rather than preference alone.
