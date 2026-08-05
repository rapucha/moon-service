import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const FULL_RESPONSE = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url), "utf8"
));
const RESPONSE = structuredClone(FULL_RESPONSE);
RESPONSE.opportunities = [RESPONSE.opportunities[0]];
RESPONSE.opportunities[0].moonPass.path = {};
RESPONSE.opportunities[0].moonPath = {};

const ANCHOR = { x: 0.46875, y: 0.5138888888888888 };
const REFERENCE_DISTANCE_METRES = 120;
const LEVELS = [
  level(0, 1350),
  level(1, 371.25),
  level(2, 102.09375),
  level(3, 28.07578125),
  level(4, 7.72083984375),
  level(5, 2.12323095703125)
];
const FORMATS = {
  fullFrame: format("digital_full_frame", 36, 3 / 2),
  apsC: format("digital_aps_c", 23.5, 3 / 2),
  microFourThirds: format("digital_micro_four_thirds", 17.3, 4 / 3),
  medium: format("digital_medium_44x33", 43.8, 4 / 3)
};
const FRAMING_UNAVAILABLE =
  "Example framing is unavailable. Moon detail and camera numbers are still available.";

test.beforeEach(async ({ page: _page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop",
    "The focused camera-preview contract runs once per browser engine.");
});

test("loads only the selected scene after opening and gives Film only Moon detail", async ({ page }) => {
  const mediaRequests = [];
  await routeJson(page, RESPONSE);
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/camera-preview/") || path.startsWith("/moon-textures/")) {
      mediaRequests.push(path);
    }
  });
  await page.goto("/search?q=Prague");

  const estimate = cameraEstimate(page);
  await expect(estimate).toHaveJSProperty("open", false);
  expect(mediaRequests).toEqual([]);
  await estimate.locator("summary").click();

  await expectPreviewReady(estimate);
  await expect.poll(() => distinctSceneRequests(mediaRequests))
    .toEqual([LEVELS[3].url]);
  expect(mediaRequests).not.toContain("/camera-preview/scene-pyramid.json");
  expect(mediaRequests.some(path => path === "/moon-textures/lroc_color_2k.jpg")).toBe(true);

  await page.getByLabel("Marked focal length (mm)").fill("2500");
  await expectPreviewReady(estimate);
  await expect.poll(() => distinctSceneRequests(mediaRequests).sort())
    .toEqual([LEVELS[3].url, LEVELS[5].url].sort());

  await page.getByLabel("Capture format").selectOption("film");
  await expect(estimate).toHaveJSProperty("open", true);
  await expect(estimate.getByText("Moon detail", { exact: true })).toBeVisible();
  await expect(estimate.getByText("Example framing", { exact: true })).toHaveCount(0);
  await expect(estimate.locator(".camera-preview-scene-canvas")).toHaveCount(0);
  expect(distinctSceneRequests(mediaRequests).sort())
    .toEqual([LEVELS[3].url, LEVELS[5].url].sort());
});

test("shows visible and accessible phase, orientation, sampling, and placement context", async ({ page }) => {
  await routeJson(page, RESPONSE);
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);

  const detailFigure = estimate.locator(".camera-preview-moon");
  const sceneFigure = estimate.locator(".camera-preview-scene");
  await expect(detailFigure.locator("figcaption"))
    .toContainText(/opportunity.*phase.*orientation/i);
  await expect(detailFigure)
    .toContainText(/maximum texture detail; this enlarged view is not to camera scale\./i);
  await expect(sceneFigure).toContainText(
    "Reference scene only—the scale is calculated; the placement is illustrative.");
  await expect(estimate.locator(".camera-preview-sampling"))
    .toHaveText("6,000 px × 4,000 px output; full Moon about 454 px across.");

  const notes = estimate.locator(".camera-preview-fallback");
  await expect(notes).toHaveCount(2);
  await expect(notes.nth(0)).toHaveText(
    "Bright-limb angle is unavailable, so the limb orientation is approximate and uses the location-independent orientation.");
  await expect(notes.nth(1)).toHaveText(
    "North-pole angle is unavailable, so the surface orientation is approximate and shown north-up.");
  await expect(estimate.locator(".camera-preview-moon-canvas"))
    .toHaveAttribute("aria-label", /Moon detail.*opportunity phase.*surface orientation/i);
  await expect(estimate.locator(".camera-preview-scene-canvas"))
    .toHaveAttribute("aria-label", /illustrative.*Moon.*clear sky.*fictional Mediterranean foreground/i);
});

test("renders finite geometry for representative cases across every digital format", async ({ page }) => {
  await instrumentCanvasDraws(page);
  await routeJson(page, withOrientation(RESPONSE));
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);

  const setups = [
    { focalLength: 4, teleconverter: 1 },
    { focalLength: 300, teleconverter: 1 },
    { focalLength: 2000, teleconverter: 1 },
    { focalLength: 2500, teleconverter: 1 },
    { focalLength: 2000, teleconverter: 2 }
  ];
  for (const selectedFormat of Object.values(FORMATS)) {
    for (const setup of setups) {
      const effectiveFocalLength = setup.focalLength * setup.teleconverter;
      const selectedLevel = levelForView(selectedFormat, effectiveFocalLength);
      const frame = await renderSetup(page, estimate, selectedFormat,
        setup.focalLength, setup.teleconverter, selectedLevel);
      expectRegisteredFrame(frame, selectedFormat, effectiveFocalLength, selectedLevel);
      const sensorHeight = selectedFormat.widthMm / selectedFormat.aspectRatio;
      for (const sensorSize of [selectedFormat.widthMm, sensorHeight]) {
        const fieldOfView = 2 * Math.atan(sensorSize / (2 * effectiveFocalLength))
          * 180 / Math.PI;
        expect(fieldOfView).toBeGreaterThan(0);
        expect(fieldOfView).toBeLessThan(180);
      }
    }
  }

  expect(await page.evaluate(() => document.documentElement.scrollWidth
    <= document.documentElement.clientWidth)).toBe(true);
});

test("selects all six levels across every boundary and keeps the shared anchor registered", async ({ page }) => {
  await instrumentCanvasDraws(page);
  await routeJson(page, withOrientation(RESPONSE));
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);

  const contacts = [];
  for (let index = 1; index < LEVELS.length; index += 1) {
    const boundary = sceneConstant(FORMATS.fullFrame) / LEVELS[index].worldWidth;
    const wider = await renderSetup(
      page, estimate, FORMATS.fullFrame, boundary * (1 - 0.0001), 1, LEVELS[index - 1]);
    const narrower = await renderSetup(
      page, estimate, FORMATS.fullFrame, boundary * (1 + 0.0001), 1, LEVELS[index]);
    const widerContact = expectRegisteredFrame(wider, FORMATS.fullFrame,
      boundary * (1 - 0.0001), LEVELS[index - 1]);
    const narrowerContact = expectRegisteredFrame(narrower, FORMATS.fullFrame,
      boundary * (1 + 0.0001), LEVELS[index]);
    expect(Math.abs(widerContact.x - narrowerContact.x)).toBeLessThan(0.01);
    expect(Math.abs(widerContact.y - narrowerContact.y)).toBeLessThan(0.01);
    contacts.push(widerContact, narrowerContact);
  }

  expect(new Set(contacts.map(contact =>
    `${contact.x.toFixed(4)}:${contact.y.toFixed(4)}`)).size).toBe(1);
  await expect(estimate).toHaveJSProperty("open", true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth
    <= document.documentElement.clientWidth)).toBe(true);
});

test("uses endpoint crops and representative focal lengths without stretching the scene", async ({ page }) => {
  await instrumentCanvasDraws(page);
  await routeJson(page, withOrientation(RESPONSE));
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);

  const cases = [
    renderCase(FORMATS.medium, 4, 1, LEVELS[0]),
    renderCase(FORMATS.fullFrame, 300, 1, LEVELS[3]),
    renderCase(FORMATS.fullFrame, 2000, 1, LEVELS[4]),
    renderCase(FORMATS.medium, 2500, 1, LEVELS[5]),
    renderCase(FORMATS.fullFrame, 2000, 2, LEVELS[5])
  ];
  const frames = [];
  for (const current of cases) {
    const frame = await renderSetup(page, estimate, current.format,
      current.focalLength, current.teleconverter, current.level);
    expectRegisteredFrame(frame, current.format,
      current.focalLength * current.teleconverter, current.level);
    frames.push(frame);
  }

  expect(frames[0].moon.width).toBeGreaterThan(0);
  expect(frames[0].moon.width).toBeLessThan(1);
  const levelFiveAt2500 = frames[3];
  const contactX = levelFiveAt2500.scene.canvasWidth * ANCHOR.x;
  expect(levelFiveAt2500.moon.x).toBeGreaterThan(contactX);
  expect(levelFiveAt2500.moon.x + levelFiveAt2500.moon.width)
    .toBeLessThanOrEqual(levelFiveAt2500.scene.canvasWidth);

  const underCovered = await renderSetup(
    page, estimate, FORMATS.fullFrame, 3, 1, LEVELS[0]);
  expectRegisteredFrame(underCovered, FORMATS.fullFrame, 3, LEVELS[0]);
  expect(underCovered.scene.width).toBeLessThan(underCovered.scene.canvasWidth);
  expect(underCovered.scene.x).toBeGreaterThan(0);
  expect(underCovered.scene.x + underCovered.scene.width)
    .toBeLessThan(underCovered.scene.canvasWidth);

  const overCropped = frames[4];
  expect(overCropped.scene.width).toBeGreaterThan(overCropped.scene.canvasWidth);
  expect(overCropped.scene.x).toBeLessThan(0);
  expect(overCropped.scene.x + overCropped.scene.width)
    .toBeGreaterThan(overCropped.scene.canvasWidth);
});

test("uses megapixels only for output and Moon sampling", async ({ page }) => {
  const sceneRequests = [];
  await instrumentCanvasDraws(page);
  await routeJson(page, withOrientation(RESPONSE));
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/camera-preview/")) sceneRequests.push(path);
  });
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);
  const initial = await latestConnectedFrame(page, LEVELS[3].url);
  const initialDimensions = await previewDimensions(estimate.locator(".camera-preview-scene-canvas"));
  const initialSampling = await estimate.locator(".camera-preview-sampling").textContent();

  const previousDraws = await canvasDrawCount(page);
  await page.getByLabel("Output resolution (MP)").fill("100");
  const changed = await waitForFrame(page, previousDraws, LEVELS[3].url);
  const changedDimensions = await previewDimensions(estimate.locator(".camera-preview-scene-canvas"));
  const changedSampling = await estimate.locator(".camera-preview-sampling").textContent();

  expect(changedSampling).not.toBe(initialSampling);
  expect(changedDimensions.backing).toEqual(initialDimensions.backing);
  expect(changedDimensions.display.width).toBeCloseTo(initialDimensions.display.width, 0);
  expect(changedDimensions.display.height).toBeCloseTo(initialDimensions.display.height, 0);
  expectDrawGeometry(changed.scene, initial.scene);
  expectDrawGeometry(changed.moon, initial.moon);
  expect(changed.moon.sourceWidth).toBeGreaterThan(initial.moon.sourceWidth);
  expect(distinctSceneRequests(sceneRequests)).toEqual([LEVELS[3].url]);
  await expect(estimate).toHaveJSProperty("open", true);
});

test("keeps the preview responsive and identifies only missing orientations as approximate", async ({ page }) => {
  await routeJson(page, withBrightLimb(RESPONSE));
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();
  await expectPreviewReady(estimate);

  const wideBoxes = await figureBoxes(estimate);
  expect(Math.abs(wideBoxes[0].top - wideBoxes[1].top)).toBeLessThan(2);
  await page.setViewportSize({ width: 660, height: 1000 });
  const narrowBoxes = await figureBoxes(estimate);
  expect(narrowBoxes[1].top).toBeGreaterThanOrEqual(narrowBoxes[0].bottom);
  expect(await page.evaluate(() => document.documentElement.scrollWidth
    <= document.documentElement.clientWidth)).toBe(true);

  await expect(estimate).toHaveJSProperty("open", true);
  await expect(estimate.locator(".camera-preview-fallback")).toHaveCount(1);
  await expect(estimate.locator(".camera-preview-fallback"))
    .toContainText("North-pole angle is unavailable");
  await expect(estimate).not.toContainText("Bright-limb angle is unavailable");
});

test("keeps numerical facts when the Moon texture cannot load", async ({ page }) => {
  const sceneRequests = [];
  await routeJson(page, RESPONSE);
  await page.route("**/moon-textures/lroc_color_2k.jpg", route => route.abort("failed"));
  await page.route("**/camera-preview/*.webp", route => {
    sceneRequests.push(new URL(route.request().url()).pathname);
    return route.continue();
  });
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();

  await expect(estimate.locator(".camera-preview-status"))
    .toHaveText("Camera preview is unavailable for this estimate.");
  await expect(estimate.locator(".camera-preview-figure")).toHaveCount(0);
  await expectNumericalFacts(estimate);
  expect(sceneRequests).toEqual([]);
});

test("does not substitute a neighboring level when the selected scene fails", async ({ page }) => {
  const sceneRequests = [];
  await routeJson(page, RESPONSE);
  await page.route("**/camera-preview/*.webp", route => {
    const path = new URL(route.request().url()).pathname;
    sceneRequests.push(path);
    return path === LEVELS[3].url ? route.abort("failed") : route.continue();
  });
  await page.goto("/search?q=Prague");
  const estimate = cameraEstimate(page);
  await estimate.locator("summary").click();

  await expect(estimate.locator(".camera-preview-status")).toHaveText(FRAMING_UNAVAILABLE);
  await expect(estimate.getByText("Moon detail", { exact: true })).toBeVisible();
  await expect(estimate.getByText("Example framing", { exact: true })).toHaveCount(0);
  await expectNumericalFacts(estimate);
  expect(distinctSceneRequests(sceneRequests)).toEqual([LEVELS[3].url]);
});

test("keeps Moon detail when a valid input cannot produce eligible framing geometry", async ({ page }) => {
  const sceneRequests = [];
  await routeJson(page, RESPONSE);
  await page.route("**/camera-preview/*.webp", route => {
    sceneRequests.push(new URL(route.request().url()).pathname);
    return route.continue();
  });
  await page.goto("/search?q=Prague");
  const focal = page.getByLabel("Marked focal length (mm)");
  await focal.fill("3e-305");
  await expect(focal).toHaveValue("3e-305");
  await expect(focal).not.toHaveAttribute("aria-invalid", "true");

  const estimate = cameraEstimate(page);
  await expect(estimate).toHaveJSProperty("open", false);
  await estimate.locator("summary").click();
  await expect(estimate.locator(".camera-preview-status")).toHaveText(FRAMING_UNAVAILABLE);
  await expect(estimate.getByText("Moon detail", { exact: true })).toBeVisible();
  await expect(estimate.getByText("Example framing", { exact: true })).toHaveCount(0);
  await expect(estimate).toContainText("Illuminated angle");
  await expect(estimate).toContainText("Maximum thickness");

  await focal.fill("1e-323");
  await expect(focal).toHaveValue("1e-323");
  const nonFiniteEstimate = cameraEstimate(page);
  await expect(nonFiniteEstimate).toHaveJSProperty("open", true);
  await expect(nonFiniteEstimate.locator(".camera-preview-status")).toHaveText(FRAMING_UNAVAILABLE);
  await expect(nonFiniteEstimate.getByText("Moon detail", { exact: true })).toBeVisible();
  await expect(nonFiniteEstimate.getByText("Example framing", { exact: true })).toHaveCount(0);
  expect(sceneRequests).toEqual([]);
});

async function routeJson(page, response) {
  await page.route("**/api/opportunities**", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(response)
  }));
}

async function expectPreviewReady(estimate) {
  await expect(estimate.getByText("Moon detail", { exact: true })).toBeVisible();
  await expect(estimate.getByText("Example framing", { exact: true })).toBeVisible();
  await expect(estimate.locator(".camera-preview-moon-canvas")).toBeVisible();
  await expect(estimate.locator(".camera-preview-scene-canvas")).toBeVisible();
}

async function expectNumericalFacts(estimate) {
  await expect(estimate).toContainText("Illuminated angle");
  await expect(estimate).toContainText("0.42°");
  await expect(estimate).toContainText("Maximum thickness");
  await expect(estimate).toContainText("368 px");
}

function cameraEstimate(page) {
  return page.locator(".moon-pass-card .camera-estimate");
}

function distinctSceneRequests(requests) {
  return [...new Set(requests.filter(path => /^\/camera-preview\/level-[0-5]\.webp$/.test(path)))];
}

async function instrumentCanvasDraws(page) {
  await page.addInitScript(() => {
    Object.defineProperty(window, "__cameraPreviewDraws", { value: [], writable: true });
    const originalDrawImage = CanvasRenderingContext2D.prototype.drawImage;
    CanvasRenderingContext2D.prototype.drawImage = function(source, ...args) {
      const result = originalDrawImage.call(this, source, ...args);
      const canvas = this.canvas;
      if (!(canvas instanceof HTMLCanvasElement)
          || !canvas.classList.contains("camera-preview-scene-canvas")
          || args.length < 4) return result;
      const destination = args.slice(-4);
      const sourceUrl = source instanceof HTMLImageElement
        ? new URL(source.currentSrc || source.src, location.href).pathname : "";
      const sourceKind = source instanceof HTMLImageElement
        ? "image" : source instanceof HTMLCanvasElement ? "canvas" : "other";
      Reflect.get(window, "__cameraPreviewDraws").push({
        sourceUrl,
        sourceKind,
        sourceWidth: Number(source.width),
        sourceHeight: Number(source.height),
        x: destination[0],
        y: destination[1],
        width: destination[2],
        height: destination[3],
        canvasWidth: canvas.width,
        canvasHeight: canvas.height,
        connected: canvas.isConnected
      });
      return result;
    };
  });
}

async function canvasDrawCount(page) {
  return page.evaluate(() => Reflect.get(window, "__cameraPreviewDraws").length);
}

async function renderSetup(page, estimate, selectedFormat, focalLength, teleconverter, expectedLevel) {
  const formatControl = page.getByLabel("Capture format");
  const focalControl = page.getByLabel("Marked focal length (mm)");
  const teleconverterControl = page.getByLabel("Teleconverter");
  const previousDraws = await canvasDrawCount(page);
  if (await formatControl.inputValue() !== selectedFormat.value) {
    await formatControl.selectOption(selectedFormat.value);
  }
  if (await teleconverterControl.inputValue() !== String(teleconverter)) {
    await teleconverterControl.selectOption(String(teleconverter));
  }
  await focalControl.fill(String(focalLength));
  await expect(estimate).toHaveJSProperty("open", true);
  const effectiveFocalLength = focalLength * teleconverter;
  await expect.poll(async () => {
    const frame = await frameAfter(page, previousDraws, expectedLevel.url);
    if (!frame) return false;
    const sceneWidth = sceneConstant(selectedFormat) / effectiveFocalLength;
    const expectedWidth = expectedLevel.worldWidth / sceneWidth * frame.scene.canvasWidth;
    return Math.abs(frame.scene.width - expectedWidth) < 0.00001;
  }).toBe(true);
  return frameAfter(page, previousDraws, expectedLevel.url);
}

async function waitForFrame(page, previousDraws, expectedUrl) {
  await expect.poll(async () => {
    const frame = await frameAfter(page, previousDraws, expectedUrl);
    return frame && frame.scene.sourceUrl;
  }).toBe(expectedUrl);
  return frameAfter(page, previousDraws, expectedUrl);
}

async function latestConnectedFrame(page, expectedUrl) {
  return frameAfter(page, 0, expectedUrl);
}

async function frameAfter(page, previousDraws, expectedUrl) {
  return page.evaluate(({ count, url }) => {
    const draws = Reflect.get(window, "__cameraPreviewDraws").slice(count);
    let sceneIndex = -1;
    for (let index = draws.length - 1; index >= 0; index -= 1) {
      if (draws[index].connected && draws[index].sourceUrl === url) {
        sceneIndex = index;
        break;
      }
    }
    if (sceneIndex < 0) return null;
    let moon = null;
    for (let index = sceneIndex - 1; index >= 0; index -= 1) {
      if (draws[index].connected && draws[index].sourceKind === "canvas") {
        moon = draws[index];
        break;
      }
    }
    return moon ? { scene: draws[sceneIndex], moon } : null;
  }, { count: previousDraws, url: expectedUrl });
}

function expectRegisteredFrame(frame, selectedFormat, effectiveFocalLength, selectedLevel) {
  expect(frame).not.toBeNull();
  const sceneWidth = sceneConstant(selectedFormat) / effectiveFocalLength;
  const expectedWidth = selectedLevel.worldWidth / sceneWidth * frame.scene.canvasWidth;
  expect(frame.scene.sourceUrl).toBe(selectedLevel.url);
  expect(frame.scene.sourceWidth).toBe(960);
  expect(frame.scene.sourceHeight).toBe(720);
  expect(frame.scene.width).toBeCloseTo(expectedWidth, 5);
  expect(frame.scene.height).toBeCloseTo(expectedWidth * 3 / 4, 5);
  expect([frame.scene.x, frame.scene.y, frame.scene.width, frame.scene.height,
    frame.moon.x, frame.moon.y, frame.moon.width, frame.moon.height]
    .every(Number.isFinite)).toBe(true);
  const contact = {
    x: frame.scene.x + ANCHOR.x * frame.scene.width,
    y: frame.scene.y + ANCHOR.y * frame.scene.height
  };
  expect(contact.x).toBeCloseTo(frame.scene.canvasWidth * ANCHOR.x, 5);
  expect(contact.y).toBeCloseTo(frame.scene.canvasHeight * ANCHOR.y, 5);
  return contact;
}

function expectDrawGeometry(actual, expected) {
  for (const field of ["x", "y", "width", "height", "canvasWidth", "canvasHeight"]) {
    expect(actual[field]).toBeCloseTo(expected[field], 5);
  }
}

async function previewDimensions(canvas) {
  return canvas.evaluate(node => {
    const bounds = node.getBoundingClientRect();
    return {
      backing: { width: node.width, height: node.height },
      display: { width: bounds.width, height: bounds.height }
    };
  });
}

async function figureBoxes(estimate) {
  return estimate.locator(".camera-preview-figure").evaluateAll(figures => figures.map(figure => {
    const bounds = figure.getBoundingClientRect();
    return { top: bounds.top, bottom: bounds.bottom, left: bounds.left, right: bounds.right };
  }));
}

function withOrientation(response) {
  const copy = structuredClone(response);
  copy.opportunities[0].moon.brightLimbTiltDegrees = 274.8;
  copy.opportunities[0].moon.northPoleTiltDegrees = 31.2;
  return copy;
}

function withBrightLimb(response) {
  const copy = structuredClone(response);
  copy.opportunities[0].moon.brightLimbTiltDegrees = 274.8;
  return copy;
}

function level(index, worldWidth) {
  return { url: `/camera-preview/level-${index}.webp`, worldWidth };
}

function format(value, widthMm, aspectRatio) {
  return { value, widthMm, aspectRatio };
}

function renderCase(selectedFormat, focalLength, teleconverter, selectedLevel) {
  return { format: selectedFormat, focalLength, teleconverter, level: selectedLevel };
}

function levelForView(selectedFormat, effectiveFocalLength) {
  const sceneWidth = sceneConstant(selectedFormat) / effectiveFocalLength;
  for (let index = LEVELS.length - 1; index >= 0; index -= 1) {
    if (LEVELS[index].worldWidth >= sceneWidth) return LEVELS[index];
  }
  return LEVELS[0];
}

function sceneConstant(selectedFormat) {
  return REFERENCE_DISTANCE_METRES * selectedFormat.widthMm;
}
