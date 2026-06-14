#!/usr/bin/env python3
"""Thin real-data scoring spike for Moon Service.

This retained script wires one resolved location to live ephemeris and weather
providers, then reuses the v0 scoring functions. It is still not backend
scaffolding: no persistence, no server, no scheduling, no provider abstraction.

Ephemeris source: NASA/JPL Horizons API, used here as a validation-grade live
source because the local environment does not include Astronomy Engine.
Weather source: Open-Meteo forecast API.
"""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request
from datetime import UTC, date, datetime, timedelta
from typing import Any

from scoring_contract_spike import (
    DEFAULT_MIN_SCORE,
    FORECAST_HORIZON_DAYS,
    confidence_label,
    format_opportunity,
    hard_filter_reasons,
    score_window,
)


HORIZONS_URL = "https://ssd.jpl.nasa.gov/api/horizons.api"
OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

LOCATIONS = {
    "prague-cz": {
        "kind": "real_location",
        "id": "openmeteo:prague-cz",
        "displayName": "Prague / Praha, Czech Republic",
        "latitude": 50.08804,
        "longitude": 14.42076,
        "elevationMeters": 202,
        "timezone": "Europe/Prague",
        "countryCode": "CZ",
    }
}


def fetch_horizons(
    command: str,
    location: dict[str, Any],
    start: datetime,
    stop: datetime,
    step_minutes: int,
    quantities: str,
) -> list[dict[str, Any]]:
    site_coord = (
        f"{location['longitude']:.6f},"
        f"{location['latitude']:.6f},"
        f"{location.get('elevationMeters', 0) / 1000:.3f}"
    )
    params = {
        "format": "json",
        "COMMAND": command,
        "OBJ_DATA": "NO",
        "MAKE_EPHEM": "YES",
        "EPHEM_TYPE": "OBSERVER",
        "CENTER": "coord",
        "SITE_COORD": quote_horizons(site_coord),
        "START_TIME": quote_horizons(format_horizons_time(start)),
        "STOP_TIME": quote_horizons(format_horizons_time(stop)),
        "STEP_SIZE": quote_horizons(f"{step_minutes}m"),
        "QUANTITIES": quote_horizons(quantities),
        "APPARENT": "REFRACTED",
        "ANG_FORMAT": "DEG",
        "CSV_FORMAT": "YES",
    }
    payload = get_json(HORIZONS_URL, params)
    if "error" in payload:
        raise RuntimeError(payload["error"])
    return parse_horizons_csv(payload["result"])


def quote_horizons(value: str) -> str:
    return f"'{value}'"


def format_horizons_time(value: datetime) -> str:
    return value.strftime("%Y-%b-%d %H:%M")


def parse_horizons_csv(result: str) -> list[dict[str, Any]]:
    rows = []
    in_table = False
    for line in result.splitlines():
        if line.strip() == "$$SOE":
            in_table = True
            continue
        if line.strip() == "$$EOE":
            break
        if not in_table or not line.strip():
            continue

        columns = [column.strip() for column in line.split(",")]
        timestamp = datetime.strptime(columns[0], "%Y-%b-%d %H:%M").replace(tzinfo=UTC)
        row = {
            "time": timestamp,
            "azimuthDegrees": float(columns[3]),
            "altitudeDegrees": float(columns[4]),
        }
        if len(columns) > 5 and columns[5]:
            row["illuminationPercent"] = float(columns[5])
        rows.append(row)
    return rows


def fetch_weather(location: dict[str, Any], forecast_days: int) -> dict[datetime, dict[str, Any]]:
    params = {
        "latitude": location["latitude"],
        "longitude": location["longitude"],
        "elevation": location.get("elevationMeters", 0),
        "hourly": ",".join(
            [
                "cloud_cover",
                "cloud_cover_low",
                "cloud_cover_mid",
                "cloud_cover_high",
                "precipitation_probability",
                "precipitation",
                "weather_code",
                "visibility",
            ]
        ),
        "forecast_days": forecast_days,
        "timezone": "UTC",
    }
    hourly = get_json(OPEN_METEO_FORECAST_URL, params)["hourly"]
    weather_by_hour = {}
    for index, raw_time in enumerate(hourly["time"]):
        instant = datetime.fromisoformat(raw_time).replace(tzinfo=UTC)
        weather_by_hour[instant] = {
            "cloudCoverPercent": hourly["cloud_cover"][index],
            "lowCloudCoverPercent": hourly["cloud_cover_low"][index],
            "midCloudCoverPercent": hourly["cloud_cover_mid"][index],
            "highCloudCoverPercent": hourly["cloud_cover_high"][index],
            "precipitationProbabilityPercent": hourly["precipitation_probability"][index],
            "precipitationMm": hourly["precipitation"][index],
            "visibilityMeters": hourly["visibility"][index],
            "weatherCode": hourly["weather_code"][index],
        }
    return weather_by_hour


def generate_real_data_preview(
    location_id: str,
    start_date: date,
    forecast_days: int,
    step_minutes: int,
    min_score: int,
    limit: int,
) -> dict[str, Any]:
    location = LOCATIONS[location_id]
    start = datetime.combine(start_date, datetime.min.time(), tzinfo=UTC)
    stop = start + timedelta(days=forecast_days)

    moon_rows = fetch_horizons("301", location, start, stop, step_minutes, "4,10")
    sun_rows = fetch_horizons("10", location, start, stop, step_minutes, "4")
    weather_by_hour = fetch_weather(location, forecast_days)

    sun_by_time = {row["time"]: row for row in sun_rows}
    opportunities = []
    rejected_counts: dict[str, int] = {}
    sampled = 0

    for moon in moon_rows:
        sun = sun_by_time.get(moon["time"])
        weather = weather_by_hour.get(round_down_to_hour(moon["time"]))
        if not sun or not weather:
            continue

        sampled += 1
        window = {
            "id": f"{location_id}-{moon['time'].strftime('%Y-%m-%dT%H%MZ')}",
            "startsAt": (moon["time"] - timedelta(minutes=step_minutes // 2)).isoformat().replace("+00:00", "Z"),
            "peaksAt": moon["time"].isoformat().replace("+00:00", "Z"),
            "endsAt": (moon["time"] + timedelta(minutes=step_minutes // 2)).isoformat().replace("+00:00", "Z"),
            "moon": {
                "altitudeDegrees": round(moon["altitudeDegrees"], 3),
                "azimuthDegrees": round(moon["azimuthDegrees"], 3),
                "illuminationPercent": round(moon["illuminationPercent"], 3),
            },
            "sun": {
                "altitudeDegrees": round(sun["altitudeDegrees"], 3),
            },
            "weather": weather,
            "forecastAgeHours": 1,
        }

        reasons = hard_filter_reasons(window)
        if reasons:
            count_rejections(rejected_counts, reasons)
            continue

        components = score_window(window)
        if components.total < min_score:
            count_rejections(rejected_counts, ["below_minimum_score"])
            continue

        opportunities.append(format_opportunity(window, location, components))

    opportunities.sort(key=lambda item: (-item["score"], item["peaksAt"]))
    return {
        "status": "ok",
        "location": location,
        "generatedAt": datetime.now(UTC).isoformat().replace("+00:00", "Z"),
        "forecastHorizonDays": forecast_days,
        "sampleStepMinutes": step_minutes,
        "samplesEvaluated": sampled,
        "opportunities": opportunities[:limit],
        "diagnostics": {
            "rejectedCounts": rejected_counts,
            "source": {
                "ephemeris": "jpl_horizons_api",
                "weather": "open_meteo_forecast_api",
            },
            "note": "This is a script-level real-data spike, not production provider integration.",
        },
        "messages": [
            {
                "level": "info",
                "code": "local_horizon_not_modelled",
                "text": "Local hills, buildings, or trees may affect exact visibility near the horizon.",
            }
        ],
    }


def count_rejections(rejected_counts: dict[str, int], reasons: list[str]) -> None:
    for reason in reasons:
        rejected_counts[reason] = rejected_counts.get(reason, 0) + 1


def round_down_to_hour(value: datetime) -> datetime:
    return value.replace(minute=0, second=0, microsecond=0)


def get_json(url: str, params: dict[str, Any]) -> dict[str, Any]:
    encoded = urllib.parse.urlencode(params)
    request = urllib.request.Request(
        f"{url}?{encoded}",
        headers={"User-Agent": "moon-service-real-data-scoring-spike/0.1"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Moon Service thin real-data scoring spike.")
    parser.add_argument("--location", default="prague-cz", choices=sorted(LOCATIONS))
    parser.add_argument(
        "--start-date",
        default=datetime.now(UTC).date().isoformat(),
        help="UTC start date in YYYY-MM-DD format. Defaults to today.",
    )
    parser.add_argument("--forecast-days", type=int, default=FORECAST_HORIZON_DAYS)
    parser.add_argument("--step-minutes", type=int, default=30)
    parser.add_argument("--min-score", type=int, default=DEFAULT_MIN_SCORE)
    parser.add_argument("--limit", type=int, default=5)
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    try:
        response = generate_real_data_preview(
            args.location,
            date.fromisoformat(args.start_date),
            args.forecast_days,
            args.step_minutes,
            args.min_score,
            args.limit,
        )
    except (urllib.error.URLError, TimeoutError) as exc:
        print(json.dumps({"status": "temporarily_unavailable", "message": str(exc)}, indent=2), file=sys.stderr)
        return 1
    except RuntimeError as exc:
        print(json.dumps({"status": "temporarily_unavailable", "message": str(exc)}, indent=2), file=sys.stderr)
        return 1

    print(json.dumps(response, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
