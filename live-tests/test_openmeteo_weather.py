import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pytest


pytestmark = [
    pytest.mark.live_weather,
    pytest.mark.skipif(
        os.environ.get("MOON_SERVICE_RUN_LIVE_TESTS") != "1",
        reason="set MOON_SERVICE_RUN_LIVE_TESTS=1 to call live providers",
    ),
]


OPEN_METEO_FORECAST_URL = "https://api.open-meteo.com/v1/forecast"
TIMEOUT_SECONDS = 10
HOURLY_FIELDS = [
    "cloud_cover",
    "cloud_cover_low",
    "cloud_cover_mid",
    "cloud_cover_high",
    "precipitation_probability",
    "precipitation",
    "weather_code",
    "visibility",
]


def fetch_weather(latitude, longitude, elevation):
    params = urlencode(
        {
            "latitude": f"{latitude:.4f}",
            "longitude": f"{longitude:.4f}",
            "elevation": str(elevation),
            "hourly": ",".join(HOURLY_FIELDS),
            "timezone": "UTC",
            "timeformat": "unixtime",
            "forecast_days": 2,
        }
    )
    request = Request(
        f"{OPEN_METEO_FORECAST_URL}?{params}",
        headers={"User-Agent": "moon-service-live-weather-check/0.1"},
    )

    with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        return json.loads(response.read().decode("utf-8"))


def assert_percent_series(values, field_name, expected_length):
    assert isinstance(values, list), f"{field_name} is not an array"
    assert len(values) == expected_length, f"{field_name} length changed"
    assert values, f"{field_name} is empty"
    sample = [value for value in values[:24] if value is not None]
    assert sample, f"{field_name} had no numeric values in the first 24 hours"
    assert all(isinstance(value, (int, float)) for value in sample), f"{field_name} contains non-numeric values"
    assert all(0 <= value <= 100 for value in sample), f"{field_name} values left percent range"


def assert_non_negative_series(values, field_name, expected_length):
    assert isinstance(values, list), f"{field_name} is not an array"
    assert len(values) == expected_length, f"{field_name} length changed"
    sample = [value for value in values[:24] if value is not None]
    assert sample, f"{field_name} had no numeric values in the first 24 hours"
    assert all(isinstance(value, (int, float)) for value in sample), f"{field_name} contains non-numeric values"
    assert all(value >= 0 for value in sample), f"{field_name} contains negative values"


@pytest.mark.parametrize(
    ("name", "latitude", "longitude", "elevation"),
    [
        ("Amsterdam", 52.37403, 4.88969, 13),
        ("Prague", 50.08804, 14.42076, 202),
    ],
)
def test_openmeteo_weather_hourly_fields_remain_available(name, latitude, longitude, elevation):
    payload = fetch_weather(latitude, longitude, elevation)
    hourly = payload.get("hourly")

    assert isinstance(hourly, dict), f"{name}: hourly object missing"
    times = hourly.get("time")
    assert isinstance(times, list), f"{name}: hourly time is not an array"
    assert len(times) >= 24, f"{name}: expected at least 24 hourly records"
    assert all(isinstance(value, int) for value in times[:24]), f"{name}: times are not unix seconds"

    for field_name in [
        "cloud_cover",
        "cloud_cover_low",
        "cloud_cover_mid",
        "cloud_cover_high",
        "precipitation_probability",
    ]:
        assert_percent_series(hourly.get(field_name), f"{name}: {field_name}", len(times))

    for field_name in ["precipitation", "weather_code", "visibility"]:
        assert_non_negative_series(hourly.get(field_name), f"{name}: {field_name}", len(times))
