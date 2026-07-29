import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const CYCLE_MILLISECONDS = 1400;
const SHAPES = [
  { label: "New", value: "new", angle: 0 },
  { label: "Crescent", value: "crescent", angle: 45 },
  { label: "Half", value: "half", angle: 90 },
  { label: "Gibbous", value: "gibbous", angle: 135 },
  { label: "Full", value: "full", angle: 180 }
];
const CANONICAL_PHASES = [
  "new_moon", "waxing_crescent", "first_quarter", "waxing_gibbous",
  "full_moon", "waning_gibbous", "last_quarter", "waning_crescent"
];
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

test("treats all shapes as unrestricted and expands proper subsets canonically", async ({
  page
}) => {
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await expect(page.locator(".pass-choice-card").first()).toContainText("Waxing gibbous");
  await openPreferences(page);

  await expect(page.locator("[data-moon-shape]:checked")).toHaveCount(SHAPES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 2);
  expect(calls[1]).toMatchObject({ method: "GET", body: null });
  expect(await storedPreferences(page)).toBeNull();

  await openPreferences(page);
  const interactionOrder = ["full", "gibbous", "half", "crescent"];
  const canonicalSubset = [
    "waxing_crescent", "first_quarter", "waxing_gibbous", "full_moon",
    "waning_gibbous", "last_quarter", "waning_crescent"
  ];
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
  await expect(page.locator("[data-moon-shape]:checked")).toHaveCount(SHAPES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
  expect(await storedPreferences(page)).toBeNull();
});

test("keeps the final shape selected with persistent accessible guidance", async ({ page }) => {
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["full"]);

  const full = page.getByLabel("Full", { exact: true });
  const help = page.locator("#preference-phase-help");
  await expect(help).toHaveText(
    "Waxing and waning phases share a shape; results keep the exact phase name. Select all five for no shape limit; at least one must remain."
  );
  expect(await page.locator("[data-moon-shape]").evaluateAll(inputs =>
    inputs.every(input => input.getAttribute("aria-describedby") === "preference-phase-help")
  )).toBe(true);
  await full.focus();
  await page.keyboard.press("Space");
  await expect(full).toBeChecked();
  await expect(full).toBeFocused();
  await expect(page.locator("[data-moon-shape]:checked")).toHaveCount(1);
  await expect(page.locator("#preference-form-status")).toBeEmpty();
});

test("restores supported stored shape unions in canonical order", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    namedPhases: ["waning_crescent", "new_moon", "waxing_crescent"]
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const canonicalSubset = ["new_moon", "waxing_crescent", "waning_crescent"];
  expect(calls[0].method).toBe("POST");
  expect(calls[0].body.preferences.namedPhases).toEqual(canonicalSubset);
  expect(await storedPreferences(page)).toEqual({
    version: 1,
    namedPhases: canonicalSubset
  });
  await openPreferences(page);
  expect(await checkedShapeValues(page)).toEqual(["new", "crescent"]);
});

test("discards an asymmetric stored phase subset without broadening it", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    namedPhases: ["waxing_crescent"]
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  expect(calls[0]).toMatchObject({ method: "GET", body: null });
  expect(await storedPreferences(page)).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toHaveText(
    "Saved preferences were discarded because their format is not supported."
  );
});

test("normalizes a stored all-phase list to unrestricted", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    namedPhases: CANONICAL_PHASES.slice().reverse()
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  expect(calls[0]).toMatchObject({ method: "GET", body: null });
  expect(await storedPreferences(page)).toBeNull();
  await openPreferences(page);
  await expect(page.locator("[data-moon-shape]:checked")).toHaveCount(SHAPES.length);
  await expect(page.locator("#preference-count")).toHaveText("None active");
});

test("draws fixed left-lit decorative thumbnails for all five shapes", async ({ page }) => {
  await page.goto("/search");
  await openPreferences(page);

  const inputs = page.locator("[data-moon-shape]");
  const thumbnails = page.locator("[data-phase-thumbnail]");
  await expect(inputs).toHaveCount(SHAPES.length);
  await expect(thumbnails).toHaveCount(SHAPES.length);
  expect(await thumbnails.evaluateAll(canvases =>
    canvases.every(canvas => canvas.getAttribute("aria-hidden") === "true")
  )).toBe(true);
  expect(await inputs.evaluateAll(elements => elements.map(input =>
    /** @type {HTMLInputElement} */ (input).value
  )))
    .toEqual(SHAPES.map(shape => shape.value));
  expect(await canvasDataUrls(thumbnails))
    .toEqual(await expectedThumbnailDataUrls(page));
});

test("cycles selected shapes once each without moving the limb target", async ({
  page
}) => {
  await page.clock.install();
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["crescent", "full"]);

  const enabled = page.getByLabel("Limit illuminated-edge direction");
  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  const preview = page.locator("#preference-limb-moon");
  await enabled.check();
  await expect(handle).toHaveAttribute("aria-valuenow", "270");

  await expectMainPhase(page, preview, 45, 270);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 270);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 45, 270);

  await selectOnly(page, ["half", "gibbous"]);
  await expectMainPhase(page, preview, 90, 270);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 135, 270);
  await expect(handle).toHaveAttribute("aria-valuenow", "270");
});

test("runs one timer only and pauses whenever the animated preview is hidden", async ({
  page
}) => {
  await page.clock.install();
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["half", "gibbous", "full"]);

  const enabled = page.getByLabel("Limit illuminated-edge direction");
  const preview = page.locator("#preference-limb-moon");
  await enabled.check();
  await expectMainPhase(page, preview, 90, 270);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 135, 270);

  await setDisclosure(page, false);
  const beforeDisclosurePause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeDisclosurePause);
  await setDisclosure(page, true);
  expect(await canvasDataUrl(preview)).toBe(beforeDisclosurePause);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 270);

  await enabled.uncheck();
  const beforeEditorPause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeEditorPause);
  await enabled.check();
  await expectMainPhase(page, preview, 90, 270);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 135, 270);

  await setDocumentVisibility(page, "hidden");
  const beforeDocumentPause = await canvasDataUrl(preview);
  await page.clock.runFor(CYCLE_MILLISECONDS * 2);
  expect(await canvasDataUrl(preview)).toBe(beforeDocumentPause);
  await setDocumentVisibility(page, "visible");
  expect(await canvasDataUrl(preview)).toBe(beforeDocumentPause);
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 180, 270);
});

test("shows the first selected shape without cycling in reduced motion", async ({ page }) => {
  await page.clock.install();
  await page.goto("/search");
  await openPreferences(page);
  await selectOnly(page, ["crescent", "gibbous"]);

  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  const preview = page.locator("#preference-limb-moon");
  await page.getByLabel("Limit illuminated-edge direction").check();
  await page.clock.runFor(CYCLE_MILLISECONDS);
  await expectMainPhase(page, preview, 135, 270);

  await page.emulateMedia({ reducedMotion: "reduce" });
  await expect.poll(() => canvasDataUrl(preview))
    .toBe(await expectedMainPhaseDataUrl(page, 45, 270));
  await page.clock.runFor(CYCLE_MILLISECONDS * 3);
  await expectMainPhase(page, preview, 45, 270);
  await expect(handle).toHaveAttribute("aria-valuenow", "270");
});

test("uses eight gap-free 45-degree sectors and axis-only interaction", async ({ page }) => {
  const sectors = [
    { target: 0, range: { start: 337.5, end: 22.5 } },
    { target: 45, range: { start: 22.5, end: 67.5 } },
    { target: 90, range: { start: 67.5, end: 112.5 } },
    { target: 135, range: { start: 112.5, end: 157.5 } },
    { target: 180, range: { start: 157.5, end: 202.5 } },
    { target: 225, range: { start: 202.5, end: 247.5 } },
    { target: 270, range: { start: 247.5, end: 292.5 } },
    { target: 315, range: { start: 292.5, end: 337.5 } }
  ];
  sectors.forEach((sector, index) => {
    const width = (sector.range.end - sector.range.start + 360) % 360;
    expect(width).toBe(45);
    expect(sector.range.end).toBe(sectors[(index + 1) % sectors.length].range.start);
  });

  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByLabel("Limit illuminated-edge direction").check();
  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  const dial = page.locator("#preference-limb-dial");
  const ring = page.locator(".preference-tolerance-ring");
  const apply = page.getByRole("button", { name: "Use these limits" });
  await expect(handle).toHaveAttribute("aria-valuemin", "0");
  await expect(handle).toHaveAttribute("aria-valuemax", "315");
  await expect(handle).toHaveAttribute("aria-valuenow", "270");

  await handle.focus();
  await page.keyboard.press("End");
  await expect(handle).toHaveAttribute("aria-valuenow", "315");
  await page.keyboard.press("ArrowRight");
  await expect(handle).toHaveAttribute("aria-valuenow", "0");
  await page.keyboard.press("ArrowLeft");
  await expect(handle).toHaveAttribute("aria-valuenow", "315");
  await page.keyboard.press("Home");
  await expect(handle).toHaveAttribute("aria-valuenow", "0");
  await page.keyboard.press("ArrowDown");
  await expect(handle).toHaveAttribute("aria-valuenow", "315");
  await page.keyboard.press("ArrowUp");
  await expect(handle).toHaveAttribute("aria-valuenow", "0");
  await dial.evaluate(element => {
    const bounds = element.getBoundingClientRect();
    const radius = bounds.width / 3;
    const angle = Math.PI / 8;
    element.dispatchEvent(new PointerEvent("pointerdown", {
      bubbles: true,
      clientX: bounds.left + bounds.width / 2 + Math.sin(angle) * radius,
      clientY: bounds.top + bounds.height / 2 - Math.cos(angle) * radius
    }));
  });
  await expect(handle).toHaveAttribute("aria-valuenow", "45");
  await page.keyboard.press("Home");
  await expect(ring).toHaveAttribute(
    "style", /conic-gradient\(from 337\.5deg, .+ 0deg, .+ 45deg, transparent 45deg, transparent 360deg\)/
  );

  for (const [index, sector] of sectors.entries()) {
    await expect(handle).toHaveAttribute("aria-valuenow", String(sector.target));
    await apply.click();
    await waitForCallCount(calls, index + 2);
    const preferences = calls[index + 1].body.preferences;
    expect(preferences.brightLimbOrientationDegrees).toEqual([sector.range]);
    expect(await storedPreferences(page)).toEqual(preferences);
    if (index < sectors.length - 1) {
      await openPreferences(page);
      await handle.focus();
      await page.keyboard.press("ArrowRight");
    }
  }
});

test("migrates the deployed 20-degree limb sector to the canonical width", async ({ page }) => {
  await preloadState(page, {
    version: 1,
    brightLimbOrientationDegrees: [{ start: 25, end: 45 }]
  });
  const calls = await captureApiCalls(page);
  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const canonical = {
    version: 1,
    brightLimbOrientationDegrees: [{ start: 22.5, end: 67.5 }]
  };
  expect(calls[0].body.preferences).toEqual(canonical);
  expect(await storedPreferences(page)).toEqual(canonical);
  await openPreferences(page);
  const handle = page.getByRole("slider", { name: "Bright-limb target orientation" });
  await expect(handle).toHaveAttribute("aria-valuemax", "315");
  await expect(handle).toHaveAttribute("aria-valuenow", "45");
});

test("keeps the shape choices responsive and keyboard accessible", async ({
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
    const input = page.locator(`[data-moon-shape][value="${value}"]`);
    if (!await input.isChecked()) {
      await input.check();
    }
  }
  for (const shape of SHAPES) {
    const input = page.locator(`[data-moon-shape][value="${shape.value}"]`);
    if (!selected.includes(shape.value) && await input.isChecked()) {
      await input.uncheck();
    }
  }
}

async function checkedShapeValues(page) {
  return page.locator("[data-moon-shape]:checked")
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
  return page.evaluate(async shapes => {
    const modulePath = "/moonPhaseView.js";
    const { drawMoonPhase } = await import(modulePath);
    return shapes.map(shape => {
      const canvas = document.createElement("canvas");
      canvas.width = 40;
      canvas.height = 40;
      drawMoonPhase(canvas, shape.angle, 270, 0);
      return canvas.toDataURL("image/png");
    });
  }, SHAPES);
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
