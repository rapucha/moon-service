import { element } from "./dom.js";
import { formatTime } from "./format.js";
import { lunarEclipseImageDataUrl } from "./lunarEclipseRenderer.js";
import { renderMoonPathPanel } from "./moonPathView.js";

var INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;

export function moonEventPathPanel(event, location) {
  var viewing = event.kind === "lunar_eclipse"
    ? event.localVisibility : event.localViewing;
  var display = viewing.displayInterval;
  var samples = viewing.moonPath.samples;
  var definingAt = event.kind === "lunar_eclipse" ? event.maximumAt : event.peakAt;
  var definingLabel = event.kind === "lunar_eclipse" ? "Maximum" : "Full Moon";
  var definingOnPath = samples.some(function (sample) { return sample.at === definingAt; });
  var featuredAt = definingOnPath ? definingAt : display.suggestedAt;
  var featuredLabel = definingOnPath ? definingLabel : "Best visible";
  var points = samples.map(function (sample) {
    var featured = sample.at === featuredAt;
    return Object.assign({}, sample, {
      role: featured ? "suggested" : "path",
      featured: featured,
      markerLabel: featured ? featuredLabel : undefined
    });
  });
  var featured = points.find(function (point) { return point.at === featuredAt; });
  var markerResolver = event.kind === "lunar_eclipse"
    ? eclipseMarkerResolver() : undefined;
  var footer = event.kind === "lunar_eclipse"
    ? eclipseRangeCue(samples, viewing.selectedInterval, location) : null;

  return renderMoonPathPanel(
    {
      moon: display.moon,
      moonPath: {
        samples: points,
        summary: [
          { label: "Start", point: points[0] },
          { label: featuredLabel, point: featured },
          { label: "End", point: points[points.length - 1] }
        ],
        description: "Moon altitude and direction across the selected local Moon pass.",
        chartSubject: "selected local Moon pass"
      }
    },
    location.timezone,
    location.countryCode,
    null,
    "Moon path",
    "selected local Moon pass",
    false,
    function () { return null; },
    footer,
    markerResolver);
}

export function validMoonEventPath(viewing, eventKind, requiredInstants) {
  if (!objectValue(viewing) || !objectValue(viewing.displayInterval)
      || !objectValue(viewing.moonPath) || !Array.isArray(viewing.moonPath.samples)
      || viewing.moonPath.samples.length < 2
      || (eventKind !== "lunar_eclipse" && eventKind !== "full_moon")
      || !Array.isArray(requiredInstants) || requiredInstants.length === 0
      || !requiredInstants.every(validInstant)) return false;
  var display = viewing.displayInterval;
  var samples = viewing.moonPath.samples;
  var instantKeys = samples.map(function (sample) {
    return validPathSample(sample, eventKind) ? instantOrderKey(sample.at) : null;
  });
  var requiredKeys = requiredInstants.map(instantOrderKey);
  return instantKeys.every(function (key) { return key !== null; })
    && validInstant(display.startsAt) && validInstant(display.suggestedAt)
    && validInstant(display.endsAt)
    && samples.some(function (sample) { return sample.at === display.suggestedAt; })
    && instantKeys[0] <= instantOrderKey(display.startsAt)
    && instantKeys[instantKeys.length - 1] >= instantOrderKey(display.endsAt)
    && requiredInstants.every(function (requiredAt, index) {
      return samples.some(function (sample) { return sample.at === requiredAt; })
        === (instantKeys[0] <= requiredKeys[index]
          && requiredKeys[index] <= instantKeys[instantKeys.length - 1]);
    })
    && instantKeys.every(function (key, index) {
      return index === 0 || key > instantKeys[index - 1];
    });
}

function validPathSample(sample, eventKind) {
  if (!objectValue(sample) || !validInstant(sample.at)
      || !finiteBetween(sample.altitudeDegrees, -90, 90)
      || !finiteBetween(sample.azimuthDegrees, 0, 360, false)
      || !finiteBetween(sample.moonPhaseAngleDegrees, 0, 360)
      || !nullableDegrees(sample.brightLimbTiltDegrees)
      || !nullableDegrees(sample.northPoleTiltDegrees)
      || !finiteBetween(sample.sunAltitudeDegrees, -90, 90)
      || !finiteBetween(sample.sunAzimuthDegrees, 0, 360, false)
      || typeof sample.lightBucket !== "string" || sample.lightBucket.length === 0) return false;
  return eventKind === "lunar_eclipse"
    ? validShadow(sample.shadow) : sample.shadow === undefined;
}

function eclipseMarkerResolver() {
  var imageUrls = new Map();
  return function (point) {
    if (!imageUrls.has(point.at)) {
      imageUrls.set(point.at,
        lunarEclipseImageDataUrl({ moon: point, shadow: point.shadow }, 64));
    }
    return imageUrls.get(point.at);
  };
}

function eclipseRangeCue(samples, interval, location) {
  var pathStartsAt = samples[0].at;
  var pathEndsAt = samples[samples.length - 1].at;
  var startsAt = laterInstant(pathStartsAt, interval.startsAt);
  var endsAt = earlierInstant(pathEndsAt, interval.endsAt);
  var pathStart = new Date(pathStartsAt).getTime();
  var pathSpan = new Date(pathEndsAt).getTime() - pathStart;
  var left = ((new Date(startsAt).getTime() - pathStart) / pathSpan) * 100;
  var width = ((new Date(endsAt).getTime() - new Date(startsAt).getTime()) / pathSpan) * 100;
  var times = formatRange(startsAt, endsAt, location);
  return element("div", {
    className: "special-moon-eclipse-range",
    role: "img",
    ariaLabel: "Visible eclipse from " + times + ", covering " + Math.round(width)
      + "% of the selected local Moon pass."
  },
    element("span", { className: "special-moon-eclipse-range-label" }, "Visible eclipse"),
    element("span", { className: "special-moon-eclipse-range-times" }, times),
    element("span", {
      className: "special-moon-eclipse-range-track"
    }, element("span", {
      className: "special-moon-eclipse-range-segment",
      style: "left:" + left.toFixed(2) + "%;width:" + width.toFixed(2) + "%;",
      "data-start-at": startsAt,
      "data-end-at": endsAt
    })));
}

function formatRange(startsAt, endsAt, location) {
  return formatTime(startsAt, location.timezone, location.countryCode)
    + "–" + formatTime(endsAt, location.timezone, location.countryCode);
}

function laterInstant(left, right) {
  return instantOrderKey(left) >= instantOrderKey(right) ? left : right;
}

function earlierInstant(left, right) {
  return instantOrderKey(left) <= instantOrderKey(right) ? left : right;
}

function validShadow(shadow) {
  return objectValue(shadow)
    && Number.isFinite(shadow.centerRightMoonRadii)
    && Number.isFinite(shadow.centerUpMoonRadii)
    && Number.isFinite(shadow.umbraRadiusMoonRadii) && shadow.umbraRadiusMoonRadii > 0
    && Number.isFinite(shadow.penumbraRadiusMoonRadii)
    && shadow.penumbraRadiusMoonRadii > shadow.umbraRadiusMoonRadii;
}

function nullableDegrees(value) {
  return value === null || finiteBetween(value, 0, 360, false);
}

function validInstant(value) {
  var parsed = typeof value === "string" && INSTANT_PATTERN.test(value)
    ? new Date(value) : null;
  return parsed !== null && Number.isFinite(parsed.getTime())
    && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
}

function instantOrderKey(value) {
  var dot = value.indexOf(".");
  var fraction = dot < 0 ? "" : value.slice(dot + 1, -1);
  return value.slice(0, 19) + fraction.padEnd(9, "0");
}

function finiteBetween(value, minimum, maximum, inclusiveMaximum) {
  return typeof value === "number" && Number.isFinite(value) && value >= minimum
    && (inclusiveMaximum === false ? value < maximum : value <= maximum);
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
