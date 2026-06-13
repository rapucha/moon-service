# Scoring Model

## Goal

The scoring model should rank upcoming Moon photography opportunities for a saved location. A strong opportunity has the Moon low enough to compose with foreground subjects, enough ambient light to expose the scene, and weather that is not obviously hostile.

The first model should be explainable. Users should see why an alert fired.

## Candidate Window

Generate candidate windows around Moon visibility events:

- Moonrise.
- Moonset.
- Low-altitude passes near the horizon.
- Full or near-full Moon windows for the first mode.

Initial search horizon should match the selected weather provider's reliable forecast range. If the provider only gives useful confidence for a few days, do not pretend to predict beyond that.

## Initial Inputs

Moon geometry:

- Altitude.
- Azimuth.
- Illumination or phase.
- Rise/set timing.

Sun and light:

- Sun altitude.
- Daylight, golden hour, civil twilight, nautical twilight, or night bucket.
- Relative timing between Sun state and Moon position.

Weather:

- Cloud cover.
- Precipitation probability.
- Visibility.
- Forecast confidence if available.
- Optional condition summary, such as clear, partly cloudy, fog, rain, snow, or overcast.

User preferences:

- Location.
- Alert lead time.
- Preferred window type, initially full or near-full Moon.
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
- Allow daylight when Moon contrast is plausible.
- Penalize full night for the MVP's dynamic-range goal, while keeping it available for later night-photo modes.

Moon illumination fit:

- First mode favors full or near-full Moon.
- Crescent-specific modes can be added later rather than weakening the first model.

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

## Known Limitations

V0 ignores:

- Terrain and true horizon elevation.
- Buildings, trees, skylines, and local obstructions.
- Exact subject alignment.
- Shooting position versus subject position.
- Lens focal length and field of view.
- Forecast model disagreement unless the provider exposes it.

These are acceptable limitations for an alert-first MVP, but the UI should avoid claiming exact composition guidance.

## Research Needed

- Validate the recommended ephemeris candidate in `docs/ephemeris-research.md` against JPL Horizons before using it in product scoring.
- Pick a weather provider and map its fields into the model.
- Collect real sample days for known good and bad Moon photography conditions.
- Tune thresholds from examples rather than preference alone.
