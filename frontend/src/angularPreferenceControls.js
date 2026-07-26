import { clamp, normalizeDegrees } from "./format.js";

var DEFAULT_ALTITUDE = { minimum: 2, maximum: 15 };
var DEFAULT_AZIMUTH = {
  included: { start: 330, end: 30 },
  excluded: { start: 350, end: 10 }
};
var EPSILON = 1.0e-9;

export function createAngularPreferenceControls(form) {
  var altitudeEnabled = form.querySelector("#preference-altitude-enabled");
  var altitudeEditor = form.querySelector("#preference-altitude-fields");
  var altitudeTrack = form.querySelector("#preference-altitude-track");
  var altitudeOutput = form.querySelector("#preference-altitude-output");
  var altitudeMinimumHandle = form.querySelector("[data-altitude-minimum]");
  var altitudeMaximumHandle = form.querySelector("[data-altitude-maximum]");
  var directionEnabled = form.querySelector("#preference-direction-enabled");
  var directionEditor = form.querySelector("#preference-direction-fields");
  var compassTrack = form.querySelector("#preference-compass-track");
  var bearingInputs = {
    includedStart: form.querySelector("#preference-included-start"),
    includedEnd: form.querySelector("#preference-included-end"),
    excludedStart: form.querySelector("#preference-excluded-start"),
    excludedEnd: form.querySelector("#preference-excluded-end")
  };
  var altitude = Object.assign({}, DEFAULT_ALTITUDE);

  altitudeEnabled.addEventListener("change", syncEditors);
  directionEnabled.addEventListener("change", syncEditors);
  Object.values(bearingInputs).forEach(function (input) {
    input.addEventListener("input", syncCompass);
  });

  wireHandle(altitudeMinimumHandle, altitudeTrack, "vertical", function () {
    return altitude.minimum;
  }, function (value) {
    altitude.minimum = Math.min(value, altitude.maximum);
    syncAltitude();
  }, function (event, bounds) {
    return Math.round(((bounds.bottom - event.clientY) / bounds.height) * 90);
  }, function () {
    return { minimum: 0, maximum: altitude.maximum };
  });
  wireHandle(altitudeMaximumHandle, altitudeTrack, "vertical", function () {
    return altitude.maximum;
  }, function (value) {
    altitude.maximum = Math.max(value, altitude.minimum);
    syncAltitude();
  }, function (event, bounds) {
    return Math.round(((bounds.bottom - event.clientY) / bounds.height) * 90);
  }, function () {
    return { minimum: altitude.minimum, maximum: 90 };
  });

  [
    ["includedStart", "[data-bearing-handle='included-start']"],
    ["includedEnd", "[data-bearing-handle='included-end']"],
    ["excludedStart", "[data-bearing-handle='excluded-start']"],
    ["excludedEnd", "[data-bearing-handle='excluded-end']"]
  ].forEach(function (binding) {
    var input = bearingInputs[binding[0]];
    wireHandle(form.querySelector(binding[1]), compassTrack, "horizontal", function () {
      return Number(input.value);
    }, function (value) {
      input.value = numberText(normalizeDegrees(value));
      syncCompass();
    }, function (event, bounds) {
      return Math.round(((event.clientX - bounds.left) / bounds.width) * 359);
    }, function () {
      return { minimum: 0, maximum: 359 };
    });
  });

  return {
    render: function (state) {
      var savedAltitude = state.altitudeDegrees || DEFAULT_ALTITUDE;
      var savedAzimuth = state.azimuthDegrees || DEFAULT_AZIMUTH;
      altitude = {
        minimum: savedAltitude.minimum,
        maximum: savedAltitude.maximum
      };
      altitudeEnabled.checked = Boolean(state.altitudeDegrees);
      directionEnabled.checked = Boolean(state.azimuthDegrees);
      bearingInputs.includedStart.value = savedAzimuth.included.start;
      bearingInputs.includedEnd.value = savedAzimuth.included.end;
      bearingInputs.excludedStart.value = savedAzimuth.excluded.start;
      bearingInputs.excludedEnd.value = savedAzimuth.excluded.end;
      syncEditors();
      syncAltitude();
      syncCompass();
    },
    read: function () {
      var state = {};
      if (altitudeEnabled.checked) {
        if (!validAltitude(altitude.minimum, altitude.maximum)) {
          return controlError(
            "Use an altitude range from 0° to 90°, with minimum not above maximum.",
            altitudeMinimumHandle);
        }
        state.altitudeDegrees = Object.assign({}, altitude);
      }
      if (directionEnabled.checked) {
        var parsed = readAzimuth(bearingInputs);
        if (parsed.error) {
          return parsed;
        }
        state.azimuthDegrees = parsed.value;
      }
      return { state: state };
    },
    focusFirst: function () {
      altitudeEnabled.focus();
    }
  };

  function syncEditors() {
    altitudeEditor.hidden = !altitudeEnabled.checked;
    altitudeEnabled.setAttribute("aria-expanded", String(altitudeEnabled.checked));
    directionEditor.hidden = !directionEnabled.checked;
    directionEnabled.setAttribute("aria-expanded", String(directionEnabled.checked));
  }

  function syncAltitude() {
    altitudeOutput.textContent = numberText(altitude.minimum) + "°–"
      + numberText(altitude.maximum) + "°";
    setVerticalHandle(altitudeMinimumHandle, altitude.minimum, 0, altitude.maximum);
    setVerticalHandle(altitudeMaximumHandle, altitude.maximum, altitude.minimum, 90);
    altitudeMinimumHandle.style.zIndex =
      (altitude.minimum + altitude.maximum) / 2 > 45 ? "4" : "";
    var fill = form.querySelector(".preference-altitude-fill");
    fill.style.bottom = (altitude.minimum / 90 * 100) + "%";
    fill.style.height = ((altitude.maximum - altitude.minimum) / 90 * 100) + "%";
  }

  function syncCompass() {
    var values = Object.fromEntries(Object.entries(bearingInputs).map(function (entry) {
      return [entry[0], Number(entry[1].value)];
    }));
    syncSector("included", values.includedStart, values.includedEnd);
    syncSector("excluded", values.excludedStart, values.excludedEnd);
  }

  function syncSector(kind, start, end) {
    var output = form.querySelector("#preference-" + kind + "-output");
    output.textContent = validBearing(start) && validBearing(end)
      ? numberText(start) + "° through " + numberText(end) + "°"
      : "Enter bearings from 0° to 359°";
    if (!validBearing(start) || !validBearing(end)) {
      return;
    }
    setHorizontalHandle(form.querySelector("[data-bearing-handle='" + kind + "-start']"), start);
    setHorizontalHandle(form.querySelector("[data-bearing-handle='" + kind + "-end']"), end);
    setWrappedFill(kind, start, end);
  }

  function setWrappedFill(kind, start, end) {
    var left = form.querySelector("[data-azimuth-fill='" + kind + "-left']");
    var right = form.querySelector("[data-azimuth-fill='" + kind + "-right']");
    if (start > end) {
      left.style.left = "0";
      left.style.width = (end / 360 * 100) + "%";
      right.style.left = (start / 360 * 100) + "%";
      right.style.width = ((360 - start) / 360 * 100) + "%";
    } else {
      left.style.left = (start / 360 * 100) + "%";
      left.style.width = ((end - start) / 360 * 100) + "%";
      right.style.left = "0";
      right.style.width = "0";
    }
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
    if (!objectValue(azimuth) || !validRange(azimuth.included)
        || !validRange(azimuth.excluded) || !contained(azimuth.included, azimuth.excluded)) {
      return null;
    }
    state.azimuthDegrees = {
      included: copyRange(azimuth.included),
      excluded: copyRange(azimuth.excluded)
    };
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
  if (!Number.isFinite(value)) {
    return "";
  }
  var directions = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
  return directions[Math.round(normalizeDegrees(value) / 22.5) % directions.length];
}

function wireHandle(handle, track, orientation, current, update, pointerValue, limits) {
  handle.addEventListener("keydown", function (event) {
    var step = event.shiftKey ? 10 : 1;
    var bounds = limits();
    var value = current();
    if (event.key === "ArrowDown" || event.key === "ArrowLeft") {
      event.preventDefault();
      update(clamp(value - step, bounds.minimum, bounds.maximum));
    } else if (event.key === "ArrowUp" || event.key === "ArrowRight") {
      event.preventDefault();
      update(clamp(value + step, bounds.minimum, bounds.maximum));
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      update(event.key === "Home" ? bounds.minimum : bounds.maximum);
    }
  });
  handle.addEventListener("pointerdown", function (event) {
    handle.setPointerCapture(event.pointerId);
    updatePointer(event);
  });
  handle.addEventListener("pointermove", function (event) {
    if (handle.hasPointerCapture(event.pointerId)) {
      updatePointer(event);
    }
  });
  function updatePointer(event) {
    var bounds = track.getBoundingClientRect();
    var range = limits();
    update(clamp(pointerValue(event, bounds), range.minimum, range.maximum));
  }
  handle.setAttribute("aria-orientation", orientation);
}

function setVerticalHandle(handle, value, minimum, maximum) {
  handle.style.bottom = (value / 90 * 100) + "%";
  setSliderValue(handle, value, minimum, maximum, numberText(value) + " degrees altitude");
}

function setHorizontalHandle(handle, value) {
  handle.style.left = (value / 360 * 100) + "%";
  setSliderValue(handle, value, 0, 359,
    numberText(value) + " degrees, " + compassDirection(value));
}

function setSliderValue(handle, value, minimum, maximum, text) {
  handle.setAttribute("aria-valuemin", numberText(minimum));
  handle.setAttribute("aria-valuemax", numberText(maximum));
  handle.setAttribute("aria-valuenow", numberText(value));
  handle.setAttribute("aria-valuetext", text);
  handle.querySelector("span").textContent = numberText(value) + "°";
}

function readAzimuth(inputs) {
  var values = {};
  for (var entry of Object.entries(inputs)) {
    var value = Number(entry[1].value);
    if (entry[1].value === "" || !validBearing(value)) {
      return controlError("Use compass bearings from 0° up to, but not including, 360°.", entry[1]);
    }
    values[entry[0]] = value;
  }
  var included = { start: values.includedStart, end: values.includedEnd };
  var excluded = { start: values.excludedStart, end: values.excludedEnd };
  if (included.start === included.end) {
    return controlError("The included compass-sector endpoints must differ.", inputs.includedEnd);
  }
  if (excluded.start === excluded.end) {
    return controlError("The blocked-view endpoints must differ.", inputs.excludedEnd);
  }
  if (!contained(included, excluded)) {
    return controlError("Keep the blocked view inside the included compass sector.", inputs.excludedStart);
  }
  return { value: { included: included, excluded: excluded } };
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

function controlError(message, focus) {
  return { error: message, focus: focus };
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

function numberText(value) {
  return String(Math.round(value * 1000) / 1000);
}
