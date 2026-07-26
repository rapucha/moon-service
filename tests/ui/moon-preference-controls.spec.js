import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

const DIRECTION = {
  included: { start: 330, end: 30 },
  excluded: { start: 350, end: 10 }
};
const EDITED_DIRECTION = {
  included: { start: 329, end: 30 },
  excluded: { start: 349, end: 10 }
};
const PHASES = [
  ["New", "new_moon"],
  ["Waxing crescent", "waxing_crescent"],
  ["First quarter", "first_quarter"],
  ["Waxing gibbous", "waxing_gibbous"],
  ["Full", "full_moon"],
  ["Waning gibbous", "waning_gibbous"],
  ["Last quarter", "last_quarter"],
  ["Waning crescent", "waning_crescent"]
];

test("edits, persists, removes, and resets the accepted controls", async ({ page }, testInfo) => {
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("GET");
  calls.length = 0;
  await openPreferences(page);

  await expect(page.locator("#preference-count")).toHaveText("None active");
  await expect(page.getByLabel("Limit Moon altitude")).not.toBeChecked();
  await expect(page.getByLabel("Limit Moon direction")).not.toBeChecked();
  await expect(page.getByLabel("Limit illuminated-edge direction")).not.toBeChecked();
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(0);
  await expect(page.locator("#active-preference-summary")).toHaveCount(0);

  await page.getByLabel("Limit Moon altitude").check();
  const altitudeMinimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  const altitudeMaximum = page.getByRole("slider", { name: "Maximum Moon altitude" });
  await expect(altitudeMinimum).toHaveAttribute("aria-orientation", "vertical");
  await expect(altitudeMaximum).toHaveAttribute("aria-orientation", "vertical");
  const minimumBox = await altitudeMinimum.boundingBox();
  const maximumBox = await altitudeMaximum.boundingBox();
  expect(minimumBox).not.toBeNull();
  expect(maximumBox).not.toBeNull();
  expect(Math.abs(minimumBox.x - maximumBox.x)).toBeLessThanOrEqual(1);
  expect(minimumBox.y).toBeGreaterThan(maximumBox.y);
  await altitudeMinimum.focus();
  await page.keyboard.press("ArrowUp");
  await expect(altitudeMinimum).toHaveAttribute("aria-valuenow", "3");

  await page.getByLabel("Limit Moon direction").check();
  const compassTrack = page.locator("#preference-compass-track");
  const bearingHandles = compassTrack.getByRole("slider");
  await expect(bearingHandles).toHaveCount(4);
  for (const name of [
    "Included compass sector start",
    "Included compass sector end",
    "Blocked view start",
    "Blocked view end"
  ]) {
    await expect(page.getByRole("slider", { name })).toHaveAttribute("aria-orientation", "horizontal");
  }
  await expect(page.locator("#preference-included-output")).toHaveText("330° through 30°");
  await expect(page.locator("#preference-excluded-output")).toHaveText("350° through 10°");
  const includedStartHandle = page.getByRole("slider", {
    name: "Included compass sector start"
  });
  await includedStartHandle.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(includedStartHandle).toHaveAttribute("aria-valuenow", "329");
  await expect(page.getByLabel("Included compass sector start in degrees")).toHaveValue("329");
  const blockedStartHandle = page.getByRole("slider", { name: "Blocked view start" });
  await blockedStartHandle.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(blockedStartHandle).toHaveAttribute("aria-valuenow", "349");
  await expect(page.getByLabel("Blocked view start in degrees")).toHaveValue("349");

  for (const phase of PHASES) {
    await page.getByLabel(phase[0], { exact: true }).check();
  }
  await page.getByLabel("Ambient light").check();
  await page.getByLabel("Civil twilight").uncheck();
  await page.getByLabel("Night").check();
  await page.getByLabel("Limit illuminated-edge direction").check();
  const limbHandle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  await expect(limbHandle).toHaveAttribute("aria-describedby", "preference-limb-instructions");
  const limbCanvas = page.locator("#preference-limb-moon");
  const canvasBefore = await limbCanvas.evaluate(canvas =>
    /** @type {HTMLCanvasElement} */ (canvas).toDataURL()
  );
  const limbDial = page.locator("#preference-limb-dial");
  const dialBox = await limbDial.boundingBox();
  expect(dialBox).not.toBeNull();
  await limbDial.click({ position: { x: dialBox.width - 12, y: dialBox.height / 2 } });
  await limbHandle.focus();
  const canvasAfter = await limbCanvas.evaluate(canvas =>
    /** @type {HTMLCanvasElement} */ (canvas).toDataURL()
  );
  expect(canvasAfter).not.toBe(canvasBefore);
  const pointerTarget = Number(await limbHandle.getAttribute("aria-valuenow"));
  expect(pointerTarget).toBeGreaterThanOrEqual(89);
  expect(pointerTarget).toBeLessThanOrEqual(91);
  await page.keyboard.press("ArrowRight");
  await expect(limbHandle).toHaveAttribute("aria-valuenow", String(pointerTarget + 1));
  await page.keyboard.press("Home");
  await expect(limbHandle).toHaveAttribute("aria-valuenow", "0");
  await expect(limbHandle).toHaveAttribute(
    "aria-valuetext",
    "0 degrees clockwise from zenith, toward zenith"
  );

  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: {
      version: 1,
      altitudeDegrees: { minimum: 3, maximum: 15 },
      azimuthDegrees: EDITED_DIRECTION,
      time: { mode: "light_bucket", buckets: ["night"] },
      namedPhases: PHASES.map(phase => phase[1]),
      brightLimbOrientationDegrees: [{ start: 350, end: 10 }]
    }
  });
  await expect(page.locator("#preference-count")).toHaveText("5 active");
  expect(await storedPreferences(page)).toEqual(calls[0].body.preferences);

  await page.reload();
  await waitForCallCount(calls, 2);
  await openPreferences(page);
  await expect(page.getByLabel("Limit Moon direction")).toBeChecked();
  await expect(page.getByLabel("Included compass sector start in degrees")).toHaveValue("329");
  await expect(page.getByLabel("Blocked view start in degrees")).toHaveValue("349");
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(8);
  await expect(limbHandle).toHaveAttribute("aria-valuenow", "0");

  await page.getByLabel("Limit Moon direction").uncheck();
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 3);
  expect(calls[2].body.preferences.azimuthDegrees).toBeUndefined();
  expect(calls[2].body.preferences.namedPhases).toEqual(PHASES.map(phase => phase[1]));
  await expect(page.locator("#preference-count")).toHaveText("4 active");

  await openPreferences(page);
  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await waitForCallCount(calls, 4);
  expect(calls[3].method).toBe("GET");
  expect(calls[3].body).toBeNull();
  expect(await storedPreferences(page)).toBeNull();
  const focusTarget = testInfo.project.name === "mobile"
    ? page.locator("#opportunity-preferences > summary")
    : page.getByLabel("Limit Moon altitude");
  await expect(focusTarget).toBeFocused();
});

test("rejects invalid manual compass sectors without sending a request", async ({ page }) => {
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);
  await page.getByLabel("Limit Moon direction").check();
  const status = page.locator("#preference-form-status");
  const apply = page.getByRole("button", { name: "Use these limits" });
  const includedStart = page.locator("#preference-included-start");
  const includedEnd = page.locator("#preference-included-end");
  const blockedStart = page.locator("#preference-excluded-start");

  await includedStart.fill("");
  await apply.click();
  await expect(status).toContainText("Use compass bearings");
  await expect(includedStart).toBeFocused();
  expect(calls).toHaveLength(0);

  await includedStart.fill("10");
  await includedEnd.fill("10");
  await apply.click();
  await expect(status).toHaveText("The included compass-sector endpoints must differ.");
  await expect(includedEnd).toBeFocused();
  expect(calls).toHaveLength(0);

  await includedStart.fill("330");
  await includedEnd.fill("30");
  await blockedStart.fill("40");
  await apply.click();
  await expect(status).toHaveText("Keep the blocked view inside the included compass sector.");
  await expect(blockedStart).toBeFocused();
  expect(calls).toHaveLength(0);

  await blockedStart.fill("350");
  await includedStart.fill("360");
  await apply.click();
  await expect(status).toContainText("Use compass bearings");
  await expect(includedStart).toBeFocused();
  expect(calls).toHaveLength(0);

  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await waitForCallCount(calls, 1);
  await expect(status).toHaveText("");
  await expect(includedStart).toHaveValue("330");
});

test("keeps the lower altitude handle accessible at an upper overlap", async ({
  page
}, testInfo) => {
  await preloadState(page, {
    version: 1,
    altitudeDegrees: { minimum: 89, maximum: 89 }
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  const minimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  const maximum = page.getByRole("slider", { name: "Maximum Moon altitude" });
  const track = page.locator("#preference-altitude-track");
  const minimumBox = await minimum.boundingBox();
  const trackBox = await track.boundingBox();
  expect(minimumBox).not.toBeNull();
  expect(trackBox).not.toBeNull();
  await expect(minimum).toHaveAttribute("aria-valuenow", "89");
  await expect(maximum).toHaveAttribute("aria-valuenow", "89");

  const start = {
    x: minimumBox.x + minimumBox.width / 2,
    y: minimumBox.y + minimumBox.height / 2
  };
  const lowerHandleIsTarget = await page.evaluate(position => {
    return Boolean(document.elementFromPoint(position.x, position.y)
      ?.closest("[data-altitude-minimum]"));
  }, start);
  expect(lowerHandleIsTarget).toBe(true);

  if (testInfo.project.name === "mobile") {
    await minimum.evaluate(element => {
      element.addEventListener("pointerdown", function () {
        element.setAttribute("data-test-pointer-hit", "true");
      }, { once: true });
    });
    await page.touchscreen.tap(start.x, start.y);
    await expect(minimum).toHaveAttribute("data-test-pointer-hit", "true");
    await expect(minimum).toHaveAttribute("aria-valuenow", "89");
  } else {
    const end = {
      x: trackBox.x + trackBox.width / 2,
      y: trackBox.y + trackBox.height / 9
    };
    await page.mouse.move(start.x, start.y);
    await page.mouse.down();
    await page.mouse.move(end.x, end.y);
    await page.mouse.up();
    await expect(minimum).toHaveAttribute("aria-valuenow", "80");
  }

  await expect(maximum).toHaveAttribute("aria-valuenow", "89");
});

const invalidStoredStates = [
  {
    name: "a missing blocked sector",
    state: { version: 1, azimuthDegrees: { included: { start: 330, end: 30 } } }
  },
  {
    name: "duplicate named phases",
    state: { version: 1, namedPhases: ["full_moon", "full_moon"] }
  },
  {
    name: "an unknown named phase",
    state: { version: 1, namedPhases: ["blue_moon"] }
  },
  {
    name: "a bright-limb range with the wrong tolerance",
    state: { version: 1, brightLimbOrientationDegrees: [{ start: 10, end: 40 }] }
  }
];

for (const invalid of invalidStoredStates) {
  test("discards stored preferences with " + invalid.name, async ({ page }) => {
    await preloadState(page, invalid.state);
    const calls = await captureApiCalls(page);
    await page.goto("/search?q=Prague");
    await waitForCallCount(calls, 1);
    expect(calls[0].method).toBe("GET");
    expect(await storedPreferences(page)).toBeNull();
    await expect(page.locator("#preference-storage-notice"))
      .toContainText("Saved preferences were discarded");
  });
}

test("dims only the complement of the authoritative full-pass azimuth mask", async ({ page }) => {
  await preloadState(page, { version: 1, azimuthDegrees: DIRECTION });
  const calls = await captureApiCalls(page, call => {
    const payload = successfulResponse(call);
    payload.opportunities.forEach(opportunity => {
      opportunity.moonPass.azimuthMatchIntervals = [
        { startsAt: "2026-07-04T00:30:00Z", endsAt: "2026-07-04T02:00:00Z" },
        { startsAt: "2026-07-04T06:00:00Z", endsAt: "2026-07-04T07:00:00Z" }
      ];
    });
    return payload;
  });
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const chart = page.locator(".moon-pass-card").first()
    .locator(".moon-altitude-chart.altitude-chart-desktop");
  await expect(chart).toHaveAttribute("aria-label", /dimmed portions fall outside/);
  const masks = chart.locator(".azimuth-preference-excluded");
  await expect(masks).toHaveCount(3);
  await expect(masks.nth(0)).toHaveAttribute("data-start-at", "2026-07-04T00:00:00.000Z");
  await expect(masks.nth(0)).toHaveAttribute("data-end-at", "2026-07-04T00:30:00.000Z");
  await expect(masks.nth(1)).toHaveAttribute("data-start-at", "2026-07-04T02:00:00.000Z");
  await expect(masks.nth(1)).toHaveAttribute("data-end-at", "2026-07-04T06:00:00.000Z");
  await expect(masks.nth(2)).toHaveAttribute("data-start-at", "2026-07-04T07:00:00.000Z");
  await expect(masks.nth(2)).toHaveAttribute("data-end-at", "2026-07-04T07:25:00.000Z");
});

async function captureApiCalls(page, responseForCall = successfulResponse) {
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const body = request.postData();
    const call = {
      method: request.method(),
      url: request.url(),
      body: body === null ? null : JSON.parse(body)
    };
    calls.push(call);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(responseForCall(call))
    });
  });
  return calls;
}

function successfulResponse(call) {
  const payload = /** @type {any} */ (JSON.parse(JSON.stringify(sourceFixture)));
  if (call.body && call.body.preferences) {
    payload.appliedPreferenceVersion = 1;
    payload.normalizedActiveFilters = call.body.preferences;
    payload.excludedSampleCount = 0;
    payload.ignoredPreferenceFields = [];
    payload.ignoredPreferenceFieldCount = 0;
    payload.additionalIgnoredPreferenceFieldCount = 0;
  }
  return payload;
}

async function waitForCallCount(calls, count) {
  await expect.poll(() => calls.length).toBe(count);
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => /** @type {HTMLDetailsElement} */ (node).open)) {
    await details.locator("summary").click();
  }
}

async function preloadState(page, state) {
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
  }, { key: STORAGE_KEY, value: state });
}

async function storedPreferences(page) {
  return page.evaluate(key => {
    const value = window.localStorage.getItem(key);
    return value === null ? null : JSON.parse(value);
  }, STORAGE_KEY);
}
