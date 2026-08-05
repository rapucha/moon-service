import { element } from "./dom.js";
import { renderHighResolutionMoon } from "./highResolutionMoonRenderer.js";
import { drawCameraReferenceScene } from "./cameraReferenceScene.js";

export var NOMINAL_MOON_DIAMETER_DEGREES = 0.52;

var SCENE_DISTANCE_METRES = 120;
var DETAIL_SAMPLES = 1024;
var SCENE_WIDTH_PIXELS = 960;
var FRAMING_UNAVAILABLE = "Example framing is unavailable. Moon detail and camera numbers are still available.";

export function attachCameraFramingPreview(disclosure, content, moon, setup, format) {
  var digital = format.medium === "digital";
  var geometry = digital ? cameraFramingGeometry(setup, format) : null;
  var detailCanvas = element("canvas", {
    className: "camera-preview-moon-canvas",
    width: DETAIL_SAMPLES,
    height: DETAIL_SAMPLES,
    ariaLabel: "Moon detail using the opportunity phase and surface orientation"
  }, "Moon detail preview");
  var detailFigure = previewFigure(
    "camera-preview-moon",
    "Moon detail",
    "Opportunity phase and orientation at maximum texture detail; this enlarged view is not to camera scale.",
    detailCanvas,
    orientationNotes(moon));
  var sceneCanvas = digital && geometry ? element("canvas", {
    className: "camera-preview-scene-canvas",
    width: SCENE_WIDTH_PIXELS,
    height: Math.round(SCENE_WIDTH_PIXELS / format.aspectRatio),
    ariaLabel: "Illustrative framing with the Moon beside a fictional Mediterranean foreground"
  }, "Example camera framing preview") : null;
  var sceneFigure = sceneCanvas ? previewFigure(
    "camera-preview-scene",
    "Example framing",
    "Reference scene only—the scale is calculated; the placement is illustrative.",
    sceneCanvas,
    [element("span", { className: "camera-preview-sampling" }, samplingText(geometry))]) : null;
  var status = element("p", {
    className: "camera-preview-status",
    role: "status",
    "aria-live": "polite"
  }, "Preview loads when this estimate is opened.");
  var grid = element("div", { className: "camera-preview-grid" },
    detailFigure, sceneFigure);
  var root = element("section", {
    className: "camera-framing-preview",
    ariaLabel: "Camera preview"
  }, status, grid);
  var sequence = 0;
  var state = "idle";

  detailFigure.hidden = true;
  if (sceneFigure) sceneFigure.hidden = true;
  content.appendChild(root);
  disclosure.addEventListener("toggle", handleToggle);
  queueMicrotask(handleToggle);

  function handleToggle() {
    if (!disclosure.open) {
      sequence += 1;
      if (state === "loading") state = "idle";
      return;
    }
    if (!disclosure.isConnected || state !== "idle") return;
    loadPreviews();
  }

  async function loadPreviews() {
    var token = ++sequence;
    state = "loading";
    status.hidden = false;
    status.textContent = "Loading camera preview…";
    try {
      await renderHighResolutionMoon(detailCanvas, moon, DETAIL_SAMPLES);
      if (stale(token)) return;
      detailFigure.hidden = false;
      if (digital && !geometry) {
        state = "ready";
        status.textContent = FRAMING_UNAVAILABLE;
        return;
      }
      if (sceneCanvas) {
        status.textContent = "Loading example framing…";
        try {
          await drawCameraReferenceScene(sceneCanvas, detailCanvas, geometry);
        } catch (error) {
          if (stale(token)) return;
          state = "ready";
          sceneFigure.remove();
          status.textContent = FRAMING_UNAVAILABLE;
          return;
        }
        if (stale(token)) return;
        sceneFigure.hidden = false;
      }
      state = "ready";
      status.textContent = "";
      status.hidden = true;
    } catch (error) {
      if (stale(token)) return;
      state = "failed";
      detailFigure.remove();
      if (sceneFigure) sceneFigure.remove();
      status.textContent = "Camera preview is unavailable for this estimate.";
      status.hidden = false;
    }
  }

  function stale(token) {
    return token !== sequence || !disclosure.open || !disclosure.isConnected;
  }
}

function cameraFramingGeometry(setup, format) {
  if (!setup || !format || format.medium !== "digital") return null;
  var megapixels = Number(setup.outputMegapixels);
  var focal = Number(setup.focalLengthMm) * Number(setup.teleconverterMultiplier);
  var sensorWidth = Number(format.widthMm);
  var aspectRatio = Number(format.aspectRatio);
  if (![megapixels, focal, sensorWidth, aspectRatio].every(positiveFinite)) return null;
  var sensorHeight = sensorWidth / aspectRatio;
  var outputWidth = Math.sqrt(megapixels * 1000000 * aspectRatio);
  var outputHeight = outputWidth / aspectRatio;
  var moonImageWidth = 2 * focal * Math.tan(NOMINAL_MOON_DIAMETER_DEGREES * Math.PI / 360);
  var moonRatio = moonImageWidth / sensorWidth;
  var result = {
    effectiveFocalLengthMm: focal,
    horizontalFovDegrees: fieldOfView(sensorWidth, focal),
    verticalFovDegrees: fieldOfView(sensorHeight, focal),
    outputWidthPixels: Math.round(outputWidth),
    outputHeightPixels: Math.round(outputHeight),
    moonDiameterOutputPixels: moonRatio * outputWidth,
    moonDiameterNormalized: moonRatio,
    sceneWidthMetres: SCENE_DISTANCE_METRES * sensorWidth / focal,
    sceneHeightMetres: SCENE_DISTANCE_METRES * sensorHeight / focal
  };
  return Object.values(result).every(positiveFinite)
    && result.horizontalFovDegrees < 180
    && result.verticalFovDegrees < 180 ? result : null;
}

function previewFigure(className, title, description, canvas, notes) {
  return element("figure", { className: "camera-preview-figure " + className },
    element("div", { className: "camera-preview-canvas-frame" }, canvas),
    element("figcaption", {},
      element("strong", {}, title),
      element("span", {}, description),
      notes));
}

function orientationNotes(moon) {
  var notes = [];
  if (!moon || !Number.isFinite(moon.brightLimbTiltDegrees)) {
    notes.push(element("span", { className: "camera-preview-fallback" },
      "Bright-limb angle is unavailable, so the limb orientation is approximate and uses the location-independent orientation."));
  }
  if (!moon || !Number.isFinite(moon.northPoleTiltDegrees)) {
    notes.push(element("span", { className: "camera-preview-fallback" },
      "North-pole angle is unavailable, so the surface orientation is approximate and shown north-up."));
  }
  return notes;
}

function samplingText(geometry) {
  return formatPixels(geometry.outputWidthPixels) + " × "
    + formatPixels(geometry.outputHeightPixels) + " output; full Moon about "
    + (geometry.moonDiameterOutputPixels < 1
      ? "under 1 px" : formatPixels(Math.round(geometry.moonDiameterOutputPixels))) + " across.";
}

function fieldOfView(sensorSize, focal) {
  return 2 * Math.atan(sensorSize / (2 * focal)) * 180 / Math.PI;
}

function positiveFinite(value) {
  return Number.isFinite(value) && value > 0;
}

function formatPixels(value) {
  return Math.round(value).toLocaleString("en-US") + " px";
}
