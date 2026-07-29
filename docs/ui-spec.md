# UI Spec

## Status

This is a working UI specification for the web MVP. It records decisions made
so far, separates them from open design questions, and gives future UI work a
stable target.

The current scope is the `/search` web page, opportunity cards, Moon path
visualization, the altitude and availability preferences tracked by
[#192](https://github.com/rapucha/moon-service/issues/192), the direction,
phase, and bright-limb preference controls tracked by
[#193](https://github.com/rapucha/moon-service/issues/193), and the
calibration-feedback interaction tracked by
[#33](https://github.com/rapucha/moon-service/issues/33). Broader visual design,
feeds, calendar export pages, account flows, and native apps are out of scope
for this document until they become active product work.

If implementation and this document disagree, treat the disagreement as a
product decision to resolve. Do not silently encode new UI behavior only in
frontend code.

## Product Intent

The web UI is a lightweight discovery tool for photographers. A user should be
able to enter a city or town and quickly decide whether an upcoming Moon window
is worth planning around.

The UI should answer:

- where the opportunity is;
- when the useful window starts, peaks, and ends;
- where the Moon is in altitude and azimuth;
- what the light and weather context is;
- why the opportunity was ranked highly;
- what caveats matter, especially local horizon obstruction.

The UI should be clear about what the MVP can and cannot model today, especially
terrain horizon, obstruction, and shooting-position limitations.

## Agreed UI Direction

- The first public surface is web-first and account-free.
- The browser page is served by the Spring Boot backend as static HTML, CSS, and
  JavaScript.
- There is one responsive `/search` page. There is no separate mobile site.
- The UI should steer users toward city or town lookup, not exact home
  addresses.
- Recent searches may be stored only in browser `localStorage`, with display
  names and canonical IDs rather than timestamps, exact addresses, cookies, or
  server-side user identifiers.
- Search keeps provider credit beside provider-derived recent searches,
  ambiguous choices, resolved locations, and opportunity results. About holds
  the full privacy, service-limit, and provider-processing explanations.
- Search keeps short warnings beside the action or result they explain. The
  form says that an exact home address is unnecessary. Results warn that local
  hills, buildings, and trees may block the view.
- The page should expose shareable lookup results.
- The UI should present ranked opportunities, not only chronological events.
- Opportunity cards are currently ranked by backend score.
- Opportunity cards may use a layered information model. The first scan should
  show the decisive facts, while score details, dense weather numbers, and some
  caveats can move into secondary or collapsible treatment.
- A physical Moon pass may cross local midnight. The UI should not split that
  pass into separate day groups merely because the civil date changes.
- One Moon pass can legitimately contain more than one ranked recommendation,
  for example one while the Moon is ascending and another while it is
  descending. The UI should render recommendations with the same `moonPass.id`
  inside one pass card, not as separate top-level cards.
- Anonymous lookup currently receives up to ten raw ranked recommendation
  windows as a provisional safeguard while scoring is being calibrated. The UI
  should describe them as top-ranked forecast candidates rather than ten
  objectively good photographs; grouping by `moonPass.id` may produce fewer
  than ten top-level pass cards.
- Use the degree symbol, for example `7.8°`, instead of `deg`.
- Dates, times, numbers, percentages, and units should go through formatting
  helpers so future localization does not require rewriting card structure.
- Display instants in the opportunity location's timezone. The 12-hour or
  24-hour clock convention should follow the user's browser locale settings.
- Card-level window and suggested-time labels should include the location's
  short timezone label when available, so comparisons with UTC-based ephemeris
  tools are less ambiguous.

## Frontend Structure

The current MVP should stay as static HTML, CSS, and plain JavaScript modules.
Do not jump to a heavier SPA framework only to support near-term UI polishing.

Authored and mixed browser files live under `frontend/src/`. Directly served
SVG assets live under `frontend/assets/`, and deterministic generated browser
modules live under `frontend/generated/`. Maven flattens all three directories
into classpath `/static`, preserving their root-relative public URLs. Root Node
tooling and `tests/ui/` stay at the repository root.

The frontend module split is intended to keep future UI changes manageable:

- `app.js`: bootstrapping, events, lookup flow;
- `api.js`: API path construction and fetch handling;
- `format.js`: date, time, degree, and percentage formatting;
- `dom.js`: DOM and SVG element helpers;
- `recentSearches.js`: localStorage behavior;
- `opportunityPreferences.js`: preference state, storage, request options,
  notices, and coordination of the focused preference controls;
- `angularPreferenceControls.js`: altitude and azimuth preference
  coordination, normalization, and result-chart azimuth helpers;
- `angularPreferencePreview.js`: schematic altitude and bearing sliders,
  fixed local Moon samples, and live exclusion shading;
- `angularPreferenceRules.js`: nonlinear altitude mapping, bearing boundary
  constraints, and merged schematic exclusion segments;
- `angularPreferencePreview.css`: schematic axes, handle lanes, generic
  foreground, exclusion shading, and responsive presentation;
- `moonAppearanceControls.js`: named-phase selection and the textured
  bright-limb dial;
- `moonAppearancePreview.css`: textured phase-thumbnail sizing and responsive
  layout;
- `moonPreferenceControls.css`: shared preference-control base, named-phase,
  and Moon-dial presentation;
- `responseView.js`: response states and result rendering;
- `opportunityCard.js`: opportunity card layout;
- `moonPathView.js`: Moon path, separate Sun pass, and suggested-time sky-position views;
- `moonPhaseView.js`: Moon phase rendering;
- `scoreView.js`: score block and score details.

## Opportunity Preferences

The accepted option A places the preference editor, labeled `Limits`, in the
existing desktop sidebar. On mobile, the same editor uses a compact native
`details` disclosure with the same summary label. Active state and
`Reset all preferences` remain inside this editor. The results region does not
repeat them in an active-limit summary or removable filter chips.

The editor exposes these hard filters:

- Moon altitude is one optional inclusive range edited with a vertical
  dual-handle slider over `[0°, 90°]` on the schematic Moon-pass selector.
  The range remains at least `10°` wide.
  The bottom is `0°` and the top is `90°`. The display position is
  `(altitude / 90)^0.85`, which gives low altitudes mildly more room. Pointer
  input uses the inverse mapping and request values remain degrees. An attempt
  to close the last `10°` moves the marker briefly toward the other marker,
  then returns it to the valid boundary. The invalid overshoot is visual only.
- `Time & light` uses exactly one mode at a time. Local-clock mode accepts
  exactly one window in the searched location's timezone and explains that it
  may cross midnight. Ambient-light mode accepts one or more of `Daylight`,
  `Golden hour`, `Civil twilight`, `Nautical twilight`, and `Night`. Switching
  modes removes the other mode from active state. `Golden hour` is the initial
  ambient-light choice.
- Moon direction is optional as a whole. When enabled, the schematic's shared
  compass axis contains distinct fills and handle pairs for an included sector
  and a blocked sector contained inside it. It has no visible numeric bearing
  inputs. Joined green endpoints include the full compass. In that state, a
  remaining blocked sector is sent as excluded-only `azimuthDegrees`.
  Coincident blocked-sector endpoints mean there is no blocked sector. If the
  green endpoints are also joined, the request omits `azimuthDegrees`. With
  distinct green endpoints, it sends the included sector without `excluded`.
  Disabling direction filtering also omits `azimuthDegrees`.
- Named phase uses eight checkboxes for `new_moon`, `waxing_crescent`,
  `first_quarter`, `waxing_gibbous`, `full_moon`, `waning_gibbous`,
  `last_quarter`, and `waning_crescent`. Any selected phase may match. An
  absent value and all eight selected both mean unrestricted, and the editor
  shows all eight boxes selected in either state. At least one box must remain
  selected. A proper subset is canonicalized in the listed order, persisted,
  and sent as `namedPhases`. Stored all-eight state normalizes to omission.
- Bright-limb orientation is optional and has one target on a circular,
  single-handle dial. It has no editable numeric range inputs. The browser
  starts a new target at `270°` (`Left`) and
  snaps the target to `0°`, `45°`, `90°`, `135°`, `180°`, `225°`, `270°`, or
  `315°` and sends exactly one inclusive `45°`-wide normalized range in
  `brightLimbOrientationDegrees`; the range may cross `0°`. Neighboring
  possible ranges share one endpoint and cover the complete circle.

The local-clock preference uses one `From` and `Until` pair of 24-hour `HH:mm`
text fields. It has no add-window or remove-window control and does not use a
browser-localized native time control.

Altitude and direction use one responsive schematic selector. Its vertical
altitude axis and horizontal absolute-bearing axis contain the real slider
handles; there are no duplicate standalone tracks. The bearing axis runs from
north at `0°` through east, south, and west to a repeated north label at
`360°`. Short tick marks divide it every `15°`; longer ticks align only with
the `90°` cardinal divisions. Its arrows mean increasing bearing, not Moon
travel direction. Handle values remain in `[0°, 360°)`. The altitude axis is
labeled directly, and the bearing arrow labels the horizontal axis. The collapsed
`? Handle help` disclosure below the schematic explains dragging, keyboard steps,
minimum ranges, marker colors, and usable-sector transfer. Hovering the plot explains
the configured included, excluded, or blocked range under the pointer. The message
hides `1.5` seconds after the last pointer movement and immediately when the pointer
leaves the plot. Handles do not open tooltips. Assistive technology gets the same
facts through descriptions.

Each range handle is a directional boundary. Its inner edge marks the exact
logical angle and aligns with the fill and schematic exclusion edge. Green
marker bodies extend outside the usable sector. Red marker bodies point into
the blocked sector. Their hit areas include the visible marker and extend
outside the blocked sector so narrow blocked sectors remain draggable.
Adjacent exclusion rectangles merge before drawing so coincident green and red
boundaries do not leave a dim hairline. At the straight `10°` minimum, one
opaque composite shape replaces both green marker bodies and the fill between
them. It has one outer border and shadow, with no internal edge or gridline.
When the green endpoints meet at maximum width, their joined marker and a full
green rail represent the full compass. Only a remaining red sector is shaded.

The schematic uses a fixed illustrative arc, small textured Moon images, and
the existing generic moving hills, trees, and buildings. It shows no time or
ambient-light buckets and does not claim to describe the searched location's
Moon path, skyline, terrain, or obstructions. Altitude and bearing changes dim
the excluded sky and landscape regions behind the fixed arc. The Moon images
use a fixed left-lit crescent and remain fully visible with the arc.
This local preview does not model lunar-disk intersection and is not
authoritative `azimuthMatchIntervals`.

The altitude mapping applies to the handles, grid, fill, fixed arc, Moon
samples, foreground height, and exclusion shading.

Both compass sectors may cross north, but an individual red handle stops at
the visible north endpoint and never appears at the other end of the axis.
Pointer, touch, and keyboard interaction keep the blocked boundaries inside
the included boundaries. Each of the two usable pieces beside the blocked
sector is either `0°` or at least `10°` wide, and at least one piece remains
usable. Opening a collapsed piece snaps it to `10°`. Closing a piece first
stops at `10°`; continuing closes it to `0°`.

When one usable piece is `0°` and a red handle closes the other from `10°`,
that moving red handle snaps to its adjacent green handle. The other red
handle moves inward by `10°`, transferring the usable piece to the other side.
If that transfer would move the other red handle across north, both handles
remain at the last valid `10°` state.
If a green handle tries to remove the only remaining piece, it stays at
`10°`. The schematic does not show transient constraint messages; handle help
and slider values explain the interaction.

Green-handle movement keeps the outside complement of the green sector at
either `0°` or at least `10°`. Closing that last `10°` joins the green endpoints
and includes the full compass. When the remaining usable direction allows it,
opening joined green endpoints creates a `10°` outside complement. Otherwise,
the handles remain joined. In the full-compass state, only a nonempty blocked
sector is stored or sent. An excluded-only stored value restores joined green
handles at the blocked sector's start boundary. Equal endpoints are not stored
for the included sector, and that display position has no request meaning.
Blocked-sector
endpoints may meet; then the browser draws no blocked fill or shading. When an
included-only stored value is loaded, the coincident blocked handles appear at
the included sector's clockwise midpoint.

Decorative Moon and landscape SVG content stays outside hit testing and the
accessibility tree. Reduced motion stops the generic foreground drift and the
elastic marker movement without removing the schematic.

The bright-limb dial explains the observer-oriented convention: `0°` points
toward local zenith, `90°` points right toward increasing azimuth, and angles
increase clockwise. It uses `moonPhaseView.js` and the canonical textured Moon.
The texture stays north-up while the illumination rotates. The illustrative
crescent is thicker than the earlier mockup but remains below quarter phase,
and the disk has a neutral or dark rim rather than a bright circumference.
`northPoleTiltDegrees` is not a preference.

The eight phase checkboxes have `aria-hidden` textured thumbnails at phase
angles `0°`, `45°`, `90°`, `135°`, `180°`, `225°`, `270°`, and `315°` in
canonical phase order. While bright-limb orientation is enabled and the
preference editor is visible and open, its preview cycles through exactly the
selected phases at the fixed target orientation. It uses at most one timer and
stops when any enabling condition no longer holds. A hidden document or
`prefers-reduced-motion` stops the cycle; reduced motion shows the first
canonical selected phase. This animation is presentational. It does not alter
preference state, request fields, or the fixed `45°` interval width.

When named phase and bright-limb orientation are both active, a sample must
match any selected named phase and the single bright-limb interval. A sample
with no reported bright-limb orientation does not match an active orientation
preference.

These controls remove candidates that fall outside the limits. They do not
adjust scores or change the order of candidates that remain. With no active
filter, Search keeps its preference-free request and current default results.
The detailed request and filtering rules remain in
[the product preference API contract](api-shape.md#product-preference-post) and
[the scoring model](scoring-model.md#version-1-hard-preferences).

Each enabled preference can be removed through its own editor control without
changing the others. Reset removes every active filter and removes the stored
preference object when browser storage accepts the removal. If removal fails,
the current page uses reset state in memory and reports the storage failure.
The location-timezone and location-only share explanations stay with the
relevant controls in `Limits`. When preferences are active, the share
explanation says that the link still contains only the location and that a
receiving browser applies its own saved preferences, if any.

Result-specific messages remain near the results without recreating an active
preference summary:

- If active filters remove every candidate, a closed native `No match`
  disclosure says that the preferences caused the result. It does not describe
  this state as an astronomy, location, or weather failure.
- A valid `preferenceImpact` reports the distinct live opportunities available
  with no preferences before ranking and the result limit inside that
  disclosure. A definition-list row for every active filter reports the count
  when that filter acts alone, the reduction from the shared baseline, and its
  next bounded theoretical match without weather. It marks every filter tied
  for the largest positive reduction.
- Filter rows are independent, not cumulative or combinatorial. The browser
  says that the other preferences are off for each row. A next match is
  formatted in the resolved location's timezone with the year and explicit
  IANA timezone label. A `not_found` row names the returned positive
  `lookAheadDays`.
- The browser shows preference impact only for an empty opportunity result. An
  omitted, unknown, or malformed impact object leaves the impact rows absent
  while retaining the ordinary no-match reason. The browser does not invent
  counts, run another search, or calculate its own long-range match.
- The result view constructs the disclosure and each row as text-only DOM.
  Response and location strings are never interpreted as HTML.
- If the server ignored fields, the warning reports them as text, never as
  HTML.
- If stored state is malformed or uses an unsupported version, the browser
  discards it and says that the saved preferences could not be used.
- If browser storage is unavailable, the browser says that preferences will
  last only for the current page while search continues.
- The browser does not expose the server's internal excluded-sample count.

The browser keeps one versioned preference state for the editor, request,
storage, reset behavior, and result explanations. It stores supported state
under `moonService.opportunityPreferences.v1`. Version 1 storage retains only
the supported `altitudeDegrees`, `time`, `azimuthDegrees`, `namedPhases`, and
`brightLimbOrientationDegrees` fields. Local-clock state stores one
`time.window` object. The former plural `time.windows` shape is unsupported and
is discarded rather than migrated. A bright-limb target is stored as an array
containing exactly one normalized `{start, end}` range; the browser derives and
snaps the dial midpoint when restoring it. An exact `20°`-wide range written by
the earlier version 1 control migrates to the nearest current axis and the
current `45°` width. The browser discards other malformed or unsupported stored
state rather than sending it. If `localStorage` is blocked or unavailable, it
keeps the state in page memory and lets search continue.
Applying the form retains valid values from a disabled altitude, direction,
availability, or bright-limb editor on the current page so re-enabling that
control restores the user's draft. Disabled values remain absent from the
request and version 1 storage; a reload restores only active stored state.
Reset clears both active preferences and these page-memory drafts.

`opportunityPreferences.js` owns this state, its normalization and storage, the
editor coordination, preference request options, and storage or ignored-field
notices.
`angularPreferenceControls.js` coordinates `angularPreferencePreview.js`;
the preview module and `moonAppearanceControls.js` own their focused editor
interactions. `app.js` coordinates the lookup flow with the preference module.
`api.js` remains responsible for the existing default request, and
`responseView.js` remains responsible for ordinary opportunity statuses,
including the structured preference impact inside an empty result.

Every preference input has a visible label. Related choices use `fieldset` and
`legend`, and reset uses a real button. Every handle supports an equivalent
keyboard interaction and exposes its name, value, and instructions to
assistive technology. Distinct sector labels, not color alone, identify the
included and blocked compass handles. The native disclosure, editor, and reset
action work from the keyboard in a logical order.

The schematic uses joined included-sector endpoints only as internal
full-compass state and prevents uncontained blocked sectors while a handle
moves. Stored equal included-sector endpoints remain invalid. Coincident
blocked-sector endpoints are valid and omit the blocked sector. Before sending,
the browser rejects any remaining nonnumeric, non-finite, out-of-range,
duplicate-phase, or unknown-phase value.
Validation identifies the affected control in text and moves focus to it.
Storage and ignored-field changes are announced to screen readers without
depending on color. An empty result announces its `No match` summary;
preference-impact rows become available after the user expands the disclosure.
Removing a preference or resetting all preferences leaves focus on a logical
surviving control.

When azimuth filtering is active, the Moon-pass chart dims only the portions
outside the authoritative `moonPass.azimuthMatchIntervals`. It must not infer
the mask from returned opportunity windows or center-position path samples.
Another hard preference or the global result limit must not dim an interval
that the backend marked as an azimuth match.

## Opportunity Card

Each card should be scan-friendly and useful without opening another page.

The card should include:

- local window start and end;
- suggested time;
- duration;
- score and confidence;
- short reason text when supplied by the API;
- Moon altitude, azimuth, illumination, and phase;
- Sun altitude and light bucket;
- weather summary and relevant forecast risk;
- exposure balance text;
- local horizon caveat when applicable;
- `.ics` action when available.

Cards should avoid hiding the main decision behind decoration. The primary
information is the opportunity itself: time, Moon position, light, weather, and
reasoning.

Cards currently carry more information than a first-scan view needs. Future UI
passes should keep the main opportunity card compact, especially on mobile, and
move secondary diagnostics into a lower-priority presentation rather than
showing every backend fact at equal visual weight.

Each user-facing result card should represent one Moon pass, even when that pass
has only one ranked recommendation window. The card should be ranked by the best
recommendation in that pass. The card title should state whether there is one
or multiple candidate windows in that Moon pass, while the page-level summary
states both the ranked Moon-pass count and the total candidate-window count.
The full pass start and end should be
shown as lower-priority Moon pass context below the recommendation cards, with
exact dates and a short location timezone label. Each recommendation card should show a
`Best` or `Alternative` badge, its raw candidate rank and score, suggested time,
window side, Moon altitude and direction, window duration, light bucket, Sun
altitude, a coarse sky/weather label, and a short photo hint. Keep the API
ranking explanation available in a collapsed candidate-level detail. Avoid
showing exact cloud-cover percentages in the compact card; keep raw weather
numbers in lower-priority details or API data where they do not imply false
precision.
The Moon path panel should be one pass-level chart that shows the path across
the pass and marks each recommendation's suggested position, rather than
showing a separate chart per recommendation.
The altitude chart should use the full card width without requiring horizontal
scrolling in normal desktop or mobile layouts. Azimuth should appear as a top
rail on the altitude chart so direction shares the same time axis as altitude
and light buckets.
This keeps after-midnight times explicit without making them look like a
separate night.

## Calibration Feedback Flow

Calibration feedback is an optional alpha-testing flow, not a general contact
form. The browser follows the exact transport and data rules in
[the calibration API contract](api-shape.md#calibration-feedback-api). It tells
the tester that notes, city-level location, and opportunity context may identify
them and asks them not to include names, exact addresses, or unrelated personal
details.
It never requests device-location or GPS permission.

The reduced alpha supports only evidence about the currently loaded real
opportunity. There is no feedback preview, historical timing, reverse
observation, feedback-only location lookup, recommendation snapshot, or saved
review queue.

### Capability and entry

The page reads feedback capability before offering the form. New entry is active
only when `featureState` is `enabled` and `submissionAvailability` is
`available`. A disabled feature shows a short explanation and no active
feedback control. When the feature is enabled but submission is `disabled` or
`unavailable`, the page suppresses new entry and uses generic wording without
guessing or displaying the cause, database state, capacity, or counts.
Capability describes a new submission; it does not reserve capacity or a write
token.

When capability allows a new submission, a feedback action on the currently
loaded real opportunity opens the form. The browser copies that result's exact
`location.id` and opportunity `id` into `locationId` and `opportunityId`. It
does not change or mint either identifier. Fictional results cannot open the
form, and changing the loaded opportunity starts a different form.

The form offers three optional evidence fields:

- ambient light: `Good`, `Too bright`, or `Too dark`;
- crescent visibility: `Visible` or `Too small to see`; and
- notes.

At least one field must remain present after server normalization. Notes are
optional rather than required. The browser may catch obvious empty or oversized
input, but the server's Unicode normalization and 1-4,000-code-point rule are
authoritative.

The browser sends no timing, location detail, coordinates, timezone, weather,
client astronomy fact, application revision, or opportunity snapshot. The
server uses its microsecond-normalized receipt instant and computes the four
stored astronomy facts. The UI says that the city and opportunity identifiers
are self-reported context rather than proof that the tester was there.

### Submission and exact retry

Immediately before the first submission attempt, the browser creates a
lowercase-canonical UUIDv4 and freezes the exact normalized semantic payload.
It sends no request automatically. An edit that changes any normalized evidence
value starts a new logical submission and requires a new UUID. A different
loaded opportunity also requires a new UUID.

`201 created` and `200 replayed` are both success states. The confirmation
distinguishes them and shows the returned server report UUID and submission
instant. A conflict explains that the client UUID already belongs to different
content; the browser never works around it automatically.

If a sent request has no definite response, the page shows `Submission outcome
unknown` and offers an explicit exact retry with the same UUID and frozen
payload. A definite `429` shows the server retry delay before offering the same
explicit action. A definite `503` may also offer an unchanged explicit retry.
No state resubmits in the background or after reload.

An exact retry can replay a committed row with its original IDs and submission
instant. If no row exists, the retry can create one using its later server
receipt instant because receipt time is not part of the idempotency digest.
When the feature remains enabled, an unavailable capability does not by itself
hide an already frozen exact-retry action because a full store can still replay
an existing row. A disabled feature or `disabled` submission availability does
not offer the action.

Validation and transport errors preserve safe input and identify the affected
field when possible. Changing any normalized digest input after a definite
validation error starts a new logical submission and requires a new UUID before
another request. Public error copy never exposes dependency details or echoes
submitted values.

### End-to-end sequence

This sequence shows the reduced current-observation capability and submission
contract. The browser copies identifiers from the loaded opportunity and sends
only tester-authored evidence. The server uses its receipt instant and computes
the four current astronomy facts.

[![Calibration feedback overview](diagrams/calibration-feedback-sequence.svg)](diagrams/calibration-feedback-sequence.svg)

[PlantUML source](diagrams/calibration-feedback-sequence.puml)

### Accessible interaction

Evidence choices use labeled controls, and the at-least-one rule has text that
does not rely on color. Errors appear in a summary that links to the affected
field; focus moves to the summary after a rejected explicit action.

Controls keep their labels while busy. A busy state prevents duplicate sends,
uses `aria-busy`, and does not erase entered values. Keyboard focus remains on
the triggering control unless an error summary or success confirmation needs
attention. Rate-limit copy includes the server retry delay without starting an
automatic retry.

Created, replayed, conflict, unknown-outcome, rate-limited, and unavailable
states are announced in a polite live region. A success confirmation stays
available to assistive technology before the form is cleared. Exact retry is a
labeled tester action, never a timer or background behavior.

## Moon Path Panel

The Moon path panel is a planning visualization. It must be internally
consistent with the numeric values shown in the same card, but it must not claim
exact terrain-aware composition guidance.

The panel should show:

- start, suggested, and end time;
- start, suggested, and end altitude;
- start, suggested, and end azimuth;
- a Moon-first chart over the opportunity window or pass, with Moon altitude
  plotted over time and Moon azimuth shown as a top rail on the same time axis;
- a separate collapsible Sun-pass chart when at least one sample has a Sun
  altitude of zero or above. It uses the same full-pass time axis and light
  bands as the Moon chart, keeps below-horizon body markers hidden, and retains
  the Sun direction rail across all samples with valid Sun position data;
- a separate collapsible quasi-dome at the selected suggested time when the Sun
  is above the horizon. It plots the Sun and Moon using their true altitude and
  azimuth, states their angular separation, and must expose those same values in
  its accessible name. The dome is a static planning diagram, not an
  interactive or terrain-aware 3D view.

The suggested marker must sit on the displayed path at the suggested moment. The
preferred way to do this is to construct the path so it passes through the real
suggested sample, rather than visually moving the dot to a different altitude.
This preference is still subject to confirmation if a better path model is
chosen.

## Altitude Chart

Agreed behavior:

- The x-axis spans only the opportunity window for single recommendations, or
  the Moon pass for grouped pass cards, not the whole night or day.
- The plotted path starts and ends at the displayed window or pass boundaries.
- Desktop and tablet charts use a stable full-card width without horizontal
  scrolling in normal layouts.
- Mobile charts show the full opportunity window or pass in the card without
  horizontal scrolling.
- Mobile charts should still communicate relative duration honestly when the
  comparison remains readable. A short window should not look the same width as
  a long window unless we explicitly decide to sacrifice duration encoding for
  readability.
- Typography, stroke width, dot size, and axis styling should be stable across
  short and long windows. Short windows must not produce huge labels, thick
  lines, or oversized dots.
- Axis labels should use degree symbols.
- The curve should read as a natural Moon altitude trend.
- Jagged sampled polylines, knotty interpolation artifacts, and pointy joins are
  not acceptable.
- The curve should not visually wrap as if it takes a major arc or goes beyond a
  plausible sky path.
- Start, suggested, and end markers should be visually distinct.
- The suggested marker may be larger than the start/end markers and should read
  as the Moon rather than a generic dot.
- Every visible Moon marker shows the compact phase and orientation for that
  marker's own sample time. `moonPhaseAngleDegrees`,
  `brightLimbTiltDegrees`, and `northPoleTiltDegrees` come from the corresponding
  `moonPass.path` or `moonPath` point; grouped Best and Alternative markers use
  their suggested-time values. Bright-limb tilt rotates the illumination, while
  north-pole tilt rotates only the canonical lunar surface texture and never the
  phase mask. The two tilt fields have independent fallbacks: an absent or
  invalid bright-limb value retains the schematic location-independent phase
  rendering, while an absent or invalid north-pole value retains the canonical
  north-up texture. When an older response has no valid per-point phase, pass
  markers may reuse the Best Moon image. Texture rotation does not yet model
  libration in longitude or latitude.
- The Moon altitude chart does not overlay Sun markers. The separate Sun-pass
  chart draws Sun samples only when Sun altitude is zero or positive and sizes
  recommendation markers by priority. It must cull lower-priority path,
  start, or end markers when their body images would overlap a recommendation
  marker. Do not raise its chart ceiling above 90 degrees; preserve real Sun
  altitude and azimuth in marker metadata and tooltip text.
- Light bucket bands may appear behind the altitude path.
- A subtle animated generic foreground silhouette layer may appear behind the
  chart markers and labels to help users build intuition for low Moon altitude.
  It is visual-only, not landmark-aware, not terrain-aware, and must respect
  `prefers-reduced-motion`. Silhouette heights should be modeled in apparent
  altitude degrees rather than fixed pixels so they shrink on high-arc Moon
  passes and grow on low-arc passes. The current reference scale is low hills
  `2.2°`, small gabled building `3.0°`, tree `4.5°`, mid-rise block `5.5°`,
  church or cathedral `6.8°`, and tall tower `11.7°`.

Current v0 curve model:

- Use a continuous monotone cubic path through the available chart samples. This
  keeps the suggested point on the path, preserves the sample values, avoids the
  sharp split-arc junction, and limits interpolation overshoot.
- The backend should provide enough canonical samples for the chart shape to be
  physically plausible. V0 window charts use regular 30-minute path samples
  plus start, suggested, end, and light-bucket boundary samples. Pass charts use
  pass-level samples across the full Moonrise-to-Moonset pass, with
  recommendation markers inserted from the grouped windows' suggested samples.
  The frontend should not have to infer a rounded peak from only sparse points.
- Treat this as a UI path model, not a terrain-aware or composition-exact Moon
  trajectory.

## Moon Path Foreground Animation

The foreground silhouettes in `moonPathView.js` are a visual altitude aid, not
time data and not location-aware scenery. They sit behind Moon markers and axis
labels, inside the altitude plot clip, so users can compare a low Moon altitude
against familiar rough objects without reading the silhouettes as chart ticks.

The runtime engine lives in `frontend/src/moonPathSilhouettes.js`. It is
symbol-based: the runtime places sanitized SVG symbols from
`frontend/generated/moonPathSilhouetteSymbols.js` instead of
constructing building/tree paths directly in the chart code.

The symbol catalog is generated from source assets under
`assets/moon-path-silhouettes/`:

- `manifest.json` lists every symbol id, source SVG file, `baselineY`,
  `intrinsicHeight`, tags, license, and attribution.
- `generic/*.svg` contains the current project-owned generic silhouettes.
- `scripts/build_moon_path_silhouette_symbols.mjs` validates and sanitizes the
  manifest plus SVG files, then writes the generated static frontend module.
- `npm run silhouettes:build` regenerates the module.
- `npm run silhouettes:check` verifies that the generated module is current and
  runs sanitizer/manifest tests.

The runtime config in `moonPathSilhouettes.js` contains:

- `SILHOUETTE_SEQUENCE_WIDTH`: the width, in SVG chart units, of one repeated
  foreground sequence. The CSS drift shifts each layer by exactly this amount so
  the repeated `<use>` elements loop without a visible jump.
- `SILHOUETTE_HEIGHT_DEGREES`: named reference heights in apparent altitude
  degrees. For example, a `4.5°` tree is drawn shorter on a chart whose ceiling
  is `70°` than on a chart whose ceiling is `35°`.
- `SILHOUETTE_LAYERS`: animated parallax layer definitions and figure placement.

SVG chart units are the units of the chart `viewBox`. They behave like local
pixels inside the fixed chart coordinate system, not CSS pixels and not time.

Symbol contract:

- `id`: lowercase kebab-case key referenced by runtime figures.
- `viewBox`: source SVG coordinate system, parsed from the SVG file.
- `baselineY`: source SVG y-coordinate for the ground/`0°` baseline.
- `intrinsicHeight`: source height used when scaling the symbol to
  `heightDegrees`.
- `tags`: lowercase metadata tags for future generic, location, or event packs.
- `license`: required metadata for project-owned and third-party art.
- `attribution`: required metadata for future legal/about surfaces.
- `elements`: generated sanitized `path` and `rect` definitions. Do not edit
  these by hand; edit the source SVG and rebuild.

Sanitization is deliberately strict. The current source subset allows only
simple root `<svg>` files containing self-closing `<path>` and `<rect>` elements
with known classes. Scripts, event handlers, external references, embedded
images, styles, filters, animation, remote URLs, and unsupported attributes are
rejected before runtime.

Layer semantics:

- `far`: faintest and slowest layer. It carries sparse hills and tall forms so
  the background reads as distant context.
- `mid`: medium opacity and medium speed. It carries mid-rise and church-like
  forms.
- `near`: strongest and fastest layer. It carries the most readable houses,
  trees, and towers.

Layer parameters:

- `id`: stable layer key used to build internal SVG definition IDs.
- `className`: CSS class added to the rendered `<g>`, currently `is-far`,
  `is-mid`, or `is-near`.
- `opacity`: passed into CSS as `--moon-path-foreground-opacity`.
- `durationSeconds`: animation duration. Smaller values move faster and create
  the near-layer parallax effect.
- `delaySeconds`: animation offset so layers do not align into one repeated
  block at page load.
- `offsetX`: horizontal offset, in SVG chart units, applied to every figure in
  that layer's sequence.
- `figures`: ordered list of objects drawn into one repeated sequence.

Symbol source locations:

- Source SVGs live under `assets/moon-path-silhouettes/generic/` for the
  current generic pack.
- Symbol metadata lives in `assets/moon-path-silhouettes/manifest.json`.
- The generated runtime catalog is
  `frontend/generated/moonPathSilhouetteSymbols.js`.
- Runtime placement and animation live in `frontend/src/moonPathSilhouettes.js`.

Figure parameters:

- `symbol`: key from the generated symbol catalog, such as
  `generic-tree-wavy` or `generic-church-small`.
- `x`: horizontal position inside the repeated sequence, in SVG chart units.
  This is not time and is not tied to the hour axis.
- `heightDegrees`: target height in apparent altitude degrees. Runtime uses
  this value plus the symbol `intrinsicHeight` and `baselineY` metadata to scale
  and baseline-align the symbol.

Runtime figure configs should not carry shape-construction parameters such as
`windowColumns`, `windowRows`, or arbitrary path instructions. If a visual
variant is needed, create a new source SVG and manifest entry, then reference
that generated `symbol` id from the layer config.

The current generic reference heights are:

- low hill: `2.2°`
- small gabled building: `3.0°`
- tree: `4.5°`
- mid-rise block: `5.5°`
- church or cathedral: `6.8°`
- tall tower: `11.7°`

To add a new symbol:

1. Add a safe SVG file under `assets/moon-path-silhouettes/`. Keep it in the
   supported subset unless the sanitizer and tests are deliberately expanded.
2. Add a manifest entry with `id`, `file`, `baselineY`, `intrinsicHeight`,
   `tags`, `license`, and `attribution`.
3. Run `npm run silhouettes:build`.
4. Reference the generated symbol id from `SILHOUETTE_LAYERS` in
   `moonPathSilhouettes.js`.
5. Run `npm run silhouettes:check` and the frontend UI checks.
6. Keep the silhouette behind markers and labels, preserve
   `prefers-reduced-motion`, and update this section when adding new reusable
   metadata or reference heights.

Future landmark-aware or event-aware silhouettes should use the same symbol
catalog and manifest path, but they need separate product and provider
decisions before the UI claims real city context.

## Azimuth Rail

Agreed behavior:

- The azimuth rail should show the Moon direction sweep across the opportunity
  window or Moon pass using compass direction labels.
- Direction labels should share the same x-axis and time scale as the altitude
  chart.
- Recommendation markers should align with the same samples as the altitude
  path.
- The suggested markers should be visually distinct.

Open questions:

- Whether the azimuth rail needs additional marker labels beyond the chart
  labels and the compact recommendation cards.

## Responsive Behavior

Agreed behavior:

- The same page should work in desktop, tablet, and mobile browser viewports.
- At widths up to 680px, Recent searches is one native disclosure that starts
  closed whenever `/search` loads. Its summary must work with a keyboard and a
  screen reader. Opening it shows the current browser-local list, storage note,
  provider credit, location selection, and clear action.
- Above 680px, Recent searches stays open and visible in the sidebar. The page
  does not show the mobile disclosure control.
- The long privacy, service-limit, and data-source explanations do not appear
  between city search and results. They remain available on About.
- Mobile should not require horizontal scrolling to understand a single
  opportunity card.
- Text must fit inside controls and cards without overlap.
- The opportunity card should remain readable in browser responsive-device
  modes in both Firefox and Chrome.

Open questions:

- Exact mobile chart width policy when opportunity durations vary widely.
- Whether opportunity cards should collapse optional detail sections on mobile.
- Whether chart legends or caveats should be shortened on narrow screens.

## Curve Model Under Discussion

The Moon altitude curve is the main unresolved UI and modeling issue.

Things already discussed:

- A sampled curve can be physically closer to backend data, but naive smoothing
  made the chart look knotty and artificial.
- A circular-arc style looked more natural than the sampled curve.
- A single arc through start, end, and one additional point is attractive for
  simplicity, but choosing the wrong point can put the suggested marker off the
  curve or make the curve imply an unnatural path.
- Splitting the curve into ascending and descending sections can make the dot
  align with the path, but the join can become visibly sharp.
- A real Moon path formula or backend-provided path geometry may still be
  preferable if sample-based rendering keeps producing unnatural shapes.
- The current v0 implementation uses regular backend chart samples with
  monotone cubic interpolation as a practical compromise.

Questions to resolve before more chart work:

- Is the altitude chart meant to be physically accurate within the available
  samples, or a schematic trend that stays visually plausible?
- Which points must the displayed curve pass through: start, suggested, end,
  peak, all samples, or a smaller set?
- Should the backend eventually provide path geometry instead of canonical
  samples for the frontend to draw a smooth curve deterministically?
- What continuity is required at the peak: smooth tangent continuity, rounded
  peak, or exact peak point even if that creates a cusp?
- How much visual approximation is acceptable if numeric labels remain exact?

Current preference:

- The path should look smooth and natural.
- The suggested dot should sit on the path at the real suggested time and
  altitude.
- The chart should preserve start and end values.
- If those constraints conflict, stop and decide the visual contract before
  adding another curve workaround.

## UI Experiment History

UI experiments, tuning passes, and small visual fixes should preserve a
repo-level history that is easy to revert, compare, and bisect. Agentic UI work
can be nondeterministic, so prefer explicit checkpoints over long uncommitted
iteration.

Use this workflow for UI exploration:

- Commit the current accepted implementation before starting a new experiment,
  so the baseline is easy to return to.
- Use a short-lived branch per experiment theme, such as
  `ui-exp-sky-icons`, `ui-exp-pass-card-density`, or
  `ui-exp-chart-layout`.
- Keep commits small and named by the visible change, for example
  `Experiment with sky condition icon in pass cards` or
  `Try denser pass recommendation facts`.
- Keep each committed checkpoint buildable enough to inspect locally. At
  minimum, run patch hygiene and the focused syntax/test checks appropriate to
  the changed files.
- Use tags for visual checkpoints when screenshots or human visual judgment are
  the main comparison tool, for example `ui-pass-card-baseline` or
  `ui-sky-text-only-v2`.
- Use `git worktree` for parallel variants that need side-by-side local review
  or separate dev-server ports.
- Do not squash experimental commits until the final direction is chosen. The
  granular history is more useful during exploration than a tidy linear story.
- Once a direction is selected, either revert rejected experiment commits or
  start a clean implementation commit from the accepted baseline.

Avoid mixing unrelated UI experiments, tuning changes, and bug fixes in one
commit. Small fixes that emerge during a UI pass should either be committed as
their own fix or called out clearly if they are inseparable from the selected
UI direction.

## Verification Expectations

For UI changes that affect opportunity cards or charts:

- Run `npm run frontend:check` when Node tooling is available. It combines
  plain-JS TypeScript checking, ESLint, and Playwright desktop/mobile smoke
  checks.
- Check desktop and mobile responsive viewports.
- Check at least one long opportunity window and one short opportunity window.
- Verify that labels, dots, strokes, and chart dimensions remain visually
  consistent.
- Verify that the suggested marker is on the displayed path.
- Verify that the chart does not contain obvious major-arc wraps, pointy joins,
  overlaps, or clipped labels.
- Prefer browser inspection or screenshots over reasoning from SVG strings
  alone.
