import { expect, test } from "@playwright/test";

const ALL_FILTERS = {
  altitudeDegrees: { minimum: 0, maximum: 10 },
  azimuthDegrees: { excluded: { start: 280, end: 80 } },
  time: { mode: "light_bucket", buckets: ["night"] },
  namedPhases: ["waxing_crescent", "waning_crescent"],
  brightLimbOrientationDegrees: [{ start: 337.5, end: 22.5 }]
};
const STORAGE_KEY = "moonService.opportunityPreferences.v1";

test("requests filtered events and renders collapsed model-derived eclipse cards", async ({ page }) => {
  await seedPreferences(page, { version: 1, ...ALL_FILTERS });
  let releaseEvents = function () {};
  const eventGate = new Promise(resolve => { releaseEvents = function () { resolve(); }; });
  const calls = await captureApis(page, {
    ordinary: call => json(ordinaryResponse(locationIdFrom(call), requestedFilters(call))),
    events: async call => {
      await eventGate;
      return json(populatedEventResponse(call.body.locationId, call.body.preferences));
    }
  });

  await page.goto("/search?locationId=moon-service-3067696");
  const section = page.locator(".special-moon-events");
  await expect(page.getByLabel("Show lunar eclipses and supermoons")).toBeChecked();
  await expect(section.getByRole("heading", { name: "Special Moon events", level: 3 })).toBeVisible();
  await expect(section.getByRole("status")).toHaveText("Checking special Moon events…");
  await expect(section).toHaveAttribute("aria-busy", "true");
  await waitForEventCalls(calls, 1);
  expect(calls.events[0].method).toBe("POST");
  expect(calls.events[0].body).toEqual({
    locationId: "moon-service-3067696",
    eventHorizonMonths: 18,
    preferences: { version: 1, ...ALL_FILTERS }
  });
  expect(new URL(calls.events[0].url).search).toBe("");

  releaseEvents();
  await expect(section.getByRole("status")).toHaveText("3 special Moon events found.");
  const cards = section.locator(".special-moon-event-card");
  await expect(cards).toHaveCount(3);
  expect(await cards.evaluateAll(nodes => nodes.every(node => !node.hasAttribute("open")))).toBe(true);
  await expect(cards.getByRole("heading", { level: 4 })).toHaveText([
    /Total lunar eclipse.*Sep 1, 2026.*Best visible/,
    /Partial lunar eclipse.*2027.*Maximum/,
    /Penumbral lunar eclipse.*2027.*Maximum/
  ]);
  await expect(cards.locator(".special-moon-preference")).toHaveCount(0);
  const summaryWarnings = cards.locator("summary .special-moon-position-warning");
  await expect(summaryWarnings).toHaveCount(3);
  await expect(cards.first().locator("summary .special-moon-position-warning"))
    .toHaveText("1.4° altitude");
  await expect(cards.first().locator("summary .special-moon-position-warning"))
    .toHaveAttribute("data-tooltip", "Outside your altitude preference.");
  await expect(cards.nth(1).locator("summary .special-moon-position-warning")).toHaveText([
    "22.2° altitude", "225.0° SW"
  ]);
  await expect(cards.nth(1).locator("summary .special-moon-position-warning").nth(1))
    .toHaveAttribute("data-tooltip", "Outside your direction preference.");
  await expect(cards.nth(2).locator(".special-moon-position-warning")).toHaveCount(0);
  await expect(cards.locator(".special-moon-event-kind")).toHaveCount(0);
  await expect(cards.locator(".special-moon-summary-copy > span")).toHaveCount(12);
  await summaryWarnings.first().focus();
  await expect(summaryWarnings.first()).toBeFocused();
  expect(await summaryWarnings.first().evaluate(node =>
    parseFloat(getComputedStyle(node, "::after").width)
      <= node.parentElement.getBoundingClientRect().width)).toBe(true);
  await expect(cards.locator("summary canvas, summary img, summary svg, summary [role='img']"))
    .toHaveCount(0);
  await expect(cards.locator(".moon-path-panel")).toHaveCount(0);
  await expect(cards.locator(".moon-sample-marker-image[href^='data:image/png']")).toHaveCount(0);

  await cards.first().locator("summary").click();
  await expect(cards.first()).toHaveAttribute("open", "");
  await expect(cards.first().locator(".moon-path-summary .moon-path-label"))
    .toHaveText(["Start", "Best visible", "End"]);
  await expect(cards.first().locator(".moon-path-panel")).toHaveCount(1);
  await expect(cards.nth(1).locator(".moon-path-panel")).toHaveCount(0);
  const eclipseMarkers = cards.first().locator(".moon-path-panel .moon-sample-marker-image");
  expect(await eclipseMarkers.count()).toBeGreaterThanOrEqual(6);
  expect(new Set(await eclipseMarkers.evaluateAll(images =>
    images.map(image => image.getAttribute("href")))).size).toBeGreaterThanOrEqual(3);
  await expect(cards.first().locator(".moon-path-panel .moon-sample-dot")).toHaveCount(0);
  await cards.nth(1).locator("summary").click();
  await expect(cards.first().getByRole("img", { name: /Moon altitude and azimuth.*featured marker: Best visible/ })).toBeVisible();
  await expect(cards.nth(1).getByRole("img", { name: /Moon altitude and azimuth.*featured marker: Maximum/ })).toBeVisible();
  const range = cards.first().locator(".special-moon-eclipse-range");
  await expect(range).toHaveAttribute("aria-label", /covering 88% of the selected local Moon pass/);
  await expect(range.locator(".special-moon-eclipse-range-segment"))
    .toHaveAttribute("style", "left:0.00%;width:88.46%;");
  const stages = cards.first().locator(".special-moon-stage");
  await expect(stages).toHaveCount(7);
  await expect(stages.locator("figcaption strong")).toHaveText([
    "Penumbral begins", "Partial begins", "Total begins", "Maximum",
    "Total ends", "Partial ends", "Penumbral ends"
  ]);
  await expect(stages.locator(".special-moon-stage-best")).toHaveText("Best visible");
  await expect(stages.locator("canvas").first()).toHaveAttribute("aria-label", /north-up/);
  const renderedStages = await stages.locator("canvas").evaluateAll(canvases =>
    canvases.map(canvas => /** @type {HTMLCanvasElement} */ (canvas).toDataURL()));
  expect(new Set(renderedStages).size).toBeGreaterThan(3);
  await expect(cards.first().locator(".special-moon-event-facts dt")).toHaveText([
    "Objective maximum", "Visible window", "Best local time", "Moon position",
    "Ambient light", "Umbral obscuration"
  ]);
  await expect(cards.first().locator(".special-moon-event-facts .special-moon-position-warning"))
    .toHaveAttribute("data-tooltip", "Outside your altitude preference.");
  await expect(cards.locator(".special-moon-preference-details")).toHaveCount(0);
  await expect(cards.first()).toContainText("does not account for terrain, buildings, or trees");
  await expect(cards.nth(1)).toContainText("unusual crescent");
  await expect(cards.nth(2)).toContainText("change can be subtle");
  await expect(section.locator("a, button, img")).toHaveCount(0);
  expect(await sectionOrder(page)).toBe(true);
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
});

test("uses exact all-off preferences and a compact empty state", async ({ page }) => {
  const calls = await captureApis(page, {
    ordinary: call => {
      const response = ordinaryResponse(locationIdFrom(call), {});
      delete response.appliedPreferenceVersion;
      delete response.normalizedActiveFilters;
      return json(response);
    },
    events: call => json(emptyEventResponse(call.body.locationId))
  });

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForEventCalls(calls, 1);
  expect(calls.events[0].body).toEqual({
    locationId: "moon-service-3067696",
    eventHorizonMonths: 18,
    preferences: { version: 1 }
  });
  await expect(page.locator(".special-moon-events-status")).toHaveText(
    "No lunar eclipse or near-perigee full Moon is available for this location in the next 18 months."
  );
  await expect(page.locator(".special-moon-event-card")).toHaveCount(0);
  await expect(page.getByText("No opportunities found in the next 7 days")).toBeVisible();
});

test("changes the local control immediately, excludes it from APIs, and restores it on reset", async ({ page }) => {
  await seedPreferences(page, {
    version: 1,
    altitudeDegrees: ALL_FILTERS.altitudeDegrees,
    showSpecialMoonEvents: false
  });
  let releaseFirst = function () {};
  const firstResponse = new Promise(resolve => { releaseFirst = function () { resolve(); }; });
  let eventResponseCount = 0;
  const calls = await captureApis(page, {
    ordinary: call => json(ordinaryResponse(locationIdFrom(call), requestedFilters(call))),
    events: async call => {
      eventResponseCount += 1;
      if (eventResponseCount === 1) await firstResponse;
      return json(emptyEventResponse(call.body.locationId, call.body.preferences));
    }
  });

  await page.goto("/search?locationId=moon-service-3067696");
  const control = page.getByLabel("Show lunar eclipses and supermoons");
  await expect(control).not.toBeChecked();
  await expect(page.locator(".special-moon-events")).toHaveCount(0);
  expect(calls.events).toHaveLength(0);
  expect(calls.ordinary[0].body.preferences).toEqual({
    version: 1,
    altitudeDegrees: ALL_FILTERS.altitudeDegrees
  });

  const ordinaryCalls = calls.ordinary.length;
  await openPreferences(page);
  await page.getByLabel("Limit Moon altitude").uncheck();
  await control.check();
  await expect(control).toBeChecked();
  await waitForEventCalls(calls, 1);
  expect(calls.events[0].body.preferences).toEqual({
    version: 1,
    altitudeDegrees: ALL_FILTERS.altitudeDegrees
  });
  expect(calls.ordinary).toHaveLength(ordinaryCalls);
  await expect(page.locator(".special-moon-events-status")).toHaveText(
    "Checking special Moon events…"
  );

  await control.uncheck();
  await expect(page.locator(".special-moon-events")).toHaveCount(0);
  expect(calls.events).toHaveLength(1);
  expect(calls.ordinary).toHaveLength(ordinaryCalls);
  expect(JSON.parse(await page.evaluate(key => localStorage.getItem(key), STORAGE_KEY))).toEqual({
    version: 1,
    altitudeDegrees: ALL_FILTERS.altitudeDegrees,
    showSpecialMoonEvents: false
  });
  releaseFirst();

  await control.check();
  await waitForEventCalls(calls, 2);
  await expect(page.locator(".special-moon-events-status")).toHaveText(
    "No lunar eclipse or near-perigee full Moon is available for this location "
      + "in the next 18 months."
  );
  expect(calls.ordinary).toHaveLength(ordinaryCalls);

  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await expect(control).toBeChecked();
  await waitForEventCalls(calls, 3);
  expect(calls.events[2].body.preferences).toEqual({ version: 1 });
  await expect.poll(() => page.evaluate(key => localStorage.getItem(key), STORAGE_KEY)).toBeNull();
  await page.reload();
  await expect(control).toBeChecked();
  await waitForEventCalls(calls, 4);
});

test("does not request events without normalized ordinary filters", async ({ page }) => {
  const ordinary = ordinaryResponse("moon-service-3067696", {});
  delete ordinary.normalizedActiveFilters;
  const calls = await captureApis(page, {
    ordinary: () => json(ordinary),
    events: call => json(emptyEventResponse(call.body.locationId))
  });

  await page.goto("/search?locationId=moon-service-3067696");
  await expect(page.getByText("No opportunities found in the next 7 days")).toBeVisible();
  await expect(page.locator(".special-moon-events")).toHaveCount(0);
  expect(calls.events).toHaveLength(0);
});

test("keeps a disabled control in memory when storage fails", async ({ page }) => {
  await page.addInitScript(key => {
    const setItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (name, value) {
      if (name === key) throw new DOMException("blocked", "QuotaExceededError");
      return setItem.call(this, name, value);
    };
  }, STORAGE_KEY);
  const calls = await captureApis(page, {
    ordinary: call => json(ordinaryResponse(locationIdFrom(call), requestedFilters(call))),
    events: call => json(emptyEventResponse(call.body.locationId))
  });

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForEventCalls(calls, 1);
  await openPreferences(page);
  await page.getByLabel("Show lunar eclipses and supermoons").uncheck();
  await expect(page.locator("#preference-storage-notice")).toContainText(
    "Changes last only on this page"
  );
  await expect(page.getByLabel("Show lunar eclipses and supermoons")).not.toBeChecked();
  await expect(page.locator(".special-moon-events")).toHaveCount(0);
  expect(calls.events).toHaveLength(1);
});

test("localizes malformed, wrong-location, and failed event responses", async ({ page }) => {
  const calls = await captureApis(page, {
    ordinary: call => json(ordinaryResponse(locationIdFrom(call), {})),
    events: call => failureScenario(call.body.locationId)
  });

  for (const id of [
    "wrong-location", "wrong-timezone", "wrong-filters", "malformed-shadow",
    "malformed-path", "missing-path-shadow", "missing-contact", "missing-maximum", "unavailable"
  ]) {
    await page.goto("/search?locationId=" + id);
    await expect(page.locator(".special-moon-events-status")).toHaveText(
      "Special Moon events are temporarily unavailable. Moon opportunities are unchanged."
    );
    await expect(page.locator(".summary-count")).toHaveText(
      "0 ranked Moon passes · 0 candidate windows"
    );
  }
  expect(calls.events).toHaveLength(9);
});

test("ignores stale responses and keeps the collapsed cards keyboard-usable on narrow screens", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  let releaseOld = function () {};
  const oldGate = new Promise(resolve => { releaseOld = function () { resolve(); }; });
  const calls = await captureApis(page, {
    ordinary: call => {
      const query = new URL(call.url).searchParams.get("q");
      return json(ordinaryResponse(query ? "brno-cz" : locationIdFrom(call), {}));
    },
    events: async call => {
      if (call.body.locationId === "old-location") {
        await oldGate;
        return json(singleEventResponse("old-location", "total"));
      }
      return json(singleEventResponse("brno-cz", "penumbral"));
    }
  });

  await page.goto("/search?locationId=old-location");
  await waitForEventCalls(calls, 1);
  await page.locator("#location-input").fill("Brno");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await waitForEventCalls(calls, 2);
  const card = page.locator(".special-moon-event-card");
  await expect(card.getByRole("heading", { level: 4 })).toContainText("Penumbral lunar eclipse");
  releaseOld();
  await expect(card).toHaveCount(1);
  await card.locator("summary").focus();
  await page.keyboard.press("Enter");
  await expect(card).toHaveAttribute("open", "");
  await expect(card.locator(".special-moon-stage-canvas").first()).toBeVisible();
  await page.keyboard.press("Enter");
  await expect(card).not.toHaveAttribute("open", "");
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
});

async function captureApis(page, responders) {
  const calls = { ordinary: [], events: [] };
  await page.route("**/api/opportunities**", async route => {
    const call = apiCall(route.request());
    calls.ordinary.push(call);
    await fulfillSafely(route, await responders.ordinary(call));
  });
  await page.route("**/api/moon-events**", async route => {
    const call = apiCall(route.request());
    calls.events.push(call);
    await fulfillSafely(route, await responders.events(call));
  });
  return calls;
}

function apiCall(request) {
  return {
    method: request.method(),
    url: request.url(),
    body: request.method() === "POST" ? request.postDataJSON() : null
  };
}

async function fulfillSafely(route, response) {
  const request = route.request();
  try {
    await route.fulfill({
      status: response.status,
      contentType: "application/json",
      body: JSON.stringify(response.body)
    });
  } catch (error) {
    if (!request.failure()) throw error;
  }
}

function json(body, status = 200) {
  return { status: status, body: body };
}

async function waitForEventCalls(calls, count) {
  await expect.poll(() => calls.events.length).toBe(count);
}

function locationIdFrom(call) {
  return new URL(call.url).searchParams.get("locationId")
    || call.body?.locationId || "moon-service-3067696";
}

function requestedFilters(call) {
  return requestedPreferenceFields(call.body?.preferences || { version: 1 });
}

function ordinaryResponse(id, normalizedActiveFilters) {
  return {
    status: "ok",
    generatedAt: "2026-08-31T10:00:00Z",
    startsAt: "2026-08-31T10:00:00Z",
    endsAt: "2026-09-07T10:00:00Z",
    location: location(id),
    forecastHorizonDays: 7,
    candidateWindowsEvaluated: 0,
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: normalizedActiveFilters,
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

function responseBase(id, normalizedActiveFilters, events) {
  return {
    status: "ok",
    generatedAt: "2026-08-31T10:00:00Z",
    startsAt: "2026-09-01T20:15:00Z",
    endsAt: "2028-02-29T11:00:00Z",
    location: location(id),
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: normalizedActiveFilters,
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    events: events
  };
}

function populatedEventResponse(id, filters) {
  return responseBase(id, Object.fromEntries(
    Object.entries(filters).filter(([key]) => key !== "version")), [
    eclipse({
      id: "total-event", subtype: "total", startsAt: "2026-09-01T20:00:00Z",
      maximumAt: "2026-09-01T22:30:00Z", endsAt: "2026-09-02T01:00:00Z",
      visible: interval("2026-09-01T20:00:00Z", "2026-09-01T22:00:00Z"),
      display: interval("2026-09-01T20:15:00Z", "2026-09-01T22:00:00Z"),
      pathStartsAt: "2026-09-01T20:05:00Z", pathEndsAt: "2026-09-01T22:15:00Z",
      suggestedAt: "2026-09-01T21:30:00Z", altitude: 1.4, azimuth: 184,
      maximumAltitude: -2, obscuration: 100, assessment: assessment("does_not_match", [
        row("altitudeDegrees", "does_not_match"), row("azimuthDegrees", "matches")
      ]),
      phases: [
        phase("penumbral", "2026-09-01T20:00:00Z", "2026-09-02T01:00:00Z", "partly_visible"),
        phase("partial", "2026-09-01T21:00:00Z", "2026-09-02T00:00:00Z", "partly_visible"),
        phase("total", "2026-09-01T21:30:00Z", "2026-09-01T23:30:00Z", "not_visible")
      ],
      positions: [4.4, 2.6, 0.6, 0, -0.6, -2.6, -4.4],
      weather: { status: "available", forecastHourStartsAt: "2026-09-01T21:00:00Z",
        summary: "partly cloudy", cloudCoverPercent: 38,
        precipitationProbabilityPercent: 5 }
    }),
    eclipse({
      id: "partial-event", subtype: "partial", startsAt: "2027-03-01T01:00:00Z",
      maximumAt: "2027-03-01T03:00:00Z", endsAt: "2027-03-01T05:00:00Z",
      visible: interval("2027-03-01T01:00:00Z", "2027-03-01T05:00:00Z"),
      suggestedAt: "2027-03-01T03:00:00Z", altitude: 22.2, azimuth: 225,
      maximumAltitude: 22.2, obscuration: 72.4, assessment: assessment("does_not_match", [
        row("altitudeDegrees", "does_not_match"), row("azimuthDegrees", "does_not_match")
      ]),
      phases: [
        phase("penumbral", "2027-03-01T01:00:00Z", "2027-03-01T05:00:00Z", "fully_visible"),
        phase("partial", "2027-03-01T02:00:00Z", "2027-03-01T04:00:00Z", "fully_visible")
      ],
      positions: [4.2, 2.5, 0.4, -2.5, -4.2],
      weather: { status: "outside_forecast_horizon" }
    }),
    eclipse({
      id: "penumbral-event", subtype: "penumbral", startsAt: "2027-08-01T02:00:00Z",
      maximumAt: "2027-08-01T04:00:00Z", endsAt: "2027-08-01T06:00:00Z",
      visible: interval("2027-08-01T02:00:00Z", "2027-08-01T06:00:00Z"),
      suggestedAt: "2027-08-01T04:00:00Z", altitude: 7.8, azimuth: 250,
      maximumAltitude: 7.8, obscuration: 0, assessment: assessment("matches", [
        row("altitudeDegrees", "matches"), row("azimuthDegrees", "matches")
      ]),
      phases: [phase("penumbral", "2027-08-01T02:00:00Z", "2027-08-01T06:00:00Z",
        "fully_visible")],
      positions: [4.2, 1.7, -4.2], weather: { status: "temporarily_unavailable" }
    })
  ]);
}

function eclipse(value) {
  const display = value.display || value.visible;
  const instants = Array.from(new Set([
    ...value.phases.flatMap(item => [item.startsAt, item.endsAt]),
    value.maximumAt,
    value.suggestedAt
  ])).sort();
  return {
    id: value.id,
    kind: "lunar_eclipse",
    subtype: value.subtype,
    startsAt: value.startsAt,
    maximumAt: value.maximumAt,
    endsAt: value.endsAt,
    umbralObscurationPercent: value.obscuration,
    phases: value.phases,
    shadowSamples: instants.map((at, index) => shadowSample(
      at, value.positions[index], index === 0 ? null : 18)),
    moonAtMaximum: { altitudeDegrees: value.maximumAltitude, azimuthDegrees: value.azimuth },
    localVisibility: {
      status: value.maximumAltitude < 0 ? "partly_visible" : "fully_visible",
      intervals: [value.visible],
      selectedInterval: value.visible,
      displayInterval: {
        startsAt: display.startsAt,
        suggestedAt: value.suggestedAt,
        endsAt: display.endsAt,
        moon: { altitudeDegrees: value.altitude, azimuthDegrees: value.azimuth },
        sun: { altitudeDegrees: -12, lightBucket: "night" }
      },
      moonPath: eclipseMoonPath(value)
    },
    preferenceAssessment: value.assessment,
    weather: value.weather
  };
}

function shadowSample(at, right, pole) {
  return {
    at: at,
    moon: { altitudeDegrees: 8, azimuthDegrees: 190, northPoleTiltDegrees: pole },
    shadow: {
      centerRightMoonRadii: right,
      centerUpMoonRadii: 0.18,
      umbraRadiusMoonRadii: 1.6,
      penumbraRadiusMoonRadii: 3.4
    }
  };
}

function eclipseMoonPath(value) {
  const display = value.display || value.visible;
  const startsAt = value.pathStartsAt || shiftedInstant(value.visible.startsAt, -30);
  const endsAt = value.pathEndsAt
    || shiftedInstant(value.visible.endsAt, value.maximumAltitude < 0 ? 15 : 30);
  const instants = Array.from(new Set([
    startsAt, display.startsAt, value.suggestedAt, display.endsAt, endsAt, value.maximumAt,
    ...value.phases.flatMap(item => [item.startsAt, item.endsAt])
  ])).filter(at => Date.parse(startsAt) <= Date.parse(at)
    && Date.parse(at) <= Date.parse(endsAt)).sort();
  return { samples: instants.map((at, index) => ({
    at: at,
    altitudeDegrees: 1 + index * 3,
    azimuthDegrees: 180 + index * 2,
    moonPhaseAngleDegrees: 180,
    brightLimbTiltDegrees: 0,
    northPoleTiltDegrees: index === 0 ? null : 18,
    sunAltitudeDegrees: -12,
    sunAzimuthDegrees: 205,
    lightBucket: "night",
    shadow: {
      centerRightMoonRadii: 2.2 - index * 1.4,
      centerUpMoonRadii: 0.18,
      umbraRadiusMoonRadii: 1.6,
      penumbraRadiusMoonRadii: 3.4
    }
  })) };
}

function shiftedInstant(at, minutes) {
  return new Date(Date.parse(at) + minutes * 60_000).toISOString().replace(".000Z", "Z");
}

function phase(kind, startsAt, endsAt, status) {
  const visible = status === "not_visible" ? [] : [status === "fully_visible"
    ? interval(startsAt, endsAt)
    : interval(new Date(Date.parse(startsAt) + 300_000).toISOString(),
      new Date(Date.parse(endsAt) - 300_000).toISOString())];
  return { kind: kind, startsAt: startsAt, endsAt: endsAt,
    localVisibility: { status: status, intervals: visible } };
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

function emptyEventResponse(id, preferences) {
  return responseBase(id, requestedPreferenceFields(preferences), []);
}

function requestedPreferenceFields(preferences) {
  return Object.fromEntries(Object.entries(preferences || {})
    .filter(([key]) => key !== "version"));
}

function singleEventResponse(id, subtype) {
  const response = populatedEventResponse(id, {});
  const event = response.events.find(item => item.subtype === subtype);
  event.preferenceAssessment = assessment("no_active_preferences", []);
  response.normalizedActiveFilters = {};
  response.events = [event];
  return response;
}

function failureScenario(id) {
  if (id === "unavailable") {
    return json({ status: "temporarily_unavailable", generatedAt: "2026-08-31T10:00:00Z",
      message: "Location lookup is temporarily unavailable." }, 503);
  }
  const response = singleEventResponse(id, id === "missing-maximum" ? "partial" : "total");
  if (id === "wrong-location") response.location.id = "another-location";
  if (id === "wrong-timezone") response.location.timezone = "Europe/London";
  if (id === "wrong-filters") response.normalizedActiveFilters = { time: ALL_FILTERS.time };
  if (id === "malformed-shadow") response.events[0].shadowSamples.pop();
  if (id === "malformed-path") {
    response.events[0].localVisibility.moonPath.samples[1].at =
      response.events[0].localVisibility.moonPath.samples[0].at;
  }
  if (id === "missing-path-shadow") {
    delete response.events[0].localVisibility.moonPath.samples[0].shadow;
  }
  const missingAt = id === "missing-contact" ? response.events[0].phases[1].startsAt
    : id === "missing-maximum" ? response.events[0].maximumAt : null;
  if (missingAt) {
    response.events[0].localVisibility.moonPath.samples =
      response.events[0].localVisibility.moonPath.samples
        .filter(sample => sample.at !== missingAt);
  }
  return json(response);
}

function location(id) {
  return {
    id: id,
    kind: "real_location",
    displayName: id === "brno-cz" ? "Brno, Czechia" : "Prague, Czechia",
    timezone: "Europe/Prague",
    countryCode: "CZ"
  };
}

async function seedPreferences(page, value) {
  await page.addInitScript(({ key, stored }) => {
    const marker = key + ".test-seeded";
    if (localStorage.getItem(marker) !== null) return;
    localStorage.setItem(key, JSON.stringify(stored));
    localStorage.setItem(marker, "true");
  }, { key: STORAGE_KEY, stored: value });
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => node.hasAttribute("open"))) {
    await details.locator(":scope > summary").click();
  }
}

async function sectionOrder(page) {
  return page.evaluate(() => {
    const summary = document.querySelector(".result-summary");
    const notice = document.querySelector(".preference-notice");
    const events = document.querySelector(".special-moon-events");
    const ordinary = document.querySelector(
      ".current-moon-card, .opportunity-list, .status-panel, .rejected-details");
    const follows = (left, right) => Boolean(
      left.compareDocumentPosition(right) & Node.DOCUMENT_POSITION_FOLLOWING);
    return Boolean(summary && events && ordinary && follows(summary, events)
      && (!notice || follows(notice, events)) && follows(events, ordinary));
  });
}

async function horizontalOverflow(page) {
  return page.evaluate(() => document.documentElement.scrollWidth
    - document.documentElement.clientWidth);
}
