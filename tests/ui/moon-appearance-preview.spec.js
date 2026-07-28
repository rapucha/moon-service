import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const CYCLE_MILLISECONDS = 1400;
const PHASES = [
  { label: "New", value: "new_moon", angle: 0 },
  { label: "Waxing crescent", value: "waxing_crescent", angle: 45 },
  { label: "First quarter", value: "first_quarter", angle: 90 },
  { label: "Waxing gibbous", value: "waxing_gibbous", angle: 135 },
  { label: "Full", value: "full_moon", angle: 180 },
  { label: "Waning gibbous", value: "waning_gibbous", angle: 225 },
  { label: "Last quarter", value: "last_quarter", angle: 270 },
  { label: "Waning crescent", value: "waning_crescent", angle: 315 }
];
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

test("treats all phases as unrestricted and persists proper subsets canonically", async ({
  page
}) => {
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(PHASES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 2);
  expect(calls[1]).toMatchObject({ method: "GET", body: null });
  expect(await storedPreferences(page)).toBeNull();

  await openPreferences(page);
  const interactionOrder = ["last_quarter", "full_moon", "first_quarter"];
  const canonicalSubset = ["first_quarter", "full_moon", "last_quarter"];
  await selectOnly(page, interactionOrder);
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 3);
  expect(calls[2].body.preferences.namedPhases).toEqual(canonicalSubset);
  expect(await storedPreferences(page)).toEqual({
    version: 1,
    namedPhases: canonicalSubset
  });
  await expect(page.locator("#preference-count")).toHaveText("1 active");

  await openPreferences(page);
  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await waitForCallCount(calls, 4);
  expect(calls[3]).toMatchObject({ method: "GET", body: null });
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(PHASES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
  expect(await storedPreferences(page)).toBeNull();
});

test("keeps the final phase selected with persistent accessible guidance", async ({ page }) => {
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["full_moon"]);

  const full = page.getByLabel("Full", { exact: true });
  const help = page.locator("#preference-phase-help");
  await expect(help).toHaveText(
    "Choose one or more. Select all eight for no phase limit; at least one must remain."
  );
  expect(await page.locator("[data-named-phase]").evaluateAll(inputs =>
    inputs.every(input => input.getAttribute("aria-describedby") === "preference-phase-help")
  )).toBe(true);
  await full.focus();
  await page.keyboard.press("Space");
  await expect(full).toBeChecked();
  await expect(full).toBeFocused();
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(1);
  await expect(page.locator("#preference-form-status")).toBeEmpty();
});

test("restores a valid stored phase subset in canonical order", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    namedPhases: ["waning_crescent", "new_moon", "full_moon"]
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const canonicalSubset = ["new_moon", "full_moon", "waning_crescent"];
  expect(calls[0].method).toBe("POST");
  expect(calls[0].body.preferences.namedPhases).toEqual(canonicalSubset);
  expect(await storedPreferences(page)).toEqual({
    version: 1,
    namedPhases: canonicalSubset
  });
  await openPreferences(page);
  expect(await checkedPhaseValues(page)).toEqual(canonicalSubset);
});

test("normalizes a stored all-phase list to unrestricted", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    namedPhases: PHASES.map(phase => phase.value).reverse()
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  expect(calls[0]).toMatchObject({ method: "GET", body: null });
  expect(await storedPreferences(page)).toBeNull();
  await openPreferences(page);
  await expect(page.locator("[data-named-phase]:checked")).toHaveCount(PHASES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
});

test("draws canonical decorative thumbnails for all eight phases", async ({ page }) => {
  await page.goto("/search");
  await openPreferences(page);

  const inputs = page.locator("[data-named-phase]");
  const thumbnails = page.locator("[data-phase-thumbnail]");
  await expect(inputs).toHaveCount(PHASES.length);
  await expect(thumbnails).toHaveCount(PHASES.length);
  expect(await thumbnails.evaluateAll(canvases =>
    canvases.every(canvas => canvas.getAttribute("aria-hidden") === "true")
  )).toBe(true);
  expect(await inputs.evaluateAll(elements => elements.map(input =>
    /** @type {HTMLInputElement} */ (input).value
  )))
    .toEqual(PHASES.map(phase => phase.value));
  expect(await canvasDataUrls(thumbnails))
    .toEqual(await expectedThumbnailDataUrls(page));
});

test("cycles selected phases and updates immediately without moving the limb target", async ({
  page
}) => {
  await page.clock.install();
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["waxing_crescent", "full_moon", "waning_crescent"]);

  const enabled = page.getByLabel("Limit illuminated-edge direction");
  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  const preview = page.locator("#preference-limb-moon");
  await enabled.check();
  await expect(handle).toHaveAttribute("aria-valuenow", "35");

  await expectMainPhase(page, preview, 45, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 315, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 45, 35);

  await selectOnly(page, ["waning_gibbous", "first_quarter"]);
  await expectMainPhase(page, preview, 90, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 225, 35);
  await expect(handle).toHaveAttribute("aria-valuenow", "35");
});

test("runs one timer only and pauses whenever the animated preview is hidden", async ({
  page
}) => {
  await page.clock.install();
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await selectOnly(page, ["first_quarter", "full_moon", "last_quarter"]);

  const enabled = page.getByLabel("Limit illuminated-edge direction");
  const preview = page.locator("#preference-limb-moon");
  await enabled.check();
  await expectMainPhase(page, preview, 90, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 35);

  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 2);
  await expectMainPhase(page, preview, 90, 35);
  await openPreferences(page);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 35);

  await setDisclosure(page, false);
  const beforeDisclosurePause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeDisclosurePause);
  await setDisclosure(page, true);
  expect(await canvasDataUrl(preview)).toBe(beforeDisclosurePause);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 270, 35);

  await enabled.uncheck();
  const beforeEditorPause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeEditorPause);
  await enabled.check();
  await expectMainPhase(page, preview, 90, 35);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 35);

  await setDocumentVisibility(page, "hidden");
  const beforeDocumentPause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeDocumentPause);
  await setDocumentVisibility(page, "visible");
  expect(await canvasDataUrl(preview)).toBe(beforeDocumentPause);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 270, 35);
});

test("shows the first selected phase without cycling in reduced motion", async ({ page }) => {
  await page.clock.install();
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["waxing_gibbous", "waning_crescent"]);

  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  const preview = page.locator("#preference-limb-moon");
  await page.getByLabel("Limit illuminated-edge direction").check();
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 315, 35);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect.poll(() => canvasDataUrl(preview))
    .toBe(await expectedMainPhaseDataUrl(page, 135, 35));
  await page.clock.runFor(CYCLE_MILLISECONDS * 3);
  await expectMainPhase(page, preview, 135, 35);
  await expect(handle).toHaveAttribute("aria-valuenow", "35");
});

test("keeps the phase choices responsive and keyboard accessible", async ({
  page
}, testInfo) => {
  if (testInfo.project.name === "mobile") {
    await page.setViewportSize({ width: 360, height: 844 });
  }
  await page.goto("/search");
  await openPreferences(page);

  const grid = page.locator(".preference-phase-grid");
  const labels = grid.locator("label");
  const expectedColumns = testInfo.project.name === "mobile" ? 1 : 2;
  const expectedThumbnailSize = testInfo.project.name === "mobile" ? 28 : 32;
  expect(await grid.evaluate(element =>
    getComputedStyle(element).gridTemplateColumns.split(" ").length
  )).toBe(expectedColumns);
  await expect(page.locator("[data-phase-thumbnail]").first())
    .toHaveCSS("width", expectedThumbnailSize + "px");
  const containment = await labels.evaluateAll(elements => {
    const gridBox = elements[0].parentElement.getBoundingClientRect();
    return elements.every(element => {
      const box = element.getBoundingClientRect();
      return box.left >= gridBox.left - 1 && box.right <= gridBox.right + 1;
    });
  });
  expect(containment).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth))
    .toBe(true);

  const newMoon = page.getByLabel("New", { exact: true });
  await newMoon.focus();
  await page.keyboard.press("Space");
  await expect(newMoon).not.toBeChecked();
  await expect(newMoon).toBeFocused();
});

async function captureApiCalls(page) {
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const body = request.postData();
    const call = {
      method: request.method(),
      body: body === null ? null : JSON.parse(body)
    };
    calls.push(call);
    const payload = /** @type {any} */ (JSON.parse(JSON.stringify(sourceFixture)));
    if (call.body?.preferences) {
      payload.appliedPreferenceVersion = 1;
      payload.normalizedActiveFilters = call.body.preferences;
      payload.excludedSampleCount = 0;
      payload.ignoredPreferenceFields = [];
      payload.ignoredPreferenceFieldCount = 0;
      payload.additionalIgnoredPreferenceFieldCount = 0;
    }
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(payload)
    });
  });
  return calls;
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

async function selectOnly(page, selected) {
  for (const value of selected) {
    const input = page.locator(`[data-named-phase][value="${value}"]`);
    if (!await input.isChecked()) {
      await input.check();
    }
  }
  for (const phase of PHASES) {
    const input = page.locator(`[data-named-phase][value="${phase.value}"]`);
    if (!selected.includes(phase.value) && await input.isChecked()) {
      await input.uncheck();
    }
  }
}

async function checkedPhaseValues(page) {
  return page.locator("[data-named-phase]:checked")
    .evaluateAll(inputs => inputs.map(input =>
      /** @type {HTMLInputElement} */ (input).value
    ));
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

async function canvasDataUrl(canvas) {
  return canvas.evaluate(element =>
    /** @type {HTMLCanvasElement} */ (element).toDataURL("image/png")
  );
}

async function canvasDataUrls(canvases) {
  return canvases.evaluateAll(elements => elements.map(element =>
    /** @type {HTMLCanvasElement} */ (element).toDataURL("image/png")
  ));
}

async function expectedThumbnailDataUrls(page) {
  return page.evaluate(async phases => {
    const modulePath = "/moonPhaseView.js";
    const { drawMoonPhase } = await import(modulePath);
    return phases.map(phase => {
      const canvas = document.createElement("canvas");
      canvas.width = 40;
      canvas.height = 40;
      drawMoonPhase(canvas, phase.angle);
      return canvas.toDataURL("image/png");
    });
  }, PHASES);
}

async function expectedMainPhaseDataUrl(page, angle, target) {
  return page.evaluate(async values => {
    const modulePath = "/moonPhaseView.js";
    const { drawMoonPhase } = await import(modulePath);
    const canvas = document.createElement("canvas");
    canvas.width = 160;
    canvas.height = 160;
    drawMoonPhase(canvas, values.angle, values.target, 0);
    const context = canvas.getContext("2d");
    context.beginPath();
    context.arc(canvas.width / 2, canvas.height / 2, canvas.width * 0.43,
      0, Math.PI * 2);
    context.strokeStyle = "#56606c";
    context.lineWidth = 2.5;
    context.stroke();
    return canvas.toDataURL("image/png");
  }, { angle, target });
}

async function expectMainPhase(page, canvas, angle, target) {
  expect(await canvasDataUrl(canvas)).toBe(
    await expectedMainPhaseDataUrl(page, angle, target)
  );
}

async function setDisclosure(page, open) {
  await page.locator("#opportunity-preferences").evaluate((node, next) => {
    /** @type {HTMLDetailsElement} */ (node).open = next;
  }, open);
  await page.waitForTimeout(10);
}

async function setDocumentVisibility(page, state) {
  await page.evaluate(next => {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      value: next
    });
    Object.defineProperty(document, "hidden", {
      configurable: true,
      value: next === "hidden"
    });
    document.dispatchEvent(new Event("visibilitychange"));
  }, state);
}
