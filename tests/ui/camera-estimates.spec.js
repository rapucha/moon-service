import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.cameraSetup.v1";
const DEFAULT_SETUP = {
  version: 1,
  captureFormat: "digital_full_frame",
  outputMegapixels: 24,
  focalLengthMm: 300,
  teleconverterMultiplier: 1
};
const FULL_RESPONSE = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url), "utf8"
));
const RESPONSE = structuredClone(FULL_RESPONSE);
RESPONSE.opportunities = [RESPONSE.opportunities[0]];
RESPONSE.opportunities[0].moonPass.path = {};
RESPONSE.opportunities[0].moonPath = {};
const ZERO_RESPONSE = structuredClone(RESPONSE);
ZERO_RESPONSE.opportunities[0].moon.illuminationPercent = 0;
const ORDINARY_EMPTY = {
  ...FULL_RESPONSE,
  opportunities: [],
  emptyReason: { code: "no_opportunities", text: "No opportunity matched." }
};
const PLANNING_SUCCESS = planningSuccess(FULL_RESPONSE);
const ACTIVE_PREFERENCES = { version: 1, altitudeDegrees: { minimum: 3, maximum: 18 } };
const FORMAT_VALUES = [
  "digital_full_frame", "digital_aps_c", "digital_micro_four_thirds",
  "digital_medium_44x33", "film"
];
const DISCLOSURE_TEST = "uses a working Camera setup disclosure at each viewport width";
const MP_SUGGESTIONS = [
  6, 8, 10, 12, 16, 20, 24, 26, 30, 33, 36, 40, 42, 45, 50, 61, 80, 100, 102, 150
];
const FOCAL_SUGGESTIONS = [
  10, 11, 12, 14, 15, 16, 17, 18, 20, 21, 24, 28, 30, 31, 35, 40, 43, 45,
  50, 55, 60, 70, 77, 80, 85, 100, 105, 120, 135, 150, 180, 200, 250, 270,
  300, 400, 450, 500, 600, 800, 1000, 1200
];
const INVALID_STORED_SETUPS = [
  { name: "malformed JSON", raw: "{bad" },
  { name: "an extra field", raw: JSON.stringify({ ...DEFAULT_SETUP, horizontalPixels: 6000 }) },
  { name: "a missing field", raw: JSON.stringify({
    version: 1,
    captureFormat: "digital_full_frame",
    outputMegapixels: 24,
    focalLengthMm: 300
  }) },
  { name: "an unsupported version", raw: JSON.stringify({ ...DEFAULT_SETUP, version: 2 }) }
];

test.beforeEach(async ({ page: _page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile" && testInfo.title !== DISCLOSURE_TEST,
    "The focused camera contract runs once per browser engine.");
});

test(DISCLOSURE_TEST, async ({ page }, testInfo) => {
  await page.goto("/search");
  const details = page.locator("#camera-setup");
  const summary = details.locator(":scope > summary");
  const initiallyOpen = testInfo.project.name === "desktop";
  expect(await details.evaluate(node => node instanceof HTMLDetailsElement)).toBe(true);
  await expect(summary).toBeVisible();
  await expect(details).toHaveJSProperty("open", initiallyOpen);
  await summary.click();
  await expect(details).toHaveJSProperty("open", !initiallyOpen);
  await summary.click();
  await expect(details).toHaveJSProperty("open", initiallyOpen);
});

test("builds the accessible editor with approved defaults, choices, and suggestions", async ({ page }) => {
  await routeJson(page, RESPONSE);
  await page.goto("/search?q=Prague");
  const editor = await openEditor(page);
  const form = page.locator("#camera-setup-form");
  const format = form.getByLabel("Capture format");
  const mp = page.getByLabel("Output resolution (MP)");
  const focal = page.getByLabel("Marked focal length (mm)");
  const teleconverter = page.getByLabel("Teleconverter");
  const formatHelp = page.locator("#camera-format-help");
  const mpHelp = page.locator("#camera-mp-help");
  const focalHelp = page.locator("#camera-focal-help");

  await expect(format).toHaveValue("digital_full_frame");
  await expect(mp).toHaveValue("24");
  await expect(focal).toHaveValue("300");
  await expect(teleconverter).toHaveValue("1");
  await expect(page.locator("#camera-focal-used")).toBeHidden();
  await expect(editor.getByRole("button", { name: /update|apply/i })).toHaveCount(0);
  await expect(editor.getByRole("button", { name: "Reset", exact: true })).toBeVisible();
  const formatWithinForm = await format.evaluate(control => {
    const formBox = control.closest("#camera-setup-form")?.getBoundingClientRect();
    const controlBox = control.getBoundingClientRect();
    return Boolean(formBox && controlBox.left >= formBox.left - 0.5
      && controlBox.right <= formBox.right + 0.5);
  });
  expect(formatWithinForm).toBe(true);
  for (const help of [mpHelp, focalHelp]) {
    await expect(help).toBeVisible();
    await expect(help).toContainText(/suggest/i);
    await expect(help).toContainText(/custom|type any positive/i);
  }
  expect(await format.evaluate(node =>
    node.closest(".camera-field")?.parentElement?.nextElementSibling?.id
      === "camera-format-help")).toBe(true);
  expect(await mp.evaluate(node =>
    node.closest(".camera-field")?.parentElement?.nextElementSibling?.id
      === "camera-mp-help")).toBe(true);
  expect(await focal.evaluate(node =>
    node.closest(".camera-field")?.parentElement?.nextElementSibling?.id
      === "camera-focal-help")).toBe(true);
  await expect(formatHelp)
    .toHaveText(/Digital uses output MP.*film original.*independent of film size/);
  expect(await hasVisibleLabelGap(teleconverter, "Teleconverter")).toBe(true);

  expect(await format.locator("option").evaluateAll(options => options.map(option =>
    /** @type {HTMLOptionElement} */ (option).value)))
    .toEqual(FORMAT_VALUES);
  expect(await format.locator("optgroup").evaluateAll(groups => groups.map(group =>
    /** @type {HTMLOptGroupElement} */ (group).label)))
    .toEqual(["Digital", "Film"]);
  const formatLabels = await format.locator("option").allTextContents();
  expect(formatLabels.slice(0, 4)).toEqual([
    "Full-frame digital", "APS-C digital", "Micro Four Thirds digital",
    "Medium format 44×33 digital"
  ]);
  expect(formatLabels.slice(4)).toEqual(["Film"]);
  expect(formatLabels.join(" ")).not.toMatch(/Fujifilm|Pentax|35 mm|\b120\b|sheet/i);
  expect(await suggestionValues(page, "#camera-mp-suggestions")).toEqual(MP_SUGGESTIONS);
  expect(await suggestionValues(page, "#camera-focal-suggestions")).toEqual(FOCAL_SUGGESTIONS);
  expect(FOCAL_SUGGESTIONS).toEqual(expect.arrayContaining([15, 21, 31, 35, 40, 43, 70, 77]));
  expect(FOCAL_SUGGESTIONS).toEqual(expect.arrayContaining([60, 250]));
  for (const value of [21, 31, 43, 77]) {
    await focal.fill(String(value));
    await expect(focal).toHaveCSS("font-weight", "800");
  }
  await editor.getByRole("button", { name: "Reset", exact: true }).click();
  await expect(focal).toHaveValue("300");
  await expect(focal).not.toHaveClass(/camera-focal-easter-egg/);
  expect(await teleconverter.locator("option").evaluateAll(options => options.map(option => ({
    value: Number(/** @type {HTMLOptionElement} */ (option).value), label: option.textContent
  })))).toEqual([
    { value: 1, label: "None (1×)" },
    { value: 1.4, label: "1.4×" },
    { value: 1.7, label: "1.7×" },
    { value: 2, label: "2×" }
  ]);

  const estimate = page.locator(".moon-pass-card .camera-estimate");
  await expect(estimate).toHaveCount(1);
  await expect(estimate.locator("summary"))
    .toHaveText("Camera estimate — Illuminated Moon thickness");
  await expect(estimate).toHaveJSProperty("open", false);
  expect(await estimateFacts(estimate)).toEqual({
    "Illuminated angle": "0.42°",
    "Maximum thickness": "368 px"
  });
  await expect(editor.locator(".preference-intro"))
    .toContainText("Estimate Moon size in digital pixels");
  await expect(estimate).not.toContainText("capture sampling");
  await expect(estimate).not.toContainText("resizing changes the pixel result");
  await expect(estimate).not.toContainText("uncropped");
  await expect(estimate).toContainText("widest illuminated thickness");
  await expect(estimate).toContainText("tapers to zero at its horns");
  await expect(estimate).not.toContainText("multi-shot pixel-shift mode");
  await expect(estimate).toContainText("This works best with a regular lens. With a fisheye, keep the Moon near the center—the edges can stretch its apparent size.");
});

test("uses each digital geometry and applies decimal MP, focal, and teleconverter edits immediately", async ({ page }) => {
  const requests = [];
  await page.route("**/api/opportunities**", async route => {
    requests.push({
      method: route.request().method(),
      url: route.request().url(),
      body: route.request().postData()
    });
    await fulfillJson(route, RESPONSE, 200);
  });
  await page.goto("/search?q=Prague");
  await openEditor(page);
  const estimate = page.locator(".moon-pass-card .camera-estimate");
  const format = page.getByLabel("Capture format");

  for (const example of [
    { format: "digital_full_frame", label: "Full-frame digital", pixels: "368 px" },
    { format: "digital_aps_c", label: "APS-C digital", pixels: "563 px" },
    { format: "digital_micro_four_thirds", label: "Micro Four Thirds digital", pixels: "721 px" },
    { format: "digital_medium_44x33", label: "44×33", pixels: "285 px" }
  ]) {
    await format.selectOption(example.format);
    await expect(estimate).toContainText(example.label);
    await expect(estimate).toContainText(example.pixels);
  }

  await format.selectOption("digital_aps_c");
  await estimate.locator("summary").click();
  await expect(estimate).toHaveJSProperty("open", true);
  await page.getByLabel("City or town").fill("Unsubmitted location text");
  await page.getByLabel("Output resolution (MP)").fill("10.5");
  await page.getByLabel("Marked focal length (mm)").fill("123.4");
  await page.getByLabel("Teleconverter").selectOption("1.7");

  await expect(estimate).toHaveJSProperty("open", true);
  await expect(estimate).toContainText("123.4 mm × 1.7 = 209.78 mm");
  await expect(estimate).toContainText("10.5 MP");
  await expect(estimate).toContainText("260 px");
  await expect(page.locator("#camera-focal-used")).toBeVisible();
  await expect(page.locator("#camera-focal-used"))
    .toHaveText("Focal length used for this estimate: 209.78 mm (123.4 mm × 1.7).");
  await expect(page.getByLabel("City or town")).toHaveValue("Unsubmitted location text");
  expect(requests).toHaveLength(1);
  expect(await storedSetup(page)).toEqual({
    version: 1,
    captureFormat: "digital_aps_c",
    outputMegapixels: 10.5,
    focalLengthMm: 123.4,
    teleconverterMultiplier: 1.7
  });

  await page.reload();
  await expect(page.getByLabel("Capture format")).toHaveValue("digital_aps_c");
  await expect(page.getByLabel("Output resolution (MP)")).toHaveValue("10.5");
  await expect(page.getByLabel("Marked focal length (mm)")).toHaveValue("123.4");
  await expect(page.getByLabel("Teleconverter")).toHaveValue("1.7");
  await expect(page.locator("#camera-focal-used"))
    .toHaveText("Focal length used for this estimate: 209.78 mm (123.4 mm × 1.7).");
  await expect(estimate).toContainText("260 px");
  expect(requests).toHaveLength(2);
  for (const request of requests) {
    expect(request.method).toBe("GET");
    expect(request.body).toBeNull();
    const url = new URL(request.url);
    expect(Array.from(url.searchParams.entries())).toEqual([["q", "Prague"]]);
    expect(request.url).not.toMatch(/camera|focal|megapixel|teleconverter/i);
  }
  expect(new URL(page.url()).searchParams.toString()).toBe("q=Prague");
  expect(await page.evaluate(() => JSON.parse(
    window.localStorage.getItem("moonService.recentSearches.v1")
  ))).toEqual([{
    displayName: "Prague, Czechia",
    slug: "moon-service-3067696",
    timezone: "Europe/Prague"
  }]);
});

test("keeps the last valid estimate and stored setup during invalid numeric input", async ({ page }) => {
  let requestCount = 0;
  await page.route("**/api/opportunities**", async route => {
    requestCount += 1;
    await fulfillJson(route, RESPONSE, 200);
  });
  await page.goto("/search?q=Prague");
  await openEditor(page);
  const estimate = page.locator(".camera-estimate");
  const validation = page.locator("#camera-validation");
  const mp = page.getByLabel("Output resolution (MP)");
  const focal = page.getByLabel("Marked focal length (mm)");

  await mp.fill("61");
  await focal.fill("400");
  const lastValid = {
    version: 1,
    captureFormat: "digital_full_frame",
    outputMegapixels: 61,
    focalLengthMm: 400,
    teleconverterMultiplier: 1
  };
  await expect(estimate).toContainText("781 px");
  expect(await storedSetup(page)).toEqual(lastValid);
  await estimate.locator("summary").click();

  await focal.fill("");
  await expect(validation).toBeVisible();
  await expect(validation).toContainText("positive marked focal length");
  await expect(focal).toHaveAttribute("aria-invalid", "true");
  await expect(estimate).toContainText("781 px");
  await expect(estimate).toHaveJSProperty("open", true);
  expect(await storedSetup(page)).toEqual(lastValid);

  await focal.fill("400.5");
  await expect(validation).toBeHidden();
  await expect(estimate).toContainText("400.5 mm");
  expect(await storedSetup(page)).toEqual({ ...lastValid, focalLengthMm: 400.5 });

  await mp.fill("0");
  await expect(validation).toContainText("positive output MP value");
  await expect(mp).toHaveAttribute("aria-invalid", "true");
  await expect(estimate).toContainText("400.5 mm");
  expect(await storedSetup(page)).toEqual({ ...lastValid, focalLengthMm: 400.5 });
  await mp.fill("10.5");
  await expect(validation).toBeHidden();
  expect(await storedSetup(page)).toEqual({
    ...lastValid, outputMegapixels: 10.5, focalLengthMm: 400.5
  });
  expect(requestCount).toBe(1);
});

test("warns about very small and very large marked focal lengths", async ({ page }) => {
  await routeJson(page, RESPONSE);
  await page.goto("/search?q=Prague");
  await openEditor(page);
  const focal = page.getByLabel("Marked focal length (mm)");
  const teleconverter = page.getByLabel("Teleconverter");
  const warning = page.locator("#camera-focal-warning");
  await expect(warning).toBeHidden();

  await focal.fill("4");
  await expect(warning).toBeVisible();
  await expect(warning).toHaveText("This marked focal length is very small.");
  await expect(page.locator(".camera-estimate")).toContainText("4 mm");
  expect((await storedSetup(page)).focalLengthMm).toBe(4);
  await teleconverter.selectOption("2");
  await expect(warning).toHaveText("This marked focal length is very small.");
  await focal.fill("4.01");
  await expect(warning).toBeHidden();
  await focal.fill("5000");
  await expect(warning).toBeHidden();
  await focal.fill("5000.1");
  await expect(warning).toBeVisible();
  await expect(warning).toHaveText("This marked focal length is really big.");
  expect((await storedSetup(page)).focalLengthMm).toBe(5000.1);
  await expect(page.locator(".camera-estimate"))
    .toContainText("With a fisheye, keep the Moon near the center");

  await teleconverter.selectOption("1");
  await page.getByLabel("Output resolution (MP)").fill("6");
  await focal.fill("1");
  await expect(page.locator(".camera-estimate")).toContainText("<1 px");
  await page.getByRole("button", { name: "Reset", exact: true }).click();
  await expect(page.getByLabel("Capture format")).toHaveValue("digital_full_frame");
  await expect(page.getByLabel("Output resolution (MP)")).toHaveValue("24");
  await expect(focal).toHaveValue("300");
  await expect(page.getByLabel("Teleconverter")).toHaveValue("1");
  await expect(warning).toBeHidden();
  await expect(page.locator(".camera-estimate")).toContainText("368 px");
  expect(await storedSetup(page)).toEqual(DEFAULT_SETUP);
});

test("uses one neutral film estimate and restores the retained digital MP", async ({ page }) => {
  await routeJson(page, RESPONSE);
  await page.goto("/search?q=Prague");
  await openEditor(page);
  const format = page.getByLabel("Capture format");
  const mp = page.getByLabel("Output resolution (MP)");
  const mpHelp = page.locator("#camera-mp-help");
  const estimate = page.locator(".camera-estimate");
  await mp.fill("61");

  await format.selectOption("film");
  await expect(mp).toBeHidden();
  await expect(mpHelp).toBeHidden();
  expect(await estimateFacts(estimate)).toEqual({
    "Full Moon diameter": "2.72 mm",
    "Illuminated thickness": "2.21 mm"
  });
  expect(await storedSetup(page)).toEqual({
    ...DEFAULT_SETUP, captureFormat: "film", outputMegapixels: 61
  });
  const filmText = await estimate.textContent();
  expect(filmText).not.toMatch(/\d+\s*[×x]\s*\d+\s*mm/i);
  expect(filmText).not.toMatch(/35 mm|\b120\b|sheet|coverage|%/i);
  await expect(estimate).toContainText("not visibility");
  await expect(estimate).toContainText("film resolving power");
  await expect(estimate).toContainText("exposure");

  await page.getByLabel("Marked focal length (mm)").fill("1");
  expect(await estimateFacts(estimate)).toEqual({
    "Full Moon diameter": "<0.01 mm",
    "Illuminated thickness": "<0.01 mm"
  });
  await page.getByLabel("Marked focal length (mm)").fill("300");
  await format.selectOption("digital_full_frame");
  await expect(mp).toBeVisible();
  await expect(mpHelp).toBeVisible();
  await expect(mp).toHaveValue("61");
  await expect(estimate).toContainText("586 px");
  expect(await storedSetup(page)).toEqual({ ...DEFAULT_SETUP, outputMegapixels: 61 });
});

test("formats zero illuminated thickness for digital and film", async ({ page }) => {
  await routeJson(page, ZERO_RESPONSE);
  await page.goto("/search?q=Prague");
  await openEditor(page);
  const estimate = page.locator(".camera-estimate");
  expect(await estimateFacts(estimate)).toEqual({
    "Illuminated angle": "0.000°",
    "Maximum thickness": "0 px"
  });
  await page.getByLabel("Capture format").selectOption("film");
  expect(await estimateFacts(estimate)).toEqual({
    "Full Moon diameter": "2.72 mm",
    "Illuminated thickness": "0 mm"
  });
});

for (const invalid of INVALID_STORED_SETUPS) {
  test("silently resets stored camera setup with " + invalid.name, async ({ page }) => {
    await page.addInitScript(({ key, raw }) => {
      window.localStorage.setItem(key, raw);
    }, { key: STORAGE_KEY, raw: invalid.raw });
    await routeJson(page, RESPONSE);
    await page.goto("/search?q=Prague");

    await expect(page.getByLabel("Capture format")).toHaveValue(DEFAULT_SETUP.captureFormat);
    await expect(page.getByLabel("Output resolution (MP)")).toHaveValue("24");
    await expect(page.getByLabel("Marked focal length (mm)")).toHaveValue("300");
    await expect(page.getByLabel("Teleconverter")).toHaveValue("1");
    await expect(page.locator("#camera-storage-notice")).toBeHidden();
    await expect(page.locator("#camera-storage-notice"))
      .not.toContainText(/discarded|not supported|unsupported/i);
    expect(await page.evaluate(key => window.localStorage.getItem(key), STORAGE_KEY)).toBeNull();
  });
}

test("keeps valid edits in page memory and warns when camera storage is unavailable", async ({ page }) => {
  await page.addInitScript(key => {
    const originalSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (candidateKey, value) {
      if (candidateKey === key) throw new Error("Camera storage blocked");
      return originalSetItem.call(this, candidateKey, value);
    };
  }, STORAGE_KEY);
  await routeJson(page, RESPONSE);
  await page.goto("/search?q=Prague");
  await openEditor(page);

  await page.getByLabel("Marked focal length (mm)").fill("450");
  await expect(page.locator(".camera-estimate")).toContainText("551 px");
  await expect(page.locator("#camera-storage-notice")).toBeVisible();
  await expect(page.locator("#camera-storage-notice")).toContainText("unavailable");
  expect(await page.evaluate(key => window.localStorage.getItem(key), STORAGE_KEY)).toBeNull();
  await page.getByLabel("Marked focal length (mm)").fill("500");
  await expect(page.locator(".camera-estimate")).toContainText("613 px");
  await expect(page.getByLabel("Marked focal length (mm)")).toHaveValue("500");
});

test("keeps camera setup out of preference POST requests", async ({ page }) => {
  await page.addInitScript(({ cameraKey, camera, preferences }) => {
    window.localStorage.setItem(cameraKey, JSON.stringify(camera));
    window.localStorage.setItem("moonService.opportunityPreferences.v1", JSON.stringify(preferences));
  }, {
    cameraKey: STORAGE_KEY,
    camera: {
      version: 1,
      captureFormat: "digital_micro_four_thirds",
      outputMegapixels: 45.5,
      focalLengthMm: 450.5,
      teleconverterMultiplier: 1.4
    },
    preferences: ACTIVE_PREFERENCES
  });
  let captured = null;
  await page.route("**/api/opportunities", async route => {
    captured = { method: route.request().method(), body: route.request().postDataJSON() };
    await fulfillJson(route, RESPONSE, 200);
  });
  await page.goto("/search?q=Prague");
  await expect(page.locator(".moon-pass-card")).toBeVisible();
  expect(captured).toEqual({
    method: "POST", body: { q: "Prague", preferences: ACTIVE_PREFERENCES }
  });
});

test("omits estimates from ordinary loading, empty, and error states", async ({ page }) => {
  let releaseLookup = () => {};
  const lookupGate = new Promise(resolve => {
    releaseLookup = () => resolve(undefined);
  });
  await page.route("**/api/opportunities**", async route => {
    await lookupGate;
    await fulfillJson(route, ORDINARY_EMPTY, 200);
  });
  await page.goto("/search?q=Prague");
  await expect(page.getByRole("heading", { name: "Looking up Prague" })).toBeVisible();
  await expect(page.locator(".camera-estimate")).toHaveCount(0);
  releaseLookup();
  await expect(page.getByText(/No opportunities found in the next/)).toBeVisible();
  await expect(page.locator(".camera-estimate")).toHaveCount(0);

  await page.unroute("**/api/opportunities**");
  await page.route("**/api/opportunities**", route => fulfillJson(route,
    { status: "temporarily_unavailable", message: "Try again shortly." }, 503));
  await page.reload();
  await expect(page.getByRole("heading", { name: "Lookup temporarily unavailable" })).toBeVisible();
  await expect(page.locator(".camera-estimate")).toHaveCount(0);
});

test("shows and live-updates an estimate only after planning succeeds", async ({ page }) => {
  let releasePlanning = () => {};
  const planningGate = new Promise(resolve => {
    releasePlanning = () => resolve(undefined);
  });
  let requestCount = 0;
  await page.route("**/api/opportunities**", async route => {
    requestCount += 1;
    const planning = new URL(route.request().url()).pathname.endsWith("/planning");
    if (planning) await planningGate;
    await fulfillJson(route, planning ? PLANNING_SUCCESS : ORDINARY_EMPTY, 200);
  });

  await page.goto("/search?q=Prague");
  await expect(page.getByRole("button", { name: "Find the next matching Moon date" })).toBeVisible();
  await expect(page.locator(".camera-estimate")).toHaveCount(0);
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();
  await expect(page.getByRole("heading", { name: "Searching for the next matching Moon date" }))
    .toBeVisible();
  await expect(page.locator(".camera-estimate")).toHaveCount(0);

  releasePlanning();
  const estimate = page.locator(".planning-date-card .camera-estimate");
  await expect(estimate.locator("summary"))
    .toHaveText("Camera estimate — Illuminated Moon thickness");
  await expect(estimate).toHaveJSProperty("open", false);
  expect(await estimateFacts(estimate)).toEqual({
    "Illuminated angle": "0.46°",
    "Maximum thickness": "404 px"
  });
  await estimate.locator("summary").click();
  await openEditor(page);
  await page.getByLabel("Marked focal length (mm)").fill("600");
  await expect(estimate).toContainText("808 px");
  await expect(estimate).toHaveJSProperty("open", true);
  expect(requestCount).toBe(2);
});

async function openEditor(page) {
  const editor = page.locator("#camera-setup");
  if (!await editor.evaluate(node => /** @type {HTMLDetailsElement} */ (node).open)) {
    await editor.locator("summary").click();
  }
  return editor;
}

async function hasVisibleLabelGap(control, expectedText) {
  return control.evaluate((node, text) => {
    const label = node.closest("label");
    const walker = label ? document.createTreeWalker(label, NodeFilter.SHOW_TEXT) : null;
    let textNode = walker?.nextNode();
    while (textNode && !textNode.textContent?.includes(text)) textNode = walker?.nextNode();
    if (!textNode) return false;
    const range = document.createRange();
    range.selectNodeContents(textNode);
    const textBox = range.getBoundingClientRect();
    const controlBox = node.getBoundingClientRect();
    const horizontalGap = Math.max(controlBox.left - textBox.right, textBox.left - controlBox.right);
    const verticalGap = Math.max(controlBox.top - textBox.bottom, textBox.top - controlBox.bottom);
    return controlBox.width > 0 && controlBox.height > 0 && Math.max(horizontalGap, verticalGap) >= 3;
  }, expectedText);
}

async function routeJson(page, response) {
  await page.route("**/api/opportunities**", route => fulfillJson(route, response, 200));
}

async function fulfillJson(route, body, status) {
  await route.fulfill({ status: status, contentType: "application/json", body: JSON.stringify(body) });
}

async function suggestionValues(page, selector) {
  return page.locator(selector + " option").evaluateAll(options => options.map(option =>
    Number(/** @type {HTMLOptionElement} */ (option).value)));
}

async function estimateFacts(estimate) {
  return estimate.locator("dl > div").evaluateAll(rows => Object.fromEntries(rows.map(row => [
    row.querySelector("dt")?.textContent,
    row.querySelector("dd")?.textContent
  ])));
}

async function storedSetup(page) {
  return page.evaluate(key => JSON.parse(window.localStorage.getItem(key)), STORAGE_KEY);
}

function planningSuccess(response) {
  const source = response.opportunities[0];
  const moon = {
    ...source.moon,
    illuminationPercent: 89,
    brightLimbTiltDegrees: 274.8,
    northPoleTiltDegrees: 31.2
  };
  const samples = source.moonPass.path.samples.map(point => planningPoint(point, moon));
  return {
    status: "ok",
    generatedAt: response.generatedAt,
    startsAt: response.startsAt,
    endsAt: response.endsAt,
    planningHorizonDays: response.forecastHorizonDays,
    location: response.location,
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: {},
    ignoredPreferenceFields: [],
    ignoredPreferenceFieldCount: 0,
    additionalIgnoredPreferenceFieldCount: 0,
    nextPlanningWindow: {
      id: source.id,
      windowKind: source.windowKind,
      startsAt: source.startsAt,
      suggestedAt: source.suggestedAt,
      endsAt: source.endsAt,
      localTimeZone: source.localTimeZone,
      moon: moon,
      sun: {
        altitudeDegrees: source.sun.altitudeDegrees,
        azimuthDegrees: source.sun.azimuthDegrees,
        lightBucket: source.sun.lightBucket
      },
      moonPass: {
        id: source.moonPass.id,
        startsAt: source.moonPass.startsAt,
        endsAt: source.moonPass.endsAt,
        path: { start: samples[0], end: samples[samples.length - 1], samples: samples }
      }
    }
  };
}

function planningPoint(point, moon) {
  return {
    ...point,
    moonPhaseAngleDegrees: moon.phaseAngleDegrees,
    brightLimbTiltDegrees: moon.brightLimbTiltDegrees,
    northPoleTiltDegrees: moon.northPoleTiltDegrees
  };
}
