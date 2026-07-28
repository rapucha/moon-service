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
test("edits, persists, removes, and resets the accepted controls", async ({ page }, testInfo) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
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
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(8);
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

  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(8);
  await page.getByLabel("Ambient light").check();
  await page.getByLabel("Civil twilight").uncheck();
  await page.getByLabel("Night").check();
  await page.getByLabel("Limit illuminated-edge direction").check();
  await page.getByLabel("New", { exact: true }).uncheck();
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
  await page.getByLabel("New", { exact: true }).check();

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
      brightLimbOrientationDegrees: [{ start: 350, end: 10 }]
    }
  });
  await expect(page.locator("#preference-count")).toHaveText("4 active");
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
  expect(calls[2].body.preferences.namedPhases).toBeUndefined();
  await expect(page.locator("#preference-count")).toHaveText("3 active");

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

test("snaps, transfers, and preserves usable compass sectors", async ({ page }) => {
  await preloadState(page, { version: 1, azimuthDegrees: DIRECTION });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);
  const apply = page.getByRole("button", { name: "Use these limits" });
  const includedStart = page.getByRole("slider", { name: "Included compass sector start" });
  const includedEnd = page.getByRole("slider", { name: "Included compass sector end" });
  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  const blockedEnd = page.getByRole("slider", { name: "Blocked view end" });

  await focusAndPress(page, blockedStart, ["Shift+ArrowLeft", "ArrowLeft"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "330");
  await focusAndPress(page, blockedEnd, ["Shift+ArrowRight", "ArrowRight"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "340");
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "30");

  await focusAndPress(page, blockedStart, ["ArrowLeft"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "330");
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "20");
  await focusAndPress(page, blockedStart, ["ArrowRight"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "340");

  await focusAndPress(page, includedStart, ["ArrowRight"]);
  await expect(includedStart).toHaveAttribute("aria-valuenow", "340");
  await focusAndPress(page, includedEnd, ["ArrowLeft"]);
  await expect(includedEnd).toHaveAttribute("aria-valuenow", "30");

  await apply.click();
  await waitForCallCount(calls, 1);
  expect(calls[0].body.preferences.azimuthDegrees).toEqual({
    included: { start: 340, end: 30 },
    excluded: { start: 340, end: 20 }
  });

  await preloadState(page, {
    version: 1, azimuthDegrees: { included: { start: 40, end: 30 } }
  });
  await page.reload();
  await waitForCallCount(calls, 2);
  await openPreferences(page);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "215");
  await focusAndPress(page, includedStart, ["ArrowLeft"]);
  await apply.click();
  await waitForCallCount(calls, 3);
  expect(calls[2].body).toBeNull();
});

test("stops red bearing handles at the visible north endpoints", async ({
  page
}, testInfo) => {
  await preloadState(page, {
    version: 1,
    azimuthDegrees: { excluded: { start: 330, end: 10 } }
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  const trackBox = await page.locator("#preference-compass-track").boundingBox();
  const includedStart = page.getByRole("slider", { name: "Included compass sector start" });
  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  const blockedEnd = page.getByRole("slider", { name: "Blocked view end" });
  await focusAndPress(page, blockedStart, ["Shift+ArrowRight", "ArrowRight"]);
  await focusAndPress(page, includedStart, ["ArrowRight"]);
  await expect(includedStart).toHaveAttribute("aria-valuenow", "330");
  const drag = testInfo.project.name === "mobile" ? dragTouchHandle : dragMouseHandle;
  await drag(page, blockedStart, trackBox.width * 2);
  await focusAndPress(page, blockedStart, ["ArrowRight"]);
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "359");

  await drag(page, blockedEnd, -trackBox.width * 2);
  await focusAndPress(page, blockedEnd, ["ArrowLeft"]);
  await expect(blockedEnd).toHaveAttribute("aria-valuenow", "0");
  expect(await page.locator("[data-preview-exclusion]").evaluate(path =>
    (path.getAttribute("d").match(/M/g) || []).length)).toBe(1);
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 2);
  expect(calls[1].body.preferences.azimuthDegrees)
    .toEqual({ excluded: { start: 359, end: 0 } });
});

test("keeps the last 10 altitude degrees elastic without changing logical state", async ({
  page
}, testInfo) => {
  await preloadState(page, {
    version: 1,
    altitudeDegrees: { minimum: 2, maximum: 15 }
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  const minimum = page.getByRole("slider", { name: "Minimum Moon altitude" });
  const maximum = page.getByRole("slider", { name: "Maximum Moon altitude" });
  await focusAndPress(page, maximum, ["Shift+ArrowDown"]);
  await expect(maximum).toHaveAttribute("aria-valuenow", "12");
  await minimum.evaluate(element => {
    const observer = new MutationObserver(() => {
      if (element.classList.contains("is-rebounding-up")) {
        element.setAttribute("data-test-rebound-seen", "true");
        observer.disconnect();
      }
    });
    observer.observe(element, { attributeFilter: ["class"] });
  });
  await focusAndPress(page, minimum, ["ArrowUp"]);
  await expect(minimum).toHaveAttribute("aria-valuenow", "2");
  await expect(minimum).toHaveAttribute("data-test-rebound-seen", "true");

  const drag = testInfo.project.name === "mobile" ? dragTouchHandle : dragMouseHandle;
  await drag(page, minimum, 0, -30);
  await expect(minimum).toHaveAttribute("aria-valuenow", "2");
  await expect(maximum).toHaveAttribute("aria-valuenow", "12");
});

test("aligns boundaries, removes the green seam, and mirrors red markers", async ({
  page
}, testInfo) => {
  await preloadState(page, {
    version: 1,
    altitudeDegrees: { minimum: 0.274, maximum: 10.274 },
    azimuthDegrees: {
      included: { start: 54.002, end: 64.002 }
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
    [end, "[data-bearing-handle='included-end']"]
  ], testInfo.project.name === "mobile");
  const fill = page.locator("[data-azimuth-fill='included-left']");
  expect(await fill.evaluate(element =>
    getComputedStyle(element, "::before").backgroundColor)).toBe("rgb(33, 111, 104)");
  expect(await start.evaluate(element =>
    getComputedStyle(element, "::before").width)).toBe("7px");
  expect(await blockedStart.evaluate(element =>
    getComputedStyle(element, "::before").width)).toBe("7px");
  expect(await page.locator(".preference-included-handle").evaluateAll(handles =>
    handles.map(handle => getComputedStyle(handle, "::before").opacity)))
    .toEqual(["0", "0"]);

  const edges = await page.evaluate(() => {
    const rectangle = selector => document.querySelector(selector).getBoundingClientRect();
    const svg = rectangle("#preference-angular-artwork");
    const start = rectangle("[data-bearing-handle='included-start']");
    const end = rectangle("[data-bearing-handle='included-end']");
    const fill = rectangle("[data-azimuth-fill='included-left']");
    const svgX = bearing => svg.left + (40 + bearing / 360 * 300) / 360 * svg.width;
    return {
      start: [start.right, fill.left, svgX(54.002)],
      end: [end.left, fill.right, svgX(64.002)]
    };
  });
  for (const aligned of [edges.start, edges.end]) {
    expect(Math.max(...aligned) - Math.min(...aligned)).toBeLessThanOrEqual(1);
  }
  await expect(page.locator("[data-azimuth-fill='included-left']"))
    .toHaveClass(/is-handle-bridge/);
  expect(await page.locator("[data-preview-exclusion]").evaluate(path =>
    (path.getAttribute("d").match(/M/g) || []).length)).toBe(4);
  expect(await blockedStart.evaluate(element =>
    getComputedStyle(element, "::before").left)).toBe("24px");
  expect(await blockedEnd.evaluate(element =>
    getComputedStyle(element, "::before").right)).toBe("24px");
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
  await expect(page.locator("#preference-angular-artwork")).toHaveAttribute("aria-hidden", "true");
  await expect(page.locator(".preference-preview-moon")).toHaveCount(13);
  expect(await page.evaluate(async () => {
    const module = await import(window.location.origin + "/moonPhaseView.js");
    const expected = module.moonPhaseImageDataUrl(65, 42, 270, 0);
    return Array.from(document.querySelectorAll(".preference-preview-moon"))
      .every(image => image.getAttribute("href") === expected);
  })).toBe(true);
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
  await expect(page.locator(".preference-axis-help")).toHaveCount(0);
  await expect(page.locator(".preference-altitude-axis-label")).toHaveText("Altitude");
  await expect(page.locator(".preference-preview-bearing-direction"))
    .toHaveText("Bearing increases →");
  await expect(altitudeMinimum).toHaveAttribute(
    "aria-describedby", /preference-handle-instructions preference-altitude-minimum-description/);

  const zone = page.locator("[data-preference-zone]");
  const zoneTooltip = zone.locator(".preference-zone-tooltip");
  const zoneBox = await zone.boundingBox();
  expect(zoneBox).not.toBeNull();
  const hoverZone = async (bearing, altitude) => zone.hover({ position: {
    x: zoneBox.width * bearing / 360,
    y: zoneBox.height * (1 - Math.pow(altitude / 90, 0.85))
  } });
  await hoverZone(340, 8);
  await expect(zoneTooltip)
    .toHaveText("Azimuth 330°–350° and altitude 2°–15° are included.");
  await hoverZone(355, 8);
  await expect(zoneTooltip).toHaveText("Azimuth 350°–10° is blocked.");
  await hoverZone(340, 30);
  await expect(zoneTooltip).toHaveText("Altitude 15°–90° is excluded.");

  await expect(zone).toHaveClass(/is-tooltip-visible/);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/, { timeout: 2500 });
  await hoverZone(340, 8);
  await altitudeMinimum.hover();
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);
  await expect(page.locator(".preference-control-tooltip")).toHaveCount(0);
  await altitudeMinimum.focus();
  await page.keyboard.press("Home");
  await altitudeMaximum.focus();
  await page.keyboard.press("End");

  const handleHelp = page.locator(".preference-handle-help");
  const helpToggle = handleHelp.getByRole("button", { name: "? Handle help" });
  const previewBox = await preview.boundingBox();
  await expect(helpToggle).toHaveAttribute("aria-expanded", "false");
  await helpToggle.click();
  await expect(handleHelp.locator(".preference-handle-help-content"))
    .toContainText(/green handles define usable direction, and red\s+handles define blocked direction/);
  const helpBox = await handleHelp.boundingBox();
  expect(helpBox?.y).toBeGreaterThanOrEqual(previewBox.y + previewBox.height);

  const exclusion = page.locator("[data-preview-exclusion]");
  const beforeBlockedChange = await exclusion.getAttribute("d");
  expect(beforeBlockedChange).toBeTruthy();

  const blockedStart = page.getByRole("slider", { name: "Blocked view start" });
  await blockedStart.focus();
  await page.keyboard.press("Shift+ArrowLeft");
  await expect(blockedStart).toHaveAttribute("aria-valuenow", "340");
  expect(await exclusion.getAttribute("d")).not.toBe(beforeBlockedChange);

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
  { name: "duplicate named phases",
    state: { version: 1, namedPhases: ["full_moon", "full_moon"] } },
  { name: "an unknown named phase",
    state: { version: 1, namedPhases: ["blue_moon"] } },
  { name: "a bright-limb range with the wrong tolerance",
    state: { version: 1, brightLimbOrientationDegrees: [{ start: 10, end: 40 }] } },
  { name: "an altitude range under 10°",
    state: { version: 1, altitudeDegrees: { minimum: 2, maximum: 11 } } },
  { name: "equal included azimuth endpoints",
    state: { version: 1, azimuthDegrees: { included: { start: 40, end: 40 } } } },
  { name: "a usable azimuth split under 10°",
    state: { version: 1, azimuthDegrees: { included: { start: 40, end: 140 },
      excluded: { start: 45, end: 135 } } } }
];

for (const invalid of invalidStoredStates) {
  test("discards stored preferences with " + invalid.name, async ({ page }) => {
    await preloadState(page, invalid.state);
    await page.goto("/search?q=Prague");
    await expect(page.locator("#preference-storage-notice"))
      .toContainText("Saved preferences were discarded");
    expect(await storedPreferences(page)).toBeNull();
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
  for (const [locator, selector, markerEdge] of entries) {
    await locator.scrollIntoViewIfNeeded();
    const box = await locator.boundingBox();
    expect(box).not.toBeNull();
    const center = {
      x: markerEdge === "left" ? box.x + 4
        : (markerEdge === "right" ? box.x + box.width - 4 : box.x + box.width / 2),
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
    await details.locator(":scope > summary").click();
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
