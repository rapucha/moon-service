import { element } from "./dom.js";

var MOON_DISK_FRACTION = 0.98;
var SCENE_ANCHOR = { x: 0.46875, y: 0.5138888888888888 };
var MOON_MARGIN_RATIO = 8 / 960;
var BASE_ARIA_LABEL = "Illustrative camera framing with the Moon in clear sky beside a fictional Mediterranean foreground";
var SCENE_LEVELS = [
  { url: "/camera-preview/level-0.webp", worldWidth: 1350 },
  { url: "/camera-preview/level-1.webp", worldWidth: 371.25 },
  { url: "/camera-preview/level-2.webp", worldWidth: 102.09375 },
  { url: "/camera-preview/level-3.webp", worldWidth: 28.07578125 },
  { url: "/camera-preview/level-4.webp", worldWidth: 7.72083984375 },
  { url: "/camera-preview/level-5.webp", worldWidth: 2.12323095703125 }
];

export async function drawCameraReferenceScene(canvas, moonCanvas, geometry) {
  var context = canvas && canvas.getContext && canvas.getContext("2d");
  if (!context) throw new Error("The example framing canvas is unavailable.");
  var level = selectLevel(geometry && geometry.sceneWidthMetres);
  var view = referenceView(canvas, geometry);
  var image = await loadSceneImage(level);

  drawTwilight(context, canvas.width, canvas.height);
  drawSampledMoon(context, canvas, moonCanvas, geometry, view);
  drawSceneImage(context, level, image, view);
  canvas.setAttribute("aria-label", BASE_ARIA_LABEL);
}

function selectLevel(sceneWidthMetres) {
  if (!positiveFinite(sceneWidthMetres)) {
    throw new Error("The example framing geometry is unavailable.");
  }
  for (var index = SCENE_LEVELS.length - 1; index >= 0; index -= 1) {
    if (SCENE_LEVELS[index].worldWidth >= sceneWidthMetres) {
      return SCENE_LEVELS[index];
    }
  }
  return SCENE_LEVELS[0];
}

function loadSceneImage(level) {
  var image = new Image();
  image.decoding = "async";
  image.src = level.url;
  return image.decode().then(function() {
    return image;
  }).catch(function(error) {
    throw new Error("The selected reference-scene image could not be decoded.", {
      cause: error
    });
  });
}

function referenceView(canvas, geometry) {
  var scale = canvas.width / geometry.sceneWidthMetres;
  if (!positiveFinite(scale)) {
    throw new Error("The example framing projection is unavailable.");
  }
  return {
    scale: scale,
    anchorX: SCENE_ANCHOR.x * canvas.width,
    anchorY: SCENE_ANCHOR.y * canvas.height
  };
}

function drawTwilight(context, width, height) {
  var sky = context.createLinearGradient(0, 0, 0, height);
  sky.addColorStop(0, "#101a35");
  sky.addColorStop(0.52, "#51475d");
  sky.addColorStop(1, "#c57d5d");
  context.fillStyle = sky;
  context.fillRect(0, 0, width, height);

  var glow = context.createRadialGradient(width * 0.2, height * 0.82, 0,
    width * 0.2, height * 0.82, width * 0.65);
  glow.addColorStop(0, "rgba(250, 180, 106, 0.34)");
  glow.addColorStop(1, "rgba(250, 180, 106, 0)");
  context.fillStyle = glow;
  context.fillRect(0, 0, width, height);

  context.fillStyle = "rgba(225, 230, 230, 0.42)";
  for (var index = 0; index < 22; index += 1) {
    var x = (index * 197 + 53) % width;
    var y = (index * 83 + 29) % Math.round(height * 0.43);
    context.fillRect(x, y, index % 5 === 0 ? 1.5 : 1, index % 5 === 0 ? 1.5 : 1);
  }
}

function drawSampledMoon(context, canvas, source, geometry, view) {
  var samples = Math.min(source.width,
    Math.max(1, Math.round(geometry.moonDiameterOutputPixels)));
  var sampled = element("canvas", { width: samples, height: samples });
  var sampledContext = sampled.getContext("2d");
  if (!sampledContext) throw new Error("The Moon sampling canvas is unavailable.");
  var sourceMargin = source.width * (1 - MOON_DISK_FRACTION) / 2;
  sampledContext.imageSmoothingEnabled = true;
  sampledContext.imageSmoothingQuality = "high";
  sampledContext.drawImage(source, sourceMargin, sourceMargin,
    source.width * MOON_DISK_FRACTION, source.height * MOON_DISK_FRACTION,
    0, 0, samples, samples);

  var diameter = geometry.moonDiameterNormalized * canvas.width;
  var radius = diameter / 2;
  var margin = canvas.width * MOON_MARGIN_RATIO;
  var centerX = Math.max(canvas.width * 2 / 3, view.anchorX + margin + radius);
  var centerY = Math.max(canvas.height / 3, margin + radius);
  context.imageSmoothingEnabled = samples > diameter;
  context.imageSmoothingQuality = "high";
  context.drawImage(sampled, centerX - radius, centerY - radius, diameter, diameter);
  context.imageSmoothingEnabled = true;
}

function drawSceneImage(context, level, image, view) {
  var width = level.worldWidth * view.scale;
  var height = width * image.height / image.width;
  var left = view.anchorX - SCENE_ANCHOR.x * width;
  var top = view.anchorY - SCENE_ANCHOR.y * height;
  if (![left, top, width, height].every(Number.isFinite)) {
    throw new Error("The example framing projection is unavailable.");
  }
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  context.drawImage(image, left, top, width, height);
}

function positiveFinite(value) {
  return Number.isFinite(value) && value > 0;
}
