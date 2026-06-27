# Geocoding Research

## Decision

Use Open-Meteo Geocoding API as the first geocoding provider candidate for the web MVP, but not as the only option until the native-script query gaps are resolved.

Docs: <https://open-meteo.com/en/docs/geocoding-api>

Rationale:

- It matches the current first UX: city or location-name lookup, not exact address autocomplete.
- It uses the same provider family as the recommended weather API, reducing provider sprawl.
- It returns latitude, longitude, elevation, timezone, country, admin areas, population, and postcodes.
- It does not require an API key for non-commercial use.
- Commercial use can move to Open-Meteo customer API resources with an API key.
- It is based on GeoNames, which is appropriate for city/town lookup.

Remaining caveats:

- It is not an exact-address geocoder and should not be positioned as one.
- It is not a full place-search product for landmarks, businesses, or shooting subjects.
- For the first MVP, use explicit search/submit rather than per-keystroke autocomplete.
- Initial validation found that Open-Meteo Geocoding handles many raw Unicode city names, including diacritics, Cyrillic, Chinese, Hindi, Thai, Arabic, and Hebrew examples. It did not resolve tested Japanese-script or Korean-script queries such as `東京`, `京都`, `大阪`, `とうきょう`, or `서울`. If broad native-script city search remains part of the v0 promise, add a curated alias/transliteration layer before considering a secondary provider.

## Internationalization

Location search must support raw Unicode input. Users may have an English browser locale but type a local-language or non-Latin place name, such as `Praha`, `Muenchen`, `München`, `Kraków`, `Αθήνα`, `東京`, or `京都`.

Rules:

- Do not ASCII-normalize or translate the submitted query before geocoding.
- Treat browser locale and `Accept-Language` as display/ranking hints only.
- Let the geocoding provider search the raw query.
- Preserve non-ASCII display names returned by the provider.
- Store UTC internally, but display event times in the resolved location timezone.
- Keep UI copy English-only for the first MVP unless translation work is explicitly added.
- Allow one visible-character place-name queries because real examples exist, such as `Å` and `Y`; handle them with exact-match and stricter abuse controls rather than a blanket minimum-length rejection.

Recommended fallback lookup sequence:

```text
1. raw query + requested/browser display language
2. raw query + provider default language behavior
3. raw query + English display language
4. raw query + optional country hint, if provided
5. curated aliases or transliterations for high-priority native-script city names that the primary provider misses
6. optional secondary geocoder only if aliases/transliteration are not enough
```

The same canonical location may be found through different query/display-language combinations. Cache display-oriented geocoding results separately from canonical locations:

```text
geocoding display cache = normalized raw query + country hint + display language
canonical location = provider place id or stable lat/lon/timezone identity
weather/scoring cache = rounded lat/lon + forecast hour + scoring preferences
```

For example, a user with `en-US` browser settings who searches `Praha` should still find Prague. The UI may display both names when available:

```text
Prague / Praha, Czech Republic
```

## Alias And Transliteration Fallback

For v0, prefer a deterministic alias layer over an LLM or translation API.

Use it only after raw geocoding fails. The goal is not general translation; it is converting known missed place-name spellings into provider-supported aliases while preserving the user's original query.

Initial curated examples:

```text
東京 -> Tokyo
京都 -> Kyoto
大阪 -> Osaka
とうきょう -> Tokyo
서울 -> Seoul
```

Fallback rules:

- Try raw provider geocoding first.
- Apply aliases only after the raw query returns no real candidates.
- Apply aliases by exact match only, especially for one-character queries.
- For one-character place names that the provider cannot search directly, use exact curated real-location records only after raw lookup returns no candidates.
- Preserve the original query in the API response.
- Mark which alias or transliteration was used internally for debugging/admin visibility.
- Cache accepted alias mappings separately from provider results.
- Keep the alias table reviewable and small at first.
- Do not send failed raw queries to an LLM by default.
- If a dynamic transliteration or translation service is added later, use it only for scripts/provider gaps with a clear need, require high confidence, and cache accepted mappings.
- If an alias produces multiple plausible cities, return `ambiguous_location` rather than silently picking one.
- If an alias maps a fictional or ambiguous term, keep fictional lookup separate from real geocoding.
- For one-character queries, do not invoke broad fuzzy fallback, dynamic transliteration, secondary geocoding, or LLM fallback by default.

## Recommended MVP Boundary

Use backend-mediated geocoding.

```text
Web UI
  - user enters city/location text
  - optional country selector or browser locale hint later
  - user selects one result

Backend
  - calls Open-Meteo geocoding
  - returns normalized result candidates
  - caches selected geocoding results
  - uses selected coordinates for weather/scoring
```

Do not put provider URLs directly into the browser app as the long-term design. A backend keeps provider migration, caching, logging, and privacy controls in one place.

## Backend Provider Adapter Boundary

The backend location seam is `dev.moonservice.backend.location.LocationResolver`.
The first Open-Meteo adapter lives in
`dev.moonservice.backend.location.openmeteo.OpenMeteoGeocodingClient` and is
tested with saved provider-shaped JSON fixtures only.

Current adapter scope:

- Builds Open-Meteo Geocoding requests against
  `https://geocoding-api.open-meteo.com/v1/search`.
- Sends `name=<raw query>`, `count=10`, `language=en`, and `format=json`.
- Uses encoded query parameters; do not build the provider URL by raw string
  concatenation.
- Maps one complete provider result to `LocationResolution.resolved`.
- Filters nearby provider-noise results, currently Open-Meteo `PPLX` districts
  and `AIRP` airport records, when they share the same country, first admin
  area, timezone, and rough city radius as a canonical populated-place result.
- Maps the remaining complete provider results to `LocationResolution.ambiguous`
  in provider order when more than one meaningfully distinct place remains.
- Maps a valid empty `results` array to `LocationResolution.notFound`.
- Maps the observed Open-Meteo no-match shape, `generationtime_ms` with no
  `results` field, to `LocationResolution.notFound`.
- Maps transport failures, HTTP failures, invalid JSON, missing `results`
  without the Open-Meteo response marker, or a non-array `results` shape to
  `LocationResolution.temporarilyUnavailable`.
- Uses the shared `dev.moonservice.backend.openmeteo.OpenMeteoTransport` seam.
  Its Spring `RestClient` implementation classifies rate limits, transient HTTP
  failures, non-retryable HTTP failures, IO failures, and timeouts as typed
  provider exceptions.
- Applies the shared Open-Meteo retrying transport decorator using Spring
  `RetryTemplate` with at most one retry for transient failures: HTTP `429`,
  `502`, `503`, `504`, timeout, or IO failure.
- Does not retry non-retryable HTTP statuses, malformed provider payloads, blank
  response bodies, valid empty `results` arrays, or provider-shaped no-match
  responses.
- Honors short `Retry-After` values before retrying; longer rate-limit delays
  fail fast as `temporarilyUnavailable`.
- Keeps the backend location id separate from the structured provider location
  id. For example, an Open-Meteo result may map to backend id
  `moon-service-3067696` and `ProviderLocationId(OPEN_METEO, "3067696")`, which
  serializes as `openmeteo:3067696`.
- Retains the coordinate-backed fields the backend resolver contract can now
  carry: backend location id, structured provider location id, display name,
  latitude, longitude, elevation, timezone, and country code.

This adapter can be selected as the Spring `LocationResolver` with
`moon.location.resolver=open-meteo`. Runtime provider selection is explicit:
missing or unsupported provider configuration fails startup rather than silently
choosing fixture behavior. The resolved-location opportunity path now consumes
coordinates, elevation, timezone, and provider metadata from the selected
location. Test-only resolver doubles still provide stable fixture locations for
network-free controller and service tests.

Fixture-backed adapter tests live under:

```text
backend/src/test/java/dev/moonservice/backend/location/openmeteo/
backend/src/test/resources/fixtures/openmeteo/geocoding/
```

Keep ordinary backend tests network-free. To check live provider drift manually,
run:

```bash
live-tests/run_live_geocoding_tests.sh
```

That command calls the external provider and writes
`live-tests/reports/openmeteo-geocoding.html`; do not include it in the normal
Maven loop.

## First UX Recommendation

Start with a simple search form:

```text
[ Prague, Czech Republic ] [Find Moon opportunities]
```

Then show a disambiguation list if needed:

```text
Prague, Hlavni mesto Praha, Czech Republic
Prague, Oklahoma, United States
Prague, Nebraska, United States
```

Avoid typeahead/autocomplete initially. It increases request volume, privacy exposure, UI complexity, and provider-specific behavior before the scoring model is proven.

Do not call geocoding on every keypress in v0. Submit explicit searches only, cache repeated queries, and let the backend protect upstream provider limits.

Fictional or legendary locations can be used as Easter eggs, but they should not bypass geocoding as real places. If a query such as `Xanadu`, `Atlantis`, `Camelot`, `Narnia`, `Minas Tirith`, or a videogame location matches a curated fictional entry, return it as a separate fictional candidate.

If there is no real provider result and no curated fictional match, a later LLM-assisted fallback may try to classify whether the query belongs to recognizable lore. If confidence is high, return a fictional candidate. If confidence is low, return `location_not_found` with helpful copy.

Fictional candidates should be distinct from geocoding provider candidates:

```text
GeocodingCandidate
  kind = "real_location"
  provider = "openmeteo"
  latitude
  longitude
  timezone

FictionalLocationCandidate
  kind = "fictional_location"
  fictional_universe
  display_name
  report_template_id
  generated_by = "curated" | "llm_suggested"
  confidence
```

Fictional reports must not include real coordinates or feed/calendar outputs. They are entertainment only.

LLM fallback rules:

- Run only after real geocoding fails.
- Do not use LLM output as a real geocoding result.
- Require a high confidence threshold and a clear fictional universe/category.
- Keep generated reports short and clearly labeled.
- Avoid copyrighted prose, character voices, or long franchise-specific text.
- Cache accepted fictional mappings and templates for reviewability and cost control.
- Provide a kill switch for LLM Easter eggs.

## Provider Comparison

### Open-Meteo Geocoding

Status: useful first provider candidate, but not sufficient alone for the current internationalized search promise.

Source: <https://open-meteo.com/en/docs/geocoding-api>

Fit:

- Good for city, town, village, and postal-code lookup.
- Returns coordinates, elevation, timezone, population, and administrative context.
- Search supports fuzzy matching for queries of 3 or more characters.
- Up to 100 results can be requested.

API key:

- No key for non-commercial use.
- API key required for commercial customer resources.

Caching:

- No detailed geocoding-specific cache rule found in the docs reviewed.
- Cache selected normalized location records to reduce repeated provider calls and preserve result stability.
- Keep attribution to GeoNames/Open-Meteo.

Privacy:

- Backend should avoid logging raw query strings if they may contain exact addresses or personal locations.
- For MVP, guide the user toward city/town names rather than exact home addresses.

Limitations:

- Not a full address/POI autocomplete service.
- Less suitable for landmark-specific planning.
- Did not resolve tested Japanese-script city queries `東京`, `京都`, `大阪`, or `とうきょう`, or Korean-script `서울`. Romanized `Tokyo` and `Kyoto` did resolve and returned Japanese display names when `language=ja` was requested, supporting a small curated alias/transliteration fallback.

## Validation Spike Results

Date run: 2026-06-14 Europe/Prague local time.

Request shape tested:

```text
GET https://geocoding-api.open-meteo.com/v1/search
  name=<raw or URL-encoded query>
  count=10
  language=<display language>
  format=json
```

Observed results:

```text
Prague, language=en
  8 results
  first result: Prague, CZ, Europe/Prague, elevation 202 m, population 1165581
  ambiguity: also returned Prague, Oklahoma and Prague, Nebraska

Praha, language=cs
  10 results
  first result: Praha, CZ, Europe/Prague, elevation 202 m, population 1165581
  ambiguity: also returned smaller Praha matches in SK and US

München, language=de
  10 results
  first result: München, DE, Europe/Berlin, elevation 524 m, population 1260391

東京, language=ja
  0 results

東京, language=ja, countryCode=JP
  0 results

Tokyo, language=ja
  10 results
  first result: 東京都, JP, Asia/Tokyo, elevation 44 m, population 9733276

京都, language=ja
  0 results

Kyoto, language=ja
  5 results
  first result: 京都市, JP, Asia/Tokyo, elevation 50 m, population 1463723

Montréal, language=fr
  5 results
  first result: Montréal, CA, America/Toronto, elevation 216 m, population 1762949

Québec, language=fr
  5 results
  first result: Québec, CA, America/Toronto, elevation 54 m, population 531902

Saint-Étienne, language=fr
  5 results
  first result: Saint-Étienne, FR, Europe/Paris, elevation 529 m, population 176280

Kraków, language=pl
  5 results
  first result: Kraków, PL, Europe/Warsaw, elevation 219 m, population 804237

Łódź, language=pl
  5 results
  first result: Łódź, PL, Europe/Warsaw, elevation 214 m, population 645693

Москва, language=ru
  4 results
  first result: Москва, RU, Europe/Moscow, elevation 155 m, population 10381222

Київ, language=uk
  5 results
  first result: Київ, UA, Europe/Kyiv, elevation 179 m, population 2952301

北京, language=zh
  3 results
  first result: 北京, CN, Asia/Shanghai, elevation 49 m, population 18960744

上海, language=zh
  5 results
  first result: 上海, CN, Asia/Shanghai, elevation 12 m, population 24874500

दिल्ली, language=hi
  1 result
  first result: दिल्ली, IN, Asia/Kolkata, elevation 227 m, population 11034555

กรุงเทพมหานคร, language=th
  1 result
  first result: กรุงเทพมหานคร, TH, Asia/Bangkok, elevation 12 m, population 5104476

القاهرة, language=ar
  5 results
  first result: القاهرة, EG, Africa/Cairo, elevation 23 m, population 9606916

ירושלים, language=he
  1 result
  first result: ירושלים, IL, Asia/Jerusalem, elevation 786 m, population 971800

서울, language=ko
  0 results

大阪, language=ja
  0 results

とうきょう, language=ja
  0 results
```

Conclusion:

- Open-Meteo Geocoding returns the fields needed by the MVP data contract for successful city matches: id, display name, latitude, longitude, elevation, timezone, country, admin area, population, and feature code.
- It handles European local-language Unicode examples and several non-Latin scripts well enough for the first prototype.
- It supports ambiguity handling for Prague/Praha-style cases.
- It does not satisfy a broad raw native-script search requirement by itself. The product/API contract should add a curated alias/transliteration fallback for high-priority missed place names before narrowing the v0 search promise or adopting a secondary provider.

## Contract Spike Notes

The retained script `scripts/geocoding_contract_spike.py` exercises the documented lookup flow with fixtures by default and can optionally query live Open-Meteo Geocoding with `--live`.

The manual pytest live check in `live-tests/test_openmeteo_geocoding.py` is a
provider drift check, not a backend unit-test contract. Run it with
`live-tests/run_live_geocoding_tests.sh` to create a local virtual environment,
install test dependencies, call live Open-Meteo Geocoding, and write an HTML
report. It verifies broad assumptions such as expected Prague ambiguity, known
native-script misses, and one-character query behavior.

Live recheck:

- Date run: 2026-06-14 Europe/Prague local time.
- Command shape: `python3 -B scripts/geocoding_contract_spike.py --live ...`
- `Prague` and `Praha` returned multiple real candidates, so `ambiguous_location` remains the correct response unless the UI supplies an explicit selection or country hint.
- Alias fallbacks for `東京`, `京都`, `大阪`, `とうきょう`, and `서울` successfully produced real candidates, but live provider results were ambiguous because the romanized alias matched multiple places. The contract already handles this by returning `ambiguous_location` rather than silently selecting the first result.
- `Xanadu` returned both a real place and a curated fictional candidate, confirming that real and fictional candidates need to stay distinct in ambiguous responses.
- One-character live queries `Å` and `Y` did not return exact Open-Meteo candidates through this flow. The retained spike now uses exact curated real-location records for those examples after raw lookup returns no candidates. Keep broad fuzzy, dynamic transliteration, secondary geocoding, and LLM fallback disabled for one-character queries by default.

### OpenStreetMap Nominatim Public API

Status: acceptable for manual low-volume development, not first production default.

Source: <https://operations.osmfoundation.org/policies/nominatim/>

Fit:

- Good open-data geocoder for places and addresses.
- Can be self-hosted later if the project needs full OSM geocoding.

API key:

- No API key for the public OSMF service.

Usage limits:

- Absolute maximum of 1 request per second for the public service.
- Requires valid identifying `User-Agent` or HTTP referer.
- Requires OSM attribution.

Caching:

- Policy says repeated identical queries must be cached.
- Proxy and caching are recommended for apps.

Privacy:

- Do not submit personal/confidential data.
- Direct browser/app calls expose user IP and query to the public service.

Limitations:

- Public service forbids autocomplete.
- Public service can change policy or withdraw access.
- Not suitable as a generic production geocoding backend unless self-hosted or a commercial Nominatim provider is used.

### Photon

Status: possible autocomplete/self-host option later.

Source: <https://photon.komoot.io/>

Fit:

- Search-as-you-type over OpenStreetMap data.
- Supports typeahead, multilingual search, typo tolerance, and reverse geocoding.
- Open source and backed by OpenSearch.

API key:

- No key for the public endpoint.

Usage limits:

- Public endpoint asks users to be fair; extensive usage may be throttled.
- No availability guarantee and usage may change.

Caching:

- No detailed caching policy found on the public landing page.
- Treat public service as unsuitable for heavy production traffic.

Privacy:

- Direct calls expose user IP and query.
- Backend proxy or self-hosting is preferable for production.

Limitations:

- Public endpoint is best for demos or light usage.
- Self-hosting Photon plus OpenSearch is extra infrastructure.

### OpenCage

Status: strong commercial fallback.

Source: <https://opencagedata.com/api>

Fit:

- Worldwide forward and reverse geocoding based on open data.
- Mature API, SDKs, confidence/ranking fields, and clear docs.
- Separate geosearch/autosuggest service exists.

API key:

- Required.

Pricing/free tier:

- Free trial is for testing: 2,500 requests/day, 1 request/second, no credit card.
- Not a free ongoing tier.

Caching:

- OpenCage explicitly allows caching/storing returned API data permanently.

Privacy:

- Account-based commercial provider; review privacy/DPA requirements before production.
- Backend should protect API key and avoid unnecessary raw-query logging.

Limitations:

- Ongoing production use requires paying.
- Autosuggest is a separate service, not the basic geocoding API.

### Mapbox Geocoding / Search

Status: strong productized option if autocomplete/map UX becomes important.

Sources:

- <https://docs.mapbox.com/api/search/geocoding/>
- <https://www.mapbox.com/search-service>

Fit:

- Good global search product with forward/reverse geocoding.
- Supports feature type filters, including `place`, `locality`, `address`, and others.
- Built-in autocomplete behavior and Search Box options.
- Useful if Moon Service later includes a map-heavy UI.

API key:

- Requires Mapbox access token.

Caching:

- Temporary geocoding results cannot be cached.
- Permanent geocoding results can be stored indefinitely, but require valid payment setup or enterprise contract and `permanent=true`.

Privacy:

- Direct browser use exposes access token constraints and user queries to Mapbox.
- Backend can control token exposure and logging.

Limitations:

- Autocomplete can generate one request per keystroke unless throttled/delayed.
- Pricing and storage model are more complex than Open-Meteo for a simple city lookup.

### Google Geocoding / Places

Status: powerful but not first choice.

Sources:

- <https://developers.google.com/maps/documentation/geocoding/overview>
- <https://developers.google.com/maps/documentation/places/web-service/legacy/autocomplete>

Fit:

- Excellent global address/place coverage.
- Places Autocomplete is mature and widely understood.

API key:

- Requires Google Cloud project, billing, and API key.

Caching:

- Review Google Maps Platform terms before storing results. Do not assume permanent storage is allowed.

Privacy:

- Direct browser use sends user queries to Google and requires careful API key restriction.
- Backend mediation is preferable if selected.

Limitations:

- More expensive and policy-heavy than needed for city-only MVP.
- Places Autocomplete cost optimization requires session-token discipline and field masks.

## Geocoding Data Contract For MVP

Normalize provider results into this internal shape:

```text
GeocodingResult
  provider
  provider_place_id
  query_text_normalized
  display_name
  local_name
  alternate_names
  display_language
  latitude
  longitude
  elevation_meters
  timezone
  country_code
  country_name
  admin1
  admin2
  population
  feature_type
  confidence
```

Rules:

- Use WGS84 decimal degrees.
- Preserve provider attribution/source.
- Do not expose provider-specific IDs as stable public product IDs unless the provider allows it.
- Treat `display_name`, coordinates, elevation, and timezone as the fields the scoring system actually needs.
- Treat elevation as observer elevation above sea level, not as terrain horizon obstruction.
- Keep raw provider payloads only temporarily for debugging, if at all.

## Caching Recommendation

For the first backend MVP:

- Cache selected geocoding results keyed by normalized query plus optional country code.
- Cache candidate lists for short periods, initially 1 to 7 days.
- Cache selected canonical locations longer, because city coordinates and timezones change rarely.
- Store provider attribution and source.
- Do not log raw queries that look like street addresses unless needed for debugging.
- If provider quota is exhausted, return a temporary service state rather than pretending the location was not found.

Provider-call caching and in-flight coalescing are tracked by
[#8](https://github.com/rapucha/moon-service/issues/8).

## Quota Observability

Track outbound geocoding provider calls locally.

Minimum counters:

- Provider.
- Current hour call count.
- Current day call count.
- Current month call count.
- Cache hit/miss before provider call.
- Error, timeout, and upstream rate-limit counts.

Admin visibility:

- Show configured provider limits and percent used when known.
- Warn at roughly 50 percent, 80 percent, and 95 percent usage.
- Keep geocoding explicit-submit only in v0; do not add autocomplete until quota behavior is understood.

Backend metrics and operator visibility are tracked by
[#9](https://github.com/rapucha/moon-service/issues/9).

If using Nominatim public API:

- Cache repeated queries.
- Keep traffic far below 1 request/second.
- Do not implement autocomplete against the public endpoint.
- Use a backend proxy and identifying `User-Agent`.

If using Mapbox:

- Do not cache temporary results.
- Use `permanent=true` only when payment/account requirements are satisfied.

## Privacy Recommendation

First MVP copy should steer users toward city-level search:

```text
Enter a city or town. Exact home addresses are not needed.
```

Privacy defaults:

- Use backend-mediated geocoding.
- Avoid raw query logging by default.
- Do not store IP address plus query text in application logs.
- Store selected city/location records separately from any future user identity.
- If exact coordinates are manually entered later, treat them as sensitive.

## Architecture Impact

Geocoding research reinforces the web-first backend MVP:

- The first UI can avoid account creation and app installation.
- Open-Meteo can cover both geocoding and weather for the first non-commercial MVP.
- Provider abstraction remains important because commercial use, autocomplete, address search, or map-heavy UX may require OpenCage, Mapbox, Google, or self-hosted OSM tooling later.

## Open Questions

- Is city/town/postal-code lookup enough for the first public MVP?
- Should the first form include an optional country selector to reduce ambiguous results?
- Should browser geolocation be offered as a separate opt-in path?
- How long should canonical location records be cached before refresh?
- When, if ever, should exact address or landmark search be supported?

The first web lookup flow, including ambiguity handling in the form, is tracked
by [#15](https://github.com/rapucha/moon-service/issues/15). Cache TTLs and
provider-call protection are tracked by
[#8](https://github.com/rapucha/moon-service/issues/8).
