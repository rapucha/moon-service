# Product Notes

## Product Direction

Moon Service helps photographers find good times to photograph the Moon near
places they care about. Its main value is a timely alert that tells a
photographer when the Moon will be low and visible and when the weather may be
worth trying.

The MVP should be small and easy to try. A website is now the favored first
product. A user enters a city or location, sees the next good Moon opportunity,
and can optionally use RSS/Atom or `.ics` export.

## Target User

The first user is a photographer who wants a quick answer about a local Moon
shot:

- Moon near the horizon.
- Enough ambient light to show foreground detail while preserving visible
  detail in the Moon.
- A forecast that suggests clear sky, partial cloud, or visually interesting
  conditions.
- Result or alert arrives early enough to travel or set up.

The product should support one-off location lookup before it supports saved
compositions. Exact landmark alignment is a later feature.

A later user may want to photograph a repeatable subject or event with the
Moon. For example, an aircraft approach, train, ferry, or other local subject
may cross a useful part of the sky at about the same local time on some days.
Moon Service should eventually help users find and follow Moon opportunities
that overlap these recurring events even when their timing varies.

## MVP User Promise

No account is required. Enter a city or location and see the next promising
Moon opportunity. Use the public Atom feed or rolling iCalendar feed to follow
future recommendations, or download one selected opportunity as an `.ics`
event. Add email or an installed client later only if users find recurring
personal alerts useful.

The first useful result can include:

- Location name.
- Date and time window.
- Moon altitude and azimuth.
- Moon phase or illumination.
- Sun state, such as daylight, golden hour, civil twilight, or night.
- Weather summary and confidence.
- A short reason the opportunity scored well.

The product should judge an opportunity as a photographer would, not only as an
astronomy calculator would. The Moon is bright and often needs a relatively
short exposure to avoid blown highlights. A useful time is often while the Sun
still gives the sky and foreground enough light for the photographer to expose
for Moon detail without losing the landscape, skyline, tree, building, or hill
in darkness. This favors the edges of daylight, golden hour, and twilight. The
product should still report darker or crescent conditions when the facts are
promising and the result explains them clearly.

## MVP Scope

In scope:

- Enter a city or location.
- Support raw Unicode city/location input.
- Disambiguate city/location search results when needed.
- Remember recent searches only in browser `localStorage`.
- Find upcoming Moon windows for that location.
- Score opportunities using Moon geometry, Sun state, and weather.
- Present ranked opportunities.
- Provide a shareable result page.
- Provide a public Atom feed for a canonical real location. The browser shows
  one contextual `Atom feed` action: applied response metadata selects the
  backend-generated filtered URL or the location-only all-off URL. RSS remains
  unaccepted.
- Provide a stateless, preference-aware `.ics` export for an individual event.
  Every displayed ordinary recommendation with a usable backend link offers
  its own calendar download. Each event embeds the opportunity's Moon phase and
  orientation with RFC 7986 `IMAGE`. A calendar client may ignore the image, so
  the ordinary event fields must remain useful without it.
- Provide a stateless, preference-aware `.ics` calendar feed for a canonical
  real location. It is a complete rolling snapshot of the current seven-day,
  ten-result search in fixed `soonest` order. A valid empty calendar contains
  no placeholder event. In manual tests, Thunderbird 153.0esr removed the final
  event; GNOME Calendar 41.2 fetched the same response but retained its final
  cached event. Moon Service does not add a placeholder to work around that
  client behavior.

Tracked MVP implementation issues:

- Coordinate-backed opportunity search: [#13](https://github.com/rapucha/moon-service/issues/13).
- Weather-backed scoring: [#14](https://github.com/rapucha/moon-service/issues/14).
- First web lookup and shareable result page: [#15](https://github.com/rapucha/moon-service/issues/15).
- Public feeds and calendar exports: [#16](https://github.com/rapucha/moon-service/issues/16).

Out of scope for the first MVP:

- Mandatory accounts.
- Cookies for remembering users.
- An installed iOS or Android app as the required first surface.
- Exact address autocomplete.
- Full map planning.
- Exact house/church/landmark alignment.
- Recurring event-aware matching, such as specific flights, transport routes,
  or user-defined weekly event patterns.
- Terrain and obstruction modeling.
- Cross-device sync.
- Calendar OAuth.
- Email alerts.
- Paid subscriptions.

## Opportunity Timing

Moon Service keeps four timing ideas separate:

- **Calculation precision:** Astronomy calculations, interval intersections,
  ordering, live eligibility, API responses, and RSS/Atom feeds keep precise
  instants.
- **Display resolution:** The browser shows ordinary opportunity times rounded
  to the nearest minute. An individual `.ics` export floors `DTSTART` and ceils
  `DTEND` to outward whole-minute bounds. Neither display nor export rounding
  changes the precise API and Atom instants.
- **Event uncertainty:** A recurring event may have an expected time, an
  uncertainty window, an overlap window, and a confidence level. An eclipse
  may have precise contacts, phases, maximum, local visibility, and safety
  bounds. Each event owns its timing rules.
- **Grouping tolerance:** The service may join precise matching fragments into
  one ordinary practical envelope only when they belong to the same physical
  Moon pass, their natural source-window coverage is continuous, and each
  active-preference mismatch gap is no more than ten minutes.

Ten minutes is not timestamp rounding or event uncertainty. A practical
envelope may contain a short preference mismatch, but `suggestedAt` must still
be a precise matching instant that passes the live cutoff. Search-horizon
bounds, Moon-pass identity, and near-Sun safety stay strict. The product does
not add a generic reminder to arrive early.

## Recurring Event Direction

Some photography opportunities depend on both the Moon and a subject that may
appear again. A future scoring layer should accept approximate recurring-event
details such as:

- Days of week or other recurrence rules.
- Approximate local time or time range.
- Early or late tolerance because real events can be delayed, cancelled, or
  early.
- Optional route, direction, azimuth, or subject position when known.
- Active date range and source confidence.

The first useful version can keep this information in one request or encode it
in a shareable URL, RSS/Atom feed, or `.ics` calendar feed. Personal saved
subscriptions should wait until the privacy model covers stored preferences,
notification endpoints, retention, update behavior, and deletion.

The scoring-context follow-up for this direction is tracked by
[#3](https://github.com/rapucha/moon-service/issues/3).

The product must describe event-aware results as planning cues, not guarantees.
For aircraft and other transport, do not imply real-time tracking, exact pass
timing, or confirmed operation unless the project deliberately integrates and
documents a live provider.

## Privacy Stance

Moon Service should avoid collecting permanent user-location data by default.

The first web and API flow should avoid permanently storing user lookup
locations.
The backend can geocode and score a request. It can also cache selected city or
location records and weather by rounded coordinate and time bucket without
storing a user profile. The UI should guide users to search for a city or town,
not an exact home address. Before adding saved alerts, push subscriptions,
email, or cloud sync, document exactly what the service stores and why.

Location search should accept local-language and non-Latin names. Browser
locale is only a display and ranking hint. It must not prevent a raw query such
as `Praha`, `München`, `東京`, or `京都` from resolving.

The web UI may store a small ordered list of recent searches in browser
`localStorage`. Store only display names and slugs or canonical IDs. Do not
store timestamps, exact addresses, cookies, or server-side user identifiers.
Provide a clear control for recent searches.

An email address stored with location preferences is personal data. Do not add
email alerts until the product has a privacy notice, consent, unsubscribe, and
deletion flows, retention rules, and a plan for the email provider and its data
processing.

Moon Service can use Reddit for manual community validation or create a
project-owned subreddit later. Do not post automatically to an existing
subreddit without moderator approval. Mastodon and Bluesky are not planned for
now.

## Browser Opportunity Preference Boundary

The browser labels its editor `Preferences`. It shows the existing hard controls
and `Weather in ranking` directly, without extra topic headings.
The browser may keep version 1 opportunity preferences in `localStorage` under
`moonService.opportunityPreferences.v1`. It may store an optional
`altitudeDegrees` range; one `time` availability mode using one local-clock window
or ambient-light buckets; an enabled `azimuthDegrees` preference containing an
included sector, a blocked sector, or both; a `namedPhases` value that is an
exact union of the browser's New / very thin, Crescent, Half, Gibbous, and Full
Moon-shape groups; and one
`brightLimbOrientationDegrees` range.

The `Weather in ranking` radio group offers exactly `Moon Service
recommendation`, `Prefer clear skies`, and `Don't use weather in ranking`.
Their request values are `balanced`, `prefer_clear`, and `ignore_weather`.
Prefer-clear changes scoring and ranking but does not filter opportunities.
Ignore-weather excludes weather and forecast confidence from scoring and
ranking but keeps the raw forecast visible.

The browser stores only a non-default raw string under the separate key
`moonService.weatherRanking.v1`. It stores `prefer_clear` or `ignore_weather`.
Absence means `balanced`, and the browser does not store `balanced`. The
disclosure summary reports the active hard-limit count separately from any
non-default weather choice.

Local-clock state stores `time.window`. Stored state that uses the former
plural `time.windows` shape is unsupported and follows the normal discard
behavior; it is not migrated.

Stored `namedPhases` that are not an exact union of complete Moon-shape groups
are unsupported and follow the same whole-state discard behavior. The browser
does not migrate, broaden, or narrow an asymmetric exact-phase selection.

Direction filtering is one preference. Joined green handles include the full
compass. In that state, the browser stores and sends only a selected blocked
sector, or omits `azimuthDegrees` when there is no obstruction. With distinct
green handles, it stores and sends the included sector and any selected blocked
sector inside it. The named phases are alternatives: a sample may match any
selected phase.

The browser's bright-limb control has one target snapped to axes `45°` apart
and a fixed total interval width of `45°`. When checked, it stores that target
as an array containing exactly one inclusive normalized `{start, end}` range
under `brightLimbOrientationDegrees`, including when the range crosses `0°`.
While active, it sends the same range. When Full is the only selected
Moon-shape group, the browser keeps the checked target in version 1 storage
through Apply and reload, disables the control, hides its dial, and excludes
the target from the active count and from ordinary and planning requests.
Selecting a non-Full group reactivates the same snapped target. With Full and
any non-Full group selected, the target remains active for the non-Full phases
and Full samples cannot match it.
Neighboring target sectors share their boundary and together cover the full
circle. The browser derives and snaps the dial target when it restores stored
state. Version 1 ranges written with the earlier `20°` width migrate to the
nearest current axis and width. It does not store a separate target field or a
user-configurable tolerance.

With no active hard preference and the default weather ranking, the browser
uses product GET. It uses product POST when any hard preference or non-default
weather choice is active. The body includes `preferences` only for active hard
preferences and top-level `weatherRanking` only for `prefer_clear` or
`ignore_weather`. Planning requests contain only the current hard-preference
snapshot and never contain `weatherRanking`. The server uses hard preferences
as hard filters to find precise matching fragments. It may join nearby
fragments only under the ordinary timing rule above. The server uses the
weather choice only for scoring and ranking. It must not permanently store the
request body or preferences or add them to a server-side profile, cookie,
analytics event, page, lookup or share URL, application log, provider cache,
opportunity cache, weather cache, or cache shared across backend instances. The
process-local Atom feed-state cache is the narrow exception: after a client
opens a filtered Atom URL, it keys rebuildable state by the URL's canonical
filtered path. The rolling iCalendar feed stores no output or subscription
state. Its private 15-minute client cache policy does not guarantee reuse; an
uncached maximum response may render ten inline Moon images and send roughly
0.8-0.9 MiB. Existing provider caches may save provider calls, but they do not
save scoring, image rendering, serialization, or bandwidth.

Search order is request state. The browser puts `order=soonest` in the page URL
and generated share links, and omits the default `best_match` order. It does
not store order in an account, cookie, server profile, or `localStorage` entry.

Share links contain the location and may contain order. They contain no
preference value. A browser opening a share link applies its own saved
preferences, if any. For a loaded real-location result, the browser shows at
most one action labeled `Atom feed`. A non-empty `normalizedActiveFilters`
object or an `appliedWeatherRanking` of `prefer_clear` or `ignore_weather`
selects filtered state. A non-blank root `links.atomWithFilters` string then
supplies the action's backend URL unchanged. If that value is absent,
non-string, or blank, the filtered result has no Atom action and does not
silently substitute an all-off feed.

Otherwise, the response is all-off and the action uses the current
location-only Atom URL. The browser ignores a stray `links.atomWithFilters` in
that state. It never shows a second Atom action or the label `Atom feed with
these filters`.

A backend-generated individual `.ics` URL carries the canonical location,
selected order, non-default weather ranking, and active hard preferences so
reopening it can reproduce the same public search. Every displayed ordinary
recommendation with a non-blank `links.ics` string shows `Download calendar
event` with that backend URL unchanged. The browser does not require
`links.icsReady` or serialize a calendar. It treats every response-supplied URL
as opaque.

The rolling calendar route accepts the canonical location, non-default weather
ranking, and active hard preferences in its public URL. It creates no account,
token, saved subscription, persistent preference, or scheduled job. Issue #305
will add a root `links.calendarFeed` value and `Copy calendar URL` action only
after #304 is deployed. The backend will own the complete root-relative path
and query; the browser will add only its current origin. The first discovery
flow will not use `webcal:` or `webcals:` and will not reconstruct preferences.

When applied response metadata reports at least one normalized hard preference
or non-default weather ranking, and at least one usable preference-bearing
calendar action or the filtered `Atom feed` action is present, the browser shows
this warning once before the opportunity list:

> This link contains your selected location and photography filters. Anyone
> with the link can see them, including your preferred observation times and
> viewing direction (altitude and azimuth). Do not share it if those details
> are private.

Every usable preference-bearing calendar action and the `Atom feed` action in
filtered state reference the one warning through its stable element ID. The
all-off Atom action does not. A missing filtered Atom action does not remove a
warning still required by a calendar action. The browser uses response metadata
rather than parsing a URL to decide whether the warning is required. Filtered
Atom, individual-export, and subscribable calendar URLs create no server
profile or durable state, but browsers,
feed and calendar clients, copied-link recipients, Funnel, and systems that log
the full request target may see the encoded filters. These can reveal preferred
observation hours and altitude or azimuth viewing direction. Moon Service
application logs omit the query string, and operators must keep these query
strings out of access logs.
Resetting all preferences removes the version 1 object and the weather-ranking
value when browser storage accepts both removals, and uses `balanced` in memory.
A weather-ranking read failure uses `balanced` for that load and shows the
storage notice. An unsupported value does the same, is removed when possible,
and shows the unsupported-format notice. A failed write
keeps the newly applied choice in memory. A failed removal keeps `balanced`
in memory and may leave the older value for a later load.
Both show the storage notice. If `localStorage` is blocked or
unavailable, the browser keeps preferences only in page memory and search
continues. There is no server preference profile or cross-device sync.

## Calibration Feedback Boundary

Moon Service may collect optional reports from alpha testers about the real
opportunity that the browser currently shows. The selected location and
opportunity context are claims from the tester. Converting the city to a
canonical record does not prove that the tester was there or that the
opportunity happened as claimed. These reports form a calibration set that a
person can inspect. They are not for user profiles, engagement tracking, or a
general feedback inbox. Issue
[#33](https://github.com/rapucha/moon-service/issues/33) owns collection,
curation, and any later evidence-backed scoring work.

A tester does not need an account to submit calibration feedback. A stored
report is not necessarily anonymous. Notes, a city-level location, opportunity
context, and the submission time may contain or reveal identifying
information. The form should tell testers not to include names, exact
addresses, or other personal details that do not help explain the evidence.

When feedback storage is enabled, Moon Service may retain only these report
fields:

- The report schema version and client and server feedback UUIDs.
- The claimed opportunity ID copied from the loaded result and the canonical
  backend city-level location ID.
- Optional normalized ambient-light, crescent-visibility, and notes evidence.
  At least one must be present.
- One server receipt instant, also used as the submission instant.
- Exactly the Moon altitude, Moon illumination, Sun altitude, and light bucket
  recomputed for that instant.
- The server-controlled application revision and raw 32-byte idempotency hash.

Application and access logs controlled by Moon Service must not retain any of
these values from feedback requests: raw request bodies, location or
opportunity IDs, evidence values, notes, either feedback UUID, astronomy
values, IP addresses, forwarded identity, or User-Agent values. This rule does
not mean that the retained report cannot identify a person.

Feedback storage is disabled by default and has a configurable limit. It has no
unlimited mode and no automatic retention period. Reports remain until an
operator deletes them. The storage and operator-warning rules are defined in
[the architecture](architecture.md#calibration-feedback-storage).

A database or NFS failure may cause Moon Service to lose this alpha calibration
evidence. The project accepts that risk. This decision applies only to
calibration reports. Any future important or personal stored data needs its own
backup and recovery decision. A storage failure may disable feedback, but it
must not affect normal opportunity lookup or readiness.

## Terrain Caveat

The MVP can use observer elevation from geocoding when it is available. It
should not claim to account for objects or terrain that block the local
horizon. In hilly cities or near mountains, the Moon or Sun may appear later or
disappear earlier than the geometric horizon suggests. Terrain horizon
modeling should wait until users can choose an exact shooting position.

## Fictional Location Easter Eggs

The web UI can include fictional, mythic, literary, and videogame locations as
Easter eggs. These locations should return clearly fictional reports, not
invented real-world coordinates.

Rules:

- Clearly label the result as fictional.
- Do not mix fictional reports into real weather, ephemeris, RSS/Atom, `.ics`, or notification outputs.
- Do not present fictional reports as real photography guidance.
- Keep the tone playful but concise.
- Prefer public-domain, mythic, or generic examples when possible.
- For modern franchises, avoid copying protected text and keep references brief.

If a query matches both real and fictional meanings, show each one as a
separate choice:

```text
Prague, Czech Republic
Prague, Oklahoma, United States
Prague, Fallout universe (fictional)
```

Fictional reports can include radstorms, impossible Moon phases, dream weather,
or fantasy-light conditions as long as the UI clearly marks the result as
fiction.

Fallback behavior:

- Try real geocoding first.
- Check curated fictional locations second.
- If neither matches, a later LLM-assisted fallback may classify whether the
  query belongs to recognizable lore and generate a clearly fictional report.
- If the LLM is uncertain, return the normal not-found response.

LLM-generated Easter eggs need guardrails:

- Never produce real coordinates.
- Never claim the place exists.
- Never imitate copyrighted prose or character voices.
- Keep franchise references short and factual.
- Cache approved fictional mappings so repeated queries do not require another
  LLM call.
- Prefer a reviewable allowlist for popular fictional universes before enabling public generation.

Useful examples:

- Xanadu.
- Atlantis.
- Camelot.
- El Dorado.
- Shangri-La.
- Utopia.
- Lilliput.
- Brobdingnag.
- Laputa.
- Narnia.
- Minas Tirith.
- Rivendell.
- Hogwarts.
- Neverland.
- R'lyeh.
- The Shire.
- Fallout-universe locations.

Possible not-found copy when there is no real or fictional match:

```text
We could not find that place on Earth or in the usual imaginary maps. Try a city or town, such as Prague, Kyoto, or Tromso.
```

## Identity Direction

Avoid mandatory accounts initially.

If Moon Service later adds server-backed saved alerts or identity, prefer this
identity model:

- Anonymous device identity created at install.
- Local credential storage using the selected platform's protected storage.
- Optional recovery code.
- Optional email magic link for backup, email notifications, or calendar features.
- The service can associate a push token with the anonymous identity later.

Device-only identity must not trap users when they lose a device. The project
should design recovery early, even if the first implementation is small.

## Later Product Ideas

- An Expo iOS/Android companion, only after the web, feed, and calendar flow is
  complete and testers show recurring demand. Keep saved places on the device
  and use local notifications first. Share contracts, validation, formatting,
  domain logic, design rules, assets, and suitable simple components with the
  website. Keep complex views, URLs, storage, notifications, permissions, and
  distribution specific to each platform. The client may cache results and
  perform bounded offline Moon calculations, but the backend remains
  authoritative for weather-backed scoring.
- Map view with Moon azimuth corridor.
- Shooting-position to subject-position planning.
- Focal length and composition hints.
- Horizon elevation and terrain modeling.
- Skyline or building obstruction checks.
- Saved compositions.
- Recurring event-aware opportunities, such as aircraft approaches, transport
  routes, public events, or user-defined weekly patterns.
- Later calendar OAuth.
- Email alerts for users who opt in.
- Telegram-style broadcast channels for popular cities or regions.
- Reddit community posts or a project-owned subreddit.
