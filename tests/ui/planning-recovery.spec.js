import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const LOCATION = {
  id: "moon-service-3067696",
  kind: "real_location",
  displayName: "Prague, Czechia",
  timezone: "Europe/Prague",
  countryCode: "CZ"
};
const ORDINARY_EMPTY = {
  status: "ok",
  location: LOCATION,
  forecastHorizonDays: 7,
  candidateWindowsEvaluated: 14,
  opportunities: [],
  emptyReason: { code: "no_opportunities", text: "No weather-backed opportunity was found." }
};
const ORDINARY_NONEMPTY = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
));
const MEMORY_PREFERENCES = {
  version: 1,
  altitudeDegrees: { minimum: 3, maximum: 18 },
  azimuthDegrees: { included: { start: 330, end: 30 } },
  time: { mode: "light_bucket", buckets: ["civil_twilight", "night"] },
  namedPhases: ["full_moon"],
  brightLimbOrientationDegrees: [{ start: 337.5, end: 22.5 }]
};
const PLANNING_SUCCESS = {
  status: "ok",
  generatedAt: "2026-10-24T12:00:00Z",
  startsAt: "2026-10-24T12:00:00Z",
  endsAt: "2027-10-24T12:00:00Z",
  planningHorizonDays: 365,
  location: LOCATION,
  appliedPreferenceVersion: 1,
  normalizedActiveFilters: {},
  ignoredPreferenceFields: [],
  ignoredPreferenceFieldCount: 0,
  additionalIgnoredPreferenceFieldCount: 0,
  nextPlanningWindow: {
    id: "prague-planning-window",
    windowKind: "moonrise_low",
    startsAt: "2027-08-20T18:53:53.585937500Z",
    suggestedAt: "2027-08-20T18:59:59.796875Z",
    endsAt: "2027-08-20T19:04:59.796875Z",
    localTimeZone: "Europe/Prague",
    moon: {
      altitudeDegrees: 8.4,
      azimuthDegrees: 82.3,
      illuminationPercent: 89,
      phaseAngleDegrees: 218.6,
      brightLimbTiltDegrees: 274.8,
      northPoleTiltDegrees: 31.2,
      phaseName: "waning_gibbous"
    },
    sun: {
      altitudeDegrees: -4.7,
      azimuthDegrees: 286.4,
      lightBucket: "civil_twilight"
    },
    moonPass: {
      id: "prague-pass-2027-08-20",
      startsAt: "2027-08-20T18:30:00Z",
      endsAt: "2027-08-20T20:30:00Z",
      path: {
        start: planningPoint("2027-08-20T18:30:00Z", 0, 76, -8, 278, "start"),
        end: planningPoint("2027-08-20T20:30:00Z", 0, 101, -18, 300, "end"),
        samples: [
          planningPoint("2027-08-20T18:30:00Z", 0, 76, -8, 278, "start"),
          planningPoint("2027-08-20T18:59:59.796100Z", 30, 81.9, -5.1, 285.9, "path"),
          planningPoint("2027-08-20T18:59:59.796875Z", 7.9, 82, -5, 286, "path"),
          planningPoint("2027-08-20T19:30:00Z", 13.8, 89, -10, 292, "path"),
          planningPoint("2027-08-20T20:30:00Z", 0, 101, -18, 300, "end")
        ]
      }
    }
  }
};

test("offers recovery only for an exact successful empty real-location result", async ({ page }) => {
  let ordinary = /** @type {any} */ (ORDINARY_EMPTY);
  const calls = await captureApi(page, {
    ordinary: () => ({ status: 200, body: ordinary })
  });

  await page.goto("/search?q=Prague");
  await waitForOrdinary(page, calls, 1);
  await expect(page.getByRole("button", { name: "Find the next matching Moon date" })).toBeVisible();
  const recovery = page.locator(".planning-recovery");
  await expect(recovery).toContainText(
    "Search ahead using Moon position, local time, and ambient light. Weather is not considered."
  );
  const button = recovery.getByRole("button");
  const descriptionId = await button.getAttribute("aria-describedby");
  expect(descriptionId).toBeTruthy();
  await expect(page.locator("#" + descriptionId)).toBeVisible();
  expect(await recovery.evaluate(node => node.closest("details") === null)).toBe(true);
  expect(planningCalls(calls)).toHaveLength(0);

  ordinary = ORDINARY_NONEMPTY;
  await searchFor(page, "Nonempty");
  await waitForOrdinary(page, calls, 2);
  await expect(recovery).toHaveCount(0);

  ordinary = { ...ORDINARY_EMPTY, location: { ...LOCATION, kind: "fictional_location" } };
  await searchFor(page, "Fictional");
  await waitForOrdinary(page, calls, 3);
  await expect(recovery).toHaveCount(0);

  ordinary = { status: "temporarily_unavailable", message: "Try again shortly." };
  await searchFor(page, "Unavailable");
  await waitForOrdinary(page, calls, 4);
  await expect(recovery).toHaveCount(0);

  ordinary = {
    status: "ok",
    location: { id: LOCATION.id, kind: "real_location" },
    opportunities: []
  };
  await searchFor(page, "Malformed");
  await waitForOrdinary(page, calls, 5);
  await expect(recovery).toHaveCount(0);
});

test("posts the live preference snapshot once without changing browser state", async ({ page }) => {
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
    const originalSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (candidateKey, candidateValue) {
      if (candidateKey === key) throw new Error("Storage write blocked");
      return originalSetItem.call(this, candidateKey, candidateValue);
    };
  }, { key: STORAGE_KEY, value: MEMORY_PREFERENCES });
  let releasePlanning = () => {};
  const planningGate = new Promise(resolve => {
    releasePlanning = () => resolve(undefined);
  });
  const calls = await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: async () => {
      await planningGate;
      return { status: 200, body: PLANNING_SUCCESS };
    }
  });

  await page.goto("/search?q=Prague");
  await waitForOrdinary(page, calls, 1);
  await openPreferences(page);
  const minimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  await minimum.focus();
  await page.keyboard.press("ArrowUp");
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForOrdinary(page, calls, 2);
  const livePreferences = ordinaryCalls(calls)[1].body.preferences;
  expect(livePreferences).not.toEqual(MEMORY_PREFERENCES);
  const initialUrl = page.url();
  const initialHistoryLength = await page.evaluate(() => window.history.length);

  await page.getByRole("button", { name: "Find the next matching Moon date" })
    .evaluate(button => {
      const nativeButton = /** @type {HTMLButtonElement} */ (button);
      nativeButton.click();
      nativeButton.click();
    });
  await expect.poll(() => planningCalls(calls).length).toBe(1);
  const planning = planningCalls(calls)[0];
  expect(planning.method).toBe("POST");
  expect(planning.path).toBe("/api/opportunities/planning");
  expect(planning.rawBody).toBe(JSON.stringify({
    locationId: LOCATION.id,
    preferences: livePreferences
  }));
  expect(planning.body.q).toBeUndefined();
  await expect(page.getByRole("button", { name: "Find", exact: true })).toBeEnabled();
  await expect(page.locator("#workspace-title")).toHaveText("Next matching Moon date");
  await expect(page.locator("#workspace-title")).toBeFocused();
  await expect(page.locator(".planning-weather-notice")).toHaveText("Weather is not considered");
  await expect(page.locator(".workspace-meta:not(.planning-weather-notice)")).toBeHidden();
  await expect(page.locator("#results")).toHaveAttribute("aria-live", "polite");
  await expect(page.getByRole("heading", {
    name: "Searching for the next matching Moon date"
  })).toBeVisible();
  await expect(page.locator("#results")).not.toContainText("365");
  expect(page.url()).toBe(initialUrl);
  expect(await page.evaluate(() => window.history.length)).toBe(initialHistoryLength);
  expect(await storedPreferences(page)).toEqual(MEMORY_PREFERENCES);

  releasePlanning();
  const card = page.locator(".planning-date-card");
  await expect(card).toBeVisible();
  await expect(page.locator("#results")).toHaveAttribute("aria-busy", "false");
  await expect(card).toContainText("Europe/Prague");
  await expect(card).toContainText("8.4°");
  await expect(card).toContainText("82.3° E");
  await expect(card).toContainText("Waning gibbous");
  await expect(card).toContainText("274.8° clockwise from local zenith");
  await expect(card).toContainText("-4.7°");
  await expect(card).toContainText("Civil twilight");
  await expect(card).toContainText("Moon pass context");
  await expect(card).not.toContainText(/weather|score|confidence|ranking|photo|sky/i);
  await expect(page.locator(".moon-pass-card, .pass-choice-card")).toHaveCount(0);
  await expect(card.getByRole("button")).toHaveCount(0);
  await expect(card.getByRole("link")).toHaveCount(0);
  await expect(card.locator(".moon-path-panel")).toHaveCount(1);
  await expect(card.locator(".sky-picture-details")).toHaveCount(0);
  for (const mode of ["desktop", "mobile"]) {
    const chart = card.locator(".moon-altitude-chart.altitude-chart-" + mode);
    await expect(chart.locator(".moon-sample-marker.is-suggested")).toHaveCount(1);
    await expect(chart.locator(".moon-sample-marker-label")).toHaveText("Suggested");
    await expect(chart.locator(
      ".moon-sample-marker[data-at='2027-08-20T18:59:59.796875Z']"
    )).toHaveAttribute("aria-label", "Suggested Moon position, 8.4° altitude");
    await expect(chart.locator(
      ".moon-sample-marker[data-at='2027-08-20T18:59:59.796100Z']"
    )).toHaveCount(1);
    await expect(chart.locator(
      ".moon-sample-marker[data-at='2027-08-20T18:30:00Z']"
    )).toHaveCount(1);
    await expect(chart.locator(
      ".moon-sample-marker[data-at='2027-08-20T20:30:00Z']"
    )).toHaveCount(1);
  }
});

test("uses the authoritative direction mask and reveals positive-Sun context", async ({ page }) => {
  const response = structuredClone(PLANNING_SUCCESS);
  response.normalizedActiveFilters = {
    azimuthDegrees: { included: { start: 80, end: 95 } }
  };
  response.nextPlanningWindow.moonPass.azimuthMatchIntervals = [{
    startsAt: "2027-08-20T18:50:00Z",
    endsAt: "2027-08-20T19:40:00Z"
  }];
  response.nextPlanningWindow.sun.altitudeDegrees = 6;
  response.nextPlanningWindow.sun.azimuthDegrees = 286;
  response.nextPlanningWindow.sun.lightBucket = "daylight";
  await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: () => ({ status: 200, body: response })
  });
  await page.goto("/search?q=Prague");
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();

  const card = page.locator(".planning-date-card");
  await expect(card.locator(".azimuth-preference-excluded")).toHaveCount(4);
  await expect(card.locator(".sky-picture-details summary")).toHaveText([
    "Sun passSun altitude and direction across the same Moon pass",
    "Sky domeSun and Moon positions at the suggested time"
  ]);
  expect(await card.locator(".sky-picture-details").evaluateAll(details =>
    details.map(detail => /** @type {HTMLDetailsElement} */ (detail).open)
  )).toEqual([false, false]);
  await card.getByText("Sky dome", { exact: true }).click();
  await expect(card.locator(".sky-dome-chart")).toHaveAccessibleName(
    /Sun .* altitude, .* azimuth .*; Moon .* altitude, .* azimuth .*; .* angular separation/
  );
  await expect(card).not.toContainText(/Alternative|Option|Rank|score|confidence|weather|photo/i);
});

test("shows a later Sun pass without a sky dome at the negative-Sun suggestion", async ({ page }) => {
  const response = structuredClone(PLANNING_SUCCESS);
  const sunlitEnd = planningPoint(
    "2027-08-20T20:30:00Z", 0, 101, 4.1, 300, "end"
  );
  const samples = response.nextPlanningWindow.moonPass.path.samples;
  response.nextPlanningWindow.moonPass.path.end = sunlitEnd;
  samples[samples.length - 1] = sunlitEnd;
  await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: () => ({ status: 200, body: response })
  });
  await page.goto("/search?q=Prague");
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();

  const card = page.locator(".planning-date-card");
  await expect(card.locator(".sky-picture-details summary")).toHaveText([
    "Sun passSun altitude and direction across the same Moon pass"
  ]);
  await expect(card.locator(".sun-altitude-chart")).toHaveCount(2);
  await expect(card.locator(".sky-dome-chart")).toHaveCount(0);
  expect(await card.locator(".sky-picture-details").evaluateAll(details =>
    details.map(detail => /** @type {HTMLDetailsElement} */ (detail).open)
  )).toEqual([false]);
});

test("explains a phase with no defined bright-limb angle", async ({ page }) => {
  const response = JSON.parse(JSON.stringify(PLANNING_SUCCESS));
  response.nextPlanningWindow.moon.brightLimbTiltDegrees = null;
  await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: () => ({ status: 200, body: response })
  });
  await page.goto("/search?q=Prague");
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();

  await expect(page.locator(".planning-date-card")).toContainText(
    "Not defined for this Moon phase."
  );
});

test("supports native keyboard activation exactly once per result", async ({ page }) => {
  const calls = await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: () => ({ status: 200, body: boundedEmpty(42) })
  });
  await page.goto("/search?q=Prague");

  for (const key of ["Enter", "Space"]) {
    const button = page.getByRole("button", { name: "Find the next matching Moon date" });
    await button.focus();
    await page.keyboard.press(key);
    const expected = key === "Enter" ? 1 : 2;
    await expect.poll(() => planningCalls(calls).length).toBe(expected);
    await page.waitForTimeout(50);
    expect(planningCalls(calls)).toHaveLength(expected);
    if (key === "Enter") await searchFor(page, "Prague");
  }
});

test("lets a new ordinary lookup supersede a planning request", async ({ page }) => {
  const calls = await captureApi(page, {
    ordinary: call => ({
      status: 200,
      body: { ...ORDINARY_EMPTY, location: {
        ...LOCATION,
        id: queryFor(call) === "Kyoto" ? "kyoto-jp" : LOCATION.id,
        displayName: queryFor(call) === "Kyoto" ? "Kyoto, Japan" : LOCATION.displayName
      } }
    }),
    planning: async () => {
      await new Promise(resolve => setTimeout(resolve, 300));
      return { status: 200, body: PLANNING_SUCCESS };
    }
  });
  await page.goto("/search?q=Prague");
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();
  await expect.poll(() => planningCalls(calls).length).toBe(1);
  await searchFor(page, "Kyoto");

  await expect(page.getByRole("heading", { name: "Kyoto, Japan" })).toBeVisible();
  await expect(page.locator("#workspace-title")).toHaveText("Opportunity review");
  await expect(page.locator(".planning-weather-notice")).toBeHidden();
  await expect(page.locator(".workspace-meta:not(.planning-weather-notice)")).toBeVisible();
  await page.waitForTimeout(350);
  await expect(page.locator(".planning-date-card")).toHaveCount(0);
  await expect(page.locator("#results")).toHaveAttribute("aria-busy", "false");
});

test("uses the server-owned horizon for a bounded empty result", async ({ page }) => {
  await captureApi(page, {
    ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
    planning: () => ({ status: 200, body: boundedEmpty(42) })
  });
  await page.goto("/search?q=Prague");
  await expect(page.locator(".planning-recovery")).not.toContainText("42");
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();

  await expect(page.getByRole("heading", { name: "No matching Moon date" })).toBeVisible();
  await expect(page.locator("#results")).toContainText(
    "The fixture found no matching Moon date in the next 42 days."
  );
  await expect(page.locator("#results")).toContainText("42 days");
  await expect(page.locator("#results")).not.toContainText("365 days");
  await expect(page.locator(".planning-weather-notice")).toBeVisible();
});

for (const scenario of [
  {
    name: "location-not-found",
    response: { status: 200, body: { status: "location_not_found", message: "No matching location found." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "invalid-request",
    response: { status: 400, body: { status: "invalid_request", message: "Check the planning request." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "oversized",
    response: { status: 413, body: { status: "request_too_large", message: "The request was too large." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "unsupported-media",
    response: { status: 415, body: { status: "unsupported_media_type", message: "Use JSON." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "rate-limited",
    response: { status: 429, body: { status: "rate_limited", message: "Please wait before planning again." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "unavailable",
    response: { status: 503, body: { status: "temporarily_unavailable", message: "Try again shortly." } },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "malformed",
    response: { status: 200, rawBody: "{" },
    heading: "Next matching Moon date unavailable"
  },
  {
    name: "malformed-success",
    response: { status: 200, body: malformedPlanningSuccess() },
    heading: "Unexpected planning response"
  },
  {
    name: "overflowing-horizon",
    response: {
      status: 200,
      body: { ...PLANNING_SUCCESS, planningHorizonDays: Number.MAX_SAFE_INTEGER }
    },
    heading: "Unexpected planning response"
  },
  {
    name: "network-failure",
    response: { abort: true },
    heading: "Planning could not be reached"
  }
]) {
  test("keeps " + scenario.name + " planning-specific", async ({ page }) => {
    await captureApi(page, {
      ordinary: () => ({ status: 200, body: ORDINARY_EMPTY }),
      planning: () => scenario.response
    });
    await page.goto("/search?q=Prague");
    await page.getByRole("button", { name: "Find the next matching Moon date" }).click();

    await expect(page.getByRole("heading", { name: scenario.heading })).toBeVisible();
    await expect(page.locator("#workspace-title")).toHaveText("Next matching Moon date");
    await expect(page.locator(".planning-weather-notice")).toBeVisible();
    await expect(page.locator(".planning-date-card")).toHaveCount(0);
  });
}

async function captureApi(page, responders) {
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const rawBody = request.postData();
    const call = {
      method: request.method(),
      path: new URL(request.url()).pathname,
      url: request.url(),
      rawBody: rawBody,
      body: rawBody === null ? null : JSON.parse(rawBody)
    };
    calls.push(call);
    const responder = call.path === "/api/opportunities/planning"
      ? responders.planning
      : responders.ordinary;
    const response = await responder(call);
    if (response.abort) {
      await route.abort();
      return;
    }
    try {
      await route.fulfill({
        status: response.status,
        contentType: "application/json",
        body: response.rawBody === undefined ? JSON.stringify(response.body) : response.rawBody
      });
    } catch (error) {
      if (!request.failure()) throw error;
    }
  });
  return calls;
}

function boundedEmpty(days) {
  return {
    ...PLANNING_SUCCESS,
    endsAt: "2026-12-05T12:00:00Z",
    planningHorizonDays: days,
    nextPlanningWindow: null,
    emptyReason: {
      code: "no_planning_date",
      text: "The fixture found no matching Moon date in the next " + days + " days."
    }
  };
}

function planningCalls(calls) {
  return calls.filter(call => call.path === "/api/opportunities/planning");
}

function ordinaryCalls(calls) {
  return calls.filter(call => call.path === "/api/opportunities");
}

function queryFor(call) {
  return new URL(call.url).searchParams.get("q") || call.body?.q || "";
}

async function searchFor(page, query) {
  await page.getByLabel("City or town").fill(query);
  await page.getByRole("button", { name: "Find", exact: true }).click();
}

async function waitForOrdinary(page, calls, count) {
  await expect.poll(() => ordinaryCalls(calls).length).toBe(count);
  await expect(page.locator("#results")).toHaveAttribute("aria-busy", "false");
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => /** @type {HTMLDetailsElement} */ (node).open)) {
    await details.locator("summary").click();
  }
}

async function storedPreferences(page) {
  const raw = await page.evaluate(key => window.localStorage.getItem(key), STORAGE_KEY);
  return raw === null ? null : JSON.parse(raw);
}

function malformedPlanningSuccess() {
  const response = /** @type {any} */ (JSON.parse(JSON.stringify(PLANNING_SUCCESS)));
  delete response.nextPlanningWindow.moonPass.path.samples[1].northPoleTiltDegrees;
  return response;
}

function planningPoint(at, altitude, azimuth, sunAltitude, sunAzimuth, role) {
  return {
    at: at,
    altitudeDegrees: altitude,
    azimuthDegrees: azimuth,
    moonPhaseAngleDegrees: 218.6,
    brightLimbTiltDegrees: 274.8,
    northPoleTiltDegrees: 31.2,
    sunAltitudeDegrees: sunAltitude,
    sunAzimuthDegrees: sunAzimuth,
    lightBucket: planningLightBucket(sunAltitude),
    role: role
  };
}

function planningLightBucket(sunAltitude) {
  if (sunAltitude >= 6) return "daylight";
  if (sunAltitude >= -0.833) return "golden_hour";
  if (sunAltitude >= -6) return "civil_twilight";
  if (sunAltitude >= -12) return "nautical_twilight";
  return "night";
}
