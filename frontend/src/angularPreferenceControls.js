import { clamp, normalizeDegrees } from "./format.js";
import {
  compassDirection as previewCompassDirection,
  createAngularPreferencePreview
} from "./angularPreferencePreview.js";

var EPSILON = 1.0e-9;

export function createAngularPreferenceControls(form) {
  var altitudeEnabled = form.querySelector("#preference-altitude-enabled");
  var directionEnabled = form.querySelector("#preference-direction-enabled");
  var editor = form.querySelector("#preference-angular-fields");
  var preview = createAngularPreferencePreview(form);

  altitudeEnabled.addEventListener("change", syncEditors);
  directionEnabled.addEventListener("change", syncEditors);

  return {
    render: function (state) {
      altitudeEnabled.checked = Boolean(state.altitudeDegrees);
      directionEnabled.checked = Boolean(state.azimuthDegrees);
      preview.render(state);
      syncEditors();
    },
    read: function () {
      return preview.read();
    },
    focusFirst: function () {
      altitudeEnabled.focus();
    }
  };

  function syncEditors() {
    editor.hidden = !altitudeEnabled.checked && !directionEnabled.checked;
    preview.setEnabled(altitudeEnabled.checked, directionEnabled.checked);
  }
}

export function normalizeAngularPreferences(value) {
  var state = {};
  if (value.altitudeDegrees !== undefined) {
    var altitude = value.altitudeDegrees;
    if (!objectValue(altitude) || !validAltitude(altitude.minimum, altitude.maximum)) {
      return null;
    }
    state.altitudeDegrees = { minimum: altitude.minimum, maximum: altitude.maximum };
  }
  if (value.azimuthDegrees !== undefined) {
    var azimuth = value.azimuthDegrees;
    var excluded = azimuth && azimuth.excluded;
    if (!objectValue(azimuth) || !validRange(azimuth.included)
        || (excluded !== undefined
          && (!validRange(excluded) || !contained(azimuth.included, excluded)))) {
      return null;
    }
    state.azimuthDegrees = {
      included: copyRange(azimuth.included)
    };
    if (excluded !== undefined) state.azimuthDegrees.excluded = copyRange(excluded);
  }
  return state;
}

export function azimuthMaskGaps(intervals, domainStart, domainEnd) {
  if (!Array.isArray(intervals)) {
    return null;
  }
  var allowed = intervals.map(function (interval) {
    var start = new Date(interval && interval.startsAt).getTime();
    var end = new Date(interval && interval.endsAt).getTime();
    if (!Number.isFinite(start) || !Number.isFinite(end) || end <= start) {
      return null;
    }
    return {
      start: clamp(start, domainStart, domainEnd),
      end: clamp(end, domainStart, domainEnd)
    };
  }).filter(function (interval) {
    return interval && interval.end > interval.start;
  }).sort(function (a, b) {
    return a.start - b.start;
  });
  var merged = [];
  allowed.forEach(function (interval) {
    var previous = merged[merged.length - 1];
    if (previous && interval.start <= previous.end) {
      previous.end = Math.max(previous.end, interval.end);
    } else {
      merged.push(Object.assign({}, interval));
    }
  });
  var gaps = [];
  var cursor = domainStart;
  merged.forEach(function (interval) {
    if (interval.start > cursor) {
      gaps.push({ start: cursor, end: interval.start });
    }
    cursor = Math.max(cursor, interval.end);
  });
  if (cursor < domainEnd) {
    gaps.push({ start: cursor, end: domainEnd });
  }
  return gaps;
}

export function azimuthRailLabels(points, mode) {
  var first = points[0];
  var last = points[points.length - 1];
  var span = Math.max(1, last.time - first.time);
  var width = Math.max(1, last.x - first.x);
  var inset = Math.min(10, width / 2);
  var count = mode === "mobile" ? 4 : 5;
  var labels = [];
  var previousText = "";
  for (var index = 0; index < count; index += 1) {
    var ratio = index / (count - 1);
    var azimuth = interpolatedAzimuth(points, first.time + span * ratio);
    var text = compassDirection(azimuth);
    if (text === previousText && index > 0 && index < count - 1) {
      continue;
    }
    var x = clamp(first.x + width * ratio, first.x + inset, last.x - inset);
    labels.push({
      text: text,
      azimuthDegrees: azimuth,
      x: x,
      anchor: x - first.x < 22 ? "start" : (last.x - x < 22 ? "end" : "middle")
    });
    previousText = text;
  }
  return labels;
}

export function compassDirection(value) {
  return Number.isFinite(value) ? previewCompassDirection(value) : "";
}

function validAltitude(minimum, maximum) {
  return finiteNumber(minimum) && finiteNumber(maximum)
    && minimum >= 0 && maximum <= 90 && minimum <= maximum;
}

function validRange(value) {
  return objectValue(value) && validBearing(value.start) && validBearing(value.end)
    && value.start !== value.end;
}

function validBearing(value) {
  return finiteNumber(value) && value >= 0 && value < 360;
}

function contained(outer, inner) {
  return clockwiseDistance(outer.start, inner.start)
    + clockwiseDistance(inner.start, inner.end)
    <= clockwiseDistance(outer.start, outer.end) + EPSILON;
}

function clockwiseDistance(start, end) {
  return normalizeDegrees(end - start);
}

function interpolatedAzimuth(points, time) {
  if (time <= points[0].time) {
    return points[0].azimuthDegrees;
  }
  for (var index = 0; index < points.length - 1; index += 1) {
    var current = points[index];
    var next = points[index + 1];
    if (time <= next.time) {
      var ratio = clamp((time - current.time) / Math.max(1, next.time - current.time), 0, 1);
      var delta = normalizeDegrees(next.azimuthDegrees - current.azimuthDegrees + 180) - 180;
      return normalizeDegrees(current.azimuthDegrees + delta * ratio);
    }
  }
  return points[points.length - 1].azimuthDegrees;
}

function copyRange(value) {
  return { start: value.start, end: value.end };
}

function finiteNumber(value) {
  return typeof value === "number" && Number.isFinite(value);
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
