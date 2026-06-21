import json
import os
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pytest


pytestmark = [
    pytest.mark.live_geocoding,
    pytest.mark.skipif(
        os.environ.get("MOON_SERVICE_RUN_LIVE_TESTS") != "1",
        reason="set MOON_SERVICE_RUN_LIVE_TESTS=1 to call live providers",
    ),
]


OPEN_METEO_GEOCODING_URL = "https://geocoding-api.open-meteo.com/v1/search"
TIMEOUT_SECONDS = 10
REQUIRED_RESULT_FIELDS = {
    "id",
    "name",
    "latitude",
    "longitude",
    "elevation",
    "timezone",
    "country_code",
}


def fetch_geocoding(query, *, language="en", count=10):
    params = urlencode(
        {
            "name": query,
            "count": count,
            "language": language,
            "format": "json",
        }
    )
    request = Request(
        f"{OPEN_METEO_GEOCODING_URL}?{params}",
        headers={"User-Agent": "moon-service-live-geocoding-check/0.1"},
    )

    with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
        payload = json.loads(response.read().decode("utf-8"))

    return payload.get("results", [])


def assert_result_has_backend_fields(result):
    missing = REQUIRED_RESULT_FIELDS - result.keys()
    assert not missing, f"missing fields from Open-Meteo result: {sorted(missing)}"


@pytest.mark.parametrize(
    ("query", "language", "country_code", "timezone"),
    [
        ("Praha", "cs", "CZ", "Europe/Prague"),
        ("Prague", "en", "CZ", "Europe/Prague"),
        ("Tokyo", "ja", "JP", "Asia/Tokyo"),
        ("München", "de", "DE", "Europe/Berlin"),
    ],
)
def test_openmeteo_resolves_expected_city_candidates(query, language, country_code, timezone):
    results = fetch_geocoding(query, language=language)

    matching = [
        result
        for result in results
        if result.get("country_code") == country_code and result.get("timezone") == timezone
    ]
    assert matching, f"{query!r} no longer returned expected {country_code}/{timezone} candidate"
    assert_result_has_backend_fields(matching[0])


def test_prague_remains_ambiguous_enough_to_require_backend_disambiguation():
    results = fetch_geocoding("Prague", language="en")
    country_codes = {result.get("country_code") for result in results}

    assert "CZ" in country_codes, "Prague no longer returned the Czech Republic candidate"
    assert "US" in country_codes, "Prague no longer returned US candidates; review ambiguity expectations"


@pytest.mark.parametrize(
    ("query", "language"),
    [
        ("東京", "ja"),
        ("京都", "ja"),
        ("大阪", "ja"),
        ("とうきょう", "ja"),
        ("서울", "ko"),
    ],
)
def test_documented_native_script_provider_gaps_still_miss_raw_lookup(query, language):
    results = fetch_geocoding(query, language=language)

    assert results == [], (
        f"{query!r} now resolves directly in Open-Meteo; "
        "review curated alias fallback assumptions"
    )


@pytest.mark.parametrize("query", ["Å", "Y"])
def test_documented_one_character_queries_still_do_not_return_exact_raw_match(query):
    results = fetch_geocoding(query, language="en")
    exact_names = [result for result in results if result.get("name") == query]

    assert exact_names == [], (
        f"{query!r} now returns exact Open-Meteo candidates; "
        "review one-character curated record handling"
    )
