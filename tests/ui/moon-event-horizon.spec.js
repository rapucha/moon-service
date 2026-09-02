import { expect, test } from "@playwright/test";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const ALTITUDE = { minimum: 10, maximum: 30 };
const LOCATION_ID = "moon-service-3067696";

test("uses the legacy 18-month default and keeps it out of opportunity requests", async ({ page }) => {
  await seedPreferences(page, { version: 1, altitudeDegrees: ALTITUDE });
  const calls = await captureApis(page);

  await page.goto("/search?locationId=" + LOCATION_ID);
  await waitForEvents(calls, 1);
  await openPreferences(page);
  const horizon = page.getByLabel("Look ahead", { exact: true });
  await expect(horizon).toHaveJSProperty("tagName", "SELECT");
  await expect(horizon.locator("option")).toHaveText([
    "6 months", "12 months", "18 months", "24 months", "36 months"
  ]);
  await expect(horizon).toHaveValue("18");
  expect(calls.events[0].body).toEqual({
    locationId: LOCATION_ID,
    eventHorizonMonths: 18,
    preferences: { version: 1, altitudeDegrees: ALTITUDE }
  });
  expect(calls.ordinary[0].body).toEqual({
    locationId: LOCATION_ID,
    preferences: { version: 1, altitudeDegrees: ALTITUDE }
  });
  expect(await storedPreferences(page)).toEqual({ version: 1, altitudeDegrees: ALTITUDE });
  await expect(page.locator(".special-moon-events-status")).toHaveText(
    "No lunar eclipse or near-perigee full Moon is available for this location in the next 18 months."
  );

  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();
  await expect.poll(() => calls.planning.length).toBe(1);
  expect(calls.planning[0].body).toEqual({
    locationId: LOCATION_ID,
    preferences: { version: 1, altitudeDegrees: ALTITUDE }
  });
  expect(await horizontalOverflow(page)).toBeLessThanOrEqual(1);
});

test("persists the horizon and replaces only enabled special-event requests", async ({ page }) => {
  let release24 = function () {};
  const wait24 = new Promise(resolve => { release24 = function () { resolve(); }; });
  const calls = await captureApis(page, async function (call) {
    if (call.body.eventHorizonMonths === 24) await wait24;
    return emptyEventResponse(call.body);
  });

  await page.goto("/search?locationId=" + LOCATION_ID);
  await waitForEvents(calls, 1);
  await openPreferences(page);
  const horizon = page.getByLabel("Look ahead", { exact: true });
  await horizon.selectOption("24");
  await waitForEvents(calls, 2);
  await expect(page.locator(".special-moon-events-status"))
    .toHaveText("Checking special Moon events…");
  await horizon.selectOption("36");
  await waitForEvents(calls, 3);
  await expect(page.locator(".special-moon-events-status")).toHaveText(
    "No lunar eclipse or near-perigee full Moon is available for this location in the next 36 months."
  );
  expect(calls.ordinary).toHaveLength(1);
  expect(await storedPreferences(page)).toEqual({ version: 1, eventHorizonMonths: 36 });
  release24();

  await page.reload();
  await waitForEvents(calls, 4);
  await openPreferences(page);
  await expect(horizon).toHaveValue("36");
  const showEvents = page.getByLabel("Show lunar eclipses and supermoons");
  await showEvents.uncheck();
  await expect(page.locator(".special-moon-events")).toHaveCount(0);
  await horizon.selectOption("6");
  expect(calls.events).toHaveLength(4);
  expect(calls.ordinary).toHaveLength(2);
  expect(await storedPreferences(page)).toEqual({
    version: 1,
    showSpecialMoonEvents: false,
    eventHorizonMonths: 6
  });

  await showEvents.check();
  await waitForEvents(calls, 5);
  expect(calls.events[4].body.eventHorizonMonths).toBe(6);
  await expect(page.locator(".special-moon-events-status")).toContainText("next 6 months");
  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await waitForEvents(calls, 6);
  await expect(horizon).toHaveValue("18");
  expect(calls.events[5].body.eventHorizonMonths).toBe(18);
  await expect.poll(() => storedPreferences(page)).toBeNull();
});

test("discards a stored unsupported horizon", async ({ page }) => {
  await seedPreferences(page, { version: 1, eventHorizonMonths: 9 });
  const calls = await captureApis(page);

  await page.goto("/search?locationId=" + LOCATION_ID);
  await waitForEvents(calls, 1);
  await openPreferences(page);
  await expect(page.getByLabel("Look ahead", { exact: true })).toHaveValue("18");
  expect(calls.events[0].body.eventHorizonMonths).toBe(18);
  await expect(page.locator("#preference-storage-notice")).toHaveText(
    "Saved preferences were discarded because their format is not supported."
  );
  expect(await storedPreferences(page)).toBeNull();
});

async function captureApis(page, eventResponder) {
  const calls = { ordinary: [], planning: [], events: [] };
  await page.route("**/api/opportunities**", async route => {
    const call = apiCall(route.request());
    if (call.path === "/api/opportunities/planning") {
      calls.planning.push(call);
      await fulfill(route, { status: "invalid_request", message: "Fixture stop." }, 400);
      return;
    }
    calls.ordinary.push(call);
    const preferences = call.body?.preferences || { version: 1 };
    await fulfill(route, ordinaryResponse(locationIdFrom(call), preferences));
  });
  await page.route("**/api/moon-events**", async route => {
    const call = apiCall(route.request());
    calls.events.push(call);
    const payload = eventResponder
      ? await eventResponder(call)
      : emptyEventResponse(call.body);
    await fulfillSafely(route, payload);
  });
  return calls;
}

function apiCall(request) {
  return {
    path: new URL(request.url()).pathname,
    body: request.postData() === null ? null : request.postDataJSON()
  };
}

async function fulfill(route, body, status = 200) {
  await route.fulfill({ status: status, contentType: "application/json", body: JSON.stringify(body) });
}

async function fulfillSafely(route, body) {
  try {
    await fulfill(route, body);
  } catch (error) {
    if (!route.request().failure()) throw error;
  }
}

function ordinaryResponse(id, preferences) {
  return {
    status: "ok",
    generatedAt: "2026-09-01T10:00:00Z",
    startsAt: "2026-09-01T10:00:00Z",
    endsAt: "2026-09-08T10:00:00Z",
    location: location(id),
    forecastHorizonDays: 7,
    candidateWindowsEvaluated: 0,
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: preferenceFields(preferences),
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    opportunities: [],
    rejected: []
  };
}

function emptyEventResponse(request) {
  const startsAt = "2026-09-01T10:00:00Z";
  const end = new Date(startsAt);
  end.setUTCMonth(end.getUTCMonth() + request.eventHorizonMonths);
  return {
    status: "ok",
    generatedAt: startsAt,
    startsAt: startsAt,
    endsAt: end.toISOString(),
    location: location(request.locationId),
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: preferenceFields(request.preferences),
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    events: []
  };
}

function preferenceFields(preferences) {
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

function locationIdFrom(call) {
  return call.body?.locationId || LOCATION_ID;
}

async function waitForEvents(calls, count) {
  await expect.poll(() => calls.events.length).toBe(count);
}

async function seedPreferences(page, value) {
  await page.addInitScript(({ key, stored }) => {
    localStorage.setItem(key, JSON.stringify(stored));
  }, { key: STORAGE_KEY, stored: value });
}

async function storedPreferences(page) {
  return page.evaluate(key => {
    const value = localStorage.getItem(key);
    return value === null ? null : JSON.parse(value);
  }, STORAGE_KEY);
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => node.hasAttribute("open"))) {
    await details.locator(":scope > summary").click();
  }
}

async function horizontalOverflow(page) {
  return page.evaluate(() => document.documentElement.scrollWidth
    - document.documentElement.clientWidth);
}
