# Product Notes

## Product Direction

Moon Service helps photographers catch photogenic Moon opportunities near locations they care about. The core value is a timely alert that says, in effect: the Moon will be low, visible, and the weather may be worth trying.

The MVP should be lightweight and easy to try. The favored first surface is now a web discovery flow: enter a city or location, see the next good Moon opportunity, and optionally use RSS/Atom or `.ics` export.

## Target User

The first user is a photographer who wants a quick answer for local Moon shots:

- Moon near the horizon.
- Ambient light still useful for foreground detail while preserving visible detail in the Moon.
- Forecast suggests clear sky, partial cloud, or visually interesting conditions.
- Result or alert arrives early enough to travel or set up.

The product should support one-off location lookup before saved compositions. Exact landmark alignment is a later feature.

A later user may care about repeatable subjects or events, not only the Moon
and weather by themselves. For example, a certain aircraft approach, train,
ferry, or other local subject may pass through a useful part of the sky at
approximately the same local time on some days. Moon Service should eventually
help users find and follow Moon opportunities that overlap those recurring but
imperfectly timed events.

## MVP User Promise

No account required. Enter a city or location and see the next promising Moon
opportunity. Use RSS/Atom feeds or `.ics` export for low-friction follow-up.
Add email or an installed client later only if recurring personal alerts become
useful.

The first useful result can include:

- Location name.
- Date and time window.
- Moon altitude and azimuth.
- Moon phase or illumination.
- Sun state, such as daylight, golden hour, civil twilight, or night.
- Weather summary and confidence.
- A short reason the opportunity scored well.

The product should think like a photographer, not only like an astronomy calculator. The Moon is bright and often needs a relatively short exposure to avoid blown highlights. A useful opportunity is often when the sky and foreground still have enough Sun-driven ambient light that the photographer can expose for Moon detail without losing the landscape, skyline, tree, building, or hill into darkness. This favors daylight edges, golden hour, and twilight, but should still report darker or crescent situations when the facts are promising and clearly explained.

## MVP Scope

In scope:

- Enter a city or location.
- Support raw Unicode city/location input.
- Disambiguate city/location search results when needed.
- Remember recent searches in browser `localStorage` only.
- Find upcoming Moon windows for that location.
- Score opportunities using Moon geometry, Sun state, and weather.
- Present ranked opportunities.
- Provide a shareable result page.
- Provide RSS/Atom feeds for public city/region or best-upcoming opportunities.
- Provide dynamic public `.ics` calendar feeds for canonical real locations, generated on demand and cached.
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

Some good photography opportunities are not just "the Moon is good here"; they
are "the Moon is good when this repeatable subject may appear." A future scoring
layer should allow approximate recurring event context such as:

- Days of week or other recurrence rules.
- Approximate local time or time range.
- Early/late tolerance, because real events can be delayed, cancelled, or early.
- Optional route, direction, azimuth, or subject position when known.
- Active date range and source confidence.

The first useful version can be request-scoped or encoded in a shareable URL,
RSS/Atom feed, or `.ics` calendar feed. Personal saved subscriptions should
wait until the privacy model covers stored preferences, notification endpoints,
retention, update behavior, and deletion.

The scoring-context follow-up for this direction is tracked by
[#3](https://github.com/rapucha/moon-service/issues/3).

The product must phrase event-aware results as planning cues, not guarantees.
For aircraft and other transport examples, do not imply real-time tracking,
exact pass timing, or confirmed operation unless a live provider is deliberately
integrated and documented.

## Privacy Stance

The product should avoid collecting permanent location data by default.

The first web/API flow should avoid permanent user-location storage. The backend can geocode, score, and cache selected city/location records plus weather by rounded coordinate/time bucket without storing a user profile. The UI should steer users toward city/town search, not exact home addresses. If saved alerts, push subscriptions, email, or cloud sync are introduced, document exactly what is stored and why.

Location search should accept local-language and non-Latin names. Browser locale is only a display/ranking hint; it must not prevent a raw query such as `Praha`, `München`, `東京`, or `京都` from resolving.

The web UI may store a small ordered list of recent searches in browser `localStorage`. Store display names and slugs/canonical IDs only, not timestamps, exact addresses, cookies, or server-side user identifiers. Provide a clear recent-searches control.

Email plus saved location preferences is personal data. Do not add email alerts until the product has a privacy notice, consent/unsubscribe/delete flows, retention rules, and an email provider/data-processing plan.

Reddit can be used for manual community validation or a project-owned subreddit later. Do not auto-post to existing subreddits without moderator approval. Mastodon and Bluesky are not planned for now.

## Terrain Caveat

The MVP can use observer elevation from geocoding when available, but it should not claim to account for local horizon obstruction. In hilly cities or near mountains, the Moon or Sun may appear later or disappear earlier than the geometric horizon suggests. Terrain horizon modeling should wait until users can choose an exact shooting position.

## Fictional Location Easter Eggs

The web UI can include fictional, mythic, literary, and videogame locations as Easter eggs. These should produce clearly fictional reports, not invented real-world coordinates.

Rules:

- Clearly label the result as fictional.
- Do not mix fictional reports into real weather, ephemeris, RSS/Atom, `.ics`, or notification outputs.
- Do not present fictional reports as real photography guidance.
- Keep the tone playful but concise.
- Prefer public-domain, mythic, or generic examples when possible.
- For modern franchises, avoid copying protected text and keep references brief.

If a query matches both real and fictional meanings, show both as distinct choices:

```text
Prague, Czech Republic
Prague, Oklahoma, United States
Prague, Fallout universe (fictional)
```

Fictional reports can include fictional conditions such as radstorms, impossible moon phases, dream weather, or fantasy-light conditions, as long as the UI is unmistakably marked as fiction.

Fallback behavior:

- Try real geocoding first.
- Check curated fictional locations second.
- If neither matches, a later LLM-assisted fallback may classify whether the query belongs to recognizable lore and generate a clearly fictional report.
- If the LLM is uncertain, return the normal not-found response.

LLM-generated Easter eggs need guardrails:

- Never produce real coordinates.
- Never claim the place exists.
- Never imitate copyrighted prose or character voices.
- Keep franchise references short and factual.
- Cache approved fictional mappings so repeated queries do not require repeated LLM calls.
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

Preferred future identity model, if server-backed saved alerts or identity are added:

- Anonymous device identity created at install.
- Local credential storage using the selected platform's protected storage.
- Optional recovery code.
- Optional email magic link for backup, email notifications, or calendar features.
- Push token registration can be associated with the anonymous identity later.

Device-only identity must not become a trap. Recovery should be designed early, even if implemented minimally.

## Later Product Ideas

- A later Expo iOS/Android companion, only after the web, feed, and calendar
  flow is complete and testers show recurring demand. Keep saved places
  device-only and notifications local-first. Share contracts, validation,
  formatting, domain logic, design rules, assets, and suitable simple
  components with the web app. Keep complex views, URLs, storage,
  notifications, permissions, and distribution platform-specific. Cached
  results and bounded offline Moon calculations are allowed, but the backend
  remains authoritative for weather-backed scoring.
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
