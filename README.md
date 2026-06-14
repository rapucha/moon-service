# Moon Service

Moon Service is an early-stage discovery and alert tool for photographers. The goal is to identify upcoming Moon photography opportunities near a selected city or location, with emphasis on a low Moon, useful ambient light, and promising weather.

The project is currently documentation-led. Backend, Android, database, and deployment scaffolding should wait until the MVP contracts and prototype boundaries are proven.

## MVP Direction

The favored first product is a zero-install web flow:

- Enter a city or location.
- Resolve ambiguous locations when needed.
- Show ranked Moon photography opportunities.
- Provide shareable result pages.
- Provide Atom/RSS feeds and `.ics` exports.
- Avoid mandatory accounts, cookies, saved server-side user profiles, and email alerts in v0.

Email alerts, native Android, saved personal preferences, terrain horizon modeling, and exact landmark alignment are deferred.

## Current Decisions

- Web-first MVP with a small backend later.
- Open-Meteo Geocoding as the first geocoding provider candidate.
- Raw Unicode location input, with curated alias/transliteration fallback for known provider gaps.
- Open-Meteo Weather as the first weather provider candidate.
- Astronomy Engine as the first ephemeris candidate for the thin scoring prototype.
- Browser `localStorage` may hold recent searches; the backend should not permanently store user locations in v0.

## Repository Map

- `AGENTS.md`: durable instructions for future coding-agent sessions.
- `docs/product-notes.md`: product stance, MVP scope, privacy posture.
- `docs/architecture.md`: architecture options and current web-first backend direction.
- `docs/api-shape.md`: first web/API contract.
- `docs/scoring-model.md`: v0 opportunity scoring model.
- `docs/ephemeris-research.md`: ephemeris decision and validation notes.
- `docs/weather-provider-research.md`: weather provider decision and validation notes.
- `docs/geocoding-research.md`: geocoding provider decision and validation notes.
- `docs/mvp-roadmap.md`: milestone plan and next steps.
- `scripts/geocoding_contract_spike.py`: retained Python spike for checking the v0 geocoding contract.
- `scripts/scoring_contract_spike.py`: retained Python spike for checking the v0 scoring contract with fixture data.

## Geocoding Contract Spike

Run the repeatable fixture-based check:

```bash
python3 -B scripts/geocoding_contract_spike.py
```

Run selected queries:

```bash
python3 -B scripts/geocoding_contract_spike.py Prague 東京 Å
```

Optionally revalidate against live Open-Meteo Geocoding:

```bash
python3 -B scripts/geocoding_contract_spike.py --live --lang ja 東京 京都 大阪
```

The default fixture mode is intended for stable local checks. Live mode is for detecting provider behavior changes.

## Scoring Contract Spike

Run the fixture-based scoring check:

```bash
python3 -B scripts/scoring_contract_spike.py
```

Run with a different minimum score:

```bash
python3 -B scripts/scoring_contract_spike.py --min-score 80
```

This script does not calculate real ephemeris or fetch live weather. It uses fixed Moon, Sun, and weather samples to exercise hard filters, score components, ranking, explanation text, and API-shaped output.

## Verification

For documentation-only changes:

```bash
git diff --check
```

For the current Python spike:

```bash
python3 -B scripts/geocoding_contract_spike.py
python3 -B -m py_compile scripts/geocoding_contract_spike.py
python3 -B scripts/scoring_contract_spike.py
python3 -B -m py_compile scripts/scoring_contract_spike.py
```
