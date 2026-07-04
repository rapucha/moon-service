# UI Spec

## Status

This is a working UI specification for the web MVP. It records decisions made
so far, separates them from open design questions, and gives future UI work a
stable target.

The current scope is the `/search` web page, opportunity cards, and Moon path
visualization. Broader visual design, feeds, calendar export pages, account
flows, and native apps are out of scope for this document until they become
active product work.

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

The frontend module split is intended to keep future UI changes manageable:

- `app.js`: bootstrapping, events, lookup flow;
- `api.js`: API path construction and fetch handling;
- `format.js`: date, time, degree, and percentage formatting;
- `dom.js`: DOM and SVG element helpers;
- `recentSearches.js`: localStorage behavior;
- `responseView.js`: response states and result rendering;
- `opportunityCard.js`: opportunity card layout;
- `moonPathView.js`: Moon path summary and combined altitude/azimuth chart;
- `moonPhaseView.js`: Moon phase rendering;
- `scoreView.js`: score block and score details.

## Opportunity Card

Each card should be scan-friendly and useful without opening another page.

The card should include:

- local window start and end;
- suggested time;
- duration;
- score and confidence;
- short reason text;
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
has only one useful recommendation window. The card should be ranked by the best
recommendation in that pass. The card title should show the pass start and end
with exact dates and the location timezone, while compact recommendation cards
show the useful window or windows inside the pass. Each recommendation card
should show a `Best` or `Alternative` badge, suggested time, window side, Moon
altitude and direction, window duration, light bucket, Sun altitude, a coarse
sky/weather label, and a short photo hint. Avoid showing exact cloud-cover
percentages in the compact card; keep raw weather numbers in lower-priority
details or API data where they do not imply false precision.
The Moon path panel should be one pass-level chart that shows the path across
the pass and marks each recommendation's suggested position, rather than
showing a separate chart per recommendation.
The altitude chart should use the full card width without requiring horizontal
scrolling in normal desktop or mobile layouts. Azimuth should appear as a top
rail on the altitude chart so direction shares the same time axis as altitude
and light buckets.
This keeps after-midnight times explicit without making them look like a
separate night.

## Moon Path Panel

The Moon path panel is a planning visualization. It must be internally
consistent with the numeric values shown in the same card, but it must not claim
exact terrain-aware composition guidance.

The panel should show:

- start, suggested, and end time;
- start, suggested, and end altitude;
- start, suggested, and end azimuth;
- a combined chart over the opportunity window or pass, with altitude plotted
  over time and azimuth shown as a top rail on the same time axis.

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
- The suggested marker may show a compact, recognizable Moon phase. V0 may use a
  schematic phase based on `phaseAngleDegrees`; true observer-oriented Moon
  rotation can wait until the backend provides a deliberate orientation value.
- Light bucket bands may appear behind the altitude path.

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

- Check desktop and mobile responsive viewports.
- Check at least one long opportunity window and one short opportunity window.
- Verify that labels, dots, strokes, and chart dimensions remain visually
  consistent.
- Verify that the suggested marker is on the displayed path.
- Verify that the chart does not contain obvious major-arc wraps, pointy joins,
  overlaps, or clipped labels.
- Prefer browser inspection or screenshots over reasoning from SVG strings
  alone.
