# First Backend/Web API Shape

This document owns product API design and intended future shapes. For the
currently implemented controller mappings, concrete consumers, and exposure,
see the [HTTP route inventory](http-route-inventory.md).

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
/calendars/prague-cz.ics
/o/prague-cz-2026-06-29T1920Z.ics
```

Implementation tracking:
[#15](https://github.com/rapucha/moon-service/issues/15) for the web lookup and
shareable result flow, and
[#16](https://github.com/rapucha/moon-service/issues/16) for feeds and
calendar exports.

The web page can call a single opportunity search endpoint:

```http
GET /api/opportunities?q=Praha&lang=cs
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

- Optional alternative to `q` for a selected canonical real location.
- The current backend accepts provider-backed IDs returned after
  `ambiguous_location`; curated fictional IDs remain a future contract.

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
- `ambiguous_location`: multiple plausible candidates; the current backend
  returns real geocoding candidates only, while fictional candidates remain a
  future contract.
- `location_not_found`: no real geocoding result. The current backend has no
  fictional fallback.
- `invalid_request`: missing, empty after trimming, too long, malformed, or unsupported input.
- `temporarily_unavailable`: the current backend could not complete geocoding
  or weather lookup. The target contract may apply the same state to other
  required dependencies.
- `rate_limited`: request was valid, but the client or service exceeded an application-level rate limit.

For a resolved real location, `status: "ok"` with `opportunities: []` means
evaluation completed successfully but produced no scored result. Today that can
mean no natural windows were generated, no live portion remained, or every
retained window was rejected by the thin-crescent visibility rule. There is no
minimum total-score threshold. Dependency failures should not be represented as
an empty list.

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
- `query_alias_used`: raw geocoding returned no candidates, so a curated alias, transliteration, or exact curated one-character place record was used.
- `input_normalized`: lookup/cache input was normalized, such as whitespace collapsing, while preserving the original query in the response.
- `one_character_query`: the query is a single visible character and was handled with stricter lookup rules.

## Rate Limits And Upstream Quotas

The backend must protect both Moon Service and upstream providers.

Application-level limits:

- Rate limit `/api/opportunities` by IP or coarse anonymous client fingerprint
  before public alpha. For home-hosted alpha, start with an edge or ingress
  limit around 30 requests per minute per client, with stricter handling for
  one-character or otherwise high-ambiguity lookups.
- Edge or ingress limits are acceptable as an early safety control, but the
  documented `rate_limited` JSON response requires application-level handling.
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

The status page should make quota risk visible before exhaustion. If known
limits are configured, show warning states at roughly 50 percent, 80 percent,
and 95 percent usage. Unknown limits must stay explicit instead of returning a
fake percent. Provider operations should stay generic enough for later
geocoding, weather, email, calendar, ephemeris, map, or LLM-backed fictional
location providers.

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

## Opportunity Window Contract

Real opportunities should represent natural visible-Moon windows, not artificial
slices produced by ephemeris sampling. Low Moon remains the strongest default
use case, but context Moon opportunities should not be excluded when light and
weather are favorable.

The backend should first find physical Moon passes: continuous intervals where
the apparent refracted Moon altitude stays above the local horizon. A pass is
bounded by Moonrise, Moonset, or the search horizon edges. Local midnight is not
a pass boundary.

Useful recommendation windows live inside a Moon pass. A single pass may
produce more than one opportunity, for example one while the Moon is ascending
and another while it is descending. Recommendation windows may be bounded by
Moonrise, Moonset, optional crossings through the configured altitude ceiling,
the pass peak, or the search horizon edges.

Response rules:

- The current response remains a flat `opportunities` array. Follow-up #53
  tracks whether the API should later become pass-centric, with Moon passes as
  the primary ranked objects and recommendation windows nested inside them.
- The anonymous `GET /api/opportunities` lookup requests up to ten raw
  recommendation windows by default, ordered by the existing score and
  tie-break rules. Ten is a provisional discovery safeguard while the scoring
  model is being evaluated under #33, not a claim that every returned candidate
  will produce an objectively good photograph.
- The direct `POST /api/opportunities/search` scoring contract keeps its
  caller-supplied `limit`; increasing the anonymous lookup default does not
  change that explicit request control.
- `startsAt` and `endsAt` define the useful opportunity window.
- `moonPass` identifies the containing physical Moon pass. Clients may use
  `moonPass.id` to group ascending and descending recommendations from the
  same pass, so ten raw candidates may render as fewer than ten pass cards.
  `moonPass.startsAt` and `moonPass.endsAt` describe the whole pass, which may
  cross local midnight.
- `moonPass.path` describes Moon movement across the whole physical pass. The
  current flat response repeats this bounded pass path on each opportunity so a
  grouped client can draw one continuous pass chart; follow-up #53 can remove
  that duplication if the API becomes pass-centric.
- `suggestedAt` is optional and only identifies a representative time inside
  the window for sorting, links, or display.
- `moon` describes the Moon at `suggestedAt`; keep this field as the compact
  suggested-time summary for compatibility.
- `moon.phaseAngleDegrees` uses the astronomical lunar phase angle: `0` is new
  Moon, `90` is first quarter, `180` is full Moon, and `270` is last quarter.
- `moon.brightLimbTiltDegrees` is an optional observer-oriented value at
  `suggestedAt`. It gives the direction from the Moon center toward the Sun in
  the local sky tangent plane: `0` points toward local zenith at the top of a
  horizon-aligned image, `90` points right toward increasing azimuth, and angles
  increase clockwise in the range `[0, 360)`. A missing or `null` value means
  clients must retain their location-independent phase fallback. This is not a
  celestial-north position angle, lunar-axis rotation, or parallactic angle.
- `moon.northPoleTiltDegrees` is an optional observer-oriented value at
  `suggestedAt`. It gives the direction from the Moon center toward the lunar
  north rotational pole in the same local tangent-plane convention: `0` points
  toward local zenith, `90` points right toward increasing azimuth, and angles
  increase clockwise in `[0, 360)`. A missing or `null` value means clients
  retain the canonical north-up surface texture. This field rotates surface
  sampling only; it does not change the phase silhouette or bright-limb
  direction and does not model libration.
- `moonPath` describes Moon movement across the window. It must include direct
  `start`, `suggested`, and `end` points plus a bounded `samples` array
  suitable for compact UI charts. V0 samples the path at regular 30-minute
  intervals, with additional samples at suggested time, window boundaries, and
  light-bucket crossings.
- `moonPath` points include `lightBucket` derived from Sun altitude so clients
  can show daylight, golden hour, twilight, and night changes across the same
  Moon path without treating weather precision as minute-level.
- `moonPath` points include `sunAzimuthDegrees` alongside
  `sunAltitudeDegrees` so clients can annotate secondary Sun positions without
  doing their own ephemeris calculation.
- Every `moonPass.path` and `moonPath` point includes Moon-disc orientation for
  its own `at` instant. `moonPhaseAngleDegrees` uses the same convention as
  `moon.phaseAngleDegrees`; nullable `brightLimbTiltDegrees` and
  `northPoleTiltDegrees` use the same observer-oriented conventions as the
  compact `moon` summary. The two tilt fields retain their independent null
  fallbacks. Clients rendering older responses without a per-point phase may
  fall back to the containing opportunity's `moon` summary.
- Do not expose ephemeris sampling cadence such as `stepMinutes` in the public
  API.

The current engine selects the hourly forecast record covering `suggestedAt`;
the response's weather summary and aggregate-shaped fields currently describe
that one record. The target V0 weather-window contract is broader:

- Weather fields on an opportunity should aggregate the merged weather
  interval. V0 uses hourly weather fields because cloud cover is the primary
  scoring input and Open-Meteo exposes cloud-cover layers hourly.
- Natural visible-Moon windows should split at provider forecast change
  boundaries when those changes affect the recommendation.
- Adjacent intervals should merge when the derived weather class and
  decision-relevant facts are equivalent.
- A single opportunity may then cover a broad interval when the Moon remains
  visible and the forecast state is stable.
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

## Calibration Feedback API

Issue [#33](https://github.com/rapucha/moon-service/issues/33) owns the
calibration-feedback initiative. This section is the version 1 public contract;
the routes are not implemented yet. Existing opportunity `id` and
`moonPass.id` values remain unchanged.

### Shared transport and values

All three routes return JSON with integer `schemaVersion: 1`, an RFC 3339 UTC
`serverTime`, and `Cache-Control: no-store`. Requests use UTF-8
`application/json`; an optional `charset=utf-8` is allowed. Other media types,
content encodings, duplicate or unknown members, non-finite numbers, and
malformed Unicode are rejected. Body limits count received bytes before JSON
parsing. A declared or streamed body over the limit is rejected without
parsing the excess.

Preview and submission use the same self-reported location record:

```json
{
  "kind": "real_location",
  "id": "openmeteo:prague-cz",
  "displayName": "Prague / Praha, Czech Republic",
  "latitude": 50.0755,
  "longitude": 14.4378,
  "elevationMeters": 250,
  "timezone": "Europe/Prague",
  "countryCode": "CZ"
}
```

This shape is `ReportedLocation`:

```text
ReportedLocation {
  kind: real_location
  id: string
  displayName: string
  latitude: number
  longitude: number
  elevationMeters: integer
  timezone: IANA zone
  countryCode: two-letter uppercase country code
}
```

The browser builds this record only from a selected `real_location` lookup
result and copies the fields unchanged. It never requests device-location or
GPS permission. A tester may first select any city, including one for a past
observation elsewhere.

The API accepts no other location members. It requires the exact
`real_location` kind, nonblank strings without leading or trailing Unicode
White_Space, finite latitude from `-90` through `90`, finite longitude from
`-180` through `180`, a signed 32-bit integer elevation, a recognized IANA
timezone, and a two-letter uppercase country code. It applies Unicode NFC to
`id` and `displayName`. It does not verify that the values came from lookup or
describe a city; direct API callers are trusted alpha testers.

Preview and submission call neither the location resolver nor a geocoder and
do not populate the resolver cache. “City-level” is a cooperating browser and
tester promise, not a server-enforced guarantee. Successful preview responses
return the normalized record. Stored location and recomputed astronomy
represent the tester's claim, not independently verified location evidence.
Fabricated evidence is an accepted alpha risk.

Timing is a tagged union. `now` has no client time fields:

```json
{ "kind": "now" }
```

The server captures one receipt instant after the bounded body is received and
uses it for a new `now` preview or report. Its accepted timing has
`source: "server_receipt"` and `confidence: "exact"`.

`past` preserves what the tester first entered and the correction used for the
calculation:

```json
{
  "kind": "past",
  "enteredLocalDateTime": "2025-10-26T02:30:00",
  "correctedLocalDateTime": "2025-10-26T02:25:00",
  "timezone": "Europe/Prague",
  "utcOffset": "+02:00",
  "source": "camera_metadata",
  "confidence": "within_5_minutes"
}
```

The request variants are:

```text
NowTiming {
  kind: now
}

PastTiming {
  kind: past
  enteredLocalDateTime: local date-time
  correctedLocalDateTime: local date-time
  timezone: IANA zone
  utcOffset: zone offset  // omitted only when the corrected value is unambiguous
  source: PastSource
  confidence: PastConfidence
}
```

Local date-times use exactly `YYYY-MM-DDTHH:mm:ss`, with no zone, offset, or
fraction. They use the proleptic Gregorian calendar and four-digit years
`0001-9999`. `timezone` must equal the selected location timezone. `utcOffset`
uses the zone's canonical `Z`, `±HH:MM`, or historical `±HH:MM:SS` form. It may
be omitted when `correctedLocalDateTime` has one valid offset and is required
when it has two. Both successful operations return the corrected value's
resolved offset and UTC `resolvedAt`; only final submission stores them.

Past `source` is one of:

```text
camera_metadata
phone_metadata
written_record
memory
other
```

Past `confidence` is one of:

```text
exact
within_5_minutes
within_30_minutes
within_2_hours
date_only
```

The source values form `PastSource`; the confidence values form
`PastConfidence`.

`date_only` still needs a corrected time so astronomy can be reconstructed; it
records that the time is not reliable. Only the corrected value is resolved,
checked for a gap or overlap, and compared with receipt plus five minutes;
equality is accepted. The entered value gets syntax and date-range validation
only and is then preserved. Browser-clock comparisons are warnings. Resolving
an allowed corrected date may produce a UTC instant in adjacent ISO year `0000`
or `+10000`; UTC response values use ISO 8601 expanded-year form. The search is
clipped where converting an edge to the selected location timezone would leave
local year `0001-9999`.

`exact` means the source timestamp is trusted to its displayed second. Each
`within_*` value is the tester's maximum estimated absolute error. These labels
record evidence quality; they do not change the reconstructed instant.

A corrected time in a daylight-saving gap is rejected. A corrected overlap
without `utcOffset` returns both valid offsets in `validUtcOffsets`, ordered by
the UTC instants they produce, so the browser can ask the tester to choose. The
original entered value does not change during correction or offset selection.

### Capability

```http
GET /api/calibration-feedback/v1/capability
```

The route always returns `200` and does not require feedback storage to be
available:

```json
{
  "schemaVersion": 1,
  "serverTime": "2026-07-19T12:00:00Z",
  "featureState": "enabled",
  "previewAvailability": "available",
  "submissionAvailability": "unavailable"
}
```

`featureState` is `enabled` or `disabled`. Each availability is `available`,
`disabled`, or `unavailable`: `disabled` means the relevant feature is not
enabled, while `unavailable` means it is enabled but cannot currently serve a
request. A disabled feature reports both operations as `disabled`; an enabled
feature may report the two operations independently. Transient token or
concurrency exhaustion does not change availability. The response never
exposes database type, configuration, health, capacity, counts, or failure text.

| Condition | Preview availability | Submission availability |
| --- | --- | --- |
| Feedback feature disabled | `disabled` | `disabled` |
| Feature enabled, preview disabled | `disabled` | Determined independently |
| Preview enabled and operational | `available` | Determined independently |
| Preview enabled but unable to calculate | `unavailable` | Determined independently |
| Submission storage not configured | Determined independently | `disabled` |
| Storage configured, reachable, and below capacity | Determined independently | `available` |
| Storage down or full | Determined independently | `unavailable` |

Availability describes a new operation, not a reservation. When submission is
`unavailable`, an explicit exact retry may still return `200` if the original
row exists and the only unavailable condition is full capacity. A database
outage cannot replay until storage is reachable.

### Historical astronomy preview

```http
POST /api/calibration-feedback/v1/preview
```

The request is at most 4 KiB (4,096 bytes) and contains only `schemaVersion`,
`location`, and `timing`. Ratings, notes, recommendation data, client preview
facts, and other fields are invalid. A valid request is non-persisting and
calls no weather provider.

```text
PreviewRequest {
  schemaVersion: 1
  location: ReportedLocation
  timing: NowTiming | PastTiming
}
```

The success response has this shape and returns at most one visible Moon pass:

```text
PreviewResponse {
  schemaVersion: 1
  serverTime
  location: ReportedLocation
  acceptedTiming: ResolvedNowTiming | ResolvedPastTiming
  searchStartsAt: UTC instant
  searchEndsAt: UTC instant
  facts: AstronomyFacts
  pass: VisiblePass | NoVisiblePass
}

ResolvedNowTiming {
  kind: now
  resolvedLocalDateTime: local date-time
  timezone: IANA zone
  utcOffset: zone offset
  source: server_receipt
  confidence: exact
  resolvedAt: UTC instant
}

ResolvedPastTiming {
  kind: past
  enteredLocalDateTime: local date-time
  correctedLocalDateTime: local date-time
  timezone: IANA zone
  utcOffset: zone offset
  source: PastSource
  confidence: PastConfidence
  resolvedAt: UTC instant
}

AstronomyFacts {
  at: UTC instant
  moonAltitudeDegrees: number
  moonAzimuthDegrees: number
  moonIlluminationPercent: number
  moonPhaseAngleDegrees: number
  brightLimbTiltDegrees: number | null
  northPoleTiltDegrees: number | null
  sunAltitudeDegrees: number
  sunAzimuthDegrees: number
  lightBucket: daylight | golden_hour | civil_twilight | nautical_twilight | night
}

VisiblePass {
  state: enclosing | nearest
  startsAt: UTC instant
  endsAt: UTC instant
  startBoundary: moonrise | search_start
  endBoundary: moonset | search_end
  sampleCount: integer
  samples: AstronomyFacts[]
}

NoVisiblePass {
  state: none
  reason: no_visible_pass_in_search_range
  sampleCount: 0
  samples: []
}
```

`facts.at` equals `acceptedTiming.resolvedAt`. Tilt meanings stay the same as
in opportunity astronomy facts.

`pass.state` is `enclosing` when the accepted instant lies in the pass,
`nearest` for the closest pass otherwise, or `none`. Samples are chronological.
A no-pass response still contains the normal location, search edges, accepted
timing, and instant facts; its pass fragment is:

```json
{
  "pass": {
    "state": "none",
    "reason": "no_visible_pass_in_search_range",
    "sampleCount": 0,
    "samples": []
  }
}
```

The nominal search interval is the inclusive instant range from
`resolvedAt - 36h` to `resolvedAt + 36h`, clipped only at the local-year edges
defined above. The coarse display set contains `resolvedAt`, every
`resolvedAt ± n × 30 minutes` inside the clipped range, and each clipped edge
when it is not already on that grid. Identical instants are deduplicated and
sorted. The full range has 145 coarse facts; clipping cannot increase that
count. The center fact is reused for `facts` and is not extra. Visibility means
apparent refracted Moon-center altitude greater than `0°`; exact zero is a
crossing.

The coarse display set is not the authority for detecting a pass. A bounded
ephemeris horizon-event search enumerates every Moon-center `0°` crossing in
the search interval. It must detect a complete rise-and-set pair even when both
adjacent coarse facts are not visible. The search accepts at most eight ordered
crossing results. If the event solver fails or would exceed that bound, the
server returns `503 feedback_unavailable` rather than a false no-pass result.

Visibility at `searchStartsAt` plus the ordered rise and set crossings forms
the passes. A pass touching an interval edge uses `search_start` or
`search_end` for that boundary. The pass containing `resolvedAt` wins.
Otherwise, the pass with the nearest refined boundary wins; an equal-distance
tie chooses the earlier pass. `state: none` is valid only when the authoritative
event search and the visibility state prove that no part of a pass lies in the
search interval.

Each crossing bracket is narrowed to at most one second with at most 11
bisections after a bracket of at most 30 minutes is known. The first visible
whole-second instant is used for a rise and the last visible whole-second
instant for a set. Event-solver, comparison, and bisection evaluations are not
response samples. The response array contains the selected pass's visible
coarse facts plus up to two refined boundary facts, in time order and with
duplicate instants emitted once. Therefore `samples` contains at most 147 facts
and `sampleCount` equals its array length.

Preview admission is shared by every visitor to one application instance. Its
bucket starts full, has capacity 12, and restores one whole token per complete
five-second interval measured by a monotonic clock, capped at 12. One
non-blocking concurrency permit is available. After validation, the server
acquires the permit and a token; only an admitted calculation consumes one.
Token exhaustion returns `429` with `Retry-After` equal to the ceiling of
seconds until the next token. A busy permit returns `429` with `Retry-After: 1`.
The permit is released on every completion or rejection. No identity, IP
address, forwarded identity, or User-Agent participates.

Refill uses the number of complete intervals since the last refill mark and
advances that mark by those intervals, retaining any partial interval. A
process restart creates a new full in-memory bucket; neither limiter is shared
between application instances.

Preview invokes neither feedback persistence, location resolution, geocoding,
nor weather. It creates no feedback record or location-cache entry. Moon
Service-controlled logs retain no preview location or timing value and no raw
preview body or identity metadata.

### Final submission

```http
POST /api/calibration-feedback/v1/submissions
```

The request is at most 16 KiB (16,384 bytes). Browser use is same-origin. The
route sends no permissive CORS headers and provides no cross-origin preflight
support; non-browser clients remain subject to this public contract. The
browser creates a UUIDv4 `clientSubmissionId` for one logical normalized
payload.

```text
SubmissionRequest {
  schemaVersion: 1
  clientSubmissionId: UUIDv4
  mode: recommendation_review | observation
  location: ReportedLocation
  timing: NowTiming | PastTiming
  ratings: Ratings
  notes: string
  recommendationSnapshot: CompactOpportunity  // recommendation_review only
}
```

`mode` is `recommendation_review` or `observation`. Ratings have these exact
values:

- `overall`: `positive|marginal|negative`
- `moon`: `clear|partial|not_visible|unknown`
- `ambientLight`: `sufficient|marginal|insufficient|unknown`
- `weather`: `better|matched|worse|not_compared`
- `horizon`: `none|minor|blocked|unknown`

Notes are required. The server changes CRLF or CR line endings to LF, applies
Unicode NFC, trims leading and trailing Unicode White_Space, rejects unpaired
surrogates, and then requires 10-4,000 Unicode code points inclusive. A code
point is not a UTF-16 unit, byte, or grapheme cluster. The normalized note is
stored; validation and logs never repeat it.

A recommendation review requires this complete compact copy of the selected
opportunity:

```text
CompactOpportunity {
  generatedAt
  id
  windowKind
  moonPass { id, startsAt, endsAt }
  startsAt
  suggestedAt: UTC instant | null
  endsAt
  localTimeZone
  score: integer
  confidence: low | medium | high
  components {
    moonAltitudeFit, sunLightFit, moonIlluminationFit,
    weatherFit, forecastConfidence
  }
  moon {
    altitudeDegrees, azimuthDegrees, illuminationPercent, phaseAngleDegrees,
    brightLimbTiltDegrees: number | null,
    northPoleTiltDegrees: number | null, phaseName
  }
  sun { altitudeDegrees, azimuthDegrees, lightBucket }
  weather {
    sourceResolution, segmentKind,
    cloudCoverMeanPercent, cloudCoverMaxPercent,
    lowCloudCoverMaxPercent, midCloudCoverMaxPercent,
    highCloudCoverMaxPercent, precipitationProbabilityMaxPercent,
    precipitationMm, visibilityMinMeters, weatherCode, summary
  }
  exposureBalance { label, text }
  reason
}
```

Member names, values, and number types are copied from the opportunity
response. `moonPass.path`, `moonPath`, `links`, and page-level messages are
excluded. The server requires pass start ≤ opportunity start ≤ opportunity end ≤ pass end
and, when present, opportunity start ≤ suggested time ≤ opportunity end. It
also requires `localTimeZone` to match the reported location. The snapshot is
claimed recommendation context, never recomputed astronomy.

An `observation` forbids `recommendationSnapshot` and accepts only
`ratings.weather: "not_compared"`. The server does not call a weather provider
for either mode. It recomputes and stores an instant Moon, Sun, and light
snapshot from the normalized reported location and `resolvedAt`; preview facts
are not an accepted request member. The location and recomputed facts remain
the tester's claim rather than verified observation evidence. The server also
supplies the application revision, submission time, server report UUID, and
idempotency hash rather than accepting them from the client.

Normalization for idempotency is deterministic:

- UUIDs use lowercase canonical text; `clientSubmissionId` must be UUIDv4.
- Object member order and insignificant JSON whitespace do not matter.
- Enums use the exact lowercase spellings above.
- UTC instants use `Z`; offsets and local times use the formats above.
- Finite JSON numbers compare by mathematical value, with negative zero
  normalized to zero and insignificant trailing zeros removed.
- Reported-location and snapshot strings use Unicode NFC and must have no
  leading or trailing whitespace. Location `kind`, timezone, and country code
  retain their validated exact spellings. Notes use their separate rule.
- Absent and `null` are different. A member may be `null` only where the source
  opportunity contract says it is nullable.

The idempotency hash covers the normalized schema version, mode, complete
reported location, timing request, ratings, note, and allowed recommendation
snapshot. It excludes `clientSubmissionId`, receipt and submission times,
server recomputation, application revision, and server-generated values. In
particular, `now` hashes as `{"kind":"now"}` so an exact retry does not become a
different report merely because its later receipt time changed.

For `now`, the request that successfully creates the row supplies the stored
receipt instant. A replay returns that original instant. If an earlier attempt
created no row, a later exact retry may create one with its later receipt
instant. Changing to a remembered clock time instead is a new `past` payload
and needs a new client UUID.

After transport, validation, and normalization, the server checks storage and
the client UUID before write admission. An available exact replay returns even
when the store is full; replay and conflict do not consume a token. A known
disabled, unavailable, or full store also consumes no token. One
instance-global whole-token bucket starts full, is shared by all visitors, has
capacity 12, and restores one token for each complete hour measured by a
monotonic clock, capped at 12. It uses the same discrete refill and restart
rules as the preview bucket. A new logical report consumes a token immediately
before recomputation and the atomic write. A race to full capacity or downstream
failure after admission does not restore it. Exhaustion returns `429`;
`Retry-After` is the ceiling of seconds until the next token. The limiter uses
no visitor identity or request metadata.

A new normalized payload and client UUID returns `201` with `status: "created"`
and a server-generated UUIDv4 `serverReportId`. An admitted exact replay returns
`200`, `status: "replayed"`, and the same server ID. Reusing the client UUID with
different normalized content returns `409`; submitting that changed report
requires a new client UUID. Disabled storage, database failure, or
configured-capacity refusal all return the same public `503`; the response does
not reveal which condition occurred or any capacity detail.

Create and replay responses have the same fields; only HTTP status and `status`
differ:

```json
{
  "schemaVersion": 1,
  "serverTime": "2026-07-19T12:00:00Z",
  "status": "created",
  "clientSubmissionId": "6d36a45c-5f58-4d78-afee-8685e66d6b2c",
  "serverReportId": "7344d2f9-a788-42b5-8a48-125fc09d2a20",
  "submittedAt": "2026-07-19T12:00:00Z"
}
```

For replay, `submittedAt` remains the original submission time.

### Error contract

Errors use this shape and never echo raw request values:

```json
{
  "schemaVersion": 1,
  "serverTime": "2026-07-19T12:00:00Z",
  "error": {
    "code": "ambiguous_local_time",
    "message": "Choose one of the valid UTC offsets.",
    "field": "timing.utcOffset",
    "validUtcOffsets": ["+02:00", "+01:00"]
  }
}
```

`error.field`, `validUtcOffsets`, and `retryAfterSeconds` are present only when
they apply. Field names use the dotted request path shown above.

Stable mappings are:

| HTTP | Code | Meaning |
| --- | --- | --- |
| `400` | `invalid_json` | JSON is malformed or has duplicate members. |
| `400` | `invalid_request` | Schema version, member, type, UUID, enum, format, or numeric range is invalid. |
| `413` | `request_too_large` | The route byte limit was exceeded. |
| `415` | `unsupported_media_type` | The media type or content encoding is unsupported. |
| `422` | `invalid_location` | The reported location shape, value, range, or timezone is invalid. |
| `422` | `invalid_report` | Notes, ratings, mode coupling, snapshot, or chronology is invalid. |
| `422` | `future_time` | The resolved instant is over five minutes after server receipt. |
| `422` | `nonexistent_local_time` | The corrected local time is in a DST gap. |
| `422` | `ambiguous_local_time` | A DST overlap needs one of the returned offsets. |
| `422` | `invalid_utc_offset` | The supplied offset is not valid for that local time and zone. |
| `409` | `client_submission_conflict` | The client UUID already names different normalized content. |
| `429` | `rate_limited` | The applicable token bucket has no token. |
| `429` | `preview_busy` | The one preview calculation permit is occupied. |
| `503` | `feedback_unavailable` | The requested feedback operation is disabled or unavailable. |

Every `429` includes an integer `Retry-After` header and matching
`error.retryAfterSeconds`. Clients may retry only after an explicit tester
action. Moon Service-controlled submission logs may retain normal
non-sensitive route metadata such as method, route, status, duration, request
ID, outcome, and aggregate storage warnings. They do not retain location,
timing, ratings, notes, snapshots, either feedback UUID, raw request bodies, IP
addresses, forwarded identity, or User-Agent values.

## Future Event-Aware Search

The first `/api/opportunities?q=...` contract is location-focused. Recurring
event-aware search should be added later only after the base location, Moon,
weather, feed, and calendar behavior works.

A later event-aware request can either extend the lookup with an optional event
context or use a separate endpoint. The event context should describe an
approximate recurring pattern, not a guaranteed occurrence:

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

Event-aware responses should keep the base opportunity facts and add event
match facts, for example:

```text
eventMatch:
  expectedLocalWindow
  uncertaintyWindow
  overlapWindow
  timingConfidence
  source
  caveat
```

Do not expose event-aware matches as exact minute-level predictions. Flights,
transport schedules, and other recurring events can be delayed, early, rerouted,
or cancelled. If the system does not use a live event provider, response copy
must say that the event timing is approximate.

Public RSS/Atom or `.ics` links may be acceptable for canonical, nonpersonal
event patterns encoded in a URL. Personal saved event subscriptions require the
privacy and storage model to cover stored preferences, notification delivery,
retention, and deletion before implementation.

## Feed And Calendar Rules

Implementation tracking:
[#16](https://github.com/rapucha/moon-service/issues/16).

RSS/Atom:

- Only for real public opportunities.
- Suitable for city/region feeds and best-upcoming feeds.
- Do not include fictional reports.
- Do not include private user preferences.

`.ics`:

- Dynamic public calendar feeds may be generated on demand for canonical real locations, such as `/calendars/prague-cz.ics`.
- Calendar feeds should contain a rolling window of upcoming public opportunities for that location and use caching rather than manual feed creation per requested city.
- Only for real individual opportunities.
- Individual event exports, such as `/o/prague-cz-2026-06-29T1920Z.ics`, should include title, time window, location display name, summary, and caveat about local horizon obstruction.
- Do not generate `.ics` for fictional reports.

## Privacy And Storage Rules

- No mandatory account.
- No server-side user profile in v0.
- No email alerts in v0.
- No cookies for remembering users.
- Server may cache geocoding/weather/scoring data by provider, canonical location, rounded coordinate, and forecast time.
- Browser may keep recent searches locally with `localStorage`.
- Backend logs should avoid raw query strings and exact coordinates where possible.

## Implemented Opportunity-Search Sequence

The browser uses only `GET /api/opportunities`; the direct POST contract shown
at the bottom is for ordinary-mode prototype and test callers. In hosted-alpha
mode, resource admission can return `429` before this application sequence
begins; the separate [resource-admission diagrams](diagrams/hosted-alpha-resource-limits.pdf)
cover that filter, its token buckets, and its concurrency permit.

### Browser GET

[![Browser GET opportunity-search sequence](diagrams/opportunity-search-get.svg)](diagrams/opportunity-search-get.svg)

[PlantUML source](diagrams/opportunity-search-get.puml)

### Direct Prototype POST

[![Direct prototype POST opportunity-search sequence](diagrams/opportunity-search-post.svg)](diagrams/opportunity-search-post.svg)

[PlantUML source](diagrams/opportunity-search-post.puml)

In prose: GET validates one lookup input, resolves it through a status-aware
cache, stops on an ambiguous, missing, or unavailable location, then obtains a
cached hourly forecast before running the opportunity pipeline. A successful
pipeline may return an empty list. POST bypasses both live provider paths and
uses the scoring prototype's sole `prague-cz` fixture and fixed fixture weather.

The implementation authority is the
[controller](../backend/src/main/java/dev/moonservice/backend/web/OpportunitySearchController.java),
[search service](../backend/src/main/java/dev/moonservice/backend/opportunity/OpportunitySearchService.java),
[location cache](../backend/src/main/java/dev/moonservice/backend/location/CachingLocationResolver.java),
[scoring engine](../backend/src/main/java/dev/moonservice/backend/opportunity/scoring/ScoringOpportunitySearchEngine.java),
and [weather cache](../backend/src/main/java/dev/moonservice/backend/weather/CachingWeatherForecastProvider.java).

## Target Internal Service Boundary

Even with a single opportunity search endpoint, keep target responsibilities
separate. Fictional lookup, feed/calendar assembly, and recurring-event search
below are not implemented by the current opportunity path.

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

This keeps the public API simple without making the backend design hard to change.

Recurring event-aware search can later add:

```text
  -> recurring event pattern validation
  -> event occurrence window generation
  -> event/Moon/weather overlap scoring
```

Keep this out of the v0 lookup until the simpler city opportunity contract is
stable.
