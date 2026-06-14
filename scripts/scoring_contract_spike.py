#!/usr/bin/env python3
"""Exercise the Moon Service v0 scoring contract with fixed fixtures.

This is a retained script-level prototype, not backend scaffolding and not a
real ephemeris/weather integration. It proves the scoring rules, hard filters,
ranking, and explanation shape against deterministic Moon/Sun/weather samples.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import dataclass
from typing import Any


FORECAST_HORIZON_DAYS = 7
DEFAULT_MIN_SCORE = 50


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


FIXTURE_WINDOWS = {
    "prague-cz": [
        {
            "id": "prague-cz-2026-06-29T1920Z",
            "startsAt": "2026-06-29T19:00:00Z",
            "peaksAt": "2026-06-29T19:20:00Z",
            "endsAt": "2026-06-29T19:50:00Z",
            "moon": {
                "altitudeDegrees": 4.2,
                "azimuthDegrees": 126.5,
                "illuminationPercent": 96,
            },
            "sun": {
                "altitudeDegrees": -4.8,
            },
            "weather": {
                "cloudCoverPercent": 38,
                "lowCloudCoverPercent": 20,
                "midCloudCoverPercent": 35,
                "highCloudCoverPercent": 44,
                "precipitationProbabilityPercent": 5,
                "precipitationMm": 0,
                "visibilityMeters": 18000,
                "weatherCode": 2,
            },
            "forecastAgeHours": 2,
        },
        {
            "id": "prague-cz-2026-06-30T0250Z",
            "startsAt": "2026-06-30T02:30:00Z",
            "peaksAt": "2026-06-30T02:50:00Z",
            "endsAt": "2026-06-30T03:20:00Z",
            "moon": {
                "altitudeDegrees": 9.5,
                "azimuthDegrees": 243.0,
                "illuminationPercent": 97,
            },
            "sun": {
                "altitudeDegrees": -8.8,
            },
            "weather": {
                "cloudCoverPercent": 18,
                "lowCloudCoverPercent": 10,
                "midCloudCoverPercent": 18,
                "highCloudCoverPercent": 22,
                "precipitationProbabilityPercent": 3,
                "precipitationMm": 0,
                "visibilityMeters": 22000,
                "weatherCode": 1,
            },
            "forecastAgeHours": 2,
        },
        {
            "id": "prague-cz-2026-07-01T1845Z",
            "startsAt": "2026-07-01T18:20:00Z",
            "peaksAt": "2026-07-01T18:45:00Z",
            "endsAt": "2026-07-01T19:15:00Z",
            "moon": {
                "altitudeDegrees": 14.1,
                "azimuthDegrees": 112.0,
                "illuminationPercent": 91,
            },
            "sun": {
                "altitudeDegrees": 0.2,
            },
            "weather": {
                "cloudCoverPercent": 30,
                "lowCloudCoverPercent": 18,
                "midCloudCoverPercent": 25,
                "highCloudCoverPercent": 41,
                "precipitationProbabilityPercent": 7,
                "precipitationMm": 0,
                "visibilityMeters": 17000,
                "weatherCode": 2,
            },
            "forecastAgeHours": 4,
        },
        {
            "id": "prague-cz-2026-07-02T1910Z",
            "startsAt": "2026-07-02T18:50:00Z",
            "peaksAt": "2026-07-02T19:10:00Z",
            "endsAt": "2026-07-02T19:40:00Z",
            "moon": {
                "altitudeDegrees": 5.1,
                "azimuthDegrees": 131.5,
                "illuminationPercent": 86,
            },
            "sun": {
                "altitudeDegrees": -3.1,
            },
            "weather": {
                "cloudCoverPercent": 94,
                "lowCloudCoverPercent": 90,
                "midCloudCoverPercent": 95,
                "highCloudCoverPercent": 88,
                "precipitationProbabilityPercent": 42,
                "precipitationMm": 1.4,
                "visibilityMeters": 9000,
                "weatherCode": 61,
            },
            "forecastAgeHours": 1,
        },
    ]
}


@dataclass(frozen=True)
class ComponentScores:
    moon_altitude_fit: int
    sun_light_fit: int
    moon_illumination_fit: int
    weather_fit: int
    forecast_confidence: int

    @property
    def total(self) -> int:
        return (
            self.moon_altitude_fit
            + self.sun_light_fit
            + self.moon_illumination_fit
            + self.weather_fit
            + self.forecast_confidence
        )


def score_location(location_id: str, min_score: int) -> dict[str, Any]:
    location = LOCATIONS.get(location_id)
    if not location:
        return {
            "status": "location_not_found",
            "message": f"No scoring fixture exists for location '{location_id}'.",
        }

    scored = []
    rejected = []
    for window in FIXTURE_WINDOWS[location_id]:
        rejection_reasons = hard_filter_reasons(window)
        if rejection_reasons:
            rejected.append(
                {
                    "id": window["id"],
                    "reasons": rejection_reasons,
                }
            )
            continue

        components = score_window(window)
        if components.total < min_score:
            rejected.append(
                {
                    "id": window["id"],
                    "score": components.total,
                    "reasons": ["below_minimum_score"],
                }
            )
            continue

        scored.append(format_opportunity(window, location, components))

    scored.sort(key=lambda item: (-item["score"], item["peaksAt"]))
    return {
        "status": "ok",
        "location": location,
        "forecastHorizonDays": FORECAST_HORIZON_DAYS,
        "opportunities": scored,
        "rejected": rejected,
        "messages": [
            {
                "level": "info",
                "code": "local_horizon_not_modelled",
                "text": "Local hills, buildings, or trees may affect exact visibility near the horizon.",
            },
            {
                "level": "info",
                "code": "fixture_data",
                "text": "This scoring spike uses fixed Moon, Sun, and weather samples.",
            },
        ],
    }


def hard_filter_reasons(window: dict[str, Any]) -> list[str]:
    reasons = []
    moon = window["moon"]
    weather = window["weather"]

    if moon["altitudeDegrees"] < 0:
        reasons.append("moon_below_horizon")
    if moon["altitudeDegrees"] > 12:
        reasons.append("moon_too_high_for_low_moon_mode")
    if weather["cloudCoverPercent"] >= 90:
        reasons.append("overcast")
    if weather["precipitationProbabilityPercent"] >= 30:
        reasons.append("high_precipitation_probability")
    if weather["visibilityMeters"] < 10000:
        reasons.append("low_visibility")
    return reasons


def score_window(window: dict[str, Any]) -> ComponentScores:
    moon = window["moon"]
    sun = window["sun"]
    weather = window["weather"]
    return ComponentScores(
        moon_altitude_fit=score_moon_altitude(moon["altitudeDegrees"]),
        sun_light_fit=score_sun_light(sun["altitudeDegrees"]),
        moon_illumination_fit=score_illumination(moon["illuminationPercent"]),
        weather_fit=score_weather(weather),
        forecast_confidence=score_confidence(window["forecastAgeHours"]),
    )


def score_moon_altitude(altitude: float) -> int:
    if altitude < 0 or altitude > 12:
        return 0
    if 1 <= altitude <= 6:
        return 30
    if altitude < 1:
        return round(18 + altitude * 6)
    return round(30 - ((altitude - 6) / 6) * 12)


def score_sun_light(sun_altitude: float) -> int:
    bucket = light_bucket(sun_altitude)
    return {
        "golden_hour": 25,
        "civil_twilight": 24,
        "daylight": 16,
        "nautical_twilight": 14,
        "night": 7,
    }[bucket]


def score_illumination(percent: float) -> int:
    if percent >= 95:
        return 15
    if percent >= 85:
        return 12
    if percent >= 70:
        return 8
    return 4


def score_weather(weather: dict[str, Any]) -> int:
    cloud = weather["cloudCoverPercent"]
    precip = weather["precipitationProbabilityPercent"]
    visibility = weather["visibilityMeters"]

    cloud_score = max(0, 13 - round(abs(cloud - 35) / 5))
    precip_score = max(0, 7 - round(precip / 5))
    visibility_score = 5 if visibility >= 20000 else 4 if visibility >= 15000 else 2
    return min(25, cloud_score + precip_score + visibility_score)


def score_confidence(forecast_age_hours: float) -> int:
    if forecast_age_hours <= 3:
        return 5
    if forecast_age_hours <= 12:
        return 4
    if forecast_age_hours <= 24:
        return 3
    return 2


def light_bucket(sun_altitude: float) -> str:
    if sun_altitude >= 6:
        return "daylight"
    if -0.833 <= sun_altitude < 6:
        return "golden_hour"
    if -6 <= sun_altitude < -0.833:
        return "civil_twilight"
    if -12 <= sun_altitude < -6:
        return "nautical_twilight"
    return "night"


def confidence_label(score: int) -> str:
    if score >= 85:
        return "high"
    if score >= 65:
        return "medium"
    return "low"


def weather_summary(weather: dict[str, Any]) -> str:
    code = weather["weatherCode"]
    if code in {0, 1}:
        return "clear to mostly clear"
    if code in {2, 3}:
        return "partly cloudy"
    if code >= 50:
        return "rain likely"
    return "mixed conditions"


def format_opportunity(
    window: dict[str, Any],
    location: dict[str, Any],
    components: ComponentScores,
) -> dict[str, Any]:
    score = components.total
    moon = window["moon"]
    sun = window["sun"]
    weather = window["weather"]
    bucket = light_bucket(sun["altitudeDegrees"])
    return {
        "id": window["id"],
        "startsAt": window["startsAt"],
        "peaksAt": window["peaksAt"],
        "endsAt": window["endsAt"],
        "localTimeZone": location["timezone"],
        "score": score,
        "confidence": confidence_label(score),
        "components": {
            "moonAltitudeFit": components.moon_altitude_fit,
            "sunLightFit": components.sun_light_fit,
            "moonIlluminationFit": components.moon_illumination_fit,
            "weatherFit": components.weather_fit,
            "forecastConfidence": components.forecast_confidence,
        },
        "moon": moon,
        "sun": {
            "altitudeDegrees": sun["altitudeDegrees"],
            "lightBucket": bucket,
        },
        "weather": {
            "cloudCoverPercent": weather["cloudCoverPercent"],
            "lowCloudCoverPercent": weather["lowCloudCoverPercent"],
            "precipitationProbabilityPercent": weather["precipitationProbabilityPercent"],
            "visibilityMeters": weather["visibilityMeters"],
            "summary": weather_summary(weather),
        },
        "reason": reason_text(moon, sun, weather, bucket),
        "links": {
            "ics": f"/o/{window['id']}.ics",
        },
    }


def reason_text(
    moon: dict[str, Any],
    sun: dict[str, Any],
    weather: dict[str, Any],
    bucket: str,
) -> str:
    return (
        f"Moon is {moon['altitudeDegrees']:.1f} degrees above the horizon "
        f"at azimuth {moon['azimuthDegrees']:.0f} degrees during "
        f"{bucket.replace('_', ' ')} with {weather_summary(weather)} "
        f"and {weather['precipitationProbabilityPercent']} percent precipitation risk."
    )


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run the Moon Service scoring contract spike.")
    parser.add_argument(
        "--location",
        default="prague-cz",
        choices=sorted(LOCATIONS),
        help="Fixture location to score.",
    )
    parser.add_argument(
        "--min-score",
        type=int,
        default=DEFAULT_MIN_SCORE,
        help="Minimum score required for returned opportunities.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    response = score_location(args.location, args.min_score)
    print(json.dumps(response, ensure_ascii=False, indent=2, sort_keys=True))
    return 0 if response["status"] == "ok" else 1


if __name__ == "__main__":
    raise SystemExit(main())
