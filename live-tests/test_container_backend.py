import json
import os
from urllib.error import HTTPError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

import pytest


pytestmark = [
    pytest.mark.container_smoke,
    pytest.mark.skipif(
        os.environ.get("MOON_SERVICE_RUN_CONTAINER_SMOKE") != "1",
        reason="set MOON_SERVICE_RUN_CONTAINER_SMOKE=1 to run container smoke tests",
    ),
]


TIMEOUT_SECONDS = 30


def fetch_json(base_url, path, params):
    query = urlencode(params)
    request = Request(
        f"{base_url}{path}?{query}",
        headers={"User-Agent": "moon-service-container-smoke/0.1"},
    )
    try:
        with urlopen(request, timeout=TIMEOUT_SECONDS) as response:
            return json.loads(response.read().decode("utf-8"))
    except HTTPError as ex:
        body = ex.read().decode("utf-8", errors="replace")
        raise AssertionError(f"request failed with HTTP {ex.code}: {body}") from ex


def test_containerized_backend_serves_live_opportunity_lookup():
    base_url = os.environ["MOON_SERVICE_BASE_URL"]

    payload = fetch_json(base_url, "/api/opportunities", {"q": "Zakopane"})

    assert payload["status"] == "ok", payload
    assert payload["forecastHorizonDays"] == 7
    assert payload["startsAt"]
    assert payload["endsAt"]
    assert isinstance(payload["candidateWindowsEvaluated"], int)
    assert payload["location"]["kind"] == "real_location"
    assert payload["location"]["id"].startswith("openmeteo:")
    assert payload["location"]["displayName"]
    assert payload["location"]["timezone"] == "Europe/Warsaw"
    assert isinstance(payload["location"]["latitude"], (int, float))
    assert isinstance(payload["location"]["longitude"], (int, float))

    opportunities = payload["opportunities"]
    assert isinstance(opportunities, list)
    assert isinstance(payload["rejected"], list)
    if not opportunities:
        return

    first = opportunities[0]
    assert first["id"].startswith("openmeteo-")
    assert first["suggestedAt"]
    assert isinstance(first["score"], int)
    assert first["links"]["ics"].endswith(".ics")

    weather = first["weather"]
    assert weather["sourceResolution"] == "hourly"
    assert weather["segmentKind"]
    assert 0 <= weather["cloudCoverMeanPercent"] <= 100
    assert 0 <= weather["cloudCoverMaxPercent"] <= 100
    assert 0 <= weather["lowCloudCoverMaxPercent"] <= 100
    assert 0 <= weather["midCloudCoverMaxPercent"] <= 100
    assert 0 <= weather["highCloudCoverMaxPercent"] <= 100
    assert 0 <= weather["precipitationProbabilityMaxPercent"] <= 100
    assert weather["precipitationMm"] >= 0
    assert weather["visibilityMinMeters"] >= 0
    assert isinstance(weather["weatherCode"], int)
    assert weather["summary"]
