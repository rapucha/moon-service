import { drawMoonPhase } from "./moonPhaseView.js";

var TEXTURE_URL = "/moon-textures/lroc_color_2k.jpg";
var MAX_DISK_SAMPLES = 1024;
var HIGH_RESOLUTION_RADIUS_RATIO = 0.49;

export async function renderHighResolutionMoon(canvas, moon, requestedSamples) {
  var context = canvas && canvas.getContext && canvas.getContext("2d");
  if (!context) {
    throw new TypeError("A canvas with a 2D context is required.");
  }
  if (!moon || !Number.isFinite(moon.phaseAngleDegrees)) {
    throw new TypeError("A finite Moon phase angle is required.");
  }
  if (!Number.isFinite(requestedSamples) || requestedSamples <= 0) {
    throw new TypeError("A positive Moon texture sample count is required.");
  }

  var diskSamples = Math.min(MAX_DISK_SAMPLES, Math.max(1, Math.floor(requestedSamples)));
  var textureImage = await loadTextureImage();
  var texture = sampleTexture(textureImage, diskSamples);
  var renderedCanvas = document.createElement("canvas");
  renderedCanvas.width = canvas.width;
  renderedCanvas.height = canvas.height;
  drawMoonPhase(
    renderedCanvas,
    moon.phaseAngleDegrees,
    moon.brightLimbTiltDegrees,
    moon.northPoleTiltDegrees,
    {
      surfaceAlbedo: createSurfaceSampler(texture),
      radiusRatio: HIGH_RESOLUTION_RADIUS_RATIO,
      outline: false
    });

  var renderedContext = renderedCanvas.getContext("2d");
  if (!renderedContext) {
    throw new Error("The high-resolution Moon rendering could not be read.");
  }
  var renderedImage = renderedContext.getImageData(
    0,
    0,
    renderedCanvas.width,
    renderedCanvas.height);
  context.putImageData(renderedImage, 0, 0);
}

function loadTextureImage() {
  var image = new Image();
  image.decoding = "async";
  image.src = TEXTURE_URL;
  return image.decode()
    .then(function() {
      return image;
    })
    .catch(function(error) {
      throw new Error("The high-resolution Moon texture could not be decoded.", {
        cause: error
      });
    });
}

function sampleTexture(image, diskSamples) {
  var canvas = document.createElement("canvas");
  canvas.width = diskSamples * 2;
  canvas.height = diskSamples;
  var context = canvas.getContext("2d", { willReadFrequently: true });
  if (!context) {
    throw new Error("The high-resolution Moon texture could not be sampled.");
  }
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = "high";
  context.drawImage(image, 0, 0, canvas.width, canvas.height);
  var pixels = context.getImageData(0, 0, canvas.width, canvas.height).data;
  var luminance = new Uint8Array(canvas.width * canvas.height);
  for (var index = 0; index < luminance.length; index += 1) {
    var pixel = index * 4;
    luminance[index] = Math.round(
      0.299 * pixels[pixel]
      + 0.587 * pixels[pixel + 1]
      + 0.114 * pixels[pixel + 2]);
  }
  return { width: canvas.width, height: canvas.height, luminance: luminance };
}

function createSurfaceSampler(texture) {
  return function(dx, dy, z) {
    var longitude = Math.atan2(dx, z);
    var latitude = Math.asin(clamp(-dy, -1, 1));
    var textureX = ((longitude / (Math.PI * 2)) + 0.5) * texture.width;
    var textureY = (0.5 - latitude / Math.PI) * (texture.height - 1);
    var floorX = Math.floor(textureX);
    var left = positiveModulo(floorX, texture.width);
    var right = (left + 1) % texture.width;
    var top = Math.floor(textureY);
    var bottom = Math.min(texture.height - 1, top + 1);
    var horizontalRatio = textureX - floorX;
    var verticalRatio = textureY - top;
    var topValue = mix(
      textureValue(texture, left, top),
      textureValue(texture, right, top),
      horizontalRatio);
    var bottomValue = mix(
      textureValue(texture, left, bottom),
      textureValue(texture, right, bottom),
      horizontalRatio);
    return mix(topValue, bottomValue, verticalRatio) / 255;
  };
}

function textureValue(texture, x, y) {
  return texture.luminance[y * texture.width + x];
}

function positiveModulo(value, divisor) {
  return ((value % divisor) + divisor) % divisor;
}

function mix(start, end, ratio) {
  return start + (end - start) * ratio;
}

function clamp(value, minimum, maximum) {
  return Math.min(maximum, Math.max(minimum, value));
}
