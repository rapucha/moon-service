# First Backend/Web API Shape

## Goal

The first API should support a single-page web experience:

```text
/search?q=Praha
```

The page should accept a city/location query, resolve it, return Moon opportunities when possible, and support playful fictional Easter eggs without requiring accounts, cookies, email, or saved server-side preferences.

## Public UX

Recommended first routes:

```text
/search?q=Praha
/l/prague-cz
/feeds/prague-cz.atom
/o/prague-cz-2026-06-29T1920Z.ics
```

The web page can call a single preview endpoint:

```http
GET /api/preview?q=Praha&lang=cs
```

`lang` is optional. If absent, use `Accept-Language` as a display/ranking hint only.

## Request Parameters

`q`:

- Required for query-based lookup.
- Raw Unicode city/location query.
- Do not ASCII-normalize before geocoding.
- Reject empty or unreasonably long queries with `invalid_request`.
- One visible-character queries are allowed because real one-symbol place names exist, such as `Å` and `Y`, but they should use stricter handling because they are highly ambiguous.

`lang`:

- Optional BCP 47 language tag.
- Used only for display/ranking preferences.
- Must not prevent local-language queries from resolving.

`country`:

- Optional ISO country code.
- Used as a disambiguation hint, not as a hard filter unless the UI explicitly says so.

`locationId`:

- Optional later parameter for a selected canonical real location or curated fictional location.
- Useful after `ambiguous_location` so the user can choose a candidate without repeating fuzzy geocoding.

## Response Statuses

Use `status` for the overall request state:

```text
ok
ambiguous_location
location_not_found
invalid_request
temporarily_unavailable
rate_limited
```

Meanings:

- `ok`: resolved one location and completed the lookup. For real locations, `opportunities` may contain results or be empty.
- `ambiguous_location`: multiple plausible real or fictional candidates; user must choose.
- `location_not_found`: no real geocoding result and no fictional match.
- `invalid_request`: missing, empty after trimming, too long, malformed, or unsupported input.
- `temporarily_unavailable`: geocoding, weather, ephemeris, scoring, or cache dependency failed or timed out.
- `rate_limited`: request was valid, but the client or service exceeded an application-level rate limit.

For a resolved real location, `status: "ok"` with `opportunities: []` means scoring completed successfully, but no opportunity passed the current filters/threshold in the forecast window. Dependency failures should not be represented as an empty list.

HTTP status codes can stay conventional:

- `200` for product states such as `ok`, `ambiguous_location`, and `location_not_found`.
- `400` for `invalid_request`.
- `429` for `rate_limited`.
- `503` for `temporarily_unavailable`.

## Message Codes

Responses may include `messages` for non-fatal context:

```text
local_horizon_not_modelled
fictional_result
query_alias_used
input_normalized
one_character_query
```

Meanings:

- `local_horizon_not_modelled`: terrain, buildings, trees, and exact local horizon are not included.
- `fictional_result`: the response is an Easter egg and not real-world guidance.
- `query_alias_used`: raw geocoding returned no candidates, so a curated alias or transliteration was used.
- `input_normalized`: lookup/cache input was normalized, such as whitespace collapsing, while preserving the original query in the response.
- `one_character_query`: the query is a single visible character and was handled with stricter lookup rules.

## Rate Limits And Upstream Quotas

The backend must protect both Moon Service and upstream providers.

Application-level limits:

- Rate limit `/api/preview` by IP or coarse anonymous client fingerprint if abuse becomes visible.
- Keep limits generous enough for manual use and testing.
- Return `status: "rate_limited"` with HTTP `429` when a client is limited.
- Include a retry hint when possible.

Upstream provider limits:

- Open-Meteo free weather API is documented as non-commercial and rate-limited.
- Open-Meteo geocoding should be cached and should not be called on every keystroke.
- Avoid typeahead/autocomplete in v0 to reduce geocoding request volume.
- Cache geocoding, weather, and scoring outputs enough to avoid repeated provider calls for the same city/time window.
- If an upstream provider quota is exhausted, return `temporarily_unavailable`, not `opportunities: []`.
- Record outbound provider calls in local counters so `/admin/status` can show hourly, daily, and monthly usage.

Example rate-limited response:

```json
{
  "status": "rate_limited",
  "message": "Too many requests. Please try again shortly.",
  "retryAfterSeconds": 60
}
```

## Input Validation And Abuse Protection

Treat lookup input as hostile even when it looks like a city name.

Validation rules:

- Require `q` after Unicode trim.
- Reject empty input.
- Use a maximum length for city/location search, initially around 100 visible characters.
- Allow one visible Unicode letter or number because real one-symbol place names exist.
- Reject or strip ASCII control characters and Unicode bidirectional control characters.
- Normalize whitespace runs to a single space for lookup/cache keys while preserving the original query for display/debug context.
- Do not call aliases, secondary geocoders, or future LLM fallback for invalid input.

One-character query rules:

- Permit one visible-character queries such as `Å` or `Y`.
- Require exact geocoding or exact curated alias match.
- Do not invoke broad fuzzy fallback, dynamic translation/transliteration, secondary geocoding, or LLM fallback by default.
- Return `ambiguous_location` if multiple plausible places match.
- Rate-limit one-character lookups conservatively because they are cheap to abuse and likely to be ambiguous.

Safety rules:

- Escape `q`, provider display names, and aliases before rendering them in HTML.
- Do not put raw `q` in structured application logs by default.
- Do not build provider URLs by string concatenation; use encoded query parameters.
- Cache negative lookups briefly, but cap negative-cache size and TTL to avoid cache pollution.
- Keep provider call counters and rate limits visible in `/admin/status`.

## Admin Status Endpoint

The first backend should expose a private operator endpoint:

```http
GET /admin/status
```

This endpoint is for the Moon Service operator, not public users. It should be protected and may render simple server-side HTML before any richer admin UI exists.

Minimum response/page fields:

```text
app:
  version
  build_time
  environment

providers:
  open_meteo_weather:
    calls_current_hour
    calls_today
    calls_this_month
    known_limits
    percent_used
    recent_errors
  open_meteo_geocoding:
    calls_current_hour
    calls_today
    calls_this_month
    known_limits
    percent_used
    recent_errors

caches:
  geocoding_hit_rate
  weather_hit_rate
  scoring_hit_rate

features:
  fictional_llm_fallback_enabled
  feed_generation_enabled

public_api:
  preview_rate_limit
```

The status page should make quota risk visible before exhaustion. If known limits are configured, show warning states at roughly 50 percent, 80 percent, and 95 percent usage.

## Candidate Kinds

Use `kind` on candidates/results, not in `status`:

```text
real_location
fictional_location
```

`real_location`:

- Comes from geocoding provider data.
- Can have latitude, longitude, elevation, timezone, country, admin areas.
- Can produce real opportunities, RSS/Atom feeds, and `.ics` exports.

`fictional_location`:

- Comes from curated fictional data or later LLM-assisted lore classification.
- Must not include real coordinates.
- Must not produce RSS/Atom feed entries, `.ics` exports, real weather, or real ephemeris results.
- Can produce a clearly labeled fictional report.

## Preview Response Examples

### Real Opportunities

```json
{
  "status": "ok",
  "query": "Praha",
  "locale": "cs",
  "location": {
    "kind": "real_location",
    "id": "openmeteo:prague-cz",
    "displayName": "Prague / Praha, Czech Republic",
    "latitude": 50.0755,
    "longitude": 14.4378,
    "elevationMeters": 250,
    "timezone": "Europe/Prague",
    "countryCode": "CZ"
  },
  "generatedAt": "2026-06-14T09:00:00Z",
  "forecastHorizonDays": 7,
  "opportunities": [
    {
      "id": "prague-cz-2026-06-29T1920Z",
      "startsAt": "2026-06-29T19:00:00Z",
      "peaksAt": "2026-06-29T19:20:00Z",
      "endsAt": "2026-06-29T19:50:00Z",
      "localTimeZone": "Europe/Prague",
      "score": 82,
      "confidence": "medium",
      "moon": {
        "altitudeDegrees": 4.2,
        "azimuthDegrees": 126.5,
        "illuminationPercent": 96
      },
      "sun": {
        "altitudeDegrees": -4.8,
        "lightBucket": "civil_twilight"
      },
      "weather": {
        "cloudCoverPercent": 38,
        "lowCloudCoverPercent": 20,
        "precipitationProbabilityPercent": 5,
        "visibilityMeters": 18000,
        "summary": "partly cloudy"
      },
      "reason": "Moon is low in the southeast during civil twilight with low precipitation risk.",
      "links": {
        "ics": "/o/prague-cz-2026-06-29T1920Z.ics"
      }
    }
  ],
  "links": {
    "self": "/search?q=Praha",
    "location": "/l/prague-cz",
    "atom": "/feeds/prague-cz.atom"
  },
  "messages": [
    {
      "level": "info",
      "code": "local_horizon_not_modelled",
      "text": "Local hills, buildings, or trees may affect exact visibility near the horizon."
    }
  ]
}
```

### Ambiguous Location

```json
{
  "status": "ambiguous_location",
  "query": "Prague",
  "candidates": [
    {
      "kind": "real_location",
      "id": "openmeteo:prague-cz",
      "displayName": "Prague / Praha, Czech Republic",
      "countryCode": "CZ",
      "timezone": "Europe/Prague"
    },
    {
      "kind": "real_location",
      "id": "openmeteo:prague-ok-us",
      "displayName": "Prague, Oklahoma, United States",
      "countryCode": "US",
      "timezone": "America/Chicago"
    },
    {
      "kind": "fictional_location",
      "id": "fictional:fallout:prague",
      "displayName": "Prague, Fallout universe",
      "fictionalUniverse": "Fallout",
      "reportTemplateId": "fallout-radstorm",
      "generatedBy": "curated"
    }
  ]
}
```

### Alias Fallback

```json
{
  "status": "ok",
  "query": "東京",
  "locale": "ja",
  "location": {
    "kind": "real_location",
    "id": "openmeteo:tokyo-jp",
    "displayName": "東京都, Japan",
    "latitude": 35.6895,
    "longitude": 139.69171,
    "elevationMeters": 44,
    "timezone": "Asia/Tokyo",
    "countryCode": "JP"
  },
  "lookup": {
    "originalQuery": "東京",
    "searchedQuery": "Tokyo",
    "aliasApplied": true,
    "aliasSource": "curated"
  },
  "opportunities": [],
  "messages": [
    {
      "level": "info",
      "code": "query_alias_used",
      "text": "We searched for Tokyo after the original place name did not resolve."
    }
  ]
}
```

### One-Character Query

```json
{
  "status": "ambiguous_location",
  "query": "Å",
  "candidates": [
    {
      "kind": "real_location",
      "id": "openmeteo:aa-nordland-no",
      "displayName": "Å, Nordland, Norway",
      "countryCode": "NO",
      "timezone": "Europe/Oslo"
    },
    {
      "kind": "real_location",
      "id": "openmeteo:aa-vasternorrland-se",
      "displayName": "Å, Västernorrland, Sweden",
      "countryCode": "SE",
      "timezone": "Europe/Stockholm"
    }
  ],
  "messages": [
    {
      "level": "info",
      "code": "one_character_query",
      "text": "One-character place names are allowed, but may be ambiguous."
    }
  ]
}
```

### Fictional Report

```json
{
  "status": "ok",
  "query": "Xanadu",
  "location": {
    "kind": "fictional_location",
    "id": "fictional:literary:xanadu",
    "displayName": "Xanadu",
    "fictionalUniverse": "literary/mythic",
    "generatedBy": "curated"
  },
  "fictionalReport": {
    "title": "Fictional Moon Report",
    "summary": "Pleasure-dome moonlight expected. This is an Easter egg, not real-world photography guidance.",
    "conditions": [
      "Alph river haze",
      "impossible silver twilight",
      "excellent dreamlike contrast"
    ]
  },
  "messages": [
    {
      "level": "warning",
      "code": "fictional_result",
      "text": "This result is fictional and does not use real weather or ephemeris data."
    }
  ]
}
```

### Location Not Found

```json
{
  "status": "location_not_found",
  "query": "Xyznotacity",
  "message": "We could not find that place on Earth or in the usual imaginary maps.",
  "suggestions": [
    "Check spelling.",
    "Try adding a country, such as \"Prague, Czech Republic\".",
    "Try a city or town rather than an exact address."
  ]
}
```

### Invalid Request

```json
{
  "status": "invalid_request",
  "query": "",
  "message": "Enter a city or town.",
  "errors": [
    {
      "field": "q",
      "code": "empty_query",
      "text": "The location query is required."
    }
  ]
}
```

```json
{
  "status": "invalid_request",
  "message": "That location query is too long.",
  "errors": [
    {
      "field": "q",
      "code": "query_too_long",
      "text": "Use a city, town, or short location name."
    }
  ]
}
```

```json
{
  "status": "invalid_request",
  "message": "That location query contains unsupported characters.",
  "errors": [
    {
      "field": "q",
      "code": "unsupported_control_characters",
      "text": "Remove control or bidirectional formatting characters."
    }
  ]
}
```

### Empty Opportunities

```json
{
  "status": "ok",
  "location": {
    "kind": "real_location",
    "id": "openmeteo:prague-cz",
    "displayName": "Prague / Praha, Czech Republic",
    "timezone": "Europe/Prague"
  },
  "forecastHorizonDays": 7,
  "opportunities": [],
  "emptyReason": {
    "code": "no_opportunities_above_threshold",
    "text": "No Moon opportunity exceeded the current score threshold in the forecast window."
  }
}
```

## Internationalization Behavior

- Accept raw Unicode input.
- Do not assume query language from browser locale.
- Use `lang` or `Accept-Language` as display/ranking hints only.
- Store times as UTC instants.
- Return the resolved location timezone.
- Display event times in the location timezone.
- Keep first MVP UI copy English-only unless translation work is explicitly added.
- Provider validation found that Open-Meteo Geocoding resolves many raw Unicode city queries, but misses some native-script cases such as Japanese `東京`, `京都`, and `大阪`, and Korean `서울`. Keeping a broad raw Unicode promise requires a curated alias/transliteration fallback before considering a secondary geocoder or narrower v0 search promise.

## Local Recent Searches

The first web MVP may use browser `localStorage` for recent searches.

Rules:

- Store only an ordered list of recent location display names and slugs/canonical IDs.
- Do not store timestamps in v0.
- Do not store exact addresses.
- Avoid storing latitude/longitude if a slug/canonical ID is enough.
- Keep a small maximum, such as 5 or 10 entries.
- Provide `Clear recent searches`.
- The app must work when localStorage is disabled or cleared.
- Do not use localStorage for auth, consent, private tokens, or server tracking.

Example:

```json
[
  {
    "displayName": "Prague / Praha, Czech Republic",
    "slug": "prague-cz",
    "timezone": "Europe/Prague"
  }
]
```

## Feed And Calendar Rules

RSS/Atom:

- Only for real public opportunities.
- Suitable for city/region feeds and best-upcoming feeds.
- Do not include fictional reports.
- Do not include private user preferences.

`.ics`:

- Only for real individual opportunities.
- Should include title, time window, location display name, summary, and caveat about local horizon obstruction.
- Do not generate `.ics` for fictional reports.

## Privacy And Storage Rules

- No mandatory account.
- No server-side user profile in v0.
- No email alerts in v0.
- No cookies for remembering users.
- Server may cache geocoding/weather/scoring data by provider, canonical location, rounded coordinate, and forecast time.
- Browser may keep recent searches locally with `localStorage`.
- Backend logs should avoid raw query strings and exact coordinates where possible.

## Internal Service Boundary

Even with a single preview endpoint, keep internal responsibilities separate:

```text
preview
  -> geocoding
  -> fictional location lookup / optional LLM lore fallback
  -> ephemeris
  -> weather
  -> scoring
  -> feed/calendar link assembly
```

This keeps the public API simple without making the backend design hard to change.
