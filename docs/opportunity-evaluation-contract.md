# Opportunity Evaluation Contract

This document describes the opportunity evaluator that runs today. It is the
authority for current Moon-window generation, hard preferences, scoring,
ordering, and score-basis data.

This document does not describe future scoring ideas.

## Authority Map

| Question | Authority |
| --- | --- |
| What does the evaluator do now? | This document and the runtime sources linked under [Implementation Authority](#implementation-authority). |
| What is the product trying to rank, and why? | [Scoring Model](scoring-model.md). |
| Where do fields appear in public HTTP requests and responses? | [API Shape](api-shape.md) and [issue #271](https://github.com/rapucha/moon-service/issues/271). |
| What can change after real-world calibration? | [Issue #33](https://github.com/rapucha/moon-service/issues/33). |
| Which work owns this contract and the current hard preferences? | [Issue #270](https://github.com/rapucha/moon-service/issues/270) owns this contract. [Issue #78](https://github.com/rapucha/moon-service/issues/78) owns Version 1 hard preferences. |

## Terms

| Term | Meaning |
| --- | --- |
| Physical Moon pass | One continuous interval when the apparent refracted Moon altitude is above the horizon. |
| Natural window | One retained interval of a pass where Moon altitude stays inside the configured visible band. A typical single-peak pass has rise-side and set-side windows around the sampled peak, but a long pass can have more. |
| Matching fragment | A continuous part of a natural window where every active Version 1 hard preference matches. |
| Practical envelope | One returned opportunity made by joining nearby matching fragments under the ten-minute grouping rule. |
| `startsAt` and `endsAt` | The bounds of a natural window, matching fragment, or grouped practical envelope. |
| `suggestedAt` | One evaluated instant inside the relevant natural window or retained matching fragment. It is not a promise of perfect composition or local visibility. |
| Weather-ranking mode | The closed scoring choice `balanced`, `prefer_clear`, or `ignore_weather`. |
| Score basis | The component points, active maximum, and excluded components used to calculate the final 0–100 score. |

## Evaluation Flow

The evaluator runs in this order:

```text
location and search horizon
  -> physical Moon passes and natural windows
  -> Version 1 hard preferences, when active
  -> live notBefore handling
  -> nearby-fragment grouping, when preferences are active
  -> thin-crescent near-conjunction rejection
  -> hourly weather at suggestedAt
  -> component scores and final score
  -> selected result order
  -> global result limit
```

Weather is fetched before scoring in every weather-ranking mode. A weather
provider failure therefore has the same effect under `ignore_weather` as it
does under the other modes.

## Candidate Moon Passes And Natural Windows

The evaluator uses apparent refracted Moon altitude. A physical pass is bounded
by Moonrise, Moonset, or a search-horizon edge when the pass continues outside
the request. Local midnight does not split a pass.

The visible band is inclusive:

```text
0 degrees <= Moon altitude <= maxMoonAltitudeDegrees
```

`maxMoonAltitudeDegrees` must be from 0 through 90. A typical single-peak pass
produces no natural window, one window, or two windows:

- The rise-side window runs from Moonrise or the search start to the upward
  ceiling crossing or sampled pass peak.
- The set-side window runs from the sampled pass peak or downward ceiling
  crossing to Moonset or the search end.

The generator does not impose a two-window cap. A long or non-monotonic pass
can produce more windows when it crosses the ceiling several times.

The current generator brackets horizon and ceiling crossings with one-hour
samples. It refines a detected crossing until the bracket is no more than one
second wide, then stores a whole-second instant. A crossing that enters and
leaves between two bracket samples can be missed.

The pass peak is the highest sample on a five-minute grid, including the pass
boundaries. It is not an analytical maximum. If several samples share the
highest altitude, the earliest one wins. If the highest sample is a pass
boundary, the generator does not add a separate peak split.

These rules create natural intervals. They do not create a separate
opportunity for every astronomy sample.

## Window Times And `suggestedAt`

Without active hard preferences, `startsAt` and `endsAt` are the natural-window
bounds. With active preferences, they bound one matching fragment or one
grouped practical envelope.

The current evaluator chooses `suggestedAt` from a candidate set built around
a five-minute grid. Without active preferences, the grid begins at the
interval start, or at the live `notBefore` adjustment when that is later, and
includes the interval end. With active preferences, the candidate range starts
at the later of the matching-fragment start and live `notBefore`. It includes
that range start, the fragment end, and five-minute pass-anchored samples
between them. The evaluator maximizes:

```text
candidateFit = moonAltitudeFit + sunLightFit
```

An earlier instant wins a tie. Moon illumination, weather, and forecast age do
not choose `suggestedAt`. They affect the later window score.

The stored `startsAt`, `suggestedAt`, and `endsAt` values are not rounded for
display grouping. Crossing refinement may still make a boundary approximate,
as described above.

### Live `notBefore`

Without active preferences, the evaluator handles `notBefore` after generating
natural windows:

- It drops a window when `endsAt` is equal to or before `notBefore`.
- It keeps an ongoing window's original `startsAt`.
- It chooses a new suggestion no earlier than the later of `startsAt` and
  `notBefore`.
- It leaves a future window unchanged.

With active preferences, the evaluator filters each complete natural window
first. It then drops a matching fragment when its `endsAt` is equal to or
before `notBefore`. Every retained fragment gets a matching suggestion no
earlier than the later of the fragment start and `notBefore`. An ongoing
fragment keeps its original start.

## Version 1 Hard Preferences

Version 1 preferences are request-scoped hard constraints. They remove times
before ranking. They do not change scoring weights.

A present preference object must have version `1`. The object and each filter
inside it are optional. An absent object and a Version 1 object with no active
filters preserve the unfiltered candidates, scores, and requested result order.

### Supported Filters

| Filter | Current rule |
| --- | --- |
| `altitudeDegrees` | One inclusive minimum and maximum. Both must be from 0 through 90, and minimum must not exceed maximum. |
| `azimuthDegrees.included` | One directed compass sector. It is optional when an excluded sector is present. |
| `azimuthDegrees.excluded` | One directed obstruction sector. When an included sector is also present, the exclusion must stay inside it. |
| `time.local_clock` | One location-local clock window with minute-precision endpoints. Start is inclusive and end is exclusive. |
| `time.light_bucket` | One or more of `daylight`, `golden_hour`, `civil_twilight`, `nautical_twilight`, and `night`. |
| `namedPhases` | One or more named Moon phases. |
| `brightLimbOrientationDegrees` | From one through eight inclusive directed ranges. |

Azimuth and bright-limb ranges use degrees in `[0, 360)`. A start greater than
the end crosses `0°`. Equal endpoints are invalid. Bright-limb range endpoints
are inclusive.

The local-clock filter uses the resolved location's timezone, not the browser
timezone. Equal start and end values are invalid. A start later than the end
crosses midnight. A daylight-saving gap contains no matching instant. Both
real instants in a daylight-saving overlap match when their local clock value
is allowed. A request cannot combine a local-clock window with ambient-light
buckets.

Ambient-light buckets use Sun altitude:

| Bucket | Sun altitude |
| --- | ---: |
| `daylight` | at least `6°` |
| `golden_hour` | at least `-0.833°` and below `6°` |
| `civil_twilight` | at least `-6°` and below `-0.833°` |
| `nautical_twilight` | at least `-12°` and below `-6°` |
| `night` | below `-12°` |

Named phases use normalized phase angle:

| Phase | Angle range |
| --- | --- |
| `new_moon` | `[337.5°, 360°)` or `[0°, 22.5°)` |
| `waxing_crescent` | `[22.5°, 67.5°)` |
| `first_quarter` | `[67.5°, 112.5°)` |
| `waxing_gibbous` | `[112.5°, 157.5°)` |
| `full_moon` | `[157.5°, 202.5°)` |
| `waning_gibbous` | `[202.5°, 247.5°)` |
| `last_quarter` | `[247.5°, 292.5°)` |
| `waning_crescent` | `[292.5°, 337.5°)` |

### Bright-Limb Orientation

Bright-limb orientation uses the observer's view. `0°` points toward local
zenith. `90°` points right, toward increasing azimuth.

A sample does not match an active bright-limb filter when
`brightLimbTiltDegrees` is absent. A sample in the `full_moon` phase bucket also
does not match, even if it carries a numeric tilt. This Full Moon rule applies
whether named phases are unrestricted or explicitly include `full_moon`.
Without a bright-limb filter, Full Moon samples use the normal named-phase
rule. `northPoleTiltDegrees` is display data, not a preference field.

### Lunar-Disk Azimuth

The azimuth filter checks the visible lunar disk, not only the Moon's center.
The ephemeris supplies the topocentric apparent angular radius at the location
and instant. The filter projects that radius onto compass bearing with
spherical geometry.

The allowed set is the included sector minus the excluded obstruction. When
only an excluded sector is present, the full compass is implicitly included.

A sample matches when the disk has a positive-area overlap with the allowed
set. A partly allowed disk matches. A disk fully outside the included sector or
fully hidden by the obstruction does not match. A tangent with zero overlap
does not match. A narrow obstruction may be unable to hide the full disk. When
the disk contains the zenith, its bearing footprint spans the full compass.

### Sampling And Refined Boundaries

The hard filter samples every five minutes from an anchor at the physical pass
start. It also samples the natural-window boundaries. When the combined result
of all active filters changes between adjacent samples, it refines that
transition to one-second tolerance.

This applies to altitude, lunar-disk azimuth, local-clock and ambient-light
boundaries, named-phase changes, and bright-limb ranges. Sampling brackets the
transition. It cannot guarantee detection of an enter-and-exit event that
happens fully between two samples. The contract sets no minimum azimuth-sector
width to hide this limit.

### Grouping Nearby Fragments

After `notBefore` handling, the evaluator joins consecutive fragments only
when all these rules hold:

- They have the same `moonPass.id`.
- Their natural source-window coverage touches or overlaps.
- The precise gap between retained fragments is no more than ten minutes.

Joining is transitive across several qualifying gaps. It never restores an
expired fragment, bridges a system-created gap between natural windows, or
joins different physical passes.

One fragment stays unchanged. A grouped opportunity keeps the earliest start
and latest end. It chooses among the member suggestions before that end. It
first prefers suggestions that pass the thin-crescent near-conjunction rule,
then uses `candidateFit`, then the earlier instant. If every member suggestion
fails the visibility rule, the later ordinary rejection records the grouped
opportunity. The selected member supplies `windowKind`.

A grouped practical envelope can contain a short time that does not match a
hard preference. Its `suggestedAt` always lies in a matching fragment. Its
`moonPath` is the chronological, deduplicated union of member path points plus
the combined boundaries and suggestion. It does not claim a continuous match
through the tolerated gap.

### Preference Diagnostics

When azimuth filtering is active, the evaluator calculates all continuous
azimuth-only matching intervals across each complete physical pass. It does so
before applying other filters, ranking, or the result limit. The mask covers
the inclusive pass domain even when live `notBefore` removes an earlier
opportunity. Its intervals are sorted and do not overlap.

The returned mask is complete for each physical pass represented by a returned
opportunity. Opportunities from the same pass share the same mask. No active
azimuth filter means no mask. Clients must not infer the mask from returned
windows or Moon-center path samples.

The result also reports normalized active filters and one excluded-sample
count. It counts a distinct pass-and-instant sample once when any active filter
rejects it. It does not count display-only path samples or samples used only to
refine a crossing. It does not return excluded samples as another result list.

## Ordinary Hard Rejection

After preference filtering and grouping, the evaluator rejects an opportunity
only when both statements are true at `suggestedAt`:

```text
moonIlluminationPercent < 1
moonSunSeparationDegrees < 8
```

Both comparisons are strict. The rejection protects extremely thin crescents
near conjunction. A value equal to `1%` illumination or `8°` separation does
not meet this rejection rule.

Natural-window altitude and active Version 1 preferences are also hard
constraints earlier in the flow. Weather, precipitation, visibility, forecast
age, and total score are not hard filters. The evaluator has no minimum-score
cutoff. If hard preferences or the ordinary rejection remove everything, the
result is successful with an empty opportunity list.

## Score Components

All component values come from the facts at `suggestedAt`.

| Component | Maximum | What contributes | Why this allocation? |
| --- | ---: | --- | --- |
| `moonAltitudeFit` | 30 | Moon altitude in degrees. | The first scoring spike made low-Moon geometry the largest component. The exact 30-point share is not empirically calibrated. |
| `sunLightFit` | 25 | The Sun-altitude light bucket. | The product aims to keep useful foreground light. The exact 25-point share is provisional. |
| `moonIlluminationFit` | 15 | Illuminated percentage of the Moon. | Brighter phases rank higher by default. The exact 15-point share is provisional. |
| `weatherFit` | 25 | Total cloud cover, precipitation probability, and visibility. | The exact share and its internal split are provisional. |
| `forecastConfidence` | 5 | Forecast age in hours. | The exact 5-point share and age bands are provisional. |

The maxima add to 100. `ignore_weather` removes the last two components, so
its active maximum is the derived value `30 + 25 + 15 = 70`.

### Moon Altitude: 0–30 Points

For Moon altitude `a` in degrees, the evaluator uses these inclusive upper
bounds. `round` means Java's nearest-integer rounding; a half value rounds up.

| Altitude | Points |
| --- | --- |
| below `0°` or above `90°` | `0` |
| `0°` through `1°` | `round(18 + a * 12)` |
| above `1°` through `6°` | `30` |
| above `6°` through `12°` | `round(30 - ((a - 6) / 6) * 8)` |
| above `12°` through `25°` | `round(22 - ((a - 12) / 13) * 8)` |
| above `25°` through `40°` | `round(14 - ((a - 25) / 15) * 6)` |
| above `40°` through `70°` | `round(8 - ((a - 40) / 30) * 4)` |
| above `70°` through `90°` | `round(4 - ((a - 70) / 20))` |

### Sunlight: 7–25 Points

| Light bucket | Points |
| --- | ---: |
| `golden_hour` | 25 |
| `civil_twilight` | 24 |
| `daylight` | 16 |
| `nautical_twilight` | 14 |
| `night` | 7 |

The Sun-altitude boundaries are in the hard-preference table above. The
sunlight curve uses the same buckets.

### Moon Illumination: 4–15 Points

| Illuminated Moon | Points |
| --- | ---: |
| at least `95%` | 15 |
| at least `85%` and below `95%` | 12 |
| at least `70%` and below `85%` | 10 |
| at least `30%` and below `70%` | 8 |
| at least `5%` and below `30%` | 6 |
| below `5%` | 4 |

These altitude, light, and illumination curves are inherited rule-of-thumb
values. They protect the current product judgment but do not prove which
conditions make a good photograph.

## Weather-Ranking Modes

The scorer supports exactly three request-scoped modes.

| Mode | Active components | Score basis |
| --- | --- | --- |
| `balanced` | All five components. | `componentMaximum = 100`; no exclusions. This is the default when the mode is omitted. |
| `prefer_clear` | All five components. | `componentMaximum = 100`; no exclusions. Only the cloud curve changes. |
| `ignore_weather` | Moon altitude, sunlight, and illumination. | `componentMaximum = 70`; excludes `weatherFit` and `forecastConfidence`. |

An omitted mode and explicit `balanced` use the same components, score,
confidence label, explanation, and order.

### `balanced` Weather Formula

```text
cloud = max(0, 13 - round(abs(cloudCoverPercent - 35) / 5.0))
precipitation = max(0, 7 - round(precipitationProbabilityPercent / 5.0))
visibility = visibilityMeters >= 20000 ? 5 : visibilityMeters >= 15000 ? 4 : 2
weatherFit = min(25, cloud + precipitation + visibility)
```

`balanced` gives the most cloud points near 35% total cloud cover. It treats
some cloud as useful texture. The other two terms prefer a lower chance of
precipitation and longer visibility.

### `prefer_clear` Weather Formula

`prefer_clear` changes only the cloud line:

```text
cloud = max(0, 13 - round(cloudCoverPercent / 5.0))
```

It keeps the `balanced` precipitation, visibility, and `weatherFit` formulas.
Clear sky gets the most cloud points. This is a soft ranking preference, not a
filter.

### Rounding And Plateaus

Cloud cover and precipitation probability arrive as whole percentages. Java
rounding makes the formulas step through plateaus:

- `balanced` gives all 13 cloud points from 33% through 37% cover. It loses the
  first point at 32% and 38%. It reaches zero cloud points at 98% through 100%.
- `prefer_clear` gives all 13 cloud points from 0% through 2%. It loses the
  first point at 3% and reaches zero at 63% through 100%.
- Precipitation gets all 7 points from 0% through 2% probability. It loses the
  first point at 3%, has 1 point from 28% through 32%, and has zero from 33%
  through 100%.
- Visibility is not interpolated. At least 20,000 metres gets 5 points. From
  15,000 through 19,999 metres gets 4. Below 15,000 metres gets 2.

### Worked Cloud Examples

These examples hold precipitation probability at 0% and visibility at 20 km.
Those two facts contribute `7 + 5 = 12` points in both modes.

| Total cloud | `balanced` cloud | `balanced weatherFit` | `prefer_clear` cloud | `prefer_clear weatherFit` |
| ---: | ---: | ---: | ---: | ---: |
| 0% | 6 | 18 | 13 | 25 |
| 35% | 13 | 25 | 6 | 18 |
| 65% | 7 | 19 | 0 | 12 |
| 100% | 0 | 12 | 0 | 12 |

The last row matters: even 100% cloud cover gets 12 of 25 weather points when
precipitation probability is 0% and visibility is at least 20 km. The mode
prefers clear sky; it does not require clear sky.

## Where The Numbers Came From

The honest short answer is that most exact values did not come from measured
photography results. They are traceable product choices, but they are not a
fitted model.

### Project history

| Change | First project record | What that record tells us |
| --- | --- | --- |
| The 0–100 scale, `30 / 25 / 15 / 25 / 5` component shares, and preferred Moon altitude of `1°` through `6°` | Initial planning document in [commit `b6fee60`](https://github.com/rapucha/moon-service/commit/b6fee60fc43972193b676996c42e7017c0e18b6b) | The document calls the weights “suggested starting weights.” Its research list says to tune thresholds from real examples, but it supplies no examples or calibration. |
| First executable score, including sunlight points, `balanced` weather formula, forecast-age points, and final-confidence bands | Fixture-backed scoring spike in [commit `2942a32`](https://github.com/rapucha/moon-service/commit/2942a32417c618e5c9b7b703e08d0b48e8514ae8) | The commit turns the plan into code with fixed samples. It gives no study, photo corpus, provider guidance, or fitted data for the exact values. |
| Current six-band illumination curve | Real-data scoring spike in [commit `da1ab0a`](https://github.com/rapucha/moon-service/commit/da1ab0af8b560610e329f186badda5b7b4d6a684) | The change says not to reject crescents only because illumination is low. It gives no evidence for the exact thresholds or points. The JVM prototype later carried the same curve into Java. |
| Current full-altitude curve | Context-Moon expansion in [commit `99299c3`](https://github.com/rapucha/moon-service/commit/99299c3d68de582caaa0f3b313c55caed9a6923f) | The recorded reason is to keep higher context-Moon shots available while ranking low-Moon shots first. It gives no evidence for the exact breakpoints or slopes. |
| Current factual weather-summary bands | Pass-card work in [commit `37b644a`](https://github.com/rapucha/moon-service/commit/37b644a6cc283271527bf2a797ef78d0fdcf7927) | The codes and thresholds create useful display labels. The record contains no calibration for the local cloud and visibility cutoffs. |
| `1%` and `8°` ordinary-visibility rejection | Near-conjunction guard in [commit `583053e`](https://github.com/rapucha/moon-service/commit/583053ec0b7c81b805fe205d4e0871e874dc0b44) | The guard was added for computed Prague and Abu Dhabi cases with about 0.1% to 0.3% illumination and only a few degrees of separation. Those examples support having a guard. They do not prove that exactly 1% and 8° are optimal worldwide. |
| `prefer_clear` target of `0%` cloud | Owner-approved opt-in choice in [issue #270](https://github.com/rapucha/moon-service/issues/270) | This is an explicit product preference. It is not calibration evidence for the default mode. |

A conventional astronomy boundary, a derived value, and a calibrated scoring
value are different things. The tables below keep those meanings separate.

### Component allocations

| Value | What it changes | Recorded origin and evidence |
| --- | --- | --- |
| `30` Moon-altitude points | Makes Moon geometry the largest part of the base score. | Provisional allocation from the initial plan, where it was marked as a suggested starting weight. The product records a reason to favor a low Moon, but no evidence for exactly 30 points. |
| `25` sunlight points | Makes ambient light the second-largest part of the base score. | Provisional allocation from the initial plan. The exposure-balance goal explains why light matters, not why it gets exactly 25 points. |
| `15` illumination points | Makes Moon brightness a smaller ranking factor. | Provisional allocation from the initial plan. No recorded empirical reason supports exactly 15 points. |
| `25` weather points | Gives weather one quarter of the original score. | Provisional allocation from the initial plan. No recorded empirical reason supports exactly 25 points. |
| `5` forecast-confidence points, currently age-based | Gives the implemented forecast-age input a small effect on the score. | The initial plan provisionally allocated 5 points to forecast confidence. The first executable spike used forecast age as the input. Neither choice has recorded empirical support. The live provider currently always supplies `1.0` hour. |
| `0–100` output scale | Makes scores easy to display and compare within one mode. | Chosen by the initial plan. It is a presentation scale, not a measured probability. |
| `100` weather-aware maximum | Sets the original score basis. | Derived arithmetic: `30 + 25 + 15 + 25 + 5`. It is not a measured probability scale. |
| `70` ignore-weather maximum | Sets the active maximum after weather and forecast age are removed. | Derived arithmetic: `30 + 25 + 15`. It is not another fitted constant. |

### Weather constants

| Value | What it changes | Recorded origin and evidence |
| --- | --- | --- |
| `13` cloud points | Sets the largest possible cloud contribution. Together, `13 + 7 + 5 = 25`. | Provisional split from the first spike. No recorded reason explains why cloud gets exactly 13 points. |
| `35%` `balanced` target | Measures total-cloud distance from 35% before applying deductions. | The design records the view that partial or textured cloud can help a photograph. It records no evidence for exactly 35%. |
| `0%` `prefer_clear` target | Measures total-cloud distance from clear sky. | Owner-approved, opt-in product choice. It is provisional, not empirical calibration. |
| Cloud divisor `5.0` | Makes each rounded five-percentage-point distance from the target cost one cloud point. A smaller divisor would punish distance faster; a larger one would punish it more slowly. | Provisional curve steepness from the first spike. No recorded evidence supports five percentage points per point. |
| `7` precipitation points | Sets the highest possible contribution from precipitation probability. | Provisional split from the first spike. No recorded reason explains exactly 7 points. |
| Precipitation divisor `5.0` | Makes each rounded five-percentage-point rise in precipitation probability cost one point. | Provisional curve steepness from the first spike. No recorded evidence supports this step size. |
| Zero floor in both `max(0, …)` formulas | Stops cloud or precipitation deductions from producing negative component points. | Structural guard from the executable spike. Zero is the component floor, not a calibrated weather threshold. |
| `20,000 m` and `15,000 m` | Define the three visibility bands. | Metres come from the provider and internal data contract. The exact 20 km and 15 km thresholds are provisional and have no recorded calibration. |
| Visibility points `5 / 4 / 2` | Give points to the `>=20 km`, `15 km to below 20 km`, and `<15 km` bands. Very poor visibility therefore lowers the result but cannot make this term zero. | Provisional values from the first spike. No recorded reason explains the exact points or the two-point floor. |
| `25` weather cap | Prevents the three weather terms from exceeding the weather allocation. | Derived from the allocation. It is currently redundant because the term maxima already add to 25. |

### Other scoring constants

| Value | What it changes | Recorded origin and evidence |
| --- | --- | --- |
| Moon altitude domain `0°` through `90°` | Keeps the curve between the astronomical horizon and zenith; values outside the domain get zero. | Horizon and zenith are physical bounds, not fitted scoring values. The current input is apparent refracted altitude. |
| Moon altitude `0°` through `1°`, rising from 18 to 30 points | Lowers the score at the horizon, then reaches the maximum at 1°. | The design records caution about refraction and local obstructions near the horizon. The exact 18-point start and one-degree slope are provisional. |
| Moon altitude `1°` through `6°`, 30 points | Creates the best-scoring low-Moon plateau for classic horizon compositions. | The initial plan proposed this range and said thresholds should be tuned from examples. No photo corpus or calibration supports the exact endpoints. |
| Moon altitude breakpoint `12°` | Ends the original low-Moon range and begins the higher context-Moon curve. | The initial plan used 12° as its provisional low-Moon ceiling. The context-Moon expansion kept the breakpoint but changed the curve there to 22 points. Neither choice was calibrated. |
| Moon altitude breakpoints `25° / 40° / 70°` | Divide higher context-Moon positions into steadily weaker segments up to zenith. | Added by the context-Moon expansion. The product reason for a gradual decline is recorded; the exact breakpoints are provisional. |
| Moon-altitude endpoint values `22 / 14 / 8 / 4 / 3` | Set how fast the context-Moon curve falls after 6°. The divisors `6 / 13 / 15 / 30 / 20` in the formulas are the widths of the chosen segments. | Provisional curve shape. The segment widths are derived arithmetic, but the chosen breakpoints and endpoint scores are not calibrated. |
| Sun boundaries `-0.833° / -6° / -12°` | Separate this product's golden-hour, civil-twilight, nautical-twilight, and night buckets. | These entered with the executable spike, which cites no source. The numbers resemble common geometric Sun boundaries, but the scorer applies them to apparent refracted Sun altitude. That difference means the conventions do not directly validate these implemented buckets. |
| Sun boundary `+6°` | Separates this product's `golden_hour` and `daylight` buckets. | Product choice from the first spike. The repository records no standard or calibration for exactly +6°. |
| Sunlight points `25 / 24 / 16 / 14 / 7` | Rank golden hour first, civil twilight nearly equal, daylight and nautical twilight lower, and night lowest. | Provisional values from the first spike. The exposure-balance goal explains the order, not the exact gaps. |
| Illumination thresholds `95% / 85% / 70%` | Define the three brightest boundaries. | Provisional thresholds from the executable spike. No recorded data supports the exact cutoffs. |
| Illumination thresholds `30% / 5%` | Add two lower-brightness boundaries to make the current six-band curve. | Added by the real-data scoring spike, then carried into the JVM prototype. No recorded data supports the exact cutoffs. |
| Illumination points `15 / 12 / 10 / 8 / 6 / 4` | Rank brighter Moons higher while keeping even a very thin crescent above zero. | The first executable spike used four bands: 15 points at 95%, 12 at 85%, 8 at 70%, and 4 below. The real-data spike changed the 70% band to 10 points and added 8 points at 30% and 6 at 5%. All remain provisional. |
| Forecast-age bands `3 / 12 / 24` hours | Reduce `forecastConfidence` as the supplied forecast age grows. | Provisional bands from the first spike. No provider study or forecast-error data supports the exact boundaries. |
| Forecast-age points `5 / 4 / 3 / 2` | Give the newest band the most points and retain a two-point floor after 24 hours. | Provisional values from the first spike. No recorded reason explains the exact steps or floor. |
| Final-score thresholds `85 / 65` | Map the normalized score to `high`, `medium`, or `low` opportunity confidence. | Provisional display thresholds from the first spike. They do not represent measured probabilities. |
| Ordinary rejection `illumination < 1%` and separation `< 8°` | Removes extremely thin, near-conjunction ordinary Moon opportunities before scoring. | Example-backed guard from commit `583053e`, not a fitted global visibility model. Both comparisons stay strict. |

Outside references explain why some Sun numbers look familiar; they are not the
recorded source of this implementation. [NOAA's solar calculation
notes](https://gml.noaa.gov/grad/solcalc/solareqns.PDF) use a `90.833°` zenith
for apparent sunrise and sunset. The [US Naval
Observatory](https://aa.usno.navy.mil/faq/RST_defs) defines civil and nautical
twilight using geometric Sun-center depressions of `6°` and `12°`. Moon
Service instead passes apparent refracted Sun altitude into its buckets. This
document therefore does not claim those references validate the current
threshold semantics or score points.

[Issue #33](https://github.com/rapucha/moon-service/issues/33) owns empirical
calibration. Until reviewed evidence supports a change, the provisional values
remain compatibility rules. Regression tests protect those rules from
accidental change; passing tests does not validate the photography judgment.

## Scored Weather And Factual Weather

The weather record contains more facts than the score uses.

| Field | Affects score? | Other current use |
| --- | --- | --- |
| Total cloud cover | Yes, except in `ignore_weather`. | Also helps choose the factual weather summary. |
| Precipitation probability | Yes, except in `ignore_weather`. | Displayed in weather data and the reason text. |
| Visibility | Yes, except in `ignore_weather`. | Also helps choose the factual weather summary. |
| Forecast age | Yes, except in `ignore_weather`. | Not shown as the opportunity `confidence` label. |
| Low cloud cover | No. | Displayed as a factual field. |
| Mid cloud cover | No. | Displayed as a factual field. |
| High cloud cover | No. | Displayed as a factual field. |
| Precipitation amount | No. | Displayed in millimetres. |
| Weather code | No. | Helps choose the factual weather summary. |

`ignore_weather` still fetches and displays all available weather facts. A
reason can still mention weather. Those facts do not affect its score or
ranking.

### Factual Weather-Summary Precedence

The evaluator checks these rules from top to bottom and uses the first match.
This label is factual output. It is separate from `weatherFit`.

| First matching rule | Segment kind | Display summary |
| --- | --- | --- |
| Weather code is at least `50` | `precipitation_risk` | `rain likely` |
| Weather code is `45` or `48`, or visibility is below `5,000 m` | `poor_visibility` | `fog or low visibility` |
| Weather code is `3`, or total cloud is at least `85%` | `overcast` | `overcast` |
| Total cloud is at least `65%` | `mostly_cloudy` | `mostly cloudy` |
| Weather code is `2`, or total cloud is at least `25%` | `partly_cloudy` | `partly cloudy` |
| Weather code is `1`, or total cloud is at least `10%` | `mostly_clear` | `mostly clear` |
| Weather code is `0` | `clear` | `clear` |
| No rule above matches | `mixed` | `mixed conditions` |

## Forecast Age And Final Confidence

Forecast age supplies the `forecastConfidence` score component:

| Forecast age | Points |
| --- | ---: |
| no more than 3 hours | 5 |
| above 3 and no more than 12 hours | 4 |
| above 12 and no more than 24 hours | 3 |
| above 24 hours | 2 |

This calculation uses forecast age only. It does not use distance from now to
the forecast hour, provider model agreement, or another confidence signal.
The live Open-Meteo adapter currently supplies the fixed value `1.0` hour for
every hourly record. Live Open-Meteo results therefore always get 5
`forecastConfidence` points when weather scoring is active. This is a known
limit, not measured provider confidence.

The separate opportunity `confidence` label comes from the final normalized
0–100 score:

| Final score | `confidence` |
| --- | --- |
| 85 through 100 | `high` |
| 65 through 84 | `medium` |
| 0 through 64 | `low` |

Changing weather mode can therefore change this label. The label is not the
`forecastConfidence` component.

## Score Basis And Normalization

For `balanced` and `prefer_clear`:

```text
componentPoints = moonAltitudeFit
                + sunLightFit
                + moonIlluminationFit
                + weatherFit
                + forecastConfidence
componentMaximum = 100
score = round(componentPoints * 100.0 / 100)
excludedComponents = []
```

For `ignore_weather`:

```text
componentPoints = moonAltitudeFit + sunLightFit + moonIlluminationFit
componentMaximum = 70
score = round(componentPoints * 100.0 / 70.0)
excludedComponents = ["weatherFit", "forecastConfidence"]
```

The excluded fields mean “not part of this score.” They do not mean the
weather received zero points. A perfect 70-point non-weather result normalizes
to 100.

The internal result carries `componentPoints`, `componentMaximum`, and
`excludedComponents` for every mode. The current fixture formatter keeps its
old `balanced` shape. It writes `scoreBasis` only for `ignore_weather`, and it
omits `weatherFit` and `forecastConfidence` from that mode's `components`.
Issue #271 owns where this basis appears in the public product response.

Scores are comparable only within the same weather-ranking mode. Every
opportunity in one request uses one mode. A score of 80 under `balanced` and a
score of 80 under `ignore_weather` do not have the same basis.

Switching modes changes only the mode-controlled component values, final score,
score basis, final confidence label, and score-based ordering. It does not
change candidate windows, active hard preference matches, `startsAt`,
`suggestedAt`, `endsAt`, weather lookup inputs, factual weather, or provider
failure behavior.

## Ordering And Limit

The evaluator sorts the complete eligible set before applying the global
result limit. It has no minimum-score cutoff.

| Order | Comparator |
| --- | --- |
| `best_match` | Final score descending, then earlier `suggestedAt`. If both match, stable source order remains. |
| `soonest` | `suggestedAt` ascending, final score descending, then stable opportunity `id` ascending. If every key matches, stable source order remains. |

The direct fixture evaluator uses score order and its caller-supplied limit.

## Current Weather Lookup

The live backend fetches the hourly forecast for the search. For each retained
opportunity, it selects the hourly record whose interval covers `suggestedAt`.
If no record covers that instant, evaluation fails through the existing
weather-unavailable path.

The evaluator does not split or merge natural windows when hourly weather
changes. Weather affects the score and factual output for the chosen
`suggestedAt` only.

## Implementation Authority

The current behavior is implemented in these sources:

- [Window generator](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/window/WindowGenerator.java)
- [Version 1 hard filter](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/window/OpportunityHardFilter.java)
- [Fragment grouping](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/window/FilteredWindowCoalescer.java)
- [Opportunity pipeline and ordering](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/service/OpportunityService.java)
- [Scoring model](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/scoring/ScoringModel.java)
- [Weather-ranking modes](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/scoring/WeatherRanking.java)
- [Score basis](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/scoring/ComponentScores.java)
- [Live-window selector](../backend/src/main/java/dev/moonservice/backend/opportunity/scoring/LiveOpportunityWindowSelector.java)
- [Hourly weather lookup](../backend/src/main/java/dev/moonservice/backend/weather/HourlyWeatherForecast.java)
- [Open-Meteo adapter](../backend/src/main/java/dev/moonservice/backend/weather/openmeteo/OpenMeteoWeatherClient.java)

The current flow diagram is [scoring-flow.svg](diagrams/scoring-flow.svg). Its
source is [scoring-flow.puml](diagrams/scoring-flow.puml).

Issue [#270](https://github.com/rapucha/moon-service/issues/270) owns this
internal scoring contract. Issue
[#271](https://github.com/rapucha/moon-service/issues/271) owns public request
and response placement for the weather-ranking mode. Issue
[#78](https://github.com/rapucha/moon-service/issues/78) owns the unchanged
Version 1 hard preferences.
