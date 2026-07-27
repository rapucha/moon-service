import { normalizeDegrees } from "./format.js";

export var MINIMUM_USABLE_DEGREES = 10;

export function altitudePosition(altitude) {
  return Math.pow(Math.max(0, Math.min(1, altitude / 90)), 0.85);
}

export function altitudeFromPosition(position) {
  return Math.pow(Math.max(0, Math.min(1, position)), 1 / 0.85) * 90;
}

export function validAltitude(minimum, maximum) {
  return finiteNumber(minimum) && finiteNumber(maximum)
    && minimum >= 0 && maximum <= 90
    && minimum + MINIMUM_USABLE_DEGREES <= maximum;
}

export function copyAzimuth(value) {
  var midpoint = normalizeDegrees(value.included.start
    + clockwiseDistance(value.included.start, value.included.end) / 2);
  var excluded = value.excluded || { start: midpoint, end: midpoint };
  return {
    included: copyRange(value.included),
    excluded: copyRange(excluded)
  };
}

export function excludedBearingSegments(included, excluded) {
  return mergeSegments(bearingSegments(included, true).concat(
    bearingSegments(excluded, false)));
}

export function bearingValue(azimuth, key) {
  if (key === "includedStart") return azimuth.included.start;
  if (key === "includedEnd") return azimuth.included.end;
  if (key === "excludedStart") return azimuth.excluded.start;
  return azimuth.excluded.end;
}

export function bearingLimits(azimuth, key) {
  var neighbors = {
    includedStart: ["includedEnd", "excludedStart", 1, 0],
    excludedStart: ["includedStart", "excludedEnd", 0, 0],
    excludedEnd: ["excludedStart", "includedEnd", 0, 0],
    includedEnd: ["excludedEnd", "includedStart", 0, 1]
  }[key];
  var current = bearingValue(azimuth, key);
  var minimum = current
    - clockwiseDistance(bearingValue(azimuth, neighbors[0]), current) + neighbors[2];
  var maximum = current
    + clockwiseDistance(current, bearingValue(azimuth, neighbors[1])) - neighbors[3];
  if (key === "includedStart"
      && azimuth.excluded.start === azimuth.included.end) {
    maximum = Math.max(current, maximum - 1);
  }
  if (key === "includedEnd"
      && azimuth.excluded.end === azimuth.included.start) {
    minimum = Math.min(current, minimum + 1);
  }
  if (isBlockedHandle(key)) {
    minimum = Math.max(0, minimum);
    maximum = Math.min(359, maximum);
  }
  return {
    minimum: minimum,
    maximum: maximum,
    home: unwrappedTarget(0, minimum, maximum, minimum),
    end: unwrappedTarget(359, minimum, maximum, maximum)
  };
}

export function moveBearing(azimuth, key, requested, direction) {
  var original = cloneAzimuth(azimuth);
  var candidate = cloneAzimuth(azimuth);
  setBearing(candidate, key, requested);
  if (sameBearing(candidate.excluded.start, candidate.excluded.end)) {
    if (!validAzimuth(candidate)) return unchanged(original, "minimum");
    return changed(candidate, sameBearing(
      original.excluded.start, original.excluded.end) ? null : "block-removed");
  }

  var side = adjacentSide(key);
  var oldGap = usableGaps(original)[side];
  var gaps = usableGaps(candidate);
  var gap = gaps[side];
  var otherGap = gaps[oppositeSide(side)];
  if (gap < MINIMUM_USABLE_DEGREES && gap !== oldGap) {
    var closing = direction === 0
      ? gap < oldGap
      : direction === closingDirection(key);
    var target = closing && oldGap <= MINIMUM_USABLE_DEGREES
      ? 0
      : MINIMUM_USABLE_DEGREES;
    if (closing && oldGap === 0) target = 0;
    setAdjacentGap(candidate, key, target);
    gap = usableGaps(candidate)[side];
    otherGap = usableGaps(candidate)[oppositeSide(side)];
    if (closing && gap === 0 && otherGap === 0) {
      return transferOrRebound(original, candidate, key);
    }
    if (validAzimuth(candidate)) {
      var effect = target === 0 ? "closed" : (oldGap === 0 ? "opened" : "minimum");
      return changed(candidate, effect);
    }
    return unchanged(original, "minimum");
  }
  if (gap === 0 && otherGap === 0) {
    return transferOrRebound(original, candidate, key);
  }
  if (!validAzimuth(candidate)) return unchanged(original, "minimum");
  return changed(candidate, oldGap > 0 && gap === 0 ? "closed" : null);
}

function transferOrRebound(original, candidate, key) {
  if (!isBlockedHandle(key)) return unchanged(original, "minimum");
  var previous;
  if (key === "excludedStart") {
    previous = candidate.excluded.end;
    candidate.excluded.end = normalizeDegrees(
      candidate.included.end - MINIMUM_USABLE_DEGREES);
  } else {
    previous = candidate.excluded.start;
    candidate.excluded.start = normalizeDegrees(
      candidate.included.start + MINIMUM_USABLE_DEGREES);
  }
  var transferred = key === "excludedStart"
    ? candidate.excluded.end
    : candidate.excluded.start;
  if (Math.abs(previous - transferred) > 180) {
    return unchanged(original, "transfer-seam");
  }
  return validAzimuth(candidate)
    ? changed(candidate, "transferred")
    : unchanged(original, "minimum");
}

function setAdjacentGap(azimuth, key, gap) {
  if (key === "includedStart") {
    azimuth.included.start = normalizeDegrees(azimuth.excluded.start - gap);
  } else if (key === "excludedStart") {
    azimuth.excluded.start = normalizeDegrees(azimuth.included.start + gap);
  } else if (key === "excludedEnd") {
    azimuth.excluded.end = normalizeDegrees(azimuth.included.end - gap);
  } else {
    azimuth.included.end = normalizeDegrees(azimuth.excluded.end + gap);
  }
}

function setBearing(azimuth, key, value) {
  var normalized = normalizeDegrees(value);
  if (key === "includedStart") azimuth.included.start = normalized;
  else if (key === "includedEnd") azimuth.included.end = normalized;
  else if (key === "excludedStart") azimuth.excluded.start = normalized;
  else azimuth.excluded.end = normalized;
}

function validAzimuth(azimuth) {
  var includedWidth = clockwiseDistance(azimuth.included.start, azimuth.included.end);
  var startGap = clockwiseDistance(azimuth.included.start, azimuth.excluded.start);
  var blockedWidth = clockwiseDistance(azimuth.excluded.start, azimuth.excluded.end);
  var endGap = clockwiseDistance(azimuth.excluded.end, azimuth.included.end);
  if (includedWidth < MINIMUM_USABLE_DEGREES
      || startGap + blockedWidth + endGap > includedWidth + 1.0e-9) {
    return false;
  }
  if (sameBearing(azimuth.excluded.start, azimuth.excluded.end)) return true;
  return validGap(startGap) && validGap(endGap)
    && (startGap >= MINIMUM_USABLE_DEGREES || endGap >= MINIMUM_USABLE_DEGREES);
}

function validGap(value) {
  return value === 0 || value >= MINIMUM_USABLE_DEGREES;
}

function usableGaps(azimuth) {
  return {
    start: clockwiseDistance(azimuth.included.start, azimuth.excluded.start),
    end: clockwiseDistance(azimuth.excluded.end, azimuth.included.end)
  };
}

function adjacentSide(key) {
  return key === "includedStart" || key === "excludedStart" ? "start" : "end";
}

function oppositeSide(side) {
  return side === "start" ? "end" : "start";
}

function closingDirection(key) {
  return key === "includedStart" || key === "excludedEnd" ? 1 : -1;
}

function isBlockedHandle(key) {
  return key === "excludedStart" || key === "excludedEnd";
}

function clockwiseDistance(start, end) {
  return normalizeDegrees(end - start);
}

function bearingSegments(range, complement) {
  if (range.start === range.end) return complement ? [[0, 360]] : [];
  if (range.start < range.end) {
    return complement ? [[0, range.start], [range.end, 360]] : [[range.start, range.end]];
  }
  return complement ? [[range.end, range.start]] : [[0, range.end], [range.start, 360]];
}

function mergeSegments(segments) {
  return segments.filter(function (segment) {
    return segment[0] < segment[1];
  }).sort(function (first, second) {
    return first[0] - second[0];
  }).reduce(function (merged, segment) {
    var previous = merged[merged.length - 1];
    if (previous && segment[0] <= previous[1]) {
      previous[1] = Math.max(previous[1], segment[1]);
    } else {
      merged.push(segment.slice());
    }
    return merged;
  }, []);
}

function sameBearing(first, second) {
  return Math.abs(first - second) < 1.0e-9;
}

function unwrappedTarget(target, minimum, maximum, fallback) {
  var value = target;
  while (value < minimum) value += 360;
  while (value > maximum) value -= 360;
  return value < minimum || value > maximum ? fallback : value;
}

function cloneAzimuth(value) {
  return {
    included: { start: value.included.start, end: value.included.end },
    excluded: { start: value.excluded.start, end: value.excluded.end }
  };
}

function copyRange(value) {
  return { start: value.start, end: value.end };
}

function finiteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function changed(azimuth, effect) {
  return { azimuth: azimuth, effect: effect };
}

function unchanged(azimuth, effect) {
  return { azimuth: azimuth, effect: effect };
}
