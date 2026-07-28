import { clamp } from "./format.js";
import { svgElement } from "./dom.js";
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";
import { altitudeForegroundArtwork } from "./moonPathSilhouettes.js";
import {
  altitudeFromPosition, altitudePosition, ANGLE_EPSILON, angularZoneDescription,
  bearingLimits, bearingValue, compassDirection, copyAltitude, copyAzimuth,
  copyRange, excludedBearingSegments, MINIMUM_USABLE_DEGREES, moveBearing,
  numberText, rectanglePath, validAltitude
} from "./angularPreferenceRules.js";

var DEFAULT_ALTITUDE = { minimum: 2, maximum: 15 };
var DEFAULT_AZIMUTH = { included: { start: 330, end: 30 }, excluded: { start: 350, end: 10 } };
var PREVIEW = { left: 40, right: 340, top: 30, bottom: 190 };
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
  var zone = form.querySelector("[data-preference-zone]");
  var altitude = copyAltitude(DEFAULT_ALTITUDE);
  var azimuth = copyAzimuth(DEFAULT_AZIMUTH);
  var altitudeEnabled = false;
  var directionEnabled = false;

  buildArtwork(artwork);
  wireAltitudeHandle(altitudeMinimumHandle, function () {
    return altitude.minimum;
  }, function (value, interaction) {
    altitude.minimum = Math.min(value, altitude.maximum);
    if (interaction.limited && interaction.attempted > interaction.maximum) {
      altitudeRebound(altitudeMinimumHandle, "up");
    }
  }, function () {
    return { minimum: 0, maximum: altitude.maximum - MINIMUM_USABLE_DEGREES };
  });
  wireAltitudeHandle(altitudeMaximumHandle, function () {
    return altitude.maximum;
  }, function (value, interaction) {
    altitude.maximum = Math.max(value, altitude.minimum);
    if (interaction.limited && interaction.attempted < interaction.minimum) {
      altitudeRebound(altitudeMaximumHandle, "down");
    }
  }, function () {
    return { minimum: altitude.minimum + MINIMUM_USABLE_DEGREES, maximum: 90 };
  });
  Object.entries(bearingHandles).forEach(function (entry) {
    wireBearingHandle(entry[0], entry[1]);
  });
  zone.addEventListener("pointerenter", updateZoneTooltip);
  zone.addEventListener("pointermove", updateZoneTooltip);

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
      syncExclusion();
    },
    read: function () {
      var state = {};
      if (altitudeEnabled) {
        if (!validAltitude(altitude.minimum, altitude.maximum)) {
          return controlError(
            "Use an altitude range from 0° to 90° that is at least 10° wide.",
            altitudeMinimumHandle);
        }
        state.altitudeDegrees = copyAltitude(altitude);
      }
      if (directionEnabled) {
        var azimuthValue = readAzimuth();
        if (azimuthValue) state.azimuthDegrees = azimuthValue;
      }
      return { state: state };
    }
  };

  function wireAltitudeHandle(handle, current, update, limits) {
    wireHandle(handle, altitudeTrack, "vertical", current, function (value, interaction) {
      update(value, interaction);
      sync();
    }, function (event, bounds) {
      return ((bounds.bottom - event.clientY) / bounds.height) * 90;
    }, limits, function () {
      return altitudePosition(current()) * 90;
    }, function (value) {
      return Math.round(altitudeFromPosition(value / 90));
    });
  }

  function wireBearingHandle(key, handle) {
    wireHandle(handle, compassTrack, "horizontal", function () {
      return bearingValue(azimuth, key);
    }, function (value, interaction) {
      var moved = moveBearing(azimuth, key, value, interaction.direction);
      azimuth = moved.azimuth;
      sync();
    }, function (event, bounds) {
      return Math.round(((event.clientX - bounds.left) / bounds.width) * 360);
    }, function () {
      return bearingLimits(azimuth, key);
    });
  }

  function sync() {
    syncAltitude();
    syncCompass();
    syncExclusion();
  }

  function syncAltitude() {
    setVerticalHandle(
      altitudeMinimumHandle, altitude.minimum, 0,
      altitude.maximum - MINIMUM_USABLE_DEGREES);
    setVerticalHandle(
      altitudeMaximumHandle, altitude.maximum,
      altitude.minimum + MINIMUM_USABLE_DEGREES, 90);
    var fill = form.querySelector(".preference-altitude-fill");
    var minimumPosition = altitudePosition(altitude.minimum) * 100;
    fill.style.bottom = minimumPosition + "%";
    fill.style.height = (altitudePosition(altitude.maximum) * 100 - minimumPosition) + "%";
  }

  function syncCompass() {
    syncSector("included", azimuth.included);
    syncSector("excluded", azimuth.excluded);
  }

  function syncSector(kind, range) {
    setHorizontalHandle(bearingHandles[kind + "Start"], range.start);
    setHorizontalHandle(bearingHandles[kind + "End"], range.end);
    setWrappedFill(kind, range.start, range.end);
  }

  function setWrappedFill(kind, start, end) {
    var left = form.querySelector("[data-azimuth-fill='" + kind + "-left']");
    var right = form.querySelector("[data-azimuth-fill='" + kind + "-right']");
    var fullCompass = kind === "included" && Math.abs(start - end) <= ANGLE_EPSILON;
    left.classList.toggle("is-handle-bridge",
      kind === "included" && start < end
      && Math.abs(end - start - MINIMUM_USABLE_DEGREES) <= ANGLE_EPSILON);
    if (fullCompass) {
      setFill(left, 0, 360);
      setFill(right, 0, 0);
    } else if (start > end) {
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

  function syncExclusion() {
    var rectangles = [];
    if (altitudeEnabled) {
      rectangles.push([PREVIEW.left, PREVIEW.top, PREVIEW.right, previewY(altitude.maximum)]);
      rectangles.push([PREVIEW.left, previewY(altitude.minimum), PREVIEW.right, PREVIEW.bottom]);
    }
    if (directionEnabled) {
      excludedBearingSegments(azimuth.included, azimuth.excluded)
        .forEach(function (segment) {
          rectangles.push([
            previewX(segment[0]), PREVIEW.top, previewX(segment[1]), PREVIEW.bottom
          ]);
        });
    }
    artwork.querySelector("[data-preview-exclusion]").setAttribute(
      "d", rectangles.map(rectanglePath).filter(Boolean).join(" "));
  }

  function updateZoneTooltip(event) {
    var bounds = zone.getBoundingClientRect();
    var bearing = ((event.clientX - bounds.left) / bounds.width) * 360;
    zone.querySelector(".preference-zone-tooltip").textContent = angularZoneDescription(
      bearing, altitudeFromPosition(1 - ((event.clientY - bounds.top) / bounds.height)),
      altitudeEnabled ? altitude : null, directionEnabled ? azimuth : null);
  }

  function readAzimuth() {
    var value = {};
    if (Math.abs(azimuth.included.start - azimuth.included.end) > ANGLE_EPSILON) {
      value.included = copyRange(azimuth.included);
    }
    if (azimuth.excluded.start !== azimuth.excluded.end) {
      value.excluded = copyRange(azimuth.excluded);
    }
    return Object.keys(value).length ? value : null;
  }

  function altitudeRebound(handle, direction) {
    var className = "is-rebounding-" + direction;
    if (handle.classList.contains(className)) return;
    handle.classList.add(className);
    window.setTimeout(function () {
      handle.classList.remove(className);
    }, 360);
  }
}

function buildArtwork(artwork) {
  var chartWidth = PREVIEW.right - PREVIEW.left;
  var chartHeight = PREVIEW.bottom - PREVIEW.top;
  var moonImage = moonPhaseImageDataUrl(65, 42, 270, 0);
  var children = [
    gridArtwork(),
    altitudeForegroundArtwork(
      PREVIEW.left, PREVIEW.top, PREVIEW.bottom, chartWidth, "preference", 217, 90, chartHeight,
      function (altitude) { return altitudePosition(altitude) * chartHeight; }),
    svgElement("path", {
      className: "preference-preview-exclusion",
      "data-preview-exclusion": "true"
    }),
    SAMPLES.slice(0, -1).map(function (sample, index) {
      var next = SAMPLES[index + 1];
      return svgElement("line", {
        className: "preference-preview-segment",
        x1: previewX(sample.bearing),
        y1: previewY(sample.altitude),
        x2: previewX(next.bearing),
        y2: previewY(next.altitude)
      });
    }),
    SAMPLES.map(function (sample) {
      return svgElement("image", {
        className: "preference-preview-moon",
        x: previewX(sample.bearing) - 12,
        y: previewY(sample.altitude) - 12,
        width: 24,
        height: 24,
        href: moonImage
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
  var ticks = Array.from({ length: 25 }, function (_unused, index) {
    var bearing = index * 15;
    var cardinal = bearing % 90 === 0;
    return svgElement("line", {
      className: "preference-preview-bearing-tick" + (cardinal ? " is-cardinal" : ""),
      x1: previewX(bearing), x2: previewX(bearing),
      y1: cardinal ? 193 : 197, y2: 209
    });
  });
  return svgElement("g", { className: "preference-preview-grid" },
    ticks,
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

function wireHandle(
  handle, track, orientation, current, update, pointerValue, limits,
  pointerCurrent, pointerResult) {
  var pointerGrabOffset = 0;
  var previousPointerValue;
  var pointerLimits;
  handle.addEventListener("keydown", function (event) {
    var step = event.shiftKey ? 10 : 1;
    var bounds = limits();
    var value = current();
    if (event.key === "ArrowDown" || event.key === "ArrowLeft") {
      event.preventDefault();
      updateWithin(value - step, bounds, -1);
    } else if (event.key === "ArrowUp" || event.key === "ArrowRight") {
      event.preventDefault();
      updateWithin(value + step, bounds, 1);
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      var target = event.key === "Home"
        ? (bounds.home ?? bounds.minimum)
        : (bounds.end ?? bounds.maximum);
      updateWithin(target, bounds, Math.sign(target - value));
    }
  });
  handle.addEventListener("pointerdown", function (event) {
    var value = pointerValue(event, track.getBoundingClientRect());
    pointerGrabOffset = (pointerCurrent ? pointerCurrent() : current()) - value;
    var adjusted = value + pointerGrabOffset;
    previousPointerValue = pointerResult ? pointerResult(adjusted) : adjusted;
    pointerLimits = limits();
    handle.setPointerCapture(event.pointerId);
  });
  handle.addEventListener("pointermove", function (event) {
    if (handle.hasPointerCapture(event.pointerId)) {
      updatePointer(event);
    }
  });
  handle.addEventListener("pointerup", clearPointer);
  handle.addEventListener("pointercancel", clearPointer);
  function updatePointer(event) {
    var bounds = track.getBoundingClientRect();
    var range = pointerLimits || limits();
    var rawValue = pointerValue(event, bounds) + pointerGrabOffset;
    var attempted = pointerResult ? pointerResult(rawValue) : rawValue;
    var direction = Math.sign(attempted - previousPointerValue);
    previousPointerValue = attempted;
    updateWithin(attempted, range, direction);
  }
  function updateWithin(attempted, bounds, direction) {
    var constrained = clamp(attempted, bounds.minimum, bounds.maximum);
    update(constrained, {
      attempted: attempted,
      direction: direction,
      limited: constrained !== attempted,
      minimum: bounds.minimum,
      maximum: bounds.maximum
    });
  }
  function clearPointer() {
    pointerLimits = null;
    previousPointerValue = undefined;
  }
  handle.setAttribute("aria-orientation", orientation);
}

function setVerticalHandle(handle, value, minimum, maximum) {
  handle.style.bottom = (altitudePosition(value) * 100) + "%";
  setSliderValue(handle, value, minimum, maximum, numberText(value) + " degrees altitude");
}

function setHorizontalHandle(handle, value) {
  handle.style.left = (value / 360 * 100) + "%";
  handle.dataset.tooltipAlign = value <= 110 ? "start" : (value < 250 ? "center" : "end");
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

function previewY(altitude) {
  return PREVIEW.bottom - altitudePosition(altitude) * (PREVIEW.bottom - PREVIEW.top);
}

function controlError(message, focus) { return { error: message, focus: focus }; }
