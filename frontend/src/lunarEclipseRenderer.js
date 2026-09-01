import { element } from "./dom.js";
import { drawMoonPhase } from "./moonPhaseView.js";

var MOON_RADIUS_RATIO = 0.34;

export function drawLunarEclipse(canvas, sample) {
  drawMoonPhase(canvas, 180, null, sample.moon.northPoleTiltDegrees, {
    outline: false,
    radiusRatio: MOON_RADIUS_RATIO
  });

  var context = canvas.getContext && canvas.getContext("2d");
  if (!context) return;
  var center = canvas.width / 2;
  var moonRadius = canvas.width * MOON_RADIUS_RATIO;
  var shadow = sample.shadow;
  var shadowX = center + shadow.centerRightMoonRadii * moonRadius;
  var shadowY = center - shadow.centerUpMoonRadii * moonRadius;

  context.save();
  context.beginPath();
  context.arc(center, center, moonRadius, 0, Math.PI * 2);
  context.clip();
  drawPenumbra(context, shadowX, shadowY, shadow.penumbraRadiusMoonRadii * moonRadius);
  drawUmbra(context, shadowX, shadowY, shadow.umbraRadiusMoonRadii * moonRadius);
  context.restore();
}

export function lunarEclipseImageDataUrl(sample, size) {
  var canvas = /** @type {HTMLCanvasElement} */ (element("canvas", {
    width: size,
    height: size
  }));
  drawLunarEclipse(canvas, sample);
  return canvas.toDataURL("image/png");
}

function drawPenumbra(context, centerX, centerY, radius) {
  var gradient = context.createRadialGradient(centerX, centerY, 0, centerX, centerY, radius);
  gradient.addColorStop(0, "rgba(36, 24, 26, 0.46)");
  gradient.addColorStop(0.78, "rgba(42, 35, 39, 0.27)");
  gradient.addColorStop(0.96, "rgba(46, 41, 44, 0.14)");
  gradient.addColorStop(1, "rgba(46, 41, 44, 0)");
  context.beginPath();
  context.arc(centerX, centerY, radius, 0, Math.PI * 2);
  context.fillStyle = gradient;
  context.fill();
}

function drawUmbra(context, centerX, centerY, radius) {
  var gradient = context.createRadialGradient(centerX, centerY, 0, centerX, centerY, radius);
  gradient.addColorStop(0, "rgba(91, 31, 24, 0.72)");
  gradient.addColorStop(0.72, "rgba(48, 20, 21, 0.82)");
  gradient.addColorStop(1, "rgba(18, 16, 20, 0.9)");
  context.beginPath();
  context.arc(centerX, centerY, radius, 0, Math.PI * 2);
  context.fillStyle = gradient;
  context.fill();
}
