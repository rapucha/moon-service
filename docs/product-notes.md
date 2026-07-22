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
Moon opportunity. Use RSS/Atom feeds or `.ics` export to follow future
opportunities. Add email or an installed client later only if users find
recurring personal alerts useful.

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
- Provide RSS/Atom feeds for public city/region or best-upcoming opportunities.
- Provide dynamic public `.ics` calendar feeds for canonical real locations.
  Generate them on demand and cache them.
- Provide `.ics` export for individual events.

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
- `.ics` export and later calendar OAuth.
- Email alerts for users who opt in.
- Telegram-style broadcast channels for popular cities or regions.
- Reddit community posts or a project-owned subreddit.
