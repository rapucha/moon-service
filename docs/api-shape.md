# First Backend/Web API Shape

This document defines the product API and its planned additions. For the routes
that the controllers currently implement, who calls them, and how they are
exposed, see the [HTTP route inventory](http-route-inventory.md).

## Goal

The first API should support this single-page web flow:

```text
/search?q=Praha
```

The page should accept and resolve a city or location query. It should return
Moon opportunities when possible and support playful fictional Easter eggs. It
should not require accounts, cookies, email, or preferences saved on the
server.

## Public UX

Recommended first routes:

```text
/search?q=Praha
/l/prague-cz
/feeds/prague-cz.atom
/calendars/prague-cz.ics
/o/prague-cz-2026-06-29T1920Z.ics
```

Implementation tracking:
[#15](https://github.com/rapucha/moon-service/issues/15) for the web lookup and
shareable result flow, and
[#16](https://github.com/rapucha/moon-service/issues/16) for feeds and
calendar exports.

The web page can use one endpoint to search for opportunities:

```http
GET /api/opportunities?q=Praha&lang=cs
```

`lang` is optional. When it is absent, use `Accept-Language` only as a hint for
display and ranking.

## Request Parameters

`q`:

- Query-based lookup requires this parameter.
- It contains the raw Unicode city or location query.
- Do not convert the query to ASCII before geocoding.
- Reject empty or unreasonably long queries with `invalid_request`.
- Allow one-visible-character queries because real one-symbol place names exist,
  such as `Å` and `Y`. These queries should receive stricter handling because
  they are highly ambiguous.

`lang`:

- Optional BCP 47 language tag.
- Use it only for display and ranking preferences.
- It must not prevent local-language queries from resolving.

`country`:

- Optional ISO country code.
- Use it as a disambiguation hint. Do not use it as a hard filter unless the UI
  explicitly says so.

`locationId`:

- Optional alternative to `q` when the user has selected a canonical real
  location.
- The current backend accepts provider-backed IDs returned after
  `ambiguous_location`; curated fictional IDs remain a future contract.

## Response Statuses

Use `status` to report the result of the whole request:

```text
ok
ambiguous_location
location_not_found
invalid_request
temporarily_unavailable
rate_limited
```

Meanings:

- `ok`: the backend resolved one location and completed the lookup. For a real
  location, `opportunities` may contain results or be empty.
- `ambiguous_location`: multiple plausible candidates matched. The current
  backend returns only real geocoding candidates. Fictional candidates remain
  part of a future contract.
- `location_not_found`: geocoding found no real location. The current backend
  has no fictional fallback.
- `invalid_request`: the input is missing, empty after trimming, too long,
  malformed, or unsupported.
- `temporarily_unavailable`: the current backend could not complete the
  geocoding or weather lookup. The target contract may use the same state when
  another required dependency fails.
- `rate_limited`: the request was valid, but the client or service exceeded an
  application-level rate limit.

For a resolved real location, `status: "ok"` with `opportunities: []` means the
backend completed evaluation but produced no scored result. Today, this can
mean that the backend generated no natural windows, found no remaining live
portion, or rejected every retained window under the thin-crescent visibility
rule. There is no minimum total-score threshold. A dependency failure should
not appear as an empty list.

HTTP status codes can stay conventional:

- `200` for product states such as `ok`, `ambiguous_location`, and `location_not_found`.
- `400` for `invalid_request`.
- `429` for `rate_limited`.
- `503` for `temporarily_unavailable`.

## Message Codes

Responses may include `messages` with non-fatal information:

```text
local_horizon_not_modelled
fictional_result
query_alias_used
input_normalized
one_character_query
```

Meanings:

- `local_horizon_not_modelled`: the calculation does not include terrain,
  buildings, trees, or the exact local horizon.
- `fictional_result`: the response is an Easter egg, not real-world guidance.
- `query_alias_used`: raw geocoding returned no candidates, so the lookup used a
  curated alias, transliteration, or exact curated one-character place record.
- `input_normalized`: the backend normalized input for lookup and cache use,
  such as by collapsing whitespace, but preserved the original query in the
  response.
- `one_character_query`: the query contains one visible character, so the
  backend used stricter lookup rules.

## Rate Limits And Upstream Quotas

The backend must protect Moon Service and its upstream providers.

Application-level limits:

- Before public alpha, rate limit `/api/opportunities` by IP or coarse anonymous
  client fingerprint. For home-hosted alpha, start with an edge or ingress
  limit around 30 requests per minute per client. Apply stricter handling to
  one-character and other highly ambiguous lookups.
- Edge or ingress limits are acceptable as an early safety control, but the
  documented `rate_limited` JSON response requires application-level handling.
- Keep the limits generous enough for manual use and testing.
- Return `status: "rate_limited"` with HTTP `429` when a client is limited.
- Include a retry hint when possible.

Upstream provider limits:

- The Open-Meteo free weather API is documented as non-commercial and
  rate-limited.
- Open-Meteo geocoding should be cached and should not be called on every keystroke.
- Avoid typeahead/autocomplete in v0 to reduce geocoding request volume.
- Cache geocoding, weather, and scoring output enough to avoid repeated provider
  calls for the same city and time window.
- If an upstream provider quota is exhausted, return `temporarily_unavailable`,
  not `opportunities: []`.
- Record outbound provider calls in local counters so `/admin/status` can show
  hourly, daily, and monthly usage.

Example rate-limited response:

```json
{
  "status": "rate_limited",
  "message": "Too many requests. Please try again shortly.",
  "retryAfterSeconds": 60
}
```

## Input Validation And Abuse Protection

Treat every lookup value as hostile input, even when it looks like a city name.

Validation rules:

- Require `q` after Unicode trim.
- Reject empty input.
- Limit city and location searches to an initial maximum of around 100 visible
  characters.
- Allow one visible Unicode letter or number because real one-symbol place names exist.
- Reject or remove ASCII control characters and Unicode bidirectional control
  characters.
- Collapse whitespace runs to one space in lookup and cache keys. Preserve the
  original query for display and debugging.
- For invalid input, do not call aliases, secondary geocoders, or a future LLM
  fallback.

One-character query rules:

- Permit one visible-character queries such as `Å` or `Y`.
- Require exact geocoding or exact curated alias match.
- Do not invoke broad fuzzy fallback, dynamic translation/transliteration,
  secondary geocoding, or LLM fallback by default.
- Return `ambiguous_location` if multiple plausible places match.
- Rate-limit one-character lookups conservatively. They are cheap to abuse and
  likely to be ambiguous.

Safety rules:

- Escape `q`, provider display names, and aliases before rendering them as
  HTML.
- Do not put raw `q` in structured application logs by default.
- Do not build provider URLs by string concatenation; use encoded query parameters.
- Cache negative lookups briefly. Cap the negative-cache size and TTL to avoid
  cache pollution.
- Keep provider call counters and rate limits visible in `/admin/status`.

## Admin Status Endpoint

The first backend should provide this private operator endpoint:

```http
GET /admin/status
```

This endpoint is for the Moon Service operator, not public users. It should be
protected. It may render simple server-side HTML before a richer admin UI
exists.

Minimum response/page fields:

```text
app:
  version
  build_time
  environment

providers:
  operations:
    open-meteo-weather:
      provider
      operation
      usage:
        hourly:
          windowStartedAt
          used
          limit
          knownLimit
          percentUsed
          warningState
        daily:
          windowStartedAt
          used
          limit
          knownLimit
          percentUsed
          warningState
        monthly:
          windowStartedAt
          used
          limit
          knownLimit
          percentUsed
          warningState
    open-meteo-geocoding:
      provider
      operation
      usage:
        hourly
        daily
        monthly
  open_meteo_weather:
    aggregate outcome counters and latency summary
  open_meteo_geocoding:
    aggregate outcome counters and latency summary

caches:
  geocoding_hit_rate
  weather_hit_rate
  scoring_hit_rate

features:
  fictional_llm_fallback_enabled
  feed_generation_enabled

public_api:
  opportunity_search_rate_limit
```

The status page should show quota risk before a quota is exhausted. When known
limits are configured, show warning states at roughly 50 percent, 80 percent,
and 95 percent usage. Unknown limits must remain explicit; do not return a fake
percentage. Provider operations should remain generic enough to support later
geocoding, weather, email, calendar, ephemeris, map, or LLM-backed fictional
location providers.

## Candidate Kinds

Put `kind` on candidates and results, not in `status`:

```text
real_location
fictional_location
```

`real_location`:

- Comes from geocoding provider data.
- Can include latitude, longitude, elevation, timezone, country, and admin
  areas.
- Can produce real opportunities, RSS/Atom feeds, and `.ics` exports.

`fictional_location`:

- Comes from curated fictional data or a later LLM-assisted lore
  classification.
- Must not include real coordinates.
- Must not produce RSS/Atom feed entries, `.ics` exports, real weather, or real ephemeris results.
- Can produce a clearly labeled fictional report.

## Opportunity Window Contract

Real opportunities should represent natural windows when the Moon is visible,
not artificial slices created by ephemeris sampling. A low Moon remains the
strongest default use case. The backend should not exclude context Moon
opportunities when light and weather are favorable.

The backend should first find physical Moon passes. A pass is a continuous
interval when the apparent refracted Moon altitude stays above the local
horizon. Moonrise, Moonset, or an edge of the search horizon bounds the pass.
Local midnight does not.

The backend finds useful recommendation windows inside each Moon pass. One pass
may produce more than one opportunity, such as one while the Moon ascends and
another while it descends. Moonrise, Moonset, optional crossings through the
configured altitude ceiling, the pass peak, or search-horizon edges may bound
a recommendation window.

Response rules:

- The current response remains a flat `opportunities` array. Follow-up #53
  tracks whether the API should later become pass-centric, with Moon passes as
  the primary ranked objects and recommendation windows nested inside them.
- By default, the anonymous `GET /api/opportunities` lookup requests up to ten
  raw recommendation windows. It orders them by the existing score and
  tie-break rules. Ten is a provisional discovery safeguard while #33 evaluates
  the scoring model. It does not mean that every returned candidate will
  produce an objectively good photograph.
- The direct `POST /api/opportunities/search` scoring contract keeps its
  caller-supplied `limit`; increasing the anonymous lookup default does not
  change that explicit request control.
- `startsAt` and `endsAt` define the useful opportunity window.
- `moonPass` identifies the physical Moon pass that contains the opportunity.
  Clients may use `moonPass.id` to group ascending and descending
  recommendations from the same pass. Therefore, ten raw candidates may render
  as fewer than ten pass cards. `moonPass.startsAt` and `moonPass.endsAt`
  describe the whole pass, which may cross local midnight.
- `moonPass.path` describes Moon movement across the whole physical pass. The
  current flat response repeats this bounded pass path on each opportunity. A
  grouped client can then draw one continuous pass chart. Follow-up #53 can
  remove that duplication if the API becomes pass-centric.
- `suggestedAt` is optional. It only identifies a representative time inside
  the window for sorting, links, or display.
- `moon` describes the Moon at `suggestedAt`; keep this field as the compact
  suggested-time summary for compatibility.
- `moon.phaseAngleDegrees` uses the astronomical lunar phase angle: `0` is new
  Moon, `90` is first quarter, `180` is full Moon, and `270` is last quarter.
- `moon.brightLimbTiltDegrees` is an optional observer-oriented value at
  `suggestedAt`. It points from the Moon center toward the Sun in the local sky
  tangent plane. In a horizon-aligned image, `0` points toward the local zenith
  at the top, `90` points right toward increasing azimuth, and angles increase
  clockwise in the range `[0, 360)`. When the value is missing or `null`,
  clients must retain their location-independent phase fallback. This value is
  not a celestial-north position angle, lunar-axis rotation, or parallactic
  angle.
- `moon.northPoleTiltDegrees` is an optional observer-oriented value at
  `suggestedAt`. It points from the Moon center toward the lunar north
  rotational pole and uses the same local tangent-plane convention. `0` points
  toward the local zenith, `90` points right toward increasing azimuth, and
  angles increase clockwise in `[0, 360)`. When the value is missing or `null`,
  clients retain the canonical north-up surface texture. This field rotates
  only surface sampling. It does not change the phase silhouette or
  bright-limb direction, and it does not model libration.
- `moonPath` describes Moon movement across the window. It must directly
  include `start`, `suggested`, and `end` points. It must also include a bounded
  `samples` array suitable for compact UI charts. V0 samples the path at regular
  30-minute intervals and adds samples at the suggested time, window
  boundaries, and light-bucket crossings.
- `moonPath` points include `lightBucket` derived from Sun altitude so clients
  can show daylight, golden hour, twilight, and night changes across the same
  Moon path without treating weather precision as minute-level.
- `moonPath` points include `sunAzimuthDegrees` alongside
  `sunAltitudeDegrees` so clients can annotate secondary Sun positions without
  doing their own ephemeris calculation.
- Every `moonPass.path` and `moonPath` point includes the Moon-disc orientation
  at its own `at` instant. `moonPhaseAngleDegrees` uses the same convention as
  `moon.phaseAngleDegrees`. Nullable `brightLimbTiltDegrees` and
  `northPoleTiltDegrees` use the same observer-oriented conventions as the
  compact `moon` summary. The two tilt fields keep separate null fallbacks.
  Clients that render older responses without a per-point phase may fall back
  to the containing opportunity's `moon` summary.
- Do not expose ephemeris sampling cadence such as `stepMinutes` in the public
  API.

The current engine selects the hourly forecast record that covers
`suggestedAt`. The response's weather summary and aggregate-shaped fields
currently describe that one record. The target V0 weather-window contract
instead describes weather across the opportunity window:

- Weather fields on an opportunity should aggregate the merged weather
  interval. V0 uses hourly weather fields because cloud cover is the primary
  scoring input and Open-Meteo exposes cloud-cover layers hourly.
- Natural visible-Moon windows should split at provider forecast boundaries
  when a forecast change affects the recommendation.
- Adjacent intervals should merge when the derived weather class and
  decision-relevant facts are equivalent.
- One opportunity may then cover a broad interval while the Moon remains
  visible and the forecast state remains stable.
- Avoid wording that implies minute-level weather certainty.

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
      "windowKind": "moonrise_low",
      "moonPass": {
        "id": "prague-cz-pass-2026-06-29T1848Z",
        "startsAt": "2026-06-29T18:48:00Z",
        "endsAt": "2026-06-30T02:12:00Z",
        "path": {
          "start": {
            "at": "2026-06-29T18:48:00Z",
            "altitudeDegrees": 0.1,
            "azimuthDegrees": 119.4,
            "moonPhaseAngleDegrees": 157.0,
            "brightLimbTiltDegrees": 1.2,
            "northPoleTiltDegrees": 56.8,
            "sunAltitudeDegrees": -1.2,
            "sunAzimuthDegrees": 306.4,
            "lightBucket": "civil_twilight",
            "role": "start"
          },
          "end": {
            "at": "2026-06-30T02:12:00Z",
            "altitudeDegrees": 0.1,
            "azimuthDegrees": 236.8,
            "moonPhaseAngleDegrees": 159.0,
            "brightLimbTiltDegrees": 310.0,
            "northPoleTiltDegrees": 42.0,
            "sunAltitudeDegrees": -14.0,
            "sunAzimuthDegrees": 42.1,
            "lightBucket": "night",
            "role": "end"
          },
          "samples": [
            {
              "at": "2026-06-29T18:48:00Z",
              "altitudeDegrees": 0.1,
              "azimuthDegrees": 119.4,
              "moonPhaseAngleDegrees": 157.0,
              "brightLimbTiltDegrees": 1.2,
              "northPoleTiltDegrees": 56.8,
              "sunAltitudeDegrees": -1.2,
              "sunAzimuthDegrees": 306.4,
              "lightBucket": "civil_twilight",
              "role": "start"
            },
            {
              "at": "2026-06-29T22:30:00Z",
              "altitudeDegrees": 31.4,
              "azimuthDegrees": 181.2,
              "moonPhaseAngleDegrees": 158.0,
              "brightLimbTiltDegrees": 340.0,
              "northPoleTiltDegrees": 50.0,
              "sunAltitudeDegrees": -15.3,
              "sunAzimuthDegrees": 354.8,
              "lightBucket": "night",
              "role": "path"
            },
            {
              "at": "2026-06-30T02:12:00Z",
              "altitudeDegrees": 0.1,
              "azimuthDegrees": 236.8,
              "moonPhaseAngleDegrees": 159.0,
              "brightLimbTiltDegrees": 310.0,
              "northPoleTiltDegrees": 42.0,
              "sunAltitudeDegrees": -14.0,
              "sunAzimuthDegrees": 42.1,
              "lightBucket": "night",
              "role": "end"
            }
          ]
        }
      },
      "startsAt": "2026-06-29T18:48:00Z",
      "suggestedAt": "2026-06-29T19:20:00Z",
      "endsAt": "2026-06-29T20:04:00Z",
      "localTimeZone": "Europe/Prague",
      "score": 82,
      "confidence": "medium",
      "components": {
        "moonAltitudeFit": 30,
        "sunLightFit": 24,
        "moonIlluminationFit": 15,
        "weatherFit": 9,
        "forecastConfidence": 4
      },
      "moon": {
        "altitudeDegrees": 4.2,
        "azimuthDegrees": 126.5,
        "illuminationPercent": 96,
        "phaseAngleDegrees": 157.1,
        "brightLimbTiltDegrees": 1.9,
        "northPoleTiltDegrees": 57.4,
        "phaseName": "waxing_gibbous"
      },
      "moonPath": {
        "start": {
          "at": "2026-06-29T18:48:00Z",
          "altitudeDegrees": 0.1,
          "azimuthDegrees": 119.4,
          "moonPhaseAngleDegrees": 157.0,
          "brightLimbTiltDegrees": 1.2,
          "northPoleTiltDegrees": 56.8,
          "sunAltitudeDegrees": -1.2,
          "sunAzimuthDegrees": 306.4,
          "lightBucket": "civil_twilight",
          "role": "start"
        },
        "suggested": {
          "at": "2026-06-29T19:20:00Z",
          "altitudeDegrees": 4.2,
          "azimuthDegrees": 126.5,
          "moonPhaseAngleDegrees": 157.1,
          "brightLimbTiltDegrees": 1.9,
          "northPoleTiltDegrees": 57.4,
          "sunAltitudeDegrees": -4.8,
          "sunAzimuthDegrees": 312.2,
          "lightBucket": "civil_twilight",
          "role": "suggested"
        },
        "end": {
          "at": "2026-06-29T20:04:00Z",
          "altitudeDegrees": 11.8,
          "azimuthDegrees": 138.2,
          "moonPhaseAngleDegrees": 157.3,
          "brightLimbTiltDegrees": 3.1,
          "northPoleTiltDegrees": 58.8,
          "sunAltitudeDegrees": -9.1,
          "sunAzimuthDegrees": 321.7,
          "lightBucket": "nautical_twilight",
          "role": "end"
        },
        "samples": [
          {
            "at": "2026-06-29T18:48:00Z",
            "altitudeDegrees": 0.1,
            "azimuthDegrees": 119.4,
            "moonPhaseAngleDegrees": 157.0,
            "brightLimbTiltDegrees": 1.2,
            "northPoleTiltDegrees": 56.8,
            "sunAltitudeDegrees": -1.2,
            "sunAzimuthDegrees": 306.4,
            "lightBucket": "civil_twilight",
            "role": "start"
          },
          {
            "at": "2026-06-29T19:07:00Z",
            "altitudeDegrees": 2.5,
            "azimuthDegrees": 123.6,
            "moonPhaseAngleDegrees": 157.1,
            "brightLimbTiltDegrees": 1.6,
            "northPoleTiltDegrees": 57.1,
            "sunAltitudeDegrees": -3.2,
            "sunAzimuthDegrees": 309.8,
            "lightBucket": "civil_twilight",
            "role": "path"
          },
          {
            "at": "2026-06-29T19:20:00Z",
            "altitudeDegrees": 4.2,
            "azimuthDegrees": 126.5,
            "moonPhaseAngleDegrees": 157.1,
            "brightLimbTiltDegrees": 1.9,
            "northPoleTiltDegrees": 57.4,
            "sunAltitudeDegrees": -4.8,
            "sunAzimuthDegrees": 312.2,
            "lightBucket": "civil_twilight",
            "role": "suggested"
          },
          {
            "at": "2026-06-29T19:42:00Z",
            "altitudeDegrees": 7.1,
            "azimuthDegrees": 131.8,
            "moonPhaseAngleDegrees": 157.2,
            "brightLimbTiltDegrees": 2.4,
            "northPoleTiltDegrees": 58.0,
            "sunAltitudeDegrees": -6.8,
            "sunAzimuthDegrees": 316.9,
            "lightBucket": "nautical_twilight",
            "role": "path"
          },
          {
            "at": "2026-06-29T20:04:00Z",
            "altitudeDegrees": 11.8,
            "azimuthDegrees": 138.2,
            "moonPhaseAngleDegrees": 157.3,
            "brightLimbTiltDegrees": 3.1,
            "northPoleTiltDegrees": 58.8,
            "sunAltitudeDegrees": -9.1,
            "sunAzimuthDegrees": 321.7,
            "lightBucket": "nautical_twilight",
            "role": "end"
          }
        ]
      },
      "sun": {
        "altitudeDegrees": -4.8,
        "azimuthDegrees": 312.2,
        "lightBucket": "civil_twilight"
      },
      "weather": {
        "sourceResolution": "hourly",
        "segmentKind": "partly_cloudy",
        "cloudCoverMeanPercent": 38,
        "cloudCoverMaxPercent": 52,
        "lowCloudCoverMaxPercent": 20,
        "midCloudCoverMaxPercent": 35,
        "highCloudCoverMaxPercent": 40,
        "precipitationProbabilityMaxPercent": 5,
        "precipitationMm": 0.0,
        "visibilityMinMeters": 18000,
        "weatherCode": 2,
        "summary": "partly cloudy"
      },
      "exposureBalance": {
        "label": "balanced",
        "text": "Twilight may still provide enough scene light while keeping the Moon readable."
      },
      "reason": "Moon is low in the southeast during a twilight window with low precipitation risk.",
      "links": {
        "ics": "/o/prague-cz-2026-06-29T1920Z.ics"
      }
    }
  ],
  "rejected": [
    {
      "startsAt": "2026-07-14T03:45:00Z",
      "endsAt": "2026-07-14T18:26:00Z",
      "reasonCode": "thin_crescent_near_conjunction",
      "reason": "The Moon is an extremely thin crescent too close to the Sun for an ordinary visible Moon opportunity.",
      "moonSunSeparationDegrees": 4.6,
      "moonIlluminationPercent": 0.2,
      "moonAltitudeDegrees": 4.4,
      "sunAltitudeDegrees": 0.2
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
    "code": "no_useful_low_moon_windows",
    "text": "No useful low-Moon window was found in the forecast period."
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
- Keep the first MVP UI copy in English only unless translation work is
  explicitly added.
- Provider validation found that Open-Meteo Geocoding resolves many raw Unicode
  city queries. It misses some native-script cases, including Japanese `東京`,
  `京都`, and `大阪`, and Korean `서울`. A broad raw Unicode promise requires a
  curated alias or transliteration fallback before considering a secondary
  geocoder or a narrower v0 search promise.

## Local Recent Searches

The first web MVP may store recent searches in browser `localStorage`.

Rules:

- Store only an ordered list of recent location display names and slugs or
  canonical IDs.
- Do not store timestamps in v0.
- Do not store exact addresses.
- Avoid storing latitude/longitude if a slug/canonical ID is enough.
- Keep a small maximum, such as 5 or 10 entries.
- Provide `Clear recent searches`.
- The app must work when `localStorage` is disabled or cleared.
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

## Calibration Feedback API

Issue [#33](https://github.com/rapucha/moon-service/issues/33) tracks the full
calibration-feedback work. Issue
[#165](https://github.com/rapucha/moon-service/issues/165) defines and
implements this smaller version 1 contract. The backend implements both routes.
Browser controls remain separate work. Existing opportunity `id` and
`moonPass.id` values do not change.

The hand-authored
[OpenAPI root](openapi/calibration-feedback-v1.yaml) is the canonical wire
contract. The local
[shared](openapi/calibration-feedback-v1-common.yaml) and
[submission](openapi/calibration-feedback-v1-submission.yaml) component files
define its referenced schemas. This Markdown section defines rules that OpenAPI
cannot state clearly: normalization, location resolution, processing order,
idempotency bytes, admission, privacy, logging, and error handling. The
implementation has no Swagger UI, generated client, runtime validation, or
build dependency for this contract.

The alpha exposes only these two routes:

- `GET /api/calibration-feedback/v1/capability`
- `POST /api/calibration-feedback/v1/submissions`

There is no historical preview route, timing mode, reverse-observation mode,
saved-review queue, or feedback-only location lookup.

### Shared transport and identifiers

Both routes return JSON. Each response contains integer `schemaVersion: 1` and
an RFC 3339 UTC `serverTime`, and it uses `Cache-Control: no-store`. Submission
accepts UTF-8 `application/json`; an optional `charset=utf-8` parameter is
allowed. The server rejects other media types, content encodings, duplicate or
unknown members, explicit `null` values, malformed Unicode, and unpaired
surrogates. It limits the received submission body to 16,384 bytes before JSON
parsing. When a declared or streamed body exceeds the limit, the server rejects
it without parsing the excess.

The browser copies `locationId` and `opportunityId` from the currently loaded
opportunity response. It does not request device-location or GPS permission.

For feedback, the server first removes the maximal leading and trailing
sequence of these Unicode White_Space code points from `locationId`:
U+0009-U+000D, U+0020, U+0085, U+00A0, U+1680, U+2000-U+200A, U+2028, U+2029,
U+202F, U+205F, and U+3000. The result must contain 1-100 Unicode code points.
Control characters and these bidirectional controls are invalid: U+061C,
U+200E-U+200F, U+202A-U+202E, and U+2066-U+2069. The server does not apply case
folding or NFC to the ID. This feedback rule does not change validation for
opportunity lookup.

`opportunityId` is an opaque value copied from the loaded result. It identifies
the opportunity that the tester claims to have used. The server accepts it
exactly as parsed, without trimming, case folding, or NFC changes. It must be
nonblank. It cannot contain outer Unicode White_Space, control characters, or
the bidirectional controls listed above. The server validates and stores the
claim, but it does not verify where the value came from or whether it belongs
to `locationId`. The alpha trusts this link supplied by the tester. It does not
add an opportunity provider or rebuild the recommendation only to prove the
link.

For a new report, the server uses the established cached `resolveLocationId`
path to resolve the normalized `locationId`. On a cache miss, the configured
geocoding provider receives only the provider-backed part derived from that
selected ID. The provider never receives the opportunity ID or feedback
content. Invalid location-ID syntax returns `400 invalid_request`. A
syntactically valid ID that no longer resolves returns
`422 location_not_found`. Resolver unavailability or an unexpected ambiguous
result for a canonical ID returns `503 feedback_unavailable`.

The server uses the resolved city as a calculation input. The city does not
prove that the tester was there. The alpha accepts the risk of fabricated
observations.

### Capability

```http
GET /api/calibration-feedback/v1/capability
```

The route always returns `200`. It does not require working feedback
persistence. OpenAPI defines these exact response fields:

- `schemaVersion`
- `serverTime`
- `featureState`: `enabled | disabled`
- `submissionAvailability`: `available | disabled | unavailable`

Explicit feature configuration sets `featureState`; database health does not.
The reduced state mapping is exact:

| Feature setting | Persistence and dependency state | `featureState` | `submissionAvailability` |
| --- | --- | --- | --- |
| Disabled | Any | `disabled` | `disabled` |
| Enabled | Persistence disabled or settings incomplete | `enabled` | `disabled` |
| Enabled | Persistence startup failed or current status is unavailable | `enabled` | `unavailable` |
| Enabled | Persistence is full | `enabled` | `unavailable` |
| Enabled | Persistence is below capacity but the resolver or astronomy engine is known unavailable | `enabled` | `unavailable` |
| Enabled | Persistence is normal or near capacity and no dependency is known unavailable | `enabled` | `available` |

Running out of write tokens temporarily does not change capability.

The response never exposes the database type, configuration, capacity, counts,
resolver or provider details, or failure text. Availability reports whether a
new submission can be made; it does not reserve capacity. When a full store
reports `unavailable`, an exact replay may still return `200` because it creates
no row. During a database outage, the server cannot replay a submission until
persistence is reachable.

### Submission wire shape

```http
POST /api/calibration-feedback/v1/submissions
```

Browsers use this route from the same origin. The route sends no permissive CORS
headers and provides no cross-origin preflight support. The same public
contract applies to non-browser clients.

The closed request object requires:

- `schemaVersion: 1`;
- lowercase-canonical UUIDv4 `clientSubmissionId`;
- `locationId` from the loaded result; and
- `opportunityId` from the loaded opportunity.

The request accepts three optional, non-null evidence members:

- `ambientLight`: `good | too_bright | too_dark`;
- `crescentVisibility`: `visible | too_small_to_see`; and
- `notes`: normalized free text.

After note normalization, at least one evidence member must remain present. An
omitted member means missing evidence, not an `unknown` answer. A present note
that normalizes to an empty string is invalid. The server does not silently
turn it into an omitted member.

For notes, the server rejects U+0000, malformed Unicode, and unpaired
surrogates. It then applies Unicode NFC and removes the maximal outer sequence
of the Unicode White_Space set listed above. The result must contain 1-4,000
Unicode code points inclusive. A code point is not a UTF-16 code unit, byte, or
grapheme cluster. Mixed scripts and emoji are allowed. The server does not
normalize CRLF or CR line endings, detect language, or translate text. It
stores the normalized note.

After the bounded body arrives, the server captures one receipt instant. It
immediately truncates that instant to microsecond precision. For a new report,
that exact normalized instant becomes the observation instant, astronomy
calculation instant, response `submittedAt`, and stored `submittedAt`. The
server stores it once.

The request cannot contain timing, mode, old rating, recommendation snapshot,
location detail, client astronomy, weather, application revision,
`serverReportId`, `submittedAt`, or an idempotency digest. The server resolves
and stores only the canonical backend location ID. At the receipt instant, it
recomputes and stores exactly Moon altitude, Moon illumination, Sun altitude,
and light bucket. It stores no coordinates, elevation, timezone, country,
display name, weather, azimuth, phase or tilt value, open-ended astronomy JSON,
or client preview fact.

### Exact idempotency digest

The repository uses `clientSubmissionId` as its lookup key. The digest compares
the five semantic client-authored slots listed below. It does not authenticate
or sign the request, and it does not prove request integrity.

The five fixed semantic slots, in order, are:

1. normalized `locationId`;
2. accepted `opportunityId`;
3. `ambientLight`, using its lowercase API spelling, if present;
4. `crescentVisibility`, using its lowercase API spelling, if present; and
5. normalized `notes`, if present.

Exclude `schemaVersion`, `clientSubmissionId`, `serverReportId`, receipt or
submission time, resolved location data, astronomy facts, application
revision, and every other server-supplied value. The `v1` suffix in the
constant prefix versions this digest representation. It is not a serialized
request field.

Build the digest input exactly as follows:

1. Write the US-ASCII bytes of
   `moon-service/calibration-feedback/idempotency/v1`.
2. Immediately append one slot frame for each of the five slots above.
3. A missing slot is the single byte `0x00`.
4. A present slot starts with `0x01`, followed by its UTF-8 byte length as one
   unsigned 32-bit big-endian integer, followed by exactly those UTF-8 bytes.
   The first two slots are required and therefore always start with `0x01`.
5. Write no length or value after `0x00`. Add no separator, prefix terminator,
   byte-order mark, padding, or trailing byte anywhere in the representation.
6. Compute SHA-256 over the complete representation and store the raw 32 digest
   bytes, not hexadecimal text.

One golden vector uses `locationId: "moon-service-3067696"`,
`opportunityId: "opportunity-1"`, `ambientLight: "good"`, an absent
`crescentVisibility`, and `notes: "Nice crescent"`. Its framed input is 119
bytes. The raw digest, rendered as hexadecimal only for this test vector, is
`cae49e707f8369f022638bcb97c365b6531e9c609bd312b920addc8cfeebd6d5`.

Use parsed and normalized values, never raw JSON spellings or bytes. Therefore,
JSON member order, insignificant JSON whitespace, equivalent JSON string
escapes, and canonically equivalent note spellings do not change the digest.
Adding, removing, or changing an optional slot does change it. Fixed slots and
presence markers distinguish each field and an omitted field without depending
on a JSON serializer.

A later change to this representation needs a new prefix/version and explicit
migration authority. Implementations require golden vectors for the framing and
SHA-256 result. They also require equivalence tests for JSON spelling and
Unicode normalization.

### Processing and admission order

For each bounded submission:

1. Capture the prospective receipt instant once the body has fully arrived.
   Truncate it to microsecond precision before using it for any calculation or
   response.
2. Parse the closed request object. Validate and normalize its values, enforce
   the evidence rule, and build the exact digest.
3. If the feedback feature is disabled, return `503 feedback_unavailable`
   without querying persistence, resolving the location, or consuming a token.
4. Query persistence by `clientSubmissionId`.
5. Handle the persistence result. `Found` with a matching digest returns `200`
   with `status: replayed` and the original `serverReportId` and `submittedAt`.
   `Found` with a different digest returns `409 client_submission_conflict`.
   `Disabled` or `Unavailable` returns `503 feedback_unavailable`. None of these
   paths resolves the location or consumes a write token. Only `NotFound`
   continues.
6. Read repository status. `Disabled`, `Unavailable`, or an available-but-full
   status returns `503` without a token. Normal and near-capacity status
   continue.
7. Resolve the normalized `locationId`. Resolver failure consumes no write
   token.
8. Consume one instance-global write token immediately before the server
   recomputes the current astronomy facts and performs the atomic store.
   Admission treats the request as new because the early lookup returned
   `NotFound`.
9. Recompute the four astronomy facts at the normalized receipt instant and
   call the repository. The repository repeats the UUID and capacity checks
   inside its transaction to close concurrent races.

When a failed attempt creates no row, the client may retry it with the same UUID
and normalized payload. That later attempt can create the row with its later
receipt instant because receipt time is not a digest slot. Changing any digest
slot requires a new client UUID. After an uncertain response, the client
retains the exact UUID and normalized payload only for this immediate retry
behavior.

Every visitor to one application instance shares the same write bucket. The
bucket starts full, has capacity 12, and restores one whole token for each
complete hour measured by a monotonic clock, up to 12. Refill advances by
complete intervals and retains a partial interval. A process restart creates a
new, full in-memory bucket. Instances do not share buckets. Accounts, IP
addresses, forwarded identity, User-Agent, and other visitor identities do not
affect the bucket.

A request that reaches admission as `NotFound` consumes a token. The server
does not restore that token after a downstream astronomy, capacity-race, or
database failure. It also does not restore the token after a transactional
replay or conflict caused by a concurrent same-UUID request winning. Early
`Found` replay and conflict paths consume no token. When the bucket is empty,
the server returns `429`. `Retry-After` equals the ceiling of the seconds until
the next token, and `error.retryAfterSeconds` contains the same integer.

After storing a new report, the server returns `201` with `status: created` and
a server-generated UUIDv4 `serverReportId`. For an exact replay, it returns
`200` with `status: replayed` and the original IDs and submission instant.
Resolver, astronomy, or persistence unavailability, disabled persistence, and
configured-capacity refusal use `503 feedback_unavailable` without revealing
the cause.

### Error, availability, and privacy boundary

OpenAPI defines the stable HTTP mappings and reduced error codes:

- `400 invalid_json | invalid_request`;
- `409 client_submission_conflict`;
- `413 request_too_large`;
- `415 unsupported_media_type`;
- `422 location_not_found | invalid_report`;
- `429 rate_limited`; and
- `503 feedback_unavailable`.

The following table defines exactly how clients complete and retry
submissions:

| Outcome | Browser treatment | Retry rule |
| --- | --- | --- |
| `201 created` or `200 replayed` | Complete the submission and show its returned status. | Do not retry. |
| `409 client_submission_conflict` | Stop and explain that the UUID belongs to different content. | Do not retry with that UUID or generate a replacement automatically. |
| `429 rate_limited` | Keep the UUID and frozen payload and show the server delay. | After `Retry-After`, the tester may explicitly retry while the feature is enabled and submission availability is not `disabled`. |
| `503 feedback_unavailable` | Keep the UUID and frozen payload and show generic unavailability. | The tester may explicitly retry only while the feature is enabled and submission availability is not `disabled`. An `unavailable` capability does not hide an existing exact-retry action. |
| No definite response | Show `Submission outcome unknown` and keep the UUID and frozen payload. | Subject to the same capability rule, the tester may explicitly retry. A committed row replays; otherwise the retry may create it at the later receipt instant. |
| Other definite `4xx` response | Preserve safe input and identify the correction when possible. | Never retry automatically. A change to any normalized digest slot requires a new UUID; a transport-only correction may retain it. |

Errors never echo request values or dependency details. Include `error.field`
only when useful. Include `error.retryAfterSeconds` only with `rate_limited`.
Every `429` includes the matching integer `Retry-After` header. Clients retry
only when the tester explicitly starts the retry.

Logs controlled by Moon Service may keep the method, route, status, duration,
request ID, coarse outcome, and aggregate storage warnings. They do not retain
raw request bodies, location ID, opportunity ID, evidence values, notes, either
feedback UUID, astronomy values, IP addresses, forwarded identity, or
User-Agent. Aggregate capacity warnings contain only state and counts.

Feedback and its persistence remain disabled by default. PostgreSQL retains an
accepted report until it is manually deleted by server report UUID. A feedback
database failure may disable submission, but it does not prevent application
startup, opportunity lookup, liveness, `/healthz`, or `/readyz`.

## Future Event-Aware Search

The first `/api/opportunities?q=...` contract searches by location. Recurring
event-aware search should be added only after the base location lookup, Moon
calculation, weather lookup, feed, and calendar features work.

A later event-aware request can add optional event details to the lookup or use
a separate endpoint. Those details should describe an approximate recurring
pattern, not promise that an event will occur:

```text
event_kind
display_name
days_of_week or recurrence_rule
local_time_window
early_late_tolerance_minutes
active_date_range
optional route/direction/azimuth fields
source and confidence fields
```

Event-aware responses should keep the base opportunity facts and add facts
about the event match, for example:

```text
eventMatch:
  expectedLocalWindow
  uncertaintyWindow
  overlapWindow
  timingConfidence
  source
  caveat
```

Do not present event-aware matches as exact minute-level predictions. Flights,
transport schedules, and other recurring events can be delayed, early,
rerouted, or cancelled. If the system does not use a live event provider, the
response text must say that the event timing is approximate.

Public RSS/Atom or `.ics` links may be acceptable when a URL encodes a
canonical, nonpersonal event pattern. Personal saved event subscriptions
require the privacy and storage model to cover stored preferences, notification
delivery, retention, and deletion before implementation.

## Feed And Calendar Rules

Implementation tracking:
[#16](https://github.com/rapucha/moon-service/issues/16).

RSS/Atom:

- Only for real public opportunities.
- Suitable for city or region feeds and best-upcoming feeds.
- Do not include fictional reports.
- Do not include private user preferences.

`.ics`:

- Dynamic public calendar feeds may be generated on demand for canonical real
  locations, such as `/calendars/prague-cz.ics`.
- Calendar feeds should contain a rolling window of upcoming public
  opportunities for that location. They should use caching instead of creating
  a feed manually for each requested city.
- Only for real individual opportunities.
- Individual event exports, such as
  `/o/prague-cz-2026-06-29T1920Z.ics`, should include the title, time window,
  location display name, summary, and a note about local horizon
  obstruction.
- Do not generate `.ics` for fictional reports.

## Privacy And Storage Rules

- No mandatory account.
- No server-side user profile in v0.
- No email alerts in v0.
- No cookies for remembering users.
- The server may cache geocoding, weather, and scoring data by provider,
  canonical location, rounded coordinate, and forecast time.
- The browser may keep recent searches locally with `localStorage`.
- Backend logs should avoid raw query strings and exact coordinates where
  possible.

## Implemented Opportunity-Search Sequence

The browser uses only `GET /api/opportunities`. The direct POST contract at the
bottom is for ordinary-mode prototype and test callers. In hosted-alpha mode,
resource admission can return `429` before this application sequence begins.
The separate [resource-admission diagrams](diagrams/hosted-alpha-resource-limits.pdf)
show that filter, its token buckets, and its concurrency permit.

### Browser GET

[![Browser GET opportunity-search sequence](diagrams/opportunity-search-get.svg)](diagrams/opportunity-search-get.svg)

[PlantUML source](diagrams/opportunity-search-get.puml)

### Direct Prototype POST

[![Direct prototype POST opportunity-search sequence](diagrams/opportunity-search-post.svg)](diagrams/opportunity-search-post.svg)

[PlantUML source](diagrams/opportunity-search-post.puml)

GET first validates one lookup input and resolves it through a status-aware
cache. It stops when the location is ambiguous, missing, or unavailable.
Otherwise, it gets a cached hourly forecast and runs the opportunity pipeline.
A successful pipeline may return an empty list. POST bypasses both live
provider paths. It uses the scoring prototype's only `prague-cz` fixture and
fixed fixture weather.

These files define the current implementation:
[controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
[search service](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchService.java),
[location cache](../backend/src/main/java/dev/moonservice/backend/location/CachingLocationResolver.java),
[scoring engine](../backend/src/main/java/dev/moonservice/backend/opportunity/scoring/ScoringOpportunitySearchEngine.java),
and [weather cache](../backend/src/main/java/dev/moonservice/backend/weather/CachingWeatherForecastProvider.java).

## Target Internal Service Boundary

Keep the target components separate even though the public API has one
opportunity search endpoint. The current opportunity path does not implement
the fictional lookup, feed and calendar assembly, or recurring-event search
shown below.

```text
opportunity_search
  -> geocoding
  -> fictional location lookup / optional LLM lore fallback
  -> ephemeris
  -> weather
  -> scoring
  -> feed/calendar link assembly
```

The coordinate-backed opportunity engine was delivered through
[#13](https://github.com/rapucha/moon-service/issues/13). The first Open-Meteo
weather integration is tracked by
[#14](https://github.com/rapucha/moon-service/issues/14).

This keeps the public API simple and the backend easy to change.

Recurring event-aware search can later add:

```text
  -> recurring event pattern validation
  -> event occurrence window generation
  -> event/Moon/weather overlap scoring
```

Keep this out of the v0 lookup until the simpler city opportunity contract is
stable.
