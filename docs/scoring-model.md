# Scoring model

Moon Service ranks upcoming Moon photography opportunities near a place the
user chooses. This page explains the product judgment behind that ranking.
It does not list every formula or implementation step.

## Where to find each rule

| Document | What it explains |
| --- | --- |
| This page | The product goal, the choices behind the score, known limits, and future ideas. |
| [Opportunity evaluation contract](opportunity-evaluation-contract.md) | The exact current rules, formulas, constants, hard filters, ordering, and score basis. |
| [API shape](api-shape.md) | The public request and response fields. |

The opportunity evaluation contract is the source of truth for current
behavior. This page links to that contract instead of copying its formulas.
Keeping each formula in one place makes drift less likely.

## Product goal

A useful opportunity gives a photographer a real chance to make an interesting
Moon photograph. The Moon needs to be visible from the chosen direction. Its
position needs to work with a foreground. The scene needs enough useful light,
and the weather needs to fit what the photographer wants.

The main problem is exposure balance. The Moon is much brighter than most
foreground subjects. A normal landscape exposure can turn it into a white
circle with no detail. Moon Service therefore favors times when Sun-driven
ambient light can help preserve both Moon detail and scene detail.

The score is a ranking aid. It is not a probability of success. It cannot know
the user's exact viewpoint, subject, camera, lens, exposure, or editing plan.

## Hard limits come before ranking

A hard limit answers: **Can this shot work at all?**

A ranking preference answers: **Which of the remaining opportunities looks
better?**

Moon Service keeps these jobs separate. A score cannot rescue a time that fails
an active hard limit.

| Input | Role |
| --- | --- |
| Natural Moon visibility | Candidate generation keeps times when the Moon is above the astronomical horizon and inside the configured visible-Moon range. |
| User-set altitude range | A hard filter when the user sets it. A time outside the range is removed. |
| User-set included azimuth or excluded obstruction sector | A hard filter when the user sets it. A time outside the allowed direction is removed. |
| User-set time, ambient-light, phase, or bright-limb range | A hard filter when the user sets it. |
| Moon altitude, ambient light, illumination, weather, and forecast age | Ranking inputs for times that remain. The selected weather mode decides whether weather takes part. |

A hard filter identifies precise matching fragments. The service may later put
nearby fragments in one practical envelope under the ordinary grouping rule.
The tolerated gap does not become a match.

This distinction matters for a hill or building. If the user knows that the
Moon is hidden below a certain altitude or in a certain direction, the user can
set a hard limit. The service removes those times instead of merely lowering
their score.

The service can enforce only the obstruction information it receives. A city
name does not describe the skyline at a specific shooting spot. The service
does not infer a local hill, building, or tree from a city lookup.

## Windows and suggested times

Moon Service starts with natural visible-Moon windows. A physical Moon pass
can cross midnight without becoming two passes. The service keeps a broad
window so the photographer can choose a precise moment inside it.

Each result also has a `suggestedAt` time. That time is a practical starting
point, not a promise of perfect alignment or the best possible exposure. The
current selection favors Moon-altitude fit and sunlight fit. Illumination and
weather affect the later ranking, not the choice of precise instant inside the
window.

Active hard preferences can split a natural window into matching parts. The
service may group nearby parts into one practical envelope, while keeping the
suggested time inside a part that really matches. The
[opportunity evaluation contract](opportunity-evaluation-contract.md) defines
the exact boundaries, grouping, ordering, and tie-break rules.

For this grouping rule, nearby means fragments from the same physical Moon
pass with continuous natural source-window coverage and no more than ten
minutes of active-preference mismatch between them. The tolerance changes only
the practical envelope. It does not round timestamps or relax live
`notBefore`, search-horizon bounds, Moon-pass identity, or near-Sun safety.

The API and RSS/Atom feeds keep precise opportunity instants. The browser shows
ordinary opportunity times rounded to the nearest minute. Event occurrence,
uncertainty, overlap, eclipse phase, maximum, visibility, and safety times keep
their own timing rules instead of using the ordinary ten-minute tolerance.

## Version 1 Hard Preferences

Version 1 preferences are request-scoped hard filters. They identify precise
matching fragments before grouping and ranking. They do not change scoring
weights.

See [Version 1 hard preferences in the opportunity evaluation
contract](opportunity-evaluation-contract.md#version-1-hard-preferences) for
the supported fields and exact behavior.

## What makes a useful opportunity

The default model combines several judgments. None of them describes a whole
photograph on its own.

### Moon altitude

The default model favors a low Moon because it is easier to compose with a
landscape, skyline, person, aircraft, tree, or other foreground subject.

A higher Moon is weaker for the classic horizon shot, but it is not invalid.
It can still work as a context Moon when the light, illumination, weather, and
foreground are helpful.

Extremely low Moon positions need caution. Refraction is less predictable near
the horizon, and a small local obstruction can hide the Moon completely.

### Ambient light

Golden hour and civil twilight usually give the default model its best
exposure balance. The foreground can still carry detail while the Moon remains
prominent.

Daylight opportunities can work when the Moon has enough contrast. Nautical
twilight and full night can also work, but the foreground is harder to retain
without silhouettes, artificial light, exposure blending, or a camera with
wide dynamic range.

The default model ranks these darker cases lower. It does not say they are bad
photographs.

### Moon illumination

The default model tends to favor a full or nearly full Moon when other facts
are equal. A bright Moon is easy to recognize and can carry more visible
surface detail.

A crescent is still useful. A thin crescent in good twilight can make a strong
photograph. Low illumination by itself is not a reason to remove an
opportunity.

The ordinary Moon flow does reject an extremely thin crescent when it is also
too close to the Sun to be practical to see. This rule prevents an attractive
altitude and light score from hiding a basic visibility problem. The exact
threshold is in the [opportunity evaluation
contract](opportunity-evaluation-contract.md).

### Exposure balance

The explanation should show Sun state and Moon illumination together. A thin
crescent in twilight and a full Moon in deep night create very different
exposure problems.

Moon Service can describe likely foreground risk. It cannot know the user's
camera settings, dynamic range, focal length, atmospheric haze, or editing
method. Explanations must not promise a specific exposure result.

### Weather

Clear sky is useful. Partial or textured cloud can also add interest. Rain,
snow, fog, heavy cloud, and poor visibility can make a promising Moon position
much less useful.

Weather is a ranking choice, not a hidden hard filter. The three weather modes
let the user decide how much that choice should matter.

### Forecast confidence

The current score has a small forecast-confidence component, but the name is
broader than the implemented input. The scorer uses forecast age only. It does
not measure provider confidence or the opportunity's distance into the
forecast horizon.

The live Open-Meteo adapter currently supplies a fixed forecast age of `1.0`
hour. Live results therefore receive the same forecast-age contribution. This
is a known limitation, not evidence that every forecast is equally reliable.

## Weather-ranking modes

The scorer supports exactly three request-scoped modes.

| Mode | What it means |
| --- | --- |
| `balanced` | The default. It keeps the original preference for some cloud texture while also considering precipitation risk and visibility. |
| `prefer_clear` | It favors clearer aggregate cloud cover. Precipitation risk and visibility still contribute in the same way as in `balanced`. |
| `ignore_weather` | Weather and forecast confidence do not affect the ranking. The remaining astronomy and light components are rescaled to the full score range. |

`prefer_clear` is a preference, not a clear-sky requirement. A cloudy result
can still rank well when its Moon position and light are strong.

`ignore_weather` does not stop weather retrieval. The service still fetches
and displays factual weather with the same provider and failure behavior. A
reason may still mention cloud, rain, or visibility. Those facts do not affect
the score or score-based order in this mode.

All opportunities in one request use the same mode. Compare scores only within
the same mode. A score from `ignore_weather` has a different basis from a score
from `balanced` or `prefer_clear`.

These modes are a closed product choice. They are not an arbitrary weight
editor or a plug-in system for scoring rules.

The exact weather formulas, point allocations, examples, and scored-versus-
display-only fields are in the [opportunity evaluation
contract](opportunity-evaluation-contract.md).

## Observer elevation and local obstruction

Observer elevation and horizon obstruction solve different problems.

| Concept | Meaning | What it changes |
| --- | --- | --- |
| Observer elevation | The shooting location's height above sea level. | Small topocentric and parallax changes in the astronomy calculation. The current standard refraction correction does not use elevation. |
| Horizon obstruction | A hill, ridge, building, tree, or other object in a specific direction. | Whether the Moon is actually visible from that shooting spot. |

Observer elevation does not reveal a local obstruction. A high viewpoint can
still face a nearby building. A low viewpoint can have a clear horizon.

Terrain-horizon support needs an exact shooting position and an azimuth-aware
terrain model. A city-level lookup is too broad for that claim. Until such a
model exists, the product should say:

> Local hills, buildings, or trees may affect exact visibility near the
> horizon.

User-set altitude and azimuth limits remain the practical way to describe a
known local obstruction.

## Where the current numbers came from

The current numeric policy grew from the initial planning document, the first
fixture-backed scoring spike, and a few later product changes. The initial plan
called its weights “suggested starting weights” and said to tune thresholds
from real examples. It supplied no such calibration. The spike made many of
those choices executable; later changes expanded the altitude and illumination
curves, weather summaries, and the near-conjunction visibility rule.

Most exact values have no recorded empirical source. They were not derived
from a photo study, a weather study, user observations, or a fitted model. We
know what each value does in the calculation. We do not yet know whether it is
the best value for photographers.

Regression tests protect the chosen behavior from accidental change. Passing
those tests does not validate the photography judgment behind the numbers.

The [per-value provenance table](opportunity-evaluation-contract.md#where-the-numbers-came-from)
explains the component allocations and scoring constants, their practical
effects, and the evidence behind them. It also shows which values are derived
arithmetic rather than new judgments.

`prefer_clear` is an owner-approved, opt-in provisional choice. It is not
evidence for changing the default `balanced` policy.

Issue [#33](https://github.com/rapucha/moon-service/issues/33) owns empirical
calibration. Changes to weights, thresholds, classifications, explanations,
or suggested-time behavior need evidence gathered and reviewed through that
work, unless the owner approves another narrow exception.

## How calibration should work

Calibration feedback is evidence for a later decision. It must not update the
live score automatically.

Raw reports stay out of fixtures and source control. If the owner publishes a
curated calibration review set, it must contain only selected, authored, and
reviewed cases. Each case must use a new case ID, omit the feedback UUID,
paraphrase free-text notes, and include only the reduced evidence and
server-controlled facts needed for review.

Data minimization, not a fixed decimal count, is the privacy rule for location
details. Current feedback stores no coordinates, so the current privacy
boundary sets no coordinate precision. The review set must not resolve and add
coordinates by default. If its reviewed issue shows that coordinates are
necessary, that issue must explain why, justify the minimum useful
representation and precision, and state the remaining privacy risk. Rounding
coordinates does not make a case anonymous.

The review set must state what it covers, how cases were selected, the limits
of that selection, and what is still uncertain. Missing evidence may be
recorded as an explicit calibration gap. There is no report quota that turns a
weak sample into strong evidence.

A report can support a change to scoring or wording. One report cannot prove
that the tester was present, reconstruct an earlier observation, or justify
moving a suggested time by an exact number of minutes.

Keep two kinds of change separate:

- Scoring changes alter weights, thresholds, classifications, or explanations.
- Window-selection changes alter how the service chooses `suggestedAt` inside
  a window.

Create either kind of change only after the curated review set supports it, and
link the change to specific authored cases. Preserve the version 0 policy tests
unless the evidence justifies changing the product judgment they record. If the
evidence supports no change, record the remaining uncertainty and calibration
gaps instead of manufacturing an adjustment.

## Explanations should help judgment

Each opportunity should state why it ranked as it did and show the facts a
photographer needs to make a different choice.

Useful facts include:

- the window and suggested time;
- Moon altitude and azimuth;
- Moon illumination;
- Sun state and ambient-light context;
- the weather summary;
- the score or confidence label; and
- a short exposure-balance hint.

Use cautious language. “Ambient light should help preserve foreground detail”
is useful. “This exposure will preserve the foreground” is not.

The existing opportunity `confidence` label comes from the final score. It is
not the forecast-confidence component. The exact bands and public field
placement are defined in the [opportunity evaluation
contract](opportunity-evaluation-contract.md) and [API shape](api-shape.md).

## Known limits

The current model does not know:

- terrain height along the local horizon;
- buildings, trees, skylines, or other local obstructions unless the user
  describes an allowed or blocked direction;
- the exact alignment between the Moon, camera, and subject;
- the shooting position relative to the subject;
- lens focal length or field of view;
- camera dynamic range, exposure choices, or editing method;
- detailed atmospheric extinction near the horizon;
- forecast-model disagreement; or
- recurring-event delays, cancellations, route changes, or schedule drift.

The current weather assessment uses the hourly forecast record that covers
`suggestedAt`. It does not split a natural Moon window when the forecast
changes inside that window.

The fixed live forecast-age value means the current forecast-confidence
component does not distinguish a near-term forecast from a later one.

Eclipses need a separate event path with safety and phase-timing rules. The
ordinary near-conjunction filter must not be weakened to make eclipses fit.
Eclipse contacts, phases, maximum, local visibility, and safety do not use the
ordinary ten-minute grouping rule.
Issue [#80](https://github.com/rapucha/moon-service/issues/80) tracks that work.

These limits are acceptable for a discovery tool, but the product must not
claim exact composition or guaranteed local visibility.

## Future ideas — not current behavior

The ideas below are not part of the current scoring contract.

### Weather-aware window segments

A later weather pipeline could split a natural Moon window at meaningful
forecast changes and merge nearby parts with equivalent conditions. Provider
forecast timestamps should define those boundaries, not the astronomy sampling
step.

This could give photographers a more honest view when a long Moon window moves
from clear to rainy. It should be added only when provider precision and real
examples justify the extra detail.

### Recurring event context

A later feature could compare Moon windows with a repeatable event such as an
aircraft approach, train, ferry, or weekly public event. The result would need
to show timing uncertainty, cancellation risk, route changes, and the age of
the source.

Without a live provider, the product must describe such a result as an expected
overlap, not a confirmed sighting.

The event's expected time, uncertainty window, and overlap window stay
event-owned. The ordinary ten-minute grouping rule must not widen or replace
them.

The first version could stay request-scoped, shareable by URL, or available as
a public feed or calendar when the pattern is not personal. Saving personal
event subscriptions on the server would need a privacy model for retention,
deletion, and notification delivery.

Issue [#3](https://github.com/rapucha/moon-service/issues/3) tracks recurring
context.

### Broader scoring profiles

Later profiles could change ranking for a specific photographic goal without
changing candidate generation or hard filters. Examples include:

- `crescent_twilight`;
- `full_moon_horizon`;
- `daylight_moon`;
- `night_silhouette`; and
- `recurring_event_overlap`.

These profiles would still show raw Moon, Sun, weather, and exposure facts.
They should not hide why one result ranked above another.

Do not add server-side user profiles merely to store a selection. If a simple
profile choice arrives before accounts, keep it request-scoped or in browser
`localStorage`.

## Research needs

- Use real good and bad photography examples to test the current judgments as
  part of [#33](https://github.com/rapucha/moon-service/issues/33).
- Keep the Astronomy Engine validation in
  [ephemeris research](ephemeris-research.md) current before a dependency
  upgrade.
- Compare weather fields and forecast timing with real Moon opportunities
  before adding weather-aware window segments.
- Collect recurring-event examples before choosing between user-entered,
  curated, and live provider-backed patterns in
  [#3](https://github.com/rapucha/moon-service/issues/3).
