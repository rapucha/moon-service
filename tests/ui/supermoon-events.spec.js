import { expect, test } from "@playwright/test";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const FILTERS = {
  altitudeDegrees: { minimum: 20, maximum: 35 },
  azimuthDegrees: { excluded: { start: 280, end: 80 } },
  time: { mode: "local_clock", window: { start: "18:00", end: "23:00" } },
  namedPhases: ["waxing_crescent", "waning_crescent"],
  brightLimbOrientationDegrees: [{ start: 337.5, end: 22.5 }]
};

test("renders visible and retained no-local supermoons in the shared event section", async ({ page }) => {
  await seedPreferences(page, { version: 1, ...FILTERS });
  /** @type {any} */
  var eventRequest;
  await routeOrdinary(page);
  await page.route("**/api/moon-events", async route => {
    eventRequest = route.request().postDataJSON();
    await fulfill(route, eventResponse("moon-service-3067696", [
      visibleSupermoon("visible-supermoon"),
      noLocalSupermoon("no-local-supermoon")
    ]));
  });

  await page.goto("/search?locationId=moon-service-3067696");
  await expect(page.getByLabel("Show lunar eclipses and supermoons")).toBeChecked();
  const section = page.locator(".special-moon-events");
  await expect(section.getByRole("status")).toHaveText("2 special Moon events found.");
  expect(eventRequest).toEqual({
    locationId: "moon-service-3067696",
    preferences: { version: 1, ...FILTERS }
  });
  expect(eventRequest).not.toHaveProperty("showSpecialMoonEvents");

  const cards = section.locator(".special-moon-event-card");
  await expect(cards).toHaveCount(2);
  expect(await cards.evaluateAll(nodes => nodes.every(node => !node.hasAttribute("open")))).toBe(true);
  await expect(cards.getByRole("heading", { level: 4 })).toHaveText([
    /Full Moon.*Supermoon.*Sep 1, 2026.*Best visible/,
    /Full Moon.*Supermoon.*2027.*Not visible from Prague, Czechia during the searched dates\./
  ]);
  await expect(cards.first().locator("summary .special-moon-position-warning"))
    .toHaveText("15.0° altitude");
  await expect(cards.first().locator("summary .special-moon-position-warning"))
    .toHaveAttribute("data-tooltip", "Outside your altitude preference.");
  await expect(cards.first().locator("summary .special-moon-position-warning")).toHaveCount(1);
  await expect(cards.nth(1).locator(".special-moon-position-warning")).toHaveCount(0);
  await expect(section.locator("a, button, img, svg, canvas")).toHaveCount(0);

  await cards.first().locator("summary").click();
  await expect(cards.first().locator(".special-moon-event-description")).toHaveText(
    "A full Moon near perigee under Moon Service definition 1. “Supermoon” is an informal term."
  );
  await expect(cards.first().locator(".special-moon-event-facts dt")).toHaveText([
    "Exact full Moon", "Distance at peak", "Near-perigee closeness", "Visible window",
    "Best local time", "Moon position", "Ambient light"
  ]);
  await expect(cards.first().locator(".special-moon-event-facts dd").nth(1))
    .toContainText(/355[,.\s]?000 km/);
  await expect(cards.first().locator(".special-moon-event-facts dd").nth(2))
    .toHaveText("90% · Moon Service definition: at least 90%");
  await expect(cards.first().locator(".special-moon-weather"))
    .toHaveText("Forecast at the best time: clear.");
  await expect(cards.first()).toContainText("does not account for terrain, buildings, or trees");

  await cards.nth(1).locator("summary").click();
  await expect(cards.nth(1).locator(".special-moon-event-facts dt")).toHaveText([
    "Exact full Moon", "Distance at peak", "Near-perigee closeness"
  ]);
  await expect(cards.nth(1).locator(".special-moon-weather, .special-moon-events-caveat"))
    .toHaveCount(0);
  expect(await page.evaluate(() => document.documentElement.scrollWidth
    - document.documentElement.clientWidth)).toBeLessThanOrEqual(1);
});

test("rejects malformed full-Moon union members without affecting ordinary results", async ({ page }) => {
  await seedPreferences(page, { version: 1, ...FILTERS });
  await routeOrdinary(page);
  await page.route("**/api/moon-events", async route => {
    const id = route.request().postDataJSON().locationId;
    const event = id === "weather-without-viewing"
      ? noLocalSupermoon(id) : visibleSupermoon(id);
    if (id === "bad-qualifier") event.qualifiers[0].closeness = 0.91;
    if (id === "weather-without-viewing") {
      event.weather = { status: "outside_forecast_horizon" };
    }
    if (id === "ignored-filter-row") {
      event.preferenceAssessment.filters.push({ filter: "time", status: "matches" });
    }
    if (id === "unknown-kind") event.kind = "solar_eclipse";
    await fulfill(route, eventResponse(id, [event]));
  });

  for (const id of [
    "bad-qualifier", "weather-without-viewing", "ignored-filter-row", "unknown-kind"
  ]) {
    await page.goto("/search?locationId=" + id);
    await expect(page.locator(".special-moon-events-status")).toHaveText(
      "Special Moon events are temporarily unavailable. Moon opportunities are unchanged."
    );
    await expect(page.getByText("No opportunities found in the next 7 days")).toBeVisible();
  }
});

async function routeOrdinary(page) {
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const body = request.method() === "POST" ? request.postDataJSON() : {};
    const id = body.locationId || new URL(request.url()).searchParams.get("locationId");
    await fulfill(route, ordinaryResponse(id, requestedFilters(body.preferences)));
  });
}

async function fulfill(route, body) {
  await route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(body)
  });
}

function ordinaryResponse(id, filters) {
  return {
    status: "ok",
    generatedAt: "2026-08-31T10:00:00Z",
    startsAt: "2026-08-31T10:00:00Z",
    endsAt: "2026-09-07T10:00:00Z",
    location: location(id),
    forecastHorizonDays: 7,
    candidateWindowsEvaluated: 0,
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: filters,
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    opportunities: [],
    rejected: [{
      startsAt: "2026-09-01T00:00:00Z",
      endsAt: "2026-09-01T01:00:00Z",
      reason: "Fixture rejected window."
    }]
  };
}

function eventResponse(id, events) {
  return {
    status: "ok",
    generatedAt: "2026-08-31T10:00:00Z",
    startsAt: "2026-08-31T10:00:00Z",
    endsAt: "2028-02-29T11:00:00Z",
    location: location(id),
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: FILTERS,
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    events: events
  };
}

function visibleSupermoon(id) {
  return {
    id: id,
    kind: "full_moon",
    peakAt: "2026-09-01T20:00:00Z",
    qualifiers: [nearPerigee(355_000, 0.9)],
    localViewing: {
      intervals: [interval("2026-09-01T21:00:00Z", "2026-09-01T23:00:00Z")],
      selectedInterval: interval("2026-09-01T21:00:00Z", "2026-09-01T23:00:00Z"),
      displayInterval: {
        startsAt: "2026-09-01T21:00:00Z",
        suggestedAt: "2026-09-01T21:00:00Z",
        endsAt: "2026-09-01T23:00:00Z",
        moon: { altitudeDegrees: 15, azimuthDegrees: 120 },
        sun: { altitudeDegrees: -18, lightBucket: "night" }
      }
    },
    preferenceAssessment: assessment("does_not_match", [
      row("altitudeDegrees", "does_not_match"), row("azimuthDegrees", "matches")
    ]),
    weather: {
      status: "available",
      forecastHourStartsAt: "2026-09-01T21:00:00Z",
      summary: "clear",
      cloudCoverPercent: 8,
      precipitationProbabilityPercent: 0
    }
  };
}

function noLocalSupermoon(id) {
  return {
    id: id,
    kind: "full_moon",
    peakAt: "2027-01-22T12:17:50.281Z",
    qualifiers: [nearPerigee(352_500, 0.95)],
    preferenceAssessment: assessment("not_applicable", [
      row("altitudeDegrees", "not_applicable"),
      row("azimuthDegrees", "not_applicable")
    ])
  };
}

function nearPerigee(distance, closeness) {
  return {
    kind: "near_perigee",
    definitionVersion: 1,
    closeness: closeness,
    distanceKilometersAtPeak: distance,
    perigeeDistanceKilometers: 350_000,
    apogeeDistanceKilometers: 400_000
  };
}

function interval(startsAt, endsAt) {
  return { startsAt: startsAt, endsAt: endsAt };
}

function assessment(overall, filters) {
  return { overall: overall, filters: filters };
}

function row(filter, status) {
  return { filter: filter, status: status };
}

function requestedFilters(preferences) {
  return Object.fromEntries(Object.entries(preferences || {})
    .filter(([key]) => key !== "version"));
}

function location(id) {
  return {
    id: id,
    kind: "real_location",
    displayName: "Prague, Czechia",
    timezone: "Europe/Prague",
    countryCode: "CZ"
  };
}

async function seedPreferences(page, value) {
  await page.addInitScript(({ key, stored }) => {
    localStorage.setItem(key, JSON.stringify(stored));
  }, { key: STORAGE_KEY, stored: value });
}
