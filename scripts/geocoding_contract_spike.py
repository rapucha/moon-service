#!/usr/bin/env python3
"""Exercise the Moon Service v0 geocoding contract.

This is intentionally a script-level spike, not app/backend scaffolding. By
default it uses captured fixtures so it can be run as a repeatable contract
check. Pass --live to query Open-Meteo Geocoding for the same normalization and
fallback flow.
"""

from __future__ import annotations

import argparse
import json
import sys
import unicodedata
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any


MAX_VISIBLE_CHARS = 100
OPEN_METEO_URL = "https://geocoding-api.open-meteo.com/v1/search"

BIDI_CONTROL_CHARS = {
    "\u061c",
    "\u200e",
    "\u200f",
    "\u202a",
    "\u202b",
    "\u202c",
    "\u202d",
    "\u202e",
    "\u2066",
    "\u2067",
    "\u2068",
    "\u2069",
}

ALIASES = {
    "東京": "Tokyo",
    "京都": "Kyoto",
    "大阪": "Osaka",
    "とうきょう": "Tokyo",
    "서울": "Seoul",
}

FICTIONAL_LOCATIONS = {
    "xanadu": {
        "kind": "fictional_location",
        "id": "fictional:literary:xanadu",
        "displayName": "Xanadu",
        "fictionalUniverse": "literary/mythic",
        "generatedBy": "curated",
    },
    "prague": {
        "kind": "fictional_location",
        "id": "fictional:fallout:prague",
        "displayName": "Prague, Fallout universe",
        "fictionalUniverse": "Fallout",
        "reportTemplateId": "fallout-radstorm",
        "generatedBy": "curated",
    },
}


CURATED_REAL_LOCATIONS = {
    "Å": [
        {
            "id": "curated:aa-nordland-no",
            "name": "Å",
            "country_code": "NO",
            "country": "Norway",
            "admin1": "Nordland",
            "latitude": 67.87926,
            "longitude": 12.98108,
            "elevation": 8,
            "timezone": "Europe/Oslo",
        },
        {
            "id": "curated:aa-vasternorrland-se",
            "name": "Å",
            "country_code": "SE",
            "country": "Sweden",
            "admin1": "Västernorrland",
            "latitude": 63.25,
            "longitude": 17.25,
            "elevation": 52,
            "timezone": "Europe/Stockholm",
        },
    ],
    "Y": [
        {
            "id": "curated:y-somme-fr",
            "name": "Y",
            "country_code": "FR",
            "country": "France",
            "admin1": "Hauts-de-France",
            "latitude": 49.803,
            "longitude": 2.995,
            "elevation": 82,
            "timezone": "Europe/Paris",
        }
    ],
}


FIXTURE_RESULTS = {
    "Prague": [
        {
            "id": 3067696,
            "name": "Prague",
            "country_code": "CZ",
            "country": "Czech Republic",
            "admin1": "Hlavni mesto Praha",
            "latitude": 50.08804,
            "longitude": 14.42076,
            "elevation": 202,
            "timezone": "Europe/Prague",
            "population": 1165581,
        },
        {
            "id": 4547690,
            "name": "Prague",
            "country_code": "US",
            "country": "United States",
            "admin1": "Oklahoma",
            "latitude": 35.48674,
            "longitude": -96.68502,
            "elevation": 319,
            "timezone": "America/Chicago",
            "population": 2386,
        },
    ],
    "Praha": [
        {
            "id": 3067696,
            "name": "Praha",
            "country_code": "CZ",
            "country": "Czech Republic",
            "admin1": "Hlavni mesto Praha",
            "latitude": 50.08804,
            "longitude": 14.42076,
            "elevation": 202,
            "timezone": "Europe/Prague",
            "population": 1165581,
        }
    ],
    "Tokyo": [
        {
            "id": 1850147,
            "name": "東京都",
            "country_code": "JP",
            "country": "Japan",
            "admin1": "Tokyo",
            "latitude": 35.6895,
            "longitude": 139.69171,
            "elevation": 44,
            "timezone": "Asia/Tokyo",
            "population": 9733276,
        }
    ],
    "Kyoto": [
        {
            "id": 1857910,
            "name": "京都市",
            "country_code": "JP",
            "country": "Japan",
            "admin1": "Kyoto",
            "latitude": 35.02107,
            "longitude": 135.75385,
            "elevation": 50,
            "timezone": "Asia/Tokyo",
            "population": 1463723,
        }
    ],
    "Osaka": [
        {
            "id": 1853909,
            "name": "大阪市",
            "country_code": "JP",
            "country": "Japan",
            "admin1": "Osaka",
            "latitude": 34.69374,
            "longitude": 135.50218,
            "elevation": 24,
            "timezone": "Asia/Tokyo",
            "population": 2752123,
        }
    ],
    "Seoul": [
        {
            "id": 1835848,
            "name": "Seoul",
            "country_code": "KR",
            "country": "South Korea",
            "admin1": "Seoul",
            "latitude": 37.566,
            "longitude": 126.9784,
            "elevation": 38,
            "timezone": "Asia/Seoul",
            "population": 10349312,
        }
    ],
    "Å": [
        {
            "id": 3163412,
            "name": "Å",
            "country_code": "NO",
            "country": "Norway",
            "admin1": "Nordland",
            "latitude": 67.87926,
            "longitude": 12.98108,
            "elevation": 8,
            "timezone": "Europe/Oslo",
            "population": 100,
        },
        {
            "id": 2728264,
            "name": "Å",
            "country_code": "SE",
            "country": "Sweden",
            "admin1": "Västernorrland",
            "latitude": 63.25,
            "longitude": 17.25,
            "elevation": 52,
            "timezone": "Europe/Stockholm",
            "population": 100,
        },
    ],
    "Y": [
        {
            "id": 2967201,
            "name": "Y",
            "country_code": "FR",
            "country": "France",
            "admin1": "Hauts-de-France",
            "latitude": 49.803,
            "longitude": 2.995,
            "elevation": 82,
            "timezone": "Europe/Paris",
            "population": 89,
        }
    ],
}

EXPECTED_STATUS = {
    "Prague": "ambiguous_location",
    "Praha": "ok",
    "東京": "ok",
    "京都": "ok",
    "大阪": "ok",
    "とうきょう": "ok",
    "서울": "ok",
    "Å": "ambiguous_location",
    "Y": "ok",
    "Xanadu": "ok",
    "Xyznotacity": "location_not_found",
    "": "invalid_request",
    "A\u202eB": "invalid_request",
    "?": "invalid_request",
}


@dataclass(frozen=True)
class ValidQuery:
    original: str
    normalized: str
    visible_length: int
    one_character: bool


class FixtureGeocoder:
    def search(self, query: str, lang: str | None, country: str | None) -> list[dict[str, Any]]:
        results = FIXTURE_RESULTS.get(query, [])
        if country:
            country = country.upper()
            return [row for row in results if row.get("country_code") == country]
        return results


class OpenMeteoGeocoder:
    def search(self, query: str, lang: str | None, country: str | None) -> list[dict[str, Any]]:
        params = {
            "name": query,
            "count": "10",
            "format": "json",
        }
        if lang:
            params["language"] = lang
        if country:
            params["countryCode"] = country.upper()

        url = f"{OPEN_METEO_URL}?{urllib.parse.urlencode(params)}"
        request = urllib.request.Request(
            url,
            headers={"User-Agent": "moon-service-geocoding-contract-spike/0.1"},
        )
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.load(response)
        return payload.get("results", [])


def validate_query(raw_query: str) -> tuple[ValidQuery | None, dict[str, Any] | None]:
    original = raw_query
    stripped = raw_query.strip()
    if not stripped:
        return None, invalid_response(original, "empty_query", "The location query is required.")

    if any(is_unsupported_control(char) for char in stripped):
        return None, invalid_response(
            original,
            "unsupported_control_characters",
            "Remove control or bidirectional formatting characters.",
        )

    normalized = " ".join(stripped.split())
    visible_length = sum(1 for char in normalized if not char.isspace())
    if visible_length > MAX_VISIBLE_CHARS:
        return None, invalid_response(
            original,
            "query_too_long",
            "Use a city, town, or short location name.",
        )

    if visible_length == 1:
        only_visible = next(char for char in normalized if not char.isspace())
        if not (only_visible.isalpha() or only_visible.isdigit()):
            return None, invalid_response(
                original,
                "unsupported_one_character_query",
                "Use a one-character place name made from a letter or number.",
            )

    return ValidQuery(original, normalized, visible_length, visible_length == 1), None


def is_unsupported_control(char: str) -> bool:
    return unicodedata.category(char)[0] == "C" or char in BIDI_CONTROL_CHARS


def invalid_response(query: str, code: str, text: str) -> dict[str, Any]:
    return {
        "status": "invalid_request",
        "query": query,
        "message": text,
        "errors": [{"field": "q", "code": code, "text": text}],
    }


def preview(
    raw_query: str,
    geocoder: FixtureGeocoder | OpenMeteoGeocoder,
    lang: str | None = None,
    country: str | None = None,
) -> dict[str, Any]:
    valid, error = validate_query(raw_query)
    if error:
        return error
    assert valid is not None

    lookup = {
        "originalQuery": valid.original,
        "searchedQuery": valid.normalized,
        "aliasApplied": False,
    }
    messages = []
    if valid.normalized != valid.original:
        messages.append({"level": "info", "code": "input_normalized"})
    if valid.one_character:
        messages.append({"level": "info", "code": "one_character_query"})

    rows = lookup_raw_candidates(geocoder, valid, lang, country)
    alias = None
    if not rows:
        alias = ALIASES.get(valid.normalized)
        if alias:
            rows = lookup_raw_candidates(geocoder, ValidQuery(valid.original, alias, len(alias), False), lang, country)
            if rows:
                lookup = {
                    "originalQuery": valid.original,
                    "searchedQuery": alias,
                    "aliasApplied": True,
                    "aliasSource": "curated",
                }
                messages.append({"level": "info", "code": "query_alias_used"})

    curated_rows = []
    if not rows:
        curated_rows = CURATED_REAL_LOCATIONS.get(valid.normalized, [])
        if country:
            curated_rows = [row for row in curated_rows if row.get("country_code") == country.upper()]
        if curated_rows:
            messages.append({"level": "info", "code": "query_alias_used"})

    real_candidates = [normalize_candidate(row) for row in rows + curated_rows]
    fictional_candidate = FICTIONAL_LOCATIONS.get(valid.normalized.casefold())

    if len(real_candidates) == 1 and not fictional_candidate:
        response = {
            "status": "ok",
            "query": valid.original,
            "location": real_candidates[0],
            "lookup": lookup,
            "opportunities": [],
        }
    elif real_candidates or fictional_candidate:
        candidates = real_candidates[:]
        if fictional_candidate:
            candidates.append(fictional_candidate)

        if len(candidates) == 1 and candidates[0]["kind"] == "fictional_location":
            response = {
                "status": "ok",
                "query": valid.original,
                "location": candidates[0],
                "fictionalReport": {
                    "title": "Fictional Moon Report",
                    "summary": "This is an Easter egg, not real-world photography guidance.",
                },
            }
            messages.append({"level": "warning", "code": "fictional_result"})
        else:
            response = {
                "status": "ambiguous_location",
                "query": valid.original,
                "candidates": candidates,
            }
            if lookup["aliasApplied"]:
                response["lookup"] = lookup
    else:
        response = {
            "status": "location_not_found",
            "query": valid.original,
            "message": "We could not find that place on Earth or in the usual imaginary maps.",
        }

    if messages:
        response["messages"] = messages
    return response


def lookup_raw_candidates(
    geocoder: FixtureGeocoder | OpenMeteoGeocoder,
    valid: ValidQuery,
    lang: str | None,
    country: str | None,
) -> list[dict[str, Any]]:
    attempts = [
        (valid.normalized, lang, None),
        (valid.normalized, None, None),
        (valid.normalized, "en", None),
    ]
    if country:
        attempts.append((valid.normalized, lang, country))

    seen_attempts = set()
    for query, attempt_lang, attempt_country in attempts:
        key = (query, attempt_lang, attempt_country)
        if key in seen_attempts:
            continue
        seen_attempts.add(key)

        rows = geocoder.search(query, attempt_lang, attempt_country)
        if valid.one_character:
            rows = [row for row in rows if exact_one_character_match(row, valid.normalized)]
        if rows:
            return rows
    return []


def exact_one_character_match(row: dict[str, Any], query: str) -> bool:
    names = [row.get("name"), row.get("admin1"), row.get("admin2")]
    return any(isinstance(name, str) and name.casefold() == query.casefold() for name in names)


def normalize_candidate(row: dict[str, Any]) -> dict[str, Any]:
    country_code = row.get("country_code") or row.get("countryCode") or ""
    slug_name = slugify(row.get("name") or "location")
    slug_country = slugify(country_code)
    provider_id = row.get("id")
    provider = "curated" if str(provider_id).startswith("curated:") else "openmeteo"
    public_id = (
        provider_id
        if provider == "curated"
        else f"openmeteo:{slug_name}-{slug_country}"
        if slug_country
        else f"openmeteo:{provider_id}"
    )
    return {
        "kind": "real_location",
        "id": public_id,
        "displayName": display_name(row),
        "latitude": row.get("latitude"),
        "longitude": row.get("longitude"),
        "elevationMeters": row.get("elevation"),
        "timezone": row.get("timezone"),
        "countryCode": country_code,
        "provider": provider,
        "providerPlaceId": provider_id,
    }


def display_name(row: dict[str, Any]) -> str:
    parts = [row.get("name"), row.get("admin1"), row.get("country")]
    return ", ".join(str(part) for part in parts if part)


def slugify(value: Any) -> str:
    text = str(value).casefold().strip()
    chars = []
    previous_dash = False
    for char in text:
        if char.isalnum():
            chars.append(char)
            previous_dash = False
        elif not previous_dash:
            chars.append("-")
            previous_dash = True
    return "".join(chars).strip("-")


def run_cases(
    queries: list[str],
    geocoder: FixtureGeocoder | OpenMeteoGeocoder,
    lang: str | None,
    country: str | None,
    check: bool,
) -> int:
    failures = []
    outputs = []
    for query in queries:
        response = preview(query, geocoder, lang=lang, country=country)
        expected = EXPECTED_STATUS.get(query)
        if check and expected and response["status"] != expected:
            failures.append(
                {
                    "query": query,
                    "expected": expected,
                    "actual": response["status"],
                }
            )
        outputs.append(response)

    print(json.dumps(outputs, ensure_ascii=False, indent=2, sort_keys=True))
    if failures:
        print("\nContract check failures:", file=sys.stderr)
        print(json.dumps(failures, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1
    return 0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Run the Moon Service geocoding contract spike.",
    )
    parser.add_argument(
        "queries",
        nargs="*",
        help="Optional queries. Defaults to the built-in contract cases.",
    )
    parser.add_argument("--live", action="store_true", help="Call Open-Meteo instead of fixtures.")
    parser.add_argument("--lang", default=None, help="Optional BCP 47 display language hint.")
    parser.add_argument("--country", default=None, help="Optional ISO country hint.")
    parser.add_argument(
        "--no-check",
        action="store_true",
        help="Print responses without checking built-in expected statuses.",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    queries = args.queries or list(EXPECTED_STATUS)
    geocoder = OpenMeteoGeocoder() if args.live else FixtureGeocoder()
    check = not args.no_check and not args.queries and not args.live
    return run_cases(queries, geocoder, args.lang, args.country, check)


if __name__ == "__main__":
    raise SystemExit(main())
