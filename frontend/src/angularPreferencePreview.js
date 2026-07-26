import { clamp, normalizeDegrees } from "./format.js";
import { svgElement } from "./dom.js";
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";
import { altitudeForegroundArtwork } from "./moonPathSilhouettes.js";

var DEFAULT_ALTITUDE = { minimum: 2, maximum: 15 };
var DEFAULT_AZIMUTH = { included: { start: 330, end: 30 }, excluded: { start: 350, end: 10 } };
var EPSILON = 1.0e-9;
var PREVIEW = { left: 40, right: 340, top: 18, bottom: 190 };
var SAMPLES = [
  { bearing: 0, altitude: 8 }, { bearing: 30, altitude: 16 },
  { bearing: 60, altitude: 28 }, { bearing: 90, altitude: 42 },
  { bearing: 120, altitude: 56 }, { bearing: 150, altitude: 68 },
  { bearing: 180, altitude: 76 }, { bearing: 210, altitude: 68 },
  { bearing: 240, altitude: 56 }, { bearing: 270, altitude: 42 },
  { bearing: 300, altitude: 28 }, { bearing: 330, altitude: 16 },
  { bearing: 360, altitude: 8 }
];

export function createAngularPreferencePreview(form) {
  var altitudeTrack = form.querySelector("#preference-altitude-track");
  var altitudeOutput = form.querySelector("#preference-altitude-output");
  var altitudeMinimumHandle = form.querySelector("[data-altitude-minimum]");
  var altitudeMaximumHandle = form.querySelector("[data-altitude-maximum]");
  var compassTrack = form.querySelector("#preference-compass-track");
  var bearingHandles = {
    includedStart: form.querySelector("[data-bearing-handle='included-start']"),
    includedEnd: form.querySelector("[data-bearing-handle='included-end']"),
    excludedStart: form.querySelector("[data-bearing-handle='excluded-start']"),
    excludedEnd: form.querySelector("[data-bearing-handle='excluded-end']")
  };
  var artwork = form.querySelector("#preference-angular-artwork");
  var editor = form.querySelector("#preference-angular-fields");
  var altitude = copyAltitude(DEFAULT_ALTITUDE);
  var azimuth = copyAzimuth(DEFAULT_AZIMUTH);
  var altitudeEnabled = false;
  var directionEnabled = false;

  buildArtwork(artwork);
  wireAltitudeHandle(altitudeMinimumHandle, function () {
    return altitude.minimum;
  }, function (value) {
    altitude.minimum = Math.min(value, altitude.maximum);
  }, function () {
    return { minimum: 0, maximum: altitude.maximum };
  });
  wireAltitudeHandle(altitudeMaximumHandle, function () {
    return altitude.maximum;
  }, function (value) {
    altitude.maximum = Math.max(value, altitude.minimum);
  }, function () {
    return { minimum: altitude.minimum, maximum: 90 };
  });
  Object.entries(bearingHandles).forEach(function (entry) {
    wireBearingHandle(entry[1], function () {
      return bearingValue(entry[0]);
    }, function (value) {
      setBearingValue(entry[0], value);
    });
  });

  return {
    render: function (state) {
      altitude = copyAltitude(state.altitudeDegrees || DEFAULT_ALTITUDE);
      azimuth = copyAzimuth(state.azimuthDegrees || DEFAULT_AZIMUTH);
      sync();
    },
    setEnabled: function (nextAltitudeEnabled, nextDirectionEnabled) {
      altitudeEnabled = nextAltitudeEnabled;
      directionEnabled = nextDirectionEnabled;
      editor.classList.toggle("is-altitude-disabled", !altitudeEnabled);
      editor.classList.toggle("is-direction-disabled", !directionEnabled);
      syncDimming();
    },
    read: function () {
      var state = {};
      if (altitudeEnabled) {
        if (!validAltitude(altitude.minimum, altitude.maximum)) {
          return controlError(
            "Use an altitude range from 0° to 90°, with minimum not above maximum.",
            altitudeMinimumHandle);
        }
        state.altitudeDegrees = copyAltitude(altitude);
      }
      if (directionEnabled) {
        var parsed = readAzimuth();
        if (parsed.error) {
          return parsed;
        }
        state.azimuthDegrees = parsed.value;
      }
      return { state: state };
    }
  };

  function wireAltitudeHandle(handle, current, update, limits) {
    wireHandle(handle, altitudeTrack, "vertical", current, function (value) {
      update(value);
      sync();
    }, function (event, bounds) {
      return Math.round(((bounds.bottom - event.clientY) / bounds.height) * 90);
    }, limits, sync);
  }

  function wireBearingHandle(handle, current, update) {
    wireHandle(handle, compassTrack, "horizontal", current, function (value) {
      update(normalizeDegrees(value));
      sync();
    }, function (event, bounds) {
      return Math.round(((event.clientX - bounds.left) / bounds.width) * 359);
    }, function () {
      return { minimum: 0, maximum: 359 };
    }, sync);
  }

  function bearingValue(key) {
    if (key === "includedStart") return azimuth.included.start;
    if (key === "includedEnd") return azimuth.included.end;
    if (key === "excludedStart") return azimuth.excluded.start;
    return azimuth.excluded.end;
  }

  function setBearingValue(key, value) {
    if (key === "includedStart") azimuth.included.start = value;
    else if (key === "includedEnd") azimuth.included.end = value;
    else if (key === "excludedStart") azimuth.excluded.start = value;
    else azimuth.excluded.end = value;
  }

  function sync() {
    syncAltitude();
    syncCompass();
    syncDimming();
  }

  function syncAltitude() {
    altitudeOutput.textContent = numberText(altitude.minimum) + "°–"
      + numberText(altitude.maximum) + "°";
    setVerticalHandle(altitudeMinimumHandle, altitude.minimum, 0, altitude.maximum);
    setVerticalHandle(altitudeMaximumHandle, altitude.maximum, altitude.minimum, 90);
    var coincident = altitude.minimum === altitude.maximum;
    setOverlapOffset(altitudeMinimumHandle, coincident ? "-10px" : "0px");
    setOverlapOffset(altitudeMaximumHandle, coincident ? "10px" : "0px");
    var fill = form.querySelector(".preference-altitude-fill");
    fill.style.bottom = (altitude.minimum / 90 * 100) + "%";
    fill.style.height = ((altitude.maximum - altitude.minimum) / 90 * 100) + "%";
  }

  function syncCompass() {
    syncSector("included", azimuth.included);
    syncSector("excluded", azimuth.excluded);
    syncBearingOverlap("included", bearingHandles.includedStart, bearingHandles.includedEnd);
    syncBearingOverlap("excluded", bearingHandles.excludedStart, bearingHandles.excludedEnd);
  }

  function syncSector(kind, range) {
    form.querySelector("#preference-" + kind + "-output").textContent =
      numberText(range.start) + "° through " + numberText(range.end) + "°";
    setHorizontalHandle(bearingHandles[kind + "Start"], range.start);
    setHorizontalHandle(bearingHandles[kind + "End"], range.end);
    setWrappedFill(kind, range.start, range.end);
  }

  function syncBearingOverlap(_kind, startHandle, endHandle) {
    var coincident = startHandle.getAttribute("aria-valuenow")
      === endHandle.getAttribute("aria-valuenow");
    setOverlapOffset(startHandle, coincident ? "-10px" : "0px");
    setOverlapOffset(endHandle, coincident ? "10px" : "0px");
  }

  function setWrappedFill(kind, start, end) {
    var left = form.querySelector("[data-azimuth-fill='" + kind + "-left']");
    var right = form.querySelector("[data-azimuth-fill='" + kind + "-right']");
    if (start > end) {
      setFill(left, 0, end);
      setFill(right, start, 360 - start);
    } else {
      setFill(left, start, end - start);
      setFill(right, 0, 0);
    }
  }

  function setFill(node, start, width) {
    node.style.left = (start / 360 * 100) + "%";
    node.style.width = (width / 360 * 100) + "%";
  }

  function syncDimming() {
    artwork.querySelectorAll("[data-preview-bearing]").forEach(function (node) {
      var bearing = Number(node.getAttribute("data-preview-bearing"));
      var sampleAltitude = Number(node.getAttribute("data-preview-altitude"));
      var dimmed = altitudeEnabled
        && (sampleAltitude < altitude.minimum || sampleAltitude > altitude.maximum);
      if (directionEnabled) {
        dimmed = dimmed || !inRange(azimuth.included, bearing)
          || inRange(azimuth.excluded, bearing);
      }
      node.classList.toggle("is-dimmed", dimmed);
      node.setAttribute("data-filtered", String(dimmed));
    });
  }

  function readAzimuth() {
    if (azimuth.included.start === azimuth.included.end) {
      return controlError(
        "The included compass-sector endpoints must differ.",
        bearingHandles.includedEnd);
    }
    if (azimuth.excluded.start === azimuth.excluded.end) {
      return controlError("The blocked-view endpoints must differ.", bearingHandles.excludedEnd);
    }
    if (!contained(azimuth.included, azimuth.excluded)) {
      var focus = inRange(azimuth.included, azimuth.excluded.start)
        ? bearingHandles.excludedEnd
        : bearingHandles.excludedStart;
      return controlError("Keep the blocked view inside the included compass sector.", focus);
    }
    return { value: copyAzimuth(azimuth) };
  }
}

function buildArtwork(artwork) {
  var chartWidth = PREVIEW.right - PREVIEW.left;
  var chartHeight = PREVIEW.bottom - PREVIEW.top;
  var moonImage = moonPhaseImageDataUrl(65, 42, 35, 0);
  var children = [
    gridArtwork(),
    altitudeForegroundArtwork(
      PREVIEW.left, PREVIEW.top, PREVIEW.bottom, chartWidth, "preference", 217, 90, chartHeight),
    SAMPLES.slice(0, -1).map(function (sample, index) {
      var next = SAMPLES[index + 1];
      var midpoint = {
        bearing: (sample.bearing + next.bearing) / 2,
        altitude: (sample.altitude + next.altitude) / 2
      };
      return svgElement("line", {
        className: "preference-preview-segment",
        x1: previewX(sample.bearing),
        y1: previewY(sample.altitude),
        x2: previewX(next.bearing),
        y2: previewY(next.altitude),
        "data-preview-bearing": midpoint.bearing,
        "data-preview-altitude": midpoint.altitude
      });
    }),
    SAMPLES.map(function (sample) {
      return svgElement("image", {
        className: "preference-preview-moon",
        x: previewX(sample.bearing) - 12,
        y: previewY(sample.altitude) - 12,
        width: 24,
        height: 24,
        href: moonImage,
        "data-preview-bearing": normalizeDegrees(sample.bearing),
        "data-preview-altitude": sample.altitude
      });
    })
  ];
  artwork.replaceChildren(...children.flat());
}

function gridArtwork() {
  var compass = [
    { bearing: 0, label: "N" }, { bearing: 90, label: "E" },
    { bearing: 180, label: "S" }, { bearing: 270, label: "W" },
    { bearing: 360, label: "N" }
  ];
  return svgElement("g", { className: "preference-preview-grid" },
    [0, 30, 60, 90].map(function (altitude) {
      return svgElement("g", {},
        svgElement("line", {
          x1: PREVIEW.left,
          x2: PREVIEW.right,
          y1: previewY(altitude),
          y2: previewY(altitude)
        }),
        svgElement("text", {
          x: PREVIEW.left - 20,
          y: previewY(altitude) + 3,
          textAnchor: "end"
        }, altitude + "°"));
    }),
    compass.map(function (point, index) {
      return svgElement("text", {
        x: previewX(point.bearing),
        y: 255,
        textAnchor: index === 0 ? "start" : (index === compass.length - 1 ? "end" : "middle")
      }, point.label);
    }),
    svgElement("text", {
      className: "preference-preview-bearing-direction",
      x: 190,
      y: 245,
      textAnchor: "middle"
    }, "bearing increases →"));
}

function wireHandle(handle, track, orientation, current, update, pointerValue, limits, finish) {
  var pointerGrabOffset = 0;
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
    pointerGrabOffset = current() - pointerValue(event, track.getBoundingClientRect());
    handle.classList.add("is-overlap-dragging");
    handle.setPointerCapture(event.pointerId);
  });
  handle.addEventListener("pointermove", function (event) {
    if (handle.hasPointerCapture(event.pointerId)) {
      updatePointer(event);
    }
  });
  handle.addEventListener("pointerup", finishPointer);
  handle.addEventListener("pointercancel", finishPointer);
  function updatePointer(event) {
    var bounds = track.getBoundingClientRect();
    var range = limits();
    update(clamp(
      pointerValue(event, bounds) + pointerGrabOffset,
      range.minimum,
      range.maximum));
  }
  function finishPointer() {
    handle.classList.remove("is-overlap-dragging");
    finish();
  }
  handle.setAttribute("aria-orientation", orientation);
}

function setOverlapOffset(handle, value) {
  if (!handle.classList.contains("is-overlap-dragging")) {
    handle.style.setProperty("--preference-overlap-offset", value);
  }
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

function previewX(bearing) { return PREVIEW.left + (bearing / 360) * (PREVIEW.right - PREVIEW.left); }

function previewY(altitude) { return PREVIEW.bottom - (altitude / 90) * (PREVIEW.bottom - PREVIEW.top); }

function validAltitude(minimum, maximum) {
  return finiteNumber(minimum) && finiteNumber(maximum)
    && minimum >= 0 && maximum <= 90 && minimum <= maximum;
}

function contained(outer, inner) {
  return clockwiseDistance(outer.start, inner.start)
    + clockwiseDistance(inner.start, inner.end)
    <= clockwiseDistance(outer.start, outer.end) + EPSILON;
}

function inRange(range, value) {
  return clockwiseDistance(range.start, normalizeDegrees(value))
    <= clockwiseDistance(range.start, range.end) + EPSILON;
}

function clockwiseDistance(start, end) { return normalizeDegrees(end - start); }

function copyAltitude(value) { return { minimum: value.minimum, maximum: value.maximum }; }

function copyAzimuth(value) {
  return {
    included: { start: value.included.start, end: value.included.end },
    excluded: { start: value.excluded.start, end: value.excluded.end }
  };
}

function finiteNumber(value) { return typeof value === "number" && Number.isFinite(value); }

function controlError(message, focus) { return { error: message, focus: focus }; }

export function compassDirection(value) {
  var directions = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
    "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
  return directions[Math.round(normalizeDegrees(value) / 22.5) % directions.length];
}

function numberText(value) { return String(Math.round(value * 1000) / 1000); }
