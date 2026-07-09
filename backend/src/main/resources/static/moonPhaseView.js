import { element } from "./dom.js";
import { normalizeDegrees, percent, readableToken } from "./format.js";
import { moonSurfaceAlbedo } from "./moonTexture.js";

var MOON_LIT_COLOR = [244, 238, 206];
var MOON_SHADED_COLOR = [92, 98, 104];

export function moonPhaseSummary(moon) {
  if (!Number.isFinite(moon.illuminationPercent) && !moon.phaseName) {
    return "Unavailable";
  }

  var phaseName = readableToken(moon.phaseName) || "Moon phase";
  var canvas = element("canvas", {
    className: "moon-phase-canvas",
    width: 48,
    height: 48,
    ariaLabel: phaseName,
    title: phaseName + ", " + percent(moon.illuminationPercent) + " lit"
  });
  drawMoonPhase(canvas, moon.phaseAngleDegrees);

  return element("span", { className: "phase-summary" },
    canvas,
    element("span", {},
      element("strong", {}, phaseName),
      element("span", {}, percent(moon.illuminationPercent) + " lit")));
}

export function moonPhaseImageDataUrl(phaseAngleDegrees, size) {
  var canvas = element("canvas", {
    width: size || 56,
    height: size || 56
  });
  drawMoonPhase(canvas, phaseAngleDegrees);
  try {
    return canvas.toDataURL("image/png");
  } catch (error) {
    return null;
  }
}

export function drawMoonPhase(canvas, phaseAngleDegrees) {
  var context = canvas.getContext && canvas.getContext("2d");
  if (!context) {
    return;
  }

  var size = canvas.width;
  var center = size / 2;
  var radius = size * 0.43;
  var image = context.createImageData(size, size);
  var phase = Number.isFinite(phaseAngleDegrees) ? normalizeDegrees(phaseAngleDegrees) : 180;
  var radians = phase * Math.PI / 180;
  var sunX = Math.sin(radians);
  var sunZ = -Math.cos(radians);

  for (var y = 0; y < size; y += 1) {
    for (var x = 0; x < size; x += 1) {
      var dx = (x + 0.5 - center) / radius;
      var dy = (y + 0.5 - center) / radius;
      var distanceSquared = dx * dx + dy * dy;
      var index = (y * size + x) * 4;
      if (distanceSquared > 1) {
        image.data[index + 3] = 0;
        continue;
      }

      var z = Math.sqrt(1 - distanceSquared);
      var lit = dx * sunX + z * sunZ > 0;
      var limbShade = 0.72 + 0.28 * z;
      var textureFactor = 0.52 + 0.65 * moonSurfaceAlbedo(dx, dy, z);
      var color = lit ? MOON_LIT_COLOR : MOON_SHADED_COLOR;
      image.data[index] = texturedChannel(color[0], limbShade, textureFactor);
      image.data[index + 1] = texturedChannel(color[1], limbShade, textureFactor);
      image.data[index + 2] = texturedChannel(color[2], limbShade, textureFactor);
      image.data[index + 3] = 255;
    }
  }

  context.putImageData(image, 0, 0);
  context.beginPath();
  context.arc(center, center, radius, 0, Math.PI * 2);
  context.strokeStyle = "#d8e0df";
  context.lineWidth = 1.5;
  context.stroke();
}

function texturedChannel(color, limbShade, textureFactor) {
  return Math.round(Math.min(255, color * limbShade * textureFactor));
}
