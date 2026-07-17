# Weather Provider Research

Client-direction note (2026-07-10): the native-Android stack is no longer
assumed; React Native/Expo is the leading installed-client candidate to
evaluate under [#109](https://github.com/rapucha/moon-service/issues/109). The
provider privacy, credential, caching, and quota conclusions remain unchanged.

## Decision

Use Open-Meteo as the first weather provider candidate for the MVP. It has passed the first field-coverage validation spike for the thin scoring prototype.

Docs: <https://open-meteo.com/en/docs>

Rationale:

- Global forecast endpoint.
- No API key for free non-commercial use.
- Hourly fields match the scoring model: cloud cover, low/mid/high cloud cover, precipitation probability, precipitation amount, weather code, visibility, wind, humidity, and solar radiation options.
- Forecasts are available for 7 days by default and up to 16 days.
- The API can request multiple coordinates in one call, which helps backend batching.
- Pricing path is straightforward if the product becomes commercial: paid endpoint, API key, higher reliability, and commercial use license.

Remaining caveats:

- The free API is non-commercial, rate-limited, has no uptime guarantee, and requires attribution through the underlying CC BY 4.0 weather data license.
- Open-Meteo may log IP addresses and request URLs, which can include coordinates, for troubleshooting. Their terms state these logs are deleted after 90 days.

### Alpha attribution and provider processing

Re-verified against the primary provider sources on 2026-07-17:

- Open-Meteo's [licence](https://open-meteo.com/en/licence) places API data under CC BY 4.0 and requires a visible Open-Meteo link next to displayed data.
- The [free API terms](https://open-meteo.com/en/terms) allow noncommercial use within the published limits. Open-Meteo's [pricing](https://open-meteo.com/en/pricing) says commercial use requires a subscription and customer API key.
- Moon Service calls Open-Meteo from its backend. The provider receives the backend address and forecast coordinates rather than a direct browser request. Open-Meteo says troubleshooting logs may contain IP addresses and request data, including coordinates, and are deleted after 90 days.
- The public lookup page credits Open-Meteo, links to the licence, identifies the noncommercial tester alpha, and states that commercial launch requires a separately approved provider plan. These are static links and copy; they add no analytics or visitor tracking.

## Recommended MVP Boundary

Use a small backend/proxy for weather access in the MVP, even if the first endpoint remains stateless.

Recommended shape:

```text
Installed client
  - stores exact saved locations locally
  - sends selected locations/preferences to Moon Service backend for refresh
  - receives scored opportunities and schedules local notifications

Moon Service backend
  - rounds coordinates before weather lookup where acceptable
  - calls Open-Meteo
  - caches weather by rounded coordinate/time/provider/model
  - computes or supports scoring
  - avoids permanent user-location storage unless push/sync is added
```

The first Open-Meteo weather adapter lives under
`backend/src/main/java/dev/moonservice/backend/weather/openmeteo/`, with shared
Open-Meteo HTTP transport, retry, and failure classification under
`backend/src/main/java/dev/moonservice/backend/openmeteo/`. The weather client
maps shared transport failures to `WeatherForecastUnavailableException`.
This work is tracked by [#14](https://github.com/rapucha/moon-service/issues/14).
It is fixture-tested in Maven and can be checked against live provider drift
with:

```bash
live-tests/run_live_weather_tests.sh
```

Provider-call protection and backend observability are tracked separately by
[#8](https://github.com/rapucha/moon-service/issues/8) and
[#9](https://github.com/rapucha/moon-service/issues/9). Containerized backend
live smoke testing is tracked by
[#27](https://github.com/rapucha/moon-service/issues/27).

Why not direct installed-client calls as the default:

- Direct calls expose user IP addresses and exact requested coordinates to the weather provider.
- Provider migration would require app changes.
- Paid/commercial Open-Meteo use introduces an API key that should not be embedded in an installed client.
- MET Norway explicitly recommends a proxy/backend for browser and mobile apps when traffic grows, and Open-Meteo paid plans also make a backend cleaner.

Direct installed-client calls remain acceptable for a throwaway prototype if there is no API key and no production users.

## Provider Comparison

### Open-Meteo

Status: recommended first provider for the thin scoring prototype.

Source: <https://open-meteo.com/en/docs>

Useful fields:

- Hourly `cloud_cover`.
- Hourly `cloud_cover_low`, `cloud_cover_mid`, `cloud_cover_high`.
- Hourly `precipitation_probability`.
- Hourly `precipitation`, `rain`, `showers`, `snowfall`.
- Hourly `weather_code`.
- Hourly `visibility`.
- Optional radiation fields if later useful for scene-light heuristics.

Forecast horizon:

- 7 days by default.
- Up to 16 days.
- Underlying model horizons vary by provider and region.

API key:

- No key for free non-commercial endpoint.
- Paid/commercial endpoint uses an API key.

Pricing/free tier:

- Free/open-access API: non-commercial only, 10,000 calls/day, 5,000 calls/hour, 600 calls/minute.
- Paid plans add commercial use and dedicated customer endpoint.

Caching terms:

- No specific product-level cache API contract found in the docs reviewed.
- Weather data is under CC BY 4.0, so attribution is required.
- For Moon Service, cache API responses for quota and repeatability, but keep source attribution and avoid redistributing raw provider data as a standalone weather product.

Privacy:

- Open-Meteo states free API logs may contain IP addresses and URLs with geographic coordinates, retained for 90 days.
- A Moon Service backend can reduce provider exposure by routing requests server-side and rounding coordinates.

Temporal precision:

- Reviewed docs on 2026-06-17: Open-Meteo exposes the cloud-cover fields Moon
  Service needs as hourly forecast variables.
- The API also exposes 15-minute forecast variables and supports
  `forecast_minutely_15`, `start_minutely_15`, and `end_minutely_15` request
  controls.
- The 15-minute variable set includes temperature, humidity, dewpoint, apparent
  temperature, precipitation amount, rain, snowfall, freezing level, sunshine
  duration, weather code, wind, visibility, CAPE, lightning potential, day/night
  state, and radiation fields.
- The 15-minute variable set does not include total/low/mid/high cloud cover or
  precipitation probability.
- Native 15-minute data is documented as available only in Central Europe and
  North America; other regions use interpolated hourly data.
- Because cloud cover is the most important weather signal for Moon photography,
  V0 should use hourly weather fields for opportunity segmentation and scoring.
- Build opportunity segments from hourly provider forecast change points, then
  merge adjacent intervals with the same derived weather class. Do not use
  5-minute or 15-minute ephemeris sampling as the product interval shape.
- 15-minute data can be revisited later for current-condition display or
  near-term precipitation/visibility refinement.

### MET Norway Locationforecast

Status: useful reference or secondary provider, not first choice.

Source: <https://api.met.no/weatherapi/locationforecast/2.0/documentation>

Useful fields:

- Global forecast for coordinates.
- JSON `complete` endpoint contains broad forecast values.
- Strong Nordic/European model coverage.

Forecast horizon:

- Next nine days.
- Most data for the Nordic region.

API key:

- No API key.
- Requires identifying `User-Agent`; missing or generic user agents are blocked.

Pricing/free tier:

- Free open data service, but no SLA.
- Anything over 20 requests/second per application requires special agreement.

Caching terms:

- Strongly cache-oriented. Clients must use cache headers and `If-Modified-Since`.
- Coordinates must be truncated to at most 4 decimals.
- They ask clients not to poll too often and to spread traffic over time.

Privacy:

- MET Norway says direct app/browser calls expose user IP and possible coordinates in their logs.
- Their terms recommend a proxy gateway to avoid revealing user IP addresses.

Fit:

- Good backup for Europe/Nordics or for cross-checking.
- Less attractive as first provider because direct mobile traffic is discouraged at scale and model coverage is strongest in the Nordic region.

### WeatherAPI.com

Status: practical fallback if Open-Meteo is unsuitable.

Source: <https://www.weatherapi.com/docs/>

Useful fields:

- Forecast API up to 14 days depending on plan.
- Hourly `cloud`, `vis_km`, `chance_of_rain`, `chance_of_snow`, precipitation, condition code/text, wind, humidity, and UV.
- Built-in astronomy endpoint, though Moon Service should still use its own ephemeris validation.

Forecast horizon:

- Free plan: 3 days.
- Paid plans: 7 days, 300 days, or more depending on plan.

API key:

- Required.

Pricing/free tier:

- Free plan shows 100,000 calls/month and 3-day forecast.
- Starter/paid tiers extend horizon and quota.

Caching terms:

- No clear cache-specific rules found in the docs reviewed.
- Treat as keyed commercial API: cache to control cost, but review terms before redistributing raw data.

Privacy:

- Direct installed-client use would expose the API key and user coordinates to the provider.
- Use backend if selected.

Fit:

- Easy fields and friendly docs.
- Free forecast horizon is probably too short for alert planning.
- Better as a fallback or paid-provider candidate than as the first open MVP provider.

### OpenWeather

Status: not first choice for MVP.

Source: <https://openweathermap.org/api/one-call-3>

Useful fields:

- One Call API includes cloudiness, visibility, precipitation probability, rain/snow, weather codes, and daily Moon data.

Forecast horizon:

- One Call 3.0 current/forecast endpoint gives hourly forecast for 48 hours and daily forecast for 8 days.
- Other OpenWeather products include 5-day/3-hour and 16-day daily forecasts.

API key:

- Required.

Pricing/free tier:

- One Call 3.0 requires subscription; docs mention 1,000 calls/day free under the One Call subscription.
- Other free APIs exist, but the useful hourly horizon is limited.

Caching terms:

- Review account terms before storing or redistributing raw data.

Privacy:

- Direct installed-client use exposes API key and coordinates.
- Backend required for real use.

Fit:

- Well-known and broad.
- Less attractive than Open-Meteo because key/subscription handling and hourly forecast horizon are not as convenient for this product.

### Visual Crossing

Status: paid/commercial candidate, not first MVP provider.

Source: <https://www.visualcrossing.com/resources/documentation/weather-api/timeline-weather-api/>

Useful fields:

- Timeline API with hourly and daily weather.
- Includes `cloudcover`, `visibility`, `precipprob`, `precip`, conditions, icons, solar radiation, and Moon phase data.
- Supports forecast, history, and statistical forecast in one API shape.

Forecast horizon:

- Forecast examples and docs refer to roughly 15-16 day model forecast behavior.

API key:

- Required.

Pricing/free tier:

- Has a free trial/free account path, but production pricing and record accounting need review before adoption.

Caching terms:

- Their pricing docs distinguish direct users and sharing stored data or derivative results. Review terms before storing raw records long term.

Privacy:

- Direct installed-client use exposes API key and coordinates.
- Backend required for real use.

Fit:

- Powerful, especially if historical forecast verification becomes important.
- Too commercial/heavy for the first MVP.

### Apple WeatherKit

Status: not suitable for the first web/backend MVP.

Source: <https://developer.apple.com/weatherkit/>

Useful fields:

- Hyperlocal current weather and 10-day hourly forecasts.
- Apple documents privacy-focused handling of location requests.

Forecast horizon:

- 10-day hourly forecast.

API key:

- Requires Apple Developer Program setup, WeatherKit service identifiers, and REST authentication outside Apple platforms.

Pricing/free tier:

- 500,000 calls/month included with Apple Developer Program membership.
- Paid call tiers are available.

Caching terms:

- Attribution requirements apply when displaying Apple Weather data.

Privacy:

- Strong Apple privacy posture, but it still adds Apple platform/account dependency.

Fit:

- Interesting if an iOS app is added later.
- Poor first choice for a web-first Java/Spring backend because it adds Apple account and service setup without clear MVP value.

### National Weather Service API

Status: useful only for U.S. support or comparison.

Source: <https://www.weather.gov/documentation/services-web-api>

Useful fields:

- U.S. forecasts, alerts, observations, and grid data.
- Hourly forecast over the next seven days.

Forecast horizon:

- Seven days for hourly forecast.

API key:

- No API key, but `User-Agent` is required.

Pricing/free tier:

- Free open data for any purpose with reasonable rate limits.

Caching terms:

- Designed to be cache-friendly; responses expire based on data lifecycle.

Privacy:

- Direct app calls expose client IP and coordinates to NWS.

Fit:

- Not global, so not a primary provider for Moon Service.

## Validation Spike Results

Date run: 2026-06-14 Europe/Prague local time.

Request shape tested:

```text
GET https://api.open-meteo.com/v1/forecast
  latitude=50.0755
  longitude=14.4378
  elevation=250
  hourly=cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,precipitation_probability,precipitation,weather_code,visibility
  forecast_days=7 or 16
  timezone=UTC
```

Observed result for Prague:

- `forecast_days=7` returned 168 hourly points.
- `forecast_days=16` returned 384 hourly points.
- All requested hourly arrays were present with matching lengths.
- Units were explicit: cloud cover and precipitation probability in percent, precipitation in millimeters, visibility in meters, and weather code as WMO code.
- Timestamps were UTC-style hourly strings when `timezone=UTC` was requested.

Conclusion:

- Open-Meteo has the fields needed by the v0 scoring model.
- Use hourly weather fields for the first scoring model because cloud cover is
  the key input and cloud-cover layers are hourly.
- Segment natural low-Moon windows by hourly provider forecast changes, then
  merge adjacent intervals with equivalent weather state.
- Use 7 days as the conservative default forecast horizon for the MVP unless scoring tests show useful value beyond that.
- A 16-day request is technically available, but model horizons vary by provider and region, so later scoring should reduce confidence for distant forecast hours rather than treating all 16 days equally.

## Weather Data Contract For MVP

Normalize provider data into this internal shape:

```text
WeatherForecastPoint
  provider
  provider_model_or_region
  fetched_at_utc
  forecast_time_utc
  latitude_bucket
  longitude_bucket
  cloud_cover_total_percent
  cloud_cover_low_percent
  cloud_cover_mid_percent
  cloud_cover_high_percent
  precipitation_probability_percent
  precipitation_mm
  visibility_meters
  weather_code
  condition_text
  confidence
```

Rules:

- Use UTC internally.
- Store provider attribution/source.
- Keep raw provider payloads only if needed for debugging, and expire them.
- Cache hourly fields by rounded coordinate and forecast hour, not by user identity.
- Start with no permanent user-location storage.

Window assessment should derive from one or more `WeatherForecastPoint` records
or merged forecast-change intervals:

- Maximum precipitation probability over the window.
- Total or maximum precipitation amount over the window.
- Minimum visibility over the window.
- Mean and maximum cloud cover over the window.
- Dominant and worst weather code over the window.

## Caching Recommendation

For the first backend prototype:

- Round coordinates for weather cache keys. The initial research target was 3
  decimal places, roughly 100 meters latitude precision, but the current
  backend runtime uses 4 decimal places to stay closer to the provider request
  shape while the weather-quality/privacy tradeoff is still being validated.
- Store forecast records by provider, rounded coordinate, forecast hour, and fetch time.
- Use a short TTL based on provider update cadence, initially 1 to 3 hours.
- Keep raw JSON payloads for no more than 7 days in alpha, or skip raw payload storage entirely.
- Store derived normalized weather points longer only if needed for scoring repeatability.
- If provider quota is exhausted, surface a temporary service state rather than returning an empty opportunity list.

Weather cache behavior should be coordinated with
[#14](https://github.com/rapucha/moon-service/issues/14) and the broader
provider-call protection work in
[#8](https://github.com/rapucha/moon-service/issues/8).

## Quota Observability

Track outbound weather provider calls locally because providers may not expose exact real-time quota usage.

Minimum counters:

- Provider.
- Endpoint or product, such as forecast.
- Current hour call count.
- Current day call count.
- Current month call count.
- Cache hit/miss before provider call.
- Error, timeout, and upstream rate-limit counts.

Admin visibility:

- Show known plan limits and percent used when limits are configured.
- Warn at roughly 50 percent, 80 percent, and 95 percent usage.
- Show projected exhaustion if current hourly usage continues.
- Distinguish cache hits from upstream calls.

If quota pressure is high:

- Prefer cached weather where still acceptable.
- Reduce feed refresh frequency.
- Disable nonessential features before core lookup.
- Consider upgrading provider plan before public alpha traffic grows.

If using MET Norway:

- Respect `Expires` and `Last-Modified`.
- Use `If-Modified-Since`.
- Truncate coordinates to at most 4 decimals.
- Avoid synchronized polling.

## Privacy Recommendation

Prefer backend-mediated weather calls because saved locations are sensitive.

Privacy defaults:

- An installed client keeps exact saved locations local.
- Backend receives exact coordinates only for the active refresh request.
- Backend rounds coordinates before provider lookup where acceptable.
- Backend logs should avoid full request URLs containing exact coordinates.
- Weather cache keys should use rounded coordinate buckets.
- Do not store user identity plus exact coordinates until push/sync requires it and the product privacy model is updated.

## Architecture Impact

Weather provider research pushes the MVP toward the hybrid architecture:

- Direct installed-client-only weather lookup is possible with Open-Meteo free non-commercial use, but it weakens privacy and future provider flexibility.
- A small backend gives immediate value: provider abstraction, cache, coordinate rounding, API-key protection, attribution handling, and scoring updates without app releases.
- The first backend can remain stateless with respect to users, while still caching weather by rounded coordinate/time bucket.

## Open Questions

- Is the project strictly non-commercial during alpha? If yes, Open-Meteo free API is acceptable. If no, budget for paid Open-Meteo or another commercial provider.
- What public API rate limit should Moon Service apply before launch?
- How much coordinate rounding is acceptable before weather quality suffers in hills, mountains, or coastal areas?
- Should forecast confidence be derived from provider/model age and forecast horizon, or should ensemble data be added later?
- Should raw provider payloads be stored at all, or only normalized derived fields?

The alpha hosting/commercial-use boundary is tracked by
[#19](https://github.com/rapucha/moon-service/issues/19). Empirical scoring
calibration is tracked by
[#33](https://github.com/rapucha/moon-service/issues/33).
