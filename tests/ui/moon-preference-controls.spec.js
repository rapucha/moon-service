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
  await expect(page.getByLabel("Limit Moon altitude")).not.toHaveAttribute("aria-expanded");
  await expect(page.getByLabel("Limit Moon direction")).not.toHaveAttribute("aria-expanded");
  await expect(page.getByLabel("Limit illuminated-edge direction")).not.toBeChecked();
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(0);
  await expect(page.locator("#active-preference-summary")).toHaveCount(0);

  await page.getByLabel("Limit Moon altitude").check();
  await expect(page.locator("#preference-angular-fields")).toBeVisible();
  await expect(page.locator("#preference-angular-fields input[type=number]")).toHaveCount(0);
  await expect(page.locator(".preference-angular-readouts")).toHaveCount(0);
  await expect(page.getByRole("slider", {
    name: "Included compass sector start"
  })).not.toBeVisible();
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
  const altitudePercent = await altitudeMaximum.evaluate(handle => parseFloat(handle.style.bottom));
  expect(altitudePercent).toBeCloseTo(Math.pow(15 / 90, 0.85) * 100, 4);
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
  const includedStartHandle = page.getByRole("slider", {
    name: "Included compass sector start"
  });
  await includedStartHandle.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(includedStartHandle).toHaveAttribute("aria-valuenow", "329");
  const blockedStartHandle = page.getByRole("slider", { name: "Blocked view start" });
  await blockedStartHandle.focus();
  await page.keyboard.press("ArrowLeft");
  await expect(blockedStartHandle).toHaveAttribute("aria-valuenow", "349");

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
  await expect(includedStartHandle).toHaveAttribute("aria-valuenow", "329");
  await expect(blockedStartHandle).toHaveAttribute("aria-valuenow", "349");
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

test("stops nested boundaries and omits a collapsed blocked view", async ({
  page
}, testInfo) => {
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);
  await page.getByLabel("Limit Moon direction").check();
  const apply = page.getByRole("button", { name: "Use these limits" });
  const includedStart = page.getByRole("slider", { name: "Included compass sector start" });
  const includedEnd = page.getByRole("slider", { name: "Included compass sector end" });
  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  const blockedEnd = page.getByRole("slider", { name: "Blocked view end" });

  await focusAndPress(page, includedStart, ["Shift+ArrowRight", "Shift+ArrowRight", "ArrowRight"]);
  await expect(includedStart).toHaveAttribute("aria-valuenow", "350");

  await focusAndPress(page, includedEnd, ["Shift+ArrowLeft", "Shift+ArrowLeft", "ArrowLeft"]);
  await expect(includedEnd).toHaveAttribute("aria-valuenow", "10");
  await pressKeys(page, ["Shift+ArrowRight", "Shift+ArrowRight"]);
  await expect(includedEnd).toHaveAttribute("aria-valuenow", "30");

  await focusAndPress(page, blockedEnd, ["Shift+ArrowRight", "Shift+ArrowRight", "ArrowRight"]);
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "30");
  await pressKeys(page, ["Shift+ArrowLeft", "Shift+ArrowLeft"]);
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "10");

  await focusAndPress(page, blockedStart, ["Shift+ArrowRight", "Shift+ArrowRight", "ArrowRight"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "10");
  expect(await page.locator("[data-azimuth-fill^='excluded']").evaluateAll(fills =>
    fills.every(fill => fill.style.width === "0%"))).toBe(true);
  const startBox = await blockedStart.boundingBox();
  const endBox = await blockedEnd.boundingBox();
  expect(startBox).not.toBeNull();
  expect(endBox).not.toBeNull();
  expect(Math.abs(startBox.x + startBox.width - endBox.x)).toBeLessThanOrEqual(1);
  await verifyPointerTargets(page, [
    [blockedStart, "[data-bearing-handle='excluded-start']"],
    [blockedEnd, "[data-bearing-handle='excluded-end']"]
  ], testInfo.project.name === "mobile");

  await apply.click();
  await waitForCallCount(calls, 1);
  expect(calls[0].body.preferences.azimuthDegrees).toEqual({
    included: { start: 350, end: 30 }
  });
  expect(await storedPreferences(page)).toEqual(calls[0].body.preferences);

  await page.reload();
  await waitForCallCount(calls, 2);
  await openPreferences(page);
  await expect(page.getByLabel("Limit Moon direction")).toBeChecked();
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "10");
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "10");
  await focusAndPress(page, blockedEnd, ["Shift+ArrowRight", "Shift+ArrowRight"]);
  await focusAndPress(page, blockedStart, ["Shift+ArrowRight", "Shift+ArrowRight"]);
  await focusAndPress(page, includedStart, Array(4).fill("Shift+ArrowRight"));
  await expect(includedStart).toHaveAttribute("aria-valuenow", "29");
  await focusAndPress(page, blockedStart, ["ArrowLeft"]);
  await focusAndPress(page, blockedEnd, ["ArrowLeft"]);
  await focusAndPress(page, includedEnd, ["ArrowLeft"]);
  await expect(includedEnd).toHaveAttribute("aria-valuenow", "30");

  await preloadState(page, {
    version: 1, azimuthDegrees: { included: { start: 29.5, end: 30 } }
  });
  await page.reload();
  await waitForCallCount(calls, 3);
  await openPreferences(page);
  await focusAndPress(page, blockedEnd, ["ArrowRight"]);
  await focusAndPress(page, blockedStart, ["ArrowRight"]);
  await focusAndPress(page, includedStart, ["ArrowRight"]);
  await expect(includedStart).toHaveAttribute("aria-valuenow", "29.5");
});

test("stops every bearing pointer at its adjacent boundary", async ({ page }, testInfo) => {
  await preloadState(page, {
    version: 1,
    azimuthDegrees: DIRECTION
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  const trackBox = await page.locator("#preference-compass-track").boundingBox();
  expect(trackBox).not.toBeNull();
  /** @type {Array<[string, number, string, string | null]>} */
  const cases = [
    ["Included compass sector start", trackBox.width * 0.2, "350", "Shift+ArrowLeft"],
    ["Blocked view start", -trackBox.width * 0.9, "330", "Shift+ArrowRight"],
    ["Blocked view end", trackBox.width * 0.9, "30", "Shift+ArrowLeft"],
    ["Included compass sector end", -trackBox.width * 0.2, "10", null]
  ];
  for (const [name, deltaX, expected, restoreKey] of cases) {
    const handle = page.getByRole("slider", { name });
    if (testInfo.project.name === "mobile") {
      await dragTouchHandle(page, handle, deltaX);
    } else {
      await dragMouseHandle(page, handle, deltaX);
    }
    await expect(handle).toHaveAttribute("aria-valuenow", expected);
    if (restoreKey) {
      await handle.focus();
      await pressKeys(page, [restoreKey, restoreKey]);
    }
  }
  const includedStart = page.getByRole("slider", { name: "Included compass sector start" });
  await includedStart.focus();
  for (const [key, expected] of [["End", "350"], ["Home", "11"]]) {
    await page.keyboard.press(key);
    await expect(includedStart).toHaveAttribute("aria-valuenow", expected);
  }
  for (const name of ["Blocked view start", "Blocked view end"]) {
    const handle = page.getByRole("slider", { name });
    await handle.focus();
    for (const [key, expected] of [["Home", "0"], ["End", "359"]]) {
      await page.keyboard.press(key);
      await expect(handle).toHaveAttribute("aria-valuenow", expected);
    }
  }
});

test("keeps coincident altitude boundaries edge-to-edge and draggable", async ({
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
  await minimum.scrollIntoViewIfNeeded();
  const minimumBox = await minimum.boundingBox();
  const trackBox = await track.boundingBox();
  expect(minimumBox).not.toBeNull();
  expect(trackBox).not.toBeNull();
  await expect(minimum).toHaveAttribute("aria-valuenow", "89");
  await expect(maximum).toHaveAttribute("aria-valuenow", "89");
  const maximumBox = await maximum.boundingBox();
  expect(maximumBox).not.toBeNull();
  expect(Math.abs(minimumBox.x - maximumBox.x)).toBeLessThanOrEqual(1);
  expect(Math.abs(minimumBox.y - maximumBox.y - maximumBox.height)).toBeLessThanOrEqual(1);

  await verifyPointerTargets(page, [
    [minimum, "[data-altitude-minimum]"],
    [maximum, "[data-altitude-maximum]"]
  ], testInfo.project.name === "mobile");

  if (testInfo.project.name === "mobile") await dragTouchHandle(page, minimum, 0, 12);
  else await dragMouseHandle(page, minimum, 0, 12);
  const movedBox = await minimum.boundingBox();
  expect(movedBox).not.toBeNull();
  expect(Math.abs(movedBox.y - minimumBox.y - 12)).toBeLessThanOrEqual(2);
  await expect(maximum).toHaveAttribute("aria-valuenow", "89");

  await minimum.focus();
  await page.keyboard.press("Home");
  const homeBox = await minimum.boundingBox();
  expect(homeBox).not.toBeNull();
  const homeCenter = { x: homeBox.x + homeBox.width / 2, y: homeBox.y + homeBox.height / 2 };
  expect(await page.evaluate(point => document.elementFromPoint(point.x, point.y)
    ?.closest("[data-altitude-minimum]") !== null, homeCenter)).toBe(true);
  const targetY = trackBox.y + trackBox.height * (1 - Math.pow(0.5, 0.85));
  const deltaY = targetY + homeBox.height / 2 - homeCenter.y;
  if (testInfo.project.name === "mobile") await dragTouchHandle(page, minimum, 0, deltaY);
  else await dragMouseHandle(page, minimum, 0, deltaY);
  await expect(minimum).toHaveAttribute("aria-valuenow", "45");
  await expect(maximum).toHaveAttribute("aria-valuenow", "89");
});

test("keeps directional bearing hit targets anchored and unclipped", async ({
  page
}, testInfo) => {
  await preloadState(page, {
    version: 1,
    altitudeDegrees: { minimum: 0, maximum: 15 },
    azimuthDegrees: {
      included: { start: 0, end: 359 },
      excluded: { start: 0, end: 359 }
    }
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  const start = page.getByRole("slider", { name: "Included compass sector start" });
  const end = page.getByRole("slider", { name: "Included compass sector end" });
  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  const blockedEnd = page.getByRole("slider", { name: "Blocked view end" });
  const altitudeMinimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  const previewBox = await page.locator(".preference-angular-preview").boundingBox();
  expect(previewBox).not.toBeNull();
  for (const handle of [start, end, blockedStart, blockedEnd]) {
    const box = await handle.boundingBox();
    expect(box).not.toBeNull();
    expect(box.x).toBeGreaterThanOrEqual(previewBox.x);
    expect(box.x + box.width).toBeLessThanOrEqual(previewBox.x + previewBox.width + 0.5);
  }
  await verifyPointerTargets(page, [
    [altitudeMinimum, "[data-altitude-minimum]"],
    [start, "[data-bearing-handle='included-start']"],
    [end, "[data-bearing-handle='included-end']"],
    [blockedStart, "[data-bearing-handle='excluded-start']"],
    [blockedEnd, "[data-bearing-handle='excluded-end']"]
  ], testInfo.project.name === "mobile");

  const blocked = testInfo.project.name === "mobile" ? blockedEnd : blockedStart;
  const trackBox = await page.locator("#preference-compass-track").boundingBox();
  expect(trackBox).not.toBeNull();
  const deltaX = testInfo.project.name === "mobile" ? -20 : 20;
  const initial = Number(await blocked.getAttribute("aria-valuenow"));
  const expected = initial + Math.round(deltaX / trackBox.width * 359);
  if (testInfo.project.name === "mobile") await dragTouchHandle(page, blocked, deltaX);
  else await dragMouseHandle(page, blocked, deltaX);
  expect(Math.abs(Number(await blocked.getAttribute("aria-valuenow")) - expected))
    .toBeLessThanOrEqual(1);
});

test("uses the schematic sliders to explain and preview angular limits", async ({
  page
}, testInfo) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByLabel("Limit Moon altitude").check();
  await page.getByLabel("Limit Moon direction").check();

  const preview = page.locator(".preference-angular-preview");
  await expect(preview).toBeVisible();
  await expect(page.locator(".preference-preview-caption")).toHaveCount(0);
  await expect(page.locator("#preference-angular-artwork")).toHaveAttribute("aria-hidden", "true");
  await expect(page.locator(".preference-preview-moon")).toHaveCount(13);
  expect(await page.locator("[data-moon-path-artwork='true']").count()).toBeGreaterThan(0);
  await expect(preview).not.toContainText(/time|daylight|twilight|night/i);
  await expect(page.locator(".moon-path-foreground").first()).toHaveCSS("animation-name", "none");
  await expect(page.locator("#preference-angular-artwork")).toHaveCSS("pointer-events", "none");
  await expect(page.locator(".preference-altitude-base")).toHaveCSS("width", "4px");
  await expect(page.locator(".preference-compass-base")).toHaveCSS("height", "4px");
  const bearingTicks = page.locator(".preference-preview-bearing-tick");
  await expect(bearingTicks).toHaveCount(25);
  const cardinalTicks = page.locator(".preference-preview-bearing-tick.is-cardinal");
  await expect(cardinalTicks).toHaveCount(5);
  expect(await cardinalTicks.evaluateAll(ticks => ticks.map(tick => Number(tick.getAttribute("x1")))))
    .toEqual([40, 115, 190, 265, 340]);

  const altitudeMinimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  const altitudeMaximum = page.getByRole("slider", { name: "Maximum Moon altitude" });
  await altitudeMinimum.focus();
  await page.keyboard.press("Home");
  await altitudeMaximum.focus();
  await page.keyboard.press("End");

  const exclusion = page.locator("[data-preview-exclusion]");
  const beforeBlockedChange = await exclusion.getAttribute("d");
  expect(beforeBlockedChange).toBeTruthy();
  await expect(page.locator(".preference-preview-moon.is-dimmed")).toHaveCount(0);
  await expect(page.locator(".preference-preview-segment.is-dimmed")).toHaveCount(0);
  expect(await page.locator("#preference-angular-artwork").evaluate(artwork => {
    const children = Array.from(artwork.children);
    const exclusionIndex = children.indexOf(artwork.querySelector("[data-preview-exclusion]"));
    return exclusionIndex < children.indexOf(artwork.querySelector(".preference-preview-segment"))
      && exclusionIndex < children.indexOf(artwork.querySelector(".preference-preview-moon"));
  })).toBe(true);

  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  await blockedStart.focus();
  await page.keyboard.press("Shift+ArrowLeft");
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "340");
  expect(await exclusion.getAttribute("d")).not.toBe(beforeBlockedChange);

  const previewBox = await preview.boundingBox();
  const formBox = await page.locator("#preference-form").boundingBox();
  expect(previewBox).not.toBeNull();
  expect(formBox).not.toBeNull();
  expect(previewBox.x).toBeGreaterThanOrEqual(formBox.x);
  expect(previewBox.x + previewBox.width).toBeLessThanOrEqual(
    formBox.x + formBox.width + 1
  );
  const viewport = page.viewportSize();
  expect(previewBox.x + previewBox.width).toBeLessThanOrEqual(viewport.width);
  if (testInfo.project.name === "mobile") {
    expect(previewBox.width).toBeLessThanOrEqual(350);
  }
});

const invalidStoredStates = [
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

async function pressKeys(page, keys) {
  for (const key of keys) {
    await page.keyboard.press(key);
  }
}

async function focusAndPress(page, locator, keys) {
  await locator.focus();
  await pressKeys(page, keys);
}

async function verifyPointerTargets(page, entries, useTouch) {
  for (const [locator, selector] of entries) {
    await locator.scrollIntoViewIfNeeded();
    const box = await locator.boundingBox();
    expect(box).not.toBeNull();
    const center = {
      x: box.x + box.width / 2,
      y: box.y + box.height / 2
    };
    expect(await page.evaluate(({ point, target }) => {
      return Boolean(document.elementFromPoint(point.x, point.y)?.closest(target));
    }, { point: center, target: selector })).toBe(true);
    await locator.evaluate(element => {
      element.addEventListener("pointerdown", function () {
        element.setAttribute("data-test-pointer-hit", "true");
      }, { once: true });
    });
    if (useTouch) {
      await page.touchscreen.tap(center.x, center.y);
    } else {
      await page.mouse.click(center.x, center.y);
    }
    await expect(locator).toHaveAttribute("data-test-pointer-hit", "true");
  }
}

async function dragMouseHandle(page, locator, deltaX, deltaY = 0) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  await page.mouse.move(x, y);
  await page.mouse.down();
  await page.mouse.move(x + deltaX, y + deltaY);
  await page.mouse.up();
}

async function dragTouchHandle(page, locator, deltaX, deltaY = 0) {
  await locator.scrollIntoViewIfNeeded();
  const box = await locator.boundingBox();
  expect(box).not.toBeNull();
  const x = box.x + box.width / 2;
  const y = box.y + box.height / 2;
  const session = await page.context().newCDPSession(page);
  try {
    await session.send("Input.dispatchTouchEvent", {
      type: "touchStart",
      touchPoints: [{ x: x, y: y, id: 1, radiusX: 1, radiusY: 1, force: 1 }]
    });
    await session.send("Input.dispatchTouchEvent", {
      type: "touchMove",
      touchPoints: [{
        x: x + deltaX, y: y + deltaY, id: 1, radiusX: 1, radiusY: 1, force: 1
      }]
    });
    await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
  } finally {
    await session.detach();
  }
}

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
