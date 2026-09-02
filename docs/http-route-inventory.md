# HTTP Route Inventory

This is the canonical inventory of HTTP operations explicitly mapped by Moon
Service controllers. It records who uses each route, why it exists, and how its
exposure differs between the ordinary application and hosted-alpha mode.

The route universe is the sixteen mappings declared by `WebPageController`,
`AtomFeedController`, `ICalendarEventController`, `ICalendarFeedController`,
`OpportunitySearchController`, `MoonPlanningController`, `MoonEventController`,
`CalibrationFeedbackController`, `HealthController`, and
`AdminStatusController`. Spring's implicit `HEAD`
handling, `/error`, exception handlers, and static-resource serving are
behavior around those mappings, not additional inventory entries. Outbound
Open-Meteo URLs are provider dependencies, not Moon Service routes.

## Summary

| Operation | Role and lifecycle | Current production consumer | Hosted alpha |
| --- | --- | --- | --- |
| `GET /` | Web entry page; current product route | Web browser | Allowlisted; whole-site bound |
| `GET /search` | Lookup and share page; current product route | Web browser | Allowlisted; whole-site bound |
| `GET /about` | Product/privacy information page | Web browser | Allowlisted; whole-site bound |
| `GET /feeds/atom` | Public Atom feed for one canonical location, with optional hard preferences and weather ranking | Feed reader; browser copies one current-origin URL for the applied state | Allowlisted; site and provider bounds |
| `GET /o/{opportunityId}.ics` | Stateless individual iCalendar export | Browser action for each usable ordinary product link | Allowlisted for valid paths; site and provider bounds |
| `GET /calendars/opportunities.ics` | Stateless rolling iCalendar feed for one canonical location, with optional hard preferences and weather ranking | Network calendar polls a backend-generated URL; browser copies it from a successful real-location response | Allowlisted; site and provider bounds |
| `GET /api/opportunities` | Location-to-opportunity product API | Browser `app.js` | Allowlisted; site and search bounds |
| `POST /api/opportunities` | Request-scoped preference product API | Browser `app.js` through `opportunityPreferences.js` | Allowlisted POST; site and provider bounds |
| `POST /api/opportunities/planning` | Weather-free next-date planning API | Browser `app.js` through `opportunityPreferences.js` | Allowlisted POST; site and provider bounds |
| `POST /api/moon-events` | Lunar-eclipse and near-perigee full-Moon discovery API | Browser `moonEventView.js` | Allowlisted POST; site and provider bounds |
| `POST /api/opportunities/search` | Direct fixture/scoring prototype contract | None | Hidden after site admission |
| `GET /api/calibration-feedback/v1/capability` | Public feedback feature/availability state | None yet | Allowlisted; exempt from hosted resource admission |
| `POST /api/calibration-feedback/v1/submissions` | Bounded current-observation feedback write | None yet | Allowlisted POST; provider-bound resolution and feedback write bucket |
| `GET /healthz` | Process liveness probe | None | Hidden after site admission |
| `GET /readyz` | Deployment readiness probe | Docker and Pi deployment tooling | Allowlisted; narrow Docker bypass |
| `GET /admin/status` | Operator diagnostics | Human operator | Site-bound and token protected |

## Shared behavior and exposure

- `@GetMapping` operations also receive Spring MVC's implicit `HEAD` handling;
  `HEAD` is not a separate application-authored mapping.
- Requests that reach `RequestLoggingFilter` receive `X-Request-Id`. Logging
  records the path but not the query string, so location values are not written
  by that filter.
- With `moon.hosted-alpha.enabled=false` (the default), mappings are available
  wherever the configured listener is reachable. Deployment binding determines
  whether that means loopback or an explicitly trusted LAN address.
- In hosted-alpha mode, resource admission runs before surface policy and admin
  authentication. Except for the exact Docker readiness probe and the two exact
  calibration-feedback paths described below, every request that reaches this
  filter first consumes from one configured process-wide bucket. Its default
  and maximum allowed hosted capacity is 40, with a default and fastest allowed
  refill of one token per second; stricter settings are valid. An empty bucket
  returns `429` with a numeric `Retry-After` before the route's usual `200`,
  `400`, `401`, `404`, `405`, or `503` behavior can be selected. `GET`, the
  product POST, planning POST, and Moon-event POST return canonical
  `rate_limited` JSON; all three POST responses include `Cache-Control:
  no-store`. `HEAD` carries the same status, headers, and would-be content
  length without a body.
- Exact `GET`/`HEAD /api/opportunities` and `POST /api/opportunities` requests
  that pass the whole-site bound ask the shared non-web
  `HostedAlphaProviderAdmission` component to acquire a concurrent
  provider-operation permit and consume from a provider bucket. The defaults
  and maximum allowed hosted settings are two concurrent provider operations,
  ten provider tokens, and a one-token-per-minute refill; stricter settings are
  valid. A refusal returns to `HostedAlphaResourceLimitFilter`, which maps it to
  `429`; an accepted permit is released when downstream handling finishes. The
  exact planning and Moon-event POSTs use the same resources after whole-site
  admission. Those resources also apply to exact `GET` or `HEAD /feeds/atom` before its feed
  cache lookup and to structurally valid `GET` or `HEAD /o/*.ics` before its
  live search. Exact `GET` or `HEAD /calendars/opportunities.ics` uses the same
  resources before its live search. A cached Atom request can therefore
  receive `429`. These resources also
  wrap feedback location resolution as described below;
  they do not apply to pages, static files, admin status, readiness, or the
  fixture POST route.
- Whole-site bypasses are the bodyless `GET /readyz` whose connector reports a
  loopback remote address and `Host: localhost`, matching the Docker health
  check, and both exact feedback paths. Other readiness requests still consume
  whole-site capacity. Capability performs no provider work. After early
  disabled, replay, conflict, and storage decisions, feedback location
  resolution asks the same `HostedAlphaProviderAdmission` owner for the shared
  provider token and concurrency guard. A refusal becomes generic
  `503 feedback_unavailable`. Successful resolution can then reach the separate
  process-wide 12-token feedback write bucket, which restores one whole token
  per complete hour. Resolver failure or provider-admission refusal consumes no
  feedback write token.
- Hosted-alpha mode exposes only exact allowlisted paths. It allows bodyless
  `GET` or `HEAD` on every approved path except feedback submissions and the
  planning and Moon-event routes. It also allows `POST` with a body on the
  exact product-opportunity, planning, Moon-event, and feedback submission
  paths, and passes each body to that route's 16,384-byte bound. It adds the
  hosted security headers, returns empty `404` for hidden or unknown path variants, empty `405` with a
  path-specific `Allow` value for disallowed methods, and empty `400` for a
  framed `GET` or `HEAD` body. The exact `/moonEventView.css`,
  `/moonEventView.js`, `/moonEventPath.js`, `/lunarEclipseCard.js`,
  `/lunarEclipseRenderer.js`, `/cameraFramingPreview.js`,
  `/cameraReferenceScene.js`, `/highResolutionMoonRenderer.js`, `/planningView.js`,
  `/cameraFramingPreview.css`, and
  `/moon-textures/lroc_color_2k.jpg` resources allow only bodyless `GET` and
  `HEAD`. The same rule applies to the six exact camera-preview paths:
  `/camera-preview/level-0.webp`, `/camera-preview/level-1.webp`,
  `/camera-preview/level-2.webp`, `/camera-preview/level-3.webp`,
  `/camera-preview/level-4.webp`, and `/camera-preview/level-5.webp`. These POST
  operations and static resources send no permissive CORS headers, and
  `OPTIONS` does not provide preflight support.
  Tomcat rejects `TRACE` before the application filter, so those application
  headers and empty-body guarantees do not apply to `TRACE`.
- The web lookup is anonymous and creates no durable user profile or preference.
  A `q` value crosses the Open-Meteo geocoding boundary; normalized queries or
  location IDs and their results are held in a bounded, process-local cache.
  Resolved location data then drives an Open-Meteo weather lookup. The product
  POST never sends preferences to either provider and returns every response
  with `Cache-Control: no-store`. Preference field names and values are not
  permanently stored, logged, or placed in a provider, opportunity, weather,
  or cross-instance cache. The process-local Atom feed-state cache is the narrow
  exception: after a client requests a filtered Atom URL, it keys rebuildable state
  by the URL's canonical filtered path. Page, lookup, and share URLs contain no
  preference values. A successful product response may contain
  backend-generated individual-export, filtered Atom, and subscribable-calendar
  URLs carrying the canonical location, non-default weather ranking, and active
  hard preferences; the individual export may also carry selected order. The
  calendar link is root-relative, and the browser adds only its current origin
  when copying it. Moon Service request logging omits these query strings, but
  browsers, feed and calendar clients, copied-link recipients, Funnel, and
  infrastructure that records the full request target can see them.
  Preference-related application logging is
  limited to the documented ignored-field aggregate event and the sanitized
  filtered-link invariant event described under `POST /api/opportunities`.

Implementation authority: [request logging](../backend/src/main/java/dev/moonservice/backend/observability/RequestLoggingFilter.java),
[hosted resource-limit filter](../backend/src/main/java/dev/moonservice/backend/web/HostedAlphaResourceLimitFilter.java),
[shared provider admission](../backend/src/main/java/dev/moonservice/backend/admission/HostedAlphaProviderAdmission.java),
[hosted-alpha surface filter](../backend/src/main/java/dev/moonservice/backend/web/HostedAlphaSurfaceFilter.java),
and [hosted-alpha functional tests](../backend/src/test/java/dev/moonservice/backend/web/HostedAlphaSurfaceFunctionalTest.java).

## Browser pages

### `GET /`

- **Handler:** `WebPageController.searchPage` internally forwards to
  `/index.html`; it is not a redirect and sends no `Location` header.
- **Purpose/audience:** zero-install browser entry point for anonymous users.
- **Production invocation:** direct navigation to the service root.
- **Other callers:** application functional tests.
- **Authentication/data:** none; the route itself has no input.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [functional test](../backend/src/test/java/dev/moonservice/backend/OpportunitySearchFunctionalTest.java).

### `GET /search`

- **Handler:** `WebPageController.searchPage`, with the same internal forward to
  `/index.html`.
- **Purpose/audience:** current lookup page and shareable result URL.
- **Production invocation:** navigation links and browser history use
  `/search?q=...` or `/search?locationId=...`, with optional `order=soonest`.
  A user may share that current browser address. Browser code reads the lookup
  and order parameters. It calls
  `GET /api/opportunities` when no hard preference is active and
  `POST /api/opportunities` when at least one is active. `locationId` wins if
  both lookup fields are present in a page URL. Preference values never enter
  the page URL, so they are absent when a user shares the current browser
  address.
- **Other callers:** browser and application functional tests.
- **Authentication/data:** none. The URL can contain a location query or
  selected location ID and is therefore visible in browser history/share links.
  The browser keeps up to five successful display names, location IDs, and
  timezones under `moonService.recentSearches.v1`. It keeps the supported
  versioned altitude and availability preferences under
  `moonService.opportunityPreferences.v1`. It separately keeps the camera
  estimate setup under `moonService.cameraSetup.v1`; exact setup values never
  enter a product API request, URL, share link, or recent-search entry. Digital
  framing requests one setup-derived `/camera-preview/level-N.webp` static
  asset, whose coarse selected-level path may appear in ordinary request logs.
  All three `localStorage` entries are optional and client-side; the page still
  works when browser storage is unavailable.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode. The exact
  `/cameraSetup.js`, `/cameraFramingPreview.js`, `/cameraReferenceScene.js`,
  `/highResolutionMoonRenderer.js`, `/cameraFramingPreview.css`, and
  `/moon-textures/lroc_color_2k.jpg` resources allow bodyless `GET` and `HEAD`
  under the same hosted-alpha static-resource rules. So do the exact
  `/camera-preview/level-0.webp`, `/camera-preview/level-1.webp`,
  `/camera-preview/level-2.webp`, `/camera-preview/level-3.webp`,
  `/camera-preview/level-4.webp`, and `/camera-preview/level-5.webp` resources.
- **Order state:** omitted order selects `best_match`; a generated Soonest link
  contains `order=soonest`. The browser keeps order only in page request state
  and the URL. It creates no order cookie, profile, or `localStorage` entry.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [browser flow](../frontend/src/app.js),
  [ordinary response composition](../frontend/src/responseView.js),
  [special Moon event view](../frontend/src/moonEventView.js),
  [recent-search storage](../frontend/src/recentSearches.js),
  [camera setup](../frontend/src/cameraSetup.js),
  [camera reference scene](../frontend/src/cameraReferenceScene.js),
  [preference state and transport](../frontend/src/opportunityPreferences.js),
  [share paths](../frontend/src/api.js).

### `GET /about`

- **Handler:** `WebPageController.aboutPage` internally forwards to
  `/about.html`.
- **Purpose/audience:** product purpose, privacy posture, and caveats for users.
- **Production invocation:** navigation from the search page and direct visits.
- **Other callers:** application functional tests.
- **Authentication/data:** none; the route has no input.
- **Exposure:** available on the ordinary listener; allowlisted but subject to
  the whole-site admission bound in hosted-alpha mode.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/WebPageController.java),
  [page](../frontend/src/about.html).

## Product and prototype APIs

### `GET /feeds/atom`

- **Handler:** `AtomFeedController`; Spring also serves matching bodyless
  `HEAD` requests.
- **Purpose/lifecycle:** current public Atom feed for one canonical location.
  The same route can apply canonical Version 1 hard preferences and
  non-default weather ranking while keeping fixed `soonest` order.
- **Production invocation:** after a real location loads, `Copy Atom feed link`
  copies the browser's current origin plus the path for the applied state. A
  feed reader can poll the copied URL. All-off uses the browser-built
  location-only path and ignores a stray filtered member. Filtered state uses a
  non-blank backend `links.atomWithFilters` string unchanged; an unusable value
  produces no Atom copy button and no all-off fallback. Both backend URL forms
  remain valid.
- **Authentication/data:** none. The query requires `locationId` and may contain
  canonical `weatherRanking` and `preferences`. Unknown preference-object
  members are ignored, while duplicate or unknown query parameters and invalid
  recognized values fail before provider work. The route creates no account,
  subscriber record, saved subscription, token, or durable feed state.
  Location-only responses are public-cacheable; filtered and any
  preference-bearing responses are private-cacheable. Application request logs
  omit the query string. Operators must also keep preference-bearing query
  strings out of access logs because they can reveal location, observation
  hours, and altitude or azimuth viewing direction.
- **State:** the existing `96 MiB` process cache keeps exact XML weight for
  location-only state and applies a `96 KiB` minimum only to filtered state.
  Semantically equivalent normalized requests share one state. State remains
  rebuildable and disappears on eviction or restart.
- **Exposure:** exact `GET` and `HEAD /feeds/atom` are allowlisted in hosted
  alpha. Whole-site and provider admission run before the process feed cache,
  so a cached request can receive `429`.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/AtomFeedController.java),
  [service](../backend/src/main/java/dev/moonservice/backend/web/AtomFeedService.java),
  [Atom contract](api-shape.md#public-atom-feed).

### `GET /o/{opportunityId}.ics`

- **Handler:** `ICalendarEventController`; Spring also serves matching bodyless
  `HEAD` requests. `/o/.ics` reaches the same validation path so a missing ID is
  an application `400`, not an accidental framework `404`.
- **Purpose/lifecycle:** current anonymous, stateless export of one ordinary
  opportunity as one iCalendar event. Product GET and preference POST responses
  supply the complete reusable URL.
- **Production invocation:** the browser renders `Download calendar event` for
  every displayed ordinary recommendation with a non-blank `links.ics` string
  and uses that string unchanged. It does not read or declare an `icsReady`
  flag.
- **Request:** the opaque path ID is bounded and must not be blank or contain
  control characters. `locationId` is required. Optional `order`,
  `weatherRanking`, and percent-encoded Version 1 `preferences` reproduce the
  product search that supplied the result. Backend-generated links use that
  fixed query order and omit default values. Unknown members inside the decoded
  preference object follow product-POST tolerance; duplicate or unknown URL
  parameters, malformed JSON, unsupported versions, and invalid known values
  are rejected before provider work.
- **Behavior:** the route reruns the current seven-day product search with its
  ten-result limit and selects only the exact requested ID. An unresolved
  canonical location is `404 location_not_found`; a resolved search without
  the exact ID is `404 opportunity_not_found`. It never substitutes another
  opportunity and has no bare-path fallback, snapshot token, account, durable
  record, or separate cache.
- **Response:** success is UTF-8 `text/calendar`, `Content-Disposition:
  attachment; filename="moon-opportunity.ics"`, and `Cache-Control: no-store`,
  with one deterministic UTC `VEVENT`. `DTSTART` is floored and `DTEND` ceiled
  to whole minutes. Each event also contains one inline RFC 7986 `IMAGE`: a
  192-by-192 transparent phase-and-orientation PNG with `ENCODING=BASE64`,
  `VALUE=BINARY`, `DISPLAY=BADGE`, and `FMTTYPE=image/png`. It has no URI or
  external request. Clients may ignore it, so normal event import remains
  required. Current GET bodies and matching HEAD `Content-Length` values are
  about 80–90 KiB. The first render initializes the existing Atom Moon texture;
  it adds no asset, cache, setting, or runtime service. Application errors are
  safe JSON for GET and bodyless for HEAD; hosted surface errors remain
  bodyless. Every route response is `no-store`; there is no ETag or
  Last-Modified validator.
- **Authentication/data:** none. Preferences remain request-scoped and never
  reach a provider or durable store. The public URL can reveal selected
  observation hours, viewing direction, and other filters to browsers,
  calendar clients, copied-link recipients, Funnel, and full request-target
  logs. Moon Service application logs omit its query string.
- **Exposure:** structurally valid `GET` and `HEAD /o/*.ics` are allowlisted in
  hosted alpha. Whole-site and provider admission run before controller and
  provider work. Other methods, framed bodies, and malformed path shapes receive
  the existing bodyless surface responses.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/ICalendarEventController.java),
  [renderer](../backend/src/main/java/dev/moonservice/backend/web/ICalendarEventRenderer.java),
  [canonical query](../backend/src/main/java/dev/moonservice/backend/web/PublicPreferenceQuery.java),
  [API contract](api-shape.md#individual-icalendar-event).

### `GET /calendars/opportunities.ics`

- **Handler:** `ICalendarFeedController`; matching `HEAD` requests use the same
  validation, admission, location resolution, and opportunity search.
- **Purpose/lifecycle:** anonymous, stateless rolling calendar for up to ten
  ordinary opportunities at one canonical location. It uses fixed `soonest`
  order and the current seven-day opportunity engine.
- **Production invocation:** a network-calendar client polls a direct URL.
  Manual validation in Thunderbird 153.0esr and GNOME Calendar 41.2 covered
  loading, additions, same-UID updates, and omission while another event
  remained. Thunderbird cleared the final event on an empty snapshot; GNOME
  fetched it but retained the final cached event. Successful canonical
  real-location product GET and POST responses expose the backend-generated
  root-relative path as `links.calendarFeed`, including when no opportunity is
  returned. The browser's `Copy calendar feed link` button copies its current
  origin plus that exact value through the existing Clipboard API or prompt
  fallback.
  An absent, non-string, empty, whitespace-padded, absolute, or network-path
  value hides the action without a fallback or reconstruction. No `webcal:` or
  `webcals:` launcher exists. With both feed paths usable, the result summary
  contains exactly the matching `Copy Atom feed link` and `Copy calendar feed
  link` buttons. Both use the same temporary `Copied` behavior. Filtered buttons
  reference the existing warning through `aria-describedby`; all-off buttons do
  not.
- **Request:** `locationId` is required. Optional non-default
  `weatherRanking` and active canonical Version 1 `preferences` follow the
  shared public query codec. The route rejects `order`, duplicate or unknown
  query parameters, and invalid recognized values before provider work.
- **Behavior:** each request runs the current search and returns a complete
  snapshot. The calendar has the resolved location's structural `VTIMEZONE`
  and zero to ten events ordered by precise `suggestedAt`, then ID. Each event
  reuses #294's UID, outward-minute UTC times, three-line description, and
  inline 192-by-192 PNG. A successful empty search is `200` with no placeholder
  event. Events absent from a later result are absent from the next response;
  client reconciliation follows the compatibility result above.
- **Response:** successful `GET` is UTF-8 `text/calendar` with
  `Cache-Control: private, max-age=900` and exact `Content-Length`. `HEAD`
  skips calendar and image serialization and omits `Content-Length`. There is
  no ETag, output cache, `Last-Modified`, attachment disposition, account,
  token, persistent subscription, scheduled generation, or new provider.
  Errors are `no-store`.
- **Cost/state:** an uncached maximum `GET` may render ten PNGs and return
  roughly 0.8-0.9 MiB. Provider caches can save provider calls, but not scoring,
  rendering, serialization, or bandwidth. The route stores no output or
  reconciliation state.
- **Authentication/data:** none. Application logs omit the query string.
  Calendar clients, copied-link recipients, Funnel, and request-target logs can
  still learn the location, observation hours, and altitude or azimuth filters.
- **Exposure:** exact `GET` and `HEAD` are allowlisted in hosted alpha.
  Whole-site and provider admission run before the search. Other methods,
  request bodies, and path variants retain the existing bodyless hosted
  rejection.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/ICalendarFeedController.java),
  [renderer](../backend/src/main/java/dev/moonservice/backend/web/ICalendarEventRenderer.java),
  [canonical query](../backend/src/main/java/dev/moonservice/backend/web/PublicPreferenceQuery.java),
  [API contract](api-shape.md#subscribable-icalendar-feed).

### `GET /api/opportunities`

- **Handler:** `OpportunitySearchController.searchByQuery`.
- **Purpose/lifecycle:** current anonymous product lookup API.
- **Why it exists:** this route turns browser-level location intent into the
  complete server-owned opportunity workflow. A free-text `q` starts geocoding
  and can return candidate choices; `locationId` continues from a selected
  candidate without another fuzzy search. After resolution, the server applies
  its current search defaults, fetches live weather, generates Moon windows,
  and scores them. The `/search` page reconstructs location intent from its
  shareable URL without knowing prototype scoring controls, provider IDs,
  coordinates, or weather contracts. The receiving browser then uses GET or
  the product POST based on its own active preferences.
- **Production invocation:** browser `app.js` calls it through `api.js` when no
  hard preference is active. Query searches use `q`, while an ambiguity
  selection uses `locationId`. The browser sends the selected optional `order`
  query parameter and does not call the direct prototype POST below.
- **Other callers:** manual HTTP/Postman requests, UI tests, application tests,
  and container/live smoke checks.
- **Request:** exactly one usable `q` or `locationId`; values are trimmed,
  limited to 100 Unicode code points, and reject control/bidirectional-format
  characters. Query whitespace is collapsed. The optional query parameter
  `order` accepts `best_match` or `soonest`; omission selects `best_match`. The
  server rejects a present empty or unsupported value before location or
  weather provider work.
- **Response:** `200 application/json` for `ok`, `ambiguous_location`, or
  `location_not_found`; `400` with `invalid_request` for invalid input; `503`
  with `temporarily_unavailable` for unavailable location or weather lookup.
  The full `ok` shape includes location, evaluated windows, opportunities,
  rejected windows, messages, and one current-Moon snapshot at the captured
  `asOf`. `generatedAt` equals `asOf`. When the Moon is at or above the
  horizon, the snapshot contains the physical pass around that instant with
  independently bounded rise and set states. When it is below the horizon,
  the snapshot contains current Moon and Sun facts and explicit
  `activePass: null`. The server returns the snapshot even when no ranked
  opportunity remains. It applies the selected order to all eligible finalized
  opportunities before taking the ten-result product limit. Hosted-alpha
  resource admission can instead return `429` with `rate_limited`,
  `retryAfterSeconds`, and `Retry-After` before the controller runs. Every
  ordinary opportunity also carries a complete canonical `links.ics` URL using
  the resolved location ID and selected order. Every successful canonical
  real-location response also carries root `links.calendarFeed`, including when
  the opportunity list is empty. Its root-relative path contains only the
  canonical location ID and never contains the product order.
- **Authentication/data:** anonymous. `q` is sent to Open-Meteo geocoding;
  normalized queries or location IDs and resolution results are cached in the
  current process with bounded size and status-specific TTLs. Resolved location
  data drives the Open-Meteo weather request. Responses include coordinates,
  timezone, weather, and Moon data, but no durable user profile is created.
- **Exposure:** available on the ordinary listener. Hosted alpha allowlists
  bodyless `GET`/`HEAD`, but applies the configured whole-site, search
  concurrency, and provider-token bounds before controller/provider work.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
  [service validation](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchService.java),
  [server defaults](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchDefaults.java),
  [current-Moon calculation](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/window/CurrentMoonCalculator.java),
  [current-Moon response](../backend/src/main/java/dev/moonservice/backend/opportunity/search/CurrentMoonResponse.java),
  [geocoding cache](../backend/src/main/java/dev/moonservice/backend/location/CachingLocationResolver.java),
  [response model](../backend/src/main/java/dev/moonservice/backend/opportunity/search/OpportunitySearchResponse.java),
  [API design](api-shape.md).

### `POST /api/opportunities`

- **Handler:** `OpportunitySearchController.searchWithPreferences`.
- **Purpose/lifecycle:** anonymous same-origin product lookup with optional
  request-scoped version 1 hard preferences and weather ranking.
- **Why it exists:** preference values stay out of shareable URLs while the
  server reuses the GET route's live location, weather, Moon-window, scoring,
  ordering, and result-limit flow. Both product routes share the ordering
  option; the fixture-backed direct POST remains score ordered.
- **Production invocation:** browser `app.js` calls it through
  `opportunityPreferences.js` when at least one supported hard preference is
  active. The module sends exactly one `q` or `locationId` with the versioned
  preference object, puts selected order in the query, disables request
  caching, and keeps preference values out of the page and share URLs.
- **Other callers:** application tests and explicit manual API clients.
- **Request:** `application/json`, including ordinary media-type parameters,
  with exactly one usable `q` or `locationId` and optional complete version 1
  `preferences`. Optional top-level `weatherRanking` accepts only `balanced`,
  `prefer_clear`, or `ignore_weather`; omission keeps the existing balanced
  behavior. The raw body is limited to 16,384 bytes for known and streamed
  lengths. Unknown top-level fields are invalid; supported-version unknown
  preference fields are ignored and reported through the bounded,
  deterministic warning contract. The optional query parameter `order` accepts
  `best_match` or `soonest`; omission selects `best_match`. `order` is not a
  JSON body field. The server rejects a present empty or unsupported query
  value before location or weather provider work. It also rejects an invalid
  weather-ranking value before that work.
- **Response:** the same product states, current-Moon snapshot, and opportunity
  facts as GET. A request with preferences adds the applied version, normalized
  active filters, excluded-sample count, ignored-field warning, and
  authoritative per-pass azimuth match intervals when azimuth filtering is
  active. Active filters that remove every candidate return `200 ok` with the
  distinct preference `emptyReason` and still include `asOf` and `currentMoon`.
  An explicit mode adds `appliedWeatherRanking` only to a scored `ok` response.
  `ignore_weather` adds each opportunity's score basis and omits its inactive
  weather score components. Weather lookup and raw weather output stay active
  in every mode. A weather-only request omits all hard-preference metadata.
  The server orders all eligible finalized opportunities before taking the
  ten-result product limit. Each ordinary opportunity carries a complete
  backend-generated `links.ics` URL that reproduces the resolved location,
  selected order, effective weather ranking, and active hard preferences.
  Every successful canonical real-location response also carries root
  `links.calendarFeed`, including when the opportunity list is empty. The
  canonical root-relative value contains the location, applied non-default
  weather ranking, and normalized active hard preferences, but never contains
  result order. An absent optional member is omitted instead of serialized as
  JSON `null`.
  After final response assembly, applied filtered state also requires root
  `links.atomWithFilters` to be a non-blank string. Applied filtered state means
  that `normalizedActiveFilters` is non-empty or `appliedWeatherRanking` is
  `prefer_clear` or `ignore_weather`. A missing, non-string, or blank link
  replaces the inconsistent successful response with `503
  temporarily_unavailable`, `Cache-Control: no-store`, and the exact generic
  message `Opportunity lookup is temporarily unavailable.` Valid filtered,
  all-off, other non-`ok` POST, and GET behavior remain unchanged.
  Errors use the documented `400 invalid_request`,
  `413 request_too_large`, and `415 unsupported_media_type` shapes.
  Invalid-order responses and every other response use
  `Cache-Control: no-store`.
- **Authentication/data:** anonymous and same-origin. The current location flow
  may send `q` or `locationId` upstream, but it never sends a preference to
  geocoding or weather. The service does not store a request body, preference,
  availability value, or user profile. Page and share URLs, cookies,
  application logs, analytics events, provider requests, and shared caches omit
  those values. The response's backend-generated individual-export, filtered
  Atom, and subscribable-calendar URLs are the deliberate exceptions: they can
  carry applied preferences and non-default weather so the exports are
  reusable. The individual link can also carry selected order. Moon Service
  request logging omits their query strings. When the filtered-link invariant
  fails, the backend writes one
  `ERROR` application-log event with fixed code
  `filtered_atom_link_invariant_failed`. The validated current request ID is
  its sole explicit dynamic value. The event contains no location, query, URL,
  preference, filter, weather data, request body, user-agent value, IP address,
  or other user data. It uses only the existing bounded application-log
  retention and adds no log destination, metric, or storage.
- **Exposure:** available on the ordinary listener. Hosted alpha allows this
  exact `POST` in addition to the existing bodyless `GET` and `HEAD`
  operations, and permits a body only for `POST`. It applies the same whole-site
  and provider admission as product GET, ignores forwarded identity headers,
  returns the current `429` shape without a provider call when admission fails,
  and does not loosen another path or method.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
  [browser flow](../frontend/src/app.js),
  [browser preference state and transport](../frontend/src/opportunityPreferences.js),
  [service validation](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchService.java),
  [response model](../backend/src/main/java/dev/moonservice/backend/opportunity/search/OpportunitySearchResponse.java),
  [API contract](api-shape.md#product-preference-post).

### `POST /api/opportunities/planning`

- **Handler:** `MoonPlanningController.search`.
- **Purpose/lifecycle:** weather-free search for the earliest Moon window that
  matches all active version 1 hard preferences within one compiled planning
  horizon, initially 365 days.
- **Why it exists:** explicit browser recovery can find one possible date beyond
  the ordinary seven-day weather horizon without representing long-range
  weather, score, confidence, or ranking facts.
- **Production invocation:** after an exact successful empty ordinary result
  for a real location, explicit `app.js` activation calls through
  `opportunityPreferences.js` with the canonical ID and complete current
  page-memory version 1 preference snapshot; `planningView.js` renders it.
- **Other callers:** application tests and explicit manual API clients.
- **Request:** same-origin `application/json` with required canonical
  `locationId`, required `preferences`, and `preferences.version: 1`. It rejects
  another top-level field, including `q`, coordinates, timezone, horizon,
  limit, or mode. The 16,384-byte known and streamed body limit and existing
  version 1 preference validation and unknown-field warning rules apply.
- **Calculation:** after validation, the server captures one instant and
  evaluates the exact half-open 365-day interval from that instant. It uses a
  fixed `90.0`-degree natural-window ceiling, applies every active hard filter
  together with five-minute sampling and one-second transition refinement,
  evaluates the complete interval, and returns the earliest retained window by
  `startsAt`, `suggestedAt`, then ID. It does not call weather, calculate or
  rank by an ordinary opportunity score, or run `preferenceImpact`.
- **Response:** `200 ok` contains the compiled `planningHorizonDays`, exact
  `startsAt` and `endsAt`, resolved location, normalized filters, bounded
  ignored-field warning, and one `nextPlanningWindow`. No match returns
  `nextPlanningWindow: null` with `no_planning_date`. Validation, location, and
  dependency failures remain distinct. Every response that reaches the planning
  route uses `Cache-Control: no-store`.
- **Authentication/data:** anonymous and same-origin. Preferences and returned
  dates are not stored, logged, or cached. The existing bounded
  location-resolution cache may retain the normalized ID and may use geocoding
  on a miss; it never sends preferences to that provider.
- **Exposure:** available on the ordinary listener. Hosted alpha applies
  whole-site admission and then shared provider admission before admitting the
  exact framed `POST`. A refusal returns canonical no-store `429 rate_limited`
  JSON with matching `Retry-After` and `retryAfterSeconds`, without a provider
  call. The exact `/planningView.js` module allows bodyless `GET` and `HEAD`;
  the shared security, method, body, path-variant, CORS, and preflight rules
  above remain closed.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/MoonPlanningController.java),
  [browser flow](../frontend/src/app.js),
  [browser planning transport](../frontend/src/opportunityPreferences.js),
  [planning renderer](../frontend/src/planningView.js),
  [response model](../backend/src/main/java/dev/moonservice/backend/opportunity/planning/MoonPlanningResponse.java),
  [API contract](api-shape.md#moon-planning-post).

### `POST /api/moon-events`

- **Handler/purpose:** `MoonEventController.search` delegates to
  `MoonEventService`, which resolves one location, discovers lunar eclipses and
  qualifying near-perigee exact full Moons during the selected 6, 12, 18, 24,
  or 36 calendar months, orders the closed event union, and coordinates one
  weather lookup.
- **Production invocation:** when the browser-local **Show lunar eclipses and supermoons**
  preference is enabled, browser `moonEventView.js` calls the route once after
  each successful real-location ordinary result. It sends the canonical result
  location, selected look-ahead period, and Version 1 preferences built from
  `normalizedActiveFilters`.
  When the preference is disabled, the browser does not show the section or
  make this request. The browser-only preference is not included in the body.
  Atom and iCalendar do not call this route yet.
- **Request:** same-origin `application/json` with required canonical
  `locationId`, required `preferences`, `preferences.version: 1`, and optional
  top-level `eventHorizonMonths`. Omission means 18 months. The event parser
  rejects query parameters and other top-level fields while sharing existing
  media handling, body limit, preference normalization, and ignored-field
  warnings.
- **Response:** the result covers the half-open timezone-aware horizon and
  contains objective eclipse facts and qualifying near-perigee full-Moon peaks.
  A full Moon whose peak is outside the horizon is included when its useful
  local viewing overlaps it. A qualifying peak inside the horizon remains when
  no local viewing overlaps and then omits local facts and weather. Members
  otherwise include applicable observer-relative visibility, request-clamped
  display selection, and a separate bounded Moon path that ordinarily covers
  the selected full above-horizon pass. The path may extend beyond the request
  horizon; bounded polar cases need not contain a rise or set. Eclipse path
  samples embed their own drawable shadow geometry; full-Moon path samples omit
  that member. Members also include Version 1 altitude/azimuth assessment and
  event-local weather. Preferences and weather never hide or reorder events.
  Weather uses zero or one existing seven-day provider lookup.
- **Authentication/data:** anonymous. It stores no request, result, or profile.
  Preferences remain in the body and outside providers, URLs, analytics,
  shared event caches, application logs, and provider-cache keys. Every
  response is `no-store`.
- **Exposure:** available on the ordinary listener. Hosted alpha allows only
  exact framed `POST`, with whole-site and shared-provider admission. Other
  methods return `405` with `Allow: POST`; variants return `404`. Admission
  refusal uses the existing no-store `429`; no CORS or preflight is added.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/MoonEventController.java),
  [browser event view](../frontend/src/moonEventView.js),
  [special-event path adapter](../frontend/src/moonEventPath.js),
  [event cards](../frontend/src/lunarEclipseCard.js),
  [eclipse renderer](../frontend/src/lunarEclipseRenderer.js),
  [ordinary response integration](../frontend/src/responseView.js),
  [aggregator](../backend/src/main/java/dev/moonservice/backend/events/MoonEventService.java),
  [eclipse discovery](../backend/src/main/java/dev/moonservice/backend/events/LunarEclipseEventService.java),
  [near-perigee discovery](../backend/src/main/java/dev/moonservice/backend/events/NearPerigeeFullMoonService.java),
  [response model](../backend/src/main/java/dev/moonservice/backend/events/MoonEventResponse.java),
  [API contract](api-shape.md#moon-event-post).

### `POST /api/opportunities/search`

- **Handler:** `OpportunitySearchController.search`.
- **Purpose/lifecycle:** older deterministic direct scoring/prototype contract;
  it preserves prototype/parity testing and is not the browser API. It bypasses
  runtime location resolution and the live weather-provider path, so its
  long-term public lifecycle is undecided.
- **Production invocation:** none.
- **Other callers:** scoring/application tests and manual HTTP, curl, or Postman
  debugging tools.
- **Request:** JSON object with required `locationId`, `start`,
  `forecastHorizonDays`, `maxMoonAltitudeDegrees`, and `limit`. Only
  `prague-cz` is supported because the direct path delegates to the scoring
  prototype's one-entry fixture registry; it does not call `LocationResolver`.
  `start` accepts an ISO date or UTC instant; ranges are 1–30 days, 0–90
  degrees, and 1–100 results. The product `order` query is not part of this
  contract; the route remains score ordered.
- **Response:** `200 application/json` with the opportunity result; malformed,
  incomplete, unsupported, or out-of-range input returns `400` with
  `invalid_request`, `generatedAt`, and a message. Successful direct responses
  omit the product-only `asOf` and `currentMoon` members.
- **Authentication/data:** unauthenticated in ordinary mode; inputs are bounded
  to the saved Prague fixture and are not stored as user data.
- **Exposure:** reachable on the ordinary listener. In hosted alpha it consumes
  whole-site capacity first and is then hidden as `404`; exhaustion can
  therefore return `429` before the surface filter returns `404`. It never uses
  the search concurrency permits or provider bucket.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
  [request model](../backend/src/main/java/dev/moonservice/backend/opportunity/search/OpportunitySearchRequest.java),
  [direct engine path](../backend/src/main/java/dev/moonservice/backend/opportunity/scoring/ScoringOpportunitySearchEngine.java),
  [fixture registry](../prototypes/jvm-scoring/src/main/java/dev/moonservice/scoringprototype/fixture/Locations.java),
  [manual requests](../backend/http/README.md).

### `GET /api/calibration-feedback/v1/capability`

- **Handler:** `CalibrationFeedbackController.capability`.
- **Purpose/lifecycle:** disabled-by-default public state for the bounded alpha
  feedback feature. A client can decide whether to offer a new submission
  without learning storage or provider details.
- **Production invocation:** none yet; browser feedback controls are a separate
  slice.
- **Other callers:** functional tests and manual HTTP clients.
- **Request:** no body or authentication. The route does not reserve storage or
  consume a feedback write token.
- **Response:** always `200 application/json` and `Cache-Control: no-store`, with
  schema version, server time, `featureState`, and `submissionAvailability`.
  A disabled feature maps to `disabled/disabled`. With the feature enabled,
  disabled or incompletely configured persistence maps to `enabled/disabled`;
  unavailable or full persistence, or a known unavailable resolver/astronomy
  dependency, maps to `enabled/unavailable`; normal or near-capacity storage
  with available dependencies maps to `enabled/available`. Write-token
  exhaustion does not change this state.
- **Authentication/data:** anonymous. The response omits database type,
  settings, capacity, counts, resolver/provider details, and failure text.
- **Exposure:** available on the ordinary listener. Hosted alpha allowlists
  bodyless `GET`/`HEAD` and applies security headers. It exempts the path from
  the whole-site limiter and performs no provider or concurrency work.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackController.java),
  [service](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackService.java),
  [wire contract](openapi/calibration-feedback-v1.yaml),
  [behavior contract](api-shape.md#calibration-feedback-api).

### `POST /api/calibration-feedback/v1/submissions`

- **Handler:** `CalibrationFeedbackController.submit`.
- **Purpose/lifecycle:** accept one anonymous current-observation calibration
  report copied from a currently loaded opportunity.
- **Production invocation:** none yet; browser feedback controls are a separate
  slice.
- **Other callers:** functional tests and explicit manual alpha clients.
- **Request:** UTF-8 `application/json` only, with optional `charset=utf-8`, no
  content encoding, and at most 16,384 received bytes. The closed object needs
  schema version 1, a lowercase canonical UUIDv4 `clientSubmissionId`, the
  loaded `locationId`, the loaded `opportunityId`, and at least one non-null
  `ambientLight`, `crescentVisibility`, or normalized `notes` value. Unknown or
  duplicate members, explicit `null`, malformed Unicode, and values outside the
  contract bounds are rejected.
- **Processing/idempotency:** after the bounded body arrives, the server captures
  one microsecond-precision receipt instant, normalizes the request, and hashes
  the five fixed semantic slots with the versioned framing and SHA-256 in the
  behavior contract. It checks the feature, then performs an early repository
  lookup by client UUID. Exact replay and changed-payload conflict finish before
  location resolution or token admission. A new report checks repository
  status, resolves the canonical location ID, consumes one feedback write
  token, recomputes Moon altitude, Moon illumination, Sun altitude, and light
  bucket, and stores transactionally. The stored `applicationRevision` is
  supplied by the server and is not accepted from the request.
- **Admission:** one process-wide bucket starts with 12 tokens and restores one
  whole token per complete monotonic hour, capped at 12. A token consumed by a
  new report is not restored after later astronomy, persistence, capacity-race,
  transactional replay, or conflict outcomes. The bucket resets on process
  restart and is not shared across instances.
- **Response:** `201 created` for a new row and `200 replayed` for an exact
  retry. Stable errors cover invalid JSON/request (`400`), UUID content conflict
  (`409`), oversized body (`413`), unsupported media (`415`), location/report
  rejection (`422`), token exhaustion (`429` with matching `Retry-After` and
  JSON seconds), and generic feedback unavailability (`503`). All responses
  use `Cache-Control: no-store` and never echo report or dependency details.
- **Authentication/data:** anonymous and same-origin for browser use. The route
  requests no GPS permission and sends no permissive CORS headers or preflight
  support. Controlled logs may keep method, route, status, duration, request
  ID, coarse outcome, and aggregate capacity warnings, but not request bodies,
  identifiers, evidence, notes, UUIDs, astronomy, IP/forwarded identity, or
  User-Agent. Persistence or dependency failure disables only feedback writes;
  startup, opportunity lookup, liveness, and readiness remain independent.
- **Exposure:** available on the ordinary listener. Hosted alpha allows only
  `POST` on this exact path, permits its bounded body, and exempts it from the
  hosted whole-site limiter. At the resolver step it shares the hosted provider
  and concurrency guards; after successful resolution it uses the stricter
  feedback write bucket above. `OPTIONS` receives no preflight support.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackController.java),
  [service](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackService.java),
  [persistence](../backend/src/main/java/dev/moonservice/backend/feedback/CalibrationFeedbackRepository.java),
  [wire contract](openapi/calibration-feedback-v1.yaml),
  [behavior contract](api-shape.md#calibration-feedback-api).

## Operational routes

### `GET /healthz`

- **Handler:** `HealthController.liveness`.
- **Purpose/lifecycle:** provider-independent process liveness probe.
- **Production invocation:** none in the current deployment.
- **Other callers:** application and container live tests; operators may probe
  it manually in ordinary mode.
- **Response:** `200` with `{status: "ok", revision}` while liveness is correct,
  otherwise `503` with `{status: "unavailable", revision}`; JSON and
  `Cache-Control: no-store` in both cases.
- **Authentication/data:** none; exposes only health state and build revision.
- **Exposure:** available on the ordinary listener. In hosted alpha it consumes
  whole-site capacity first and is then hidden as `404`, so exhaustion can
  return `429` before route hiding.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/HealthController.java),
  [live container test](../live-tests/test_container_backend.py).

### `GET /readyz`

- **Handler:** `HealthController.readiness`.
- **Purpose/lifecycle:** provider-independent readiness and deployment revision
  probe.
- **Production invocation:** Docker image health checking and Raspberry Pi
  deploy/control scripts.
- **Other callers:** image-publication verification, CI/live smoke checks, and
  manual deployment verification.
- **Response:** `200` with `{status: "ok", revision}` while accepting traffic,
  otherwise `503` with `{status: "unavailable", revision}`; JSON and
  `Cache-Control: no-store` in both cases.
- **Authentication/data:** none; exposes only readiness and build revision.
- **Exposure:** available on the ordinary listener and allowlisted in hosted
  alpha. The exact Docker probe bypasses resource admission; Pi, CI, public, and
  manual requests consume whole-site capacity and can receive `429`.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/HealthController.java),
  [Docker health check](../backend/docker/healthcheck.sh),
  [Pi deployment](../deployment/raspberry-pi/README.md).

### `GET /admin/status`

- **Handler:** `AdminStatusController.status`, guarded by `AdminAccessFilter`.
- **Purpose/lifecycle:** small operator-only view of build revision, provider
  outcomes/retries/quota windows, and cache counters; it is not a user account
  or general admin application.
- **Production invocation:** human operator inspection; no automated runtime
  consumer is currently established.
- **Other callers:** functional/unit tests and manual curl requests.
- **Authentication/data:** `X-Moon-Admin-Token` is required. With admin routes
  disabled, `/admin/**` returns `404`; a missing or wrong configured token gives
  `401`. The response contains process-level metrics, not raw location queries.
- **Exposure:** conditional on admin configuration in ordinary mode. Hosted
  alpha requires an explicit 64-hex token and exposes only exact
  `/admin/status`. Every attempt consumes whole-site capacity before method,
  body, or token policy, so `429` can precede `400`, `401`, or `405`; both the
  limiter and downstream policy preserve hosted security headers and
  `Cache-Control: no-store`.
- **References:** [controller](../backend/src/main/java/dev/moonservice/backend/web/AdminStatusController.java),
  [access filter](../backend/src/main/java/dev/moonservice/backend/web/AdminAccessFilter.java),
  [operator documentation](../backend/README.md#operational-health).

## Deliberate non-routes

- Files under `frontend/src/`, `frontend/assets/`, and `frontend/generated/`
  are packaged into classpath `/static`. The build also packages only
  `assets/moon-textures/lroc_color_2k.jpg` at
  `/moon-textures/lroc_color_2k.jpg`, with content type `image/jpeg`. It packages
  the six accepted proof-of-concept foreground levels at the exact public paths
  `/camera-preview/level-0.webp`, `/camera-preview/level-1.webp`,
  `/camera-preview/level-2.webp`, `/camera-preview/level-3.webp`,
  `/camera-preview/level-4.webp`, and `/camera-preview/level-5.webp`, each with
  content type `image/webp`. The build does not publish the package's
  `scene-pyramid.json` tooling manifest. These static resources support the
  three browser mappings but are not independent controller operations in this
  inventory.
- `/error` is Spring Boot's internal error-dispatch path, not an application
  controller mapping. `/test/slow` exists only in `GracefulShutdownTest`.
- `/l/{location}` and calendar path variants other than exact
  `/calendars/opportunities.ics` are design/roadmap shapes, not implemented
  routes. Individual `/o/*.ics` exports are implemented and product responses
  carry complete URLs that the browser uses for ordinary calendar actions.
- There are no Actuator, OpenAPI, Swagger UI, or Spring REST Docs endpoints.

## Maintenance rule

The change that adds, removes, or changes a controller operation must update
this inventory. The same applies when a production client starts or stops using
an operation, or when authentication, exposure, sensitivity, or lifecycle
changes. Keep production consumers distinct from tests, manual tools, CI,
deployment probes, and prototypes. Controller code and functional tests remain
the implementation evidence; [API shape](api-shape.md) remains the product
design authority for future contracts.
