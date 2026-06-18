#!/usr/bin/env python3
"""Check contract parity across the retained scoring prototypes.

This is a local verification harness, not backend scaffolding. It compares:

- the retained Python fixture scoring spike,
- the source-file JVM ephemeris/scoring prototype,
- the Maven JVM scoring prototype.

The Python spike and source-file JVM prototype are historical references, so
they are expected to match field shape and vocabulary rather than exact
opportunity timing. The Maven JVM prototype is the active contract target for
natural low-Moon windows.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
REQUIRED_TOP_LEVEL = {
    "status",
    "location",
    "forecastHorizonDays",
    "opportunities",
    "rejected",
    "messages",
}
REQUIRED_OPPORTUNITY = {
    "id",
    "startsAt",
    "endsAt",
    "localTimeZone",
    "score",
    "confidence",
    "components",
    "moon",
    "sun",
    "weather",
    "exposureBalance",
    "reason",
    "links",
}
EXPOSURE_LABELS = {
    "moon_detail_easy_foreground_supported",
    "thin_crescent_visible_but_subtle",
    "moon_bright_foreground_risk",
    "balanced",
    "foreground_likely_dark",
}
REJECTION_REASONS = {
    "moon_below_horizon",
    "moon_too_high_for_low_moon_mode",
    "overcast",
    "high_precipitation_probability",
    "low_visibility",
    "below_minimum_score",
}


def main() -> int:
    python_response = run_json([
        sys.executable,
        "-B",
        "scripts/scoring_contract_spike.py",
        "--min-score",
        "50",
    ])
    source_response = run_json(source_file_command())
    maven_response = run_json([
        "mvn",
        "-q",
        "org.codehaus.mojo:exec-maven-plugin:3.3.0:java",
        "-Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype",
        "-Dexec.args=--request fixtures/prague-preview-request.json",
    ], cwd=ROOT / "prototypes/jvm-scoring")

    for name, response in {
        "python": python_response,
        "source-jvm": source_response,
        "maven-jvm": maven_response,
    }.items():
        assert_contract_shape(name, response)

    assert_maven_refactor_contract(maven_response)
    print("prototype contract parity ok")
    print("python top opportunity:", python_response["opportunities"][0]["id"])
    print("jvm top opportunity:", maven_response["opportunities"][0]["id"])
    return 0


def run_json(command: list[str], cwd: Path = ROOT) -> dict[str, Any]:
    completed = subprocess.run(
        command,
        cwd=cwd,
        check=True,
        text=True,
        capture_output=True,
    )
    return json.loads(completed.stdout)


def source_file_command() -> list[str]:
    classpath = os.pathsep.join(str(path) for path in astronomy_classpath())
    return [
        "java",
        "-cp",
        classpath,
        "prototypes/jvm-ephemeris/MoonWindowPrototype.java",
        "--location",
        "prague-cz",
        "--start",
        "2026-06-29",
        "--days",
        "7",
        "--limit",
        "5",
    ]


def astronomy_classpath() -> list[Path]:
    home = Path.home()
    candidates = [
        home / ".m2/repository/io/github/cosinekitty/astronomy/2.1.19/astronomy-2.1.19.jar",
        home / ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib-jdk8/1.6.10/kotlin-stdlib-jdk8-1.6.10.jar",
        home / ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib-jdk7/1.6.10/kotlin-stdlib-jdk7-1.6.10.jar",
        home / ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib/1.6.10/kotlin-stdlib-1.6.10.jar",
        home / ".m2/repository/org/jetbrains/kotlin/kotlin-stdlib-common/1.6.10/kotlin-stdlib-common-1.6.10.jar",
    ]
    fallback = [
        Path("/tmp/astronomy-2.1.19.jar"),
        Path("/tmp/kotlin-stdlib-jdk8-1.6.10.jar"),
        Path("/tmp/kotlin-stdlib-jdk7-1.6.10.jar"),
        Path("/tmp/kotlin-stdlib-1.6.10.jar"),
        Path("/tmp/kotlin-stdlib-common-1.6.10.jar"),
    ]
    if all(path.exists() for path in candidates):
        return candidates
    if all(path.exists() for path in fallback):
        return fallback
    missing = [str(path) for path in candidates if not path.exists()]
    raise SystemExit("Missing Astronomy Engine jars. Run mvn test in prototypes/jvm-scoring first. Missing: " + ", ".join(missing))


def assert_contract_shape(name: str, response: dict[str, Any]) -> None:
    missing_top = REQUIRED_TOP_LEVEL - response.keys()
    if missing_top:
        raise AssertionError(f"{name}: missing top-level fields {sorted(missing_top)}")
    if response["status"] != "ok":
        raise AssertionError(f"{name}: expected status ok, got {response['status']!r}")
    if response["location"]["id"] != "openmeteo:prague-cz":
        raise AssertionError(f"{name}: unexpected location id {response['location']['id']!r}")
    if response["forecastHorizonDays"] != 7:
        raise AssertionError(f"{name}: unexpected forecast horizon {response['forecastHorizonDays']!r}")
    if not response["opportunities"]:
        raise AssertionError(f"{name}: expected at least one opportunity")

    for opportunity in response["opportunities"]:
        missing_opportunity = REQUIRED_OPPORTUNITY - opportunity.keys()
        if missing_opportunity:
            raise AssertionError(f"{name}: missing opportunity fields {sorted(missing_opportunity)}")
        if "suggestedAt" not in opportunity and "peaksAt" not in opportunity:
            raise AssertionError(f"{name}: opportunity must include suggestedAt or historical peaksAt")
        label = opportunity["exposureBalance"]["label"]
        if label not in EXPOSURE_LABELS:
            raise AssertionError(f"{name}: unknown exposure label {label!r}")

    for rejected in response["rejected"]:
        for reason in rejected["reasons"]:
            if reason not in REJECTION_REASONS:
                raise AssertionError(f"{name}: unknown rejection reason {reason!r}")

    message_codes = {message["code"] for message in response["messages"]}
    if "local_horizon_not_modelled" not in message_codes:
        raise AssertionError(f"{name}: missing local horizon message")


def assert_maven_refactor_contract(response: dict[str, Any]) -> None:
    forbidden_top_level = {"sampleStepMinutes", "samplesEvaluated", "minScore"}
    present_forbidden = forbidden_top_level & response.keys()
    if present_forbidden:
        raise AssertionError(f"maven-jvm: obsolete top-level fields present {sorted(present_forbidden)}")
    if response.get("candidateWindowsEvaluated", 0) <= 0:
        raise AssertionError("maven-jvm: expected candidateWindowsEvaluated to be positive")
    for opportunity in response["opportunities"]:
        if "suggestedAt" not in opportunity:
            raise AssertionError("maven-jvm: expected suggestedAt in each opportunity")
        if "peaksAt" in opportunity:
            raise AssertionError("maven-jvm: obsolete peaksAt field present")
        if opportunity["weather"].get("sourceResolution") != "hourly":
            raise AssertionError("maven-jvm: expected hourly weather sourceResolution")


if __name__ == "__main__":
    raise SystemExit(main())
