# Geocoding Research

## Decision

Use Open-Meteo Geocoding API as the first geocoding provider for the web MVP.

Docs: <https://open-meteo.com/en/docs/geocoding-api>

Rationale:

- It matches the current first UX: city or location-name lookup, not exact address autocomplete.
- It uses the same provider family as the recommended weather API, reducing provider sprawl.
- It returns latitude, longitude, elevation, timezone, country, admin areas, population, and postcodes.
- It does not require an API key for non-commercial use.
- Commercial use can move to Open-Meteo customer API resources with an API key.
- It is based on GeoNames, which is appropriate for city/town lookup.

Initial caveat:

- It is not an exact-address geocoder and should not be positioned as one.
- It is not a full place-search product for landmarks, businesses, or shooting subjects.
- For the first MVP, use explicit search/submit rather than per-keystroke autocomplete.

## Internationalization

Location search must support raw Unicode input. Users may have an English browser locale but type a local-language or non-Latin place name, such as `Praha`, `Muenchen`, `München`, `Kraków`, `Αθήνα`, `東京`, or `京都`.

Rules:

- Do not ASCII-normalize or translate the submitted query before geocoding.
- Treat browser locale and `Accept-Language` as display/ranking hints only.
- Let the geocoding provider search the raw query.
- Preserve non-ASCII display names returned by the provider.
- Store UTC internally, but display event times in the resolved location timezone.
- Keep UI copy English-only for the first MVP unless translation work is explicitly added.

Recommended fallback lookup sequence:

```text
1. raw query + requested/browser display language
2. raw query + provider default language behavior
3. raw query + English display language
4. raw query + optional country hint, if provided
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

Status: recommended first provider.

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
