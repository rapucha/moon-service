import { normalizeDegrees } from "./format.js";
import { drawMoonPhase } from "./moonPhaseView.js";

var PHASES = [
  "new_moon",
  "waxing_crescent",
  "first_quarter",
  "waxing_gibbous",
  "full_moon",
  "waning_gibbous",
  "last_quarter",
  "waning_crescent"
];
var PHASE_ANGLES = [0, 45, 90, 135, 180, 225, 270, 315];
var PHASE_CYCLE_MILLISECONDS = 1400;
var FULL_CIRCLE_DEGREES = 360;
// Keep the step a divisor of 360 so every exposed axis belongs to a complete set.
var LIMB_ORIENTATION_STEP_DEGREES = 45;
// Equal step and width cover the whole circle without gaps. The backend DegreeRange
// filter includes both endpoints, so adjacent choices share one endpoint. The UI
// still sends only the single sector selected by the user.
var LIMB_ORIENTATION_WIDTH_DEGREES = 45;
var LIMB_ORIENTATION_HALF_WIDTH_DEGREES = LIMB_ORIENTATION_WIDTH_DEGREES / 2;
// Deployed localStorage v1 values used one exact 20-degree sector.
var LEGACY_LIMB_ORIENTATION_WIDTH_DEGREES = 20;
var DEFAULT_LIMB_ORIENTATION_DEGREES = 270;
var RANGE_EPSILON = 1.0e-6;

export function createMoonAppearanceControls(form) {
  var phaseInputs = Array.from(form.querySelectorAll("[data-named-phase]"));
  var preferenceDetails = /** @type {HTMLDetailsElement} */ (
    form.closest("#opportunity-preferences")
  );
  var limbEnabled = form.querySelector("#preference-limb-enabled");
  var limbEditor = form.querySelector("#preference-limb-fields");
  var limbDial = form.querySelector("#preference-limb-dial");
  var limbHandle = form.querySelector("#preference-limb-handle");
  var toleranceRing = form.querySelector(".preference-tolerance-ring");
  var limbOutput = form.querySelector("#preference-limb-output");
  var limbMoon = form.querySelector("#preference-limb-moon");
  var reducedMotion = window.matchMedia("(prefers-reduced-motion: reduce)");
  var target = DEFAULT_LIMB_ORIENTATION_DEGREES;
  var phaseIndex = 0;
  /** @type {number|null} */
  var phaseTimer = null;

  phaseInputs.forEach(function (input) {
    var thumbnail = /** @type {HTMLCanvasElement} */ (
      input.closest("label").querySelector("[data-phase-thumbnail]")
    );
    drawMoonPhase(thumbnail, phaseAngle(input.value));
    input.addEventListener("change", phaseSelectionChanged);
  });
  limbEnabled.addEventListener("change", function () {
    phaseIndex = 0;
    syncEditor();
    syncDial();
    syncAnimation();
  });
  preferenceDetails.addEventListener("toggle", syncAnimation);
  document.addEventListener("visibilitychange", syncAnimation);
  reducedMotion.addEventListener("change", syncAnimation);
  limbHandle.addEventListener("keydown", function (event) {
    if (event.key === "ArrowLeft" || event.key === "ArrowDown") {
      event.preventDefault();
      setTarget(target - LIMB_ORIENTATION_STEP_DEGREES);
    } else if (event.key === "ArrowRight" || event.key === "ArrowUp") {
      event.preventDefault();
      setTarget(target + LIMB_ORIENTATION_STEP_DEGREES);
    } else if (event.key === "Home" || event.key === "End") {
      event.preventDefault();
      setTarget(event.key === "Home"
        ? 0
        : FULL_CIRCLE_DEGREES - LIMB_ORIENTATION_STEP_DEGREES);
    }
  });
  limbHandle.addEventListener("pointerdown", function (event) {
    limbHandle.setPointerCapture(event.pointerId);
    setFromPointer(event);
  });
  limbHandle.addEventListener("pointermove", function (event) {
    if (limbHandle.hasPointerCapture(event.pointerId)) {
      setFromPointer(event);
    }
  });
  limbDial.addEventListener("pointerdown", function (event) {
    if (!limbHandle.contains(event.target)) {
      setFromPointer(event);
      limbHandle.focus();
    }
  });

  return {
    render: function (state) {
      var phases = Array.isArray(state.namedPhases) ? state.namedPhases : PHASES;
      phaseInputs.forEach(function (input) {
        input.checked = phases.includes(input.value);
      });
      phaseIndex = 0;
      limbEnabled.checked = Boolean(state.brightLimbOrientationDegrees);
      target = limbEnabled.checked
        ? targetForRange(state.brightLimbOrientationDegrees[0])
        : DEFAULT_LIMB_ORIENTATION_DEGREES;
      syncEditor();
      syncDial();
      syncAnimation();
    },
    read: function () {
      var state = {};
      var selected = selectedPhases();
      if (selected.length < PHASES.length) {
        state.namedPhases = selected;
      }
      if (limbEnabled.checked) {
        state.brightLimbOrientationDegrees = [rangeForTarget(target)];
      }
      return { state: state };
    }
  };

  function syncEditor() {
    limbEditor.hidden = !limbEnabled.checked;
    limbEnabled.setAttribute("aria-expanded", String(limbEnabled.checked));
  }

  function phaseSelectionChanged(event) {
    var input = /** @type {HTMLInputElement} */ (event.currentTarget);
    if (selectedPhases().length === 0) {
      input.checked = true;
    }
    phaseIndex = 0;
    syncDial();
    syncAnimation();
  }

  function setFromPointer(event) {
    var bounds = limbDial.getBoundingClientRect();
    var x = event.clientX - (bounds.left + bounds.width / 2);
    var y = event.clientY - (bounds.top + bounds.height / 2);
    setTarget(Math.atan2(x, -y) * 180 / Math.PI);
  }

  function setTarget(value) {
    target = snapTarget(value);
    syncDial();
  }

  function syncDial() {
    limbDial.style.setProperty("--preference-limb-angle", target + "deg");
    toleranceRing.style.background = "conic-gradient(from "
      + normalizedNumber(target - LIMB_ORIENTATION_HALF_WIDTH_DEGREES)
      + "deg, #d9ad55 0deg " + LIMB_ORIENTATION_WIDTH_DEGREES
      + "deg, transparent " + LIMB_ORIENTATION_WIDTH_DEGREES + "deg 360deg)";
    limbHandle.setAttribute("aria-valuemin", "0");
    limbHandle.setAttribute("aria-valuemax",
      String(FULL_CIRCLE_DEGREES - LIMB_ORIENTATION_STEP_DEGREES));
    limbHandle.setAttribute("aria-valuenow", String(target));
    limbHandle.setAttribute(
      "aria-valuetext",
      target + " degrees clockwise from zenith, " + directionName(target).toLowerCase());
    limbOutput.textContent = "Illuminated edge: " + directionName(target).toLowerCase();
    drawLimbMoon();
  }

  function drawLimbMoon() {
    var phases = selectedPhases();
    if (phaseIndex >= phases.length) {
      phaseIndex = 0;
    }
    drawMoonPhase(limbMoon, phaseAngle(phases[phaseIndex]), target, 0);
    var context = limbMoon.getContext("2d");
    context.beginPath();
    context.arc(limbMoon.width / 2, limbMoon.height / 2, limbMoon.width * 0.43,
      0, Math.PI * 2);
    context.strokeStyle = "#56606c";
    context.lineWidth = 2.5;
    context.stroke();
  }

  function syncAnimation() {
    stopAnimation();
    if (reducedMotion.matches) {
      phaseIndex = 0;
      drawLimbMoon();
      return;
    }
    if (!shouldAnimate()) {
      return;
    }
    phaseTimer = window.setInterval(function () {
      phaseIndex = (phaseIndex + 1) % selectedPhases().length;
      drawLimbMoon();
    }, PHASE_CYCLE_MILLISECONDS);
  }

  function stopAnimation() {
    if (phaseTimer !== null) {
      window.clearInterval(phaseTimer);
      phaseTimer = null;
    }
  }

  function shouldAnimate() {
    return limbEnabled.checked && !limbEditor.hidden && preferenceDetails.open
      && document.visibilityState === "visible" && selectedPhases().length > 1;
  }

  function selectedPhases() {
    return phaseInputs.filter(function (input) {
      return input.checked;
    }).map(function (input) {
      return input.value;
    });
  }
}

export function normalizeMoonAppearancePreferences(value) {
  var state = {};
  if (value.namedPhases !== undefined) {
    if (!Array.isArray(value.namedPhases) || value.namedPhases.length < 1
        || value.namedPhases.length > PHASES.length
        || new Set(value.namedPhases).size !== value.namedPhases.length
        || value.namedPhases.some(function (phase) {
          return typeof phase !== "string" || !PHASES.includes(phase);
        })) {
      return null;
    }
    var selected = PHASES.filter(function (phase) {
      return value.namedPhases.includes(phase);
    });
    if (selected.length < PHASES.length) {
      state.namedPhases = selected;
    }
  }
  if (value.brightLimbOrientationDegrees !== undefined) {
    var ranges = value.brightLimbOrientationDegrees;
    if (!Array.isArray(ranges) || ranges.length !== 1 || !validTargetRange(ranges[0])) {
      return null;
    }
    state.brightLimbOrientationDegrees = [rangeForTarget(targetForRange(ranges[0]))];
  }
  return state;
}

function validTargetRange(value) {
  if (!objectValue(value) || !validBearing(value.start) || !validBearing(value.end)
      || value.start === value.end) {
    return false;
  }
  var width = clockwiseDistance(value.start, value.end);
  return matchesWidth(width, LIMB_ORIENTATION_WIDTH_DEGREES)
    || matchesWidth(width, LEGACY_LIMB_ORIENTATION_WIDTH_DEGREES);
}

function rangeForTarget(value) {
  return {
    start: normalizedNumber(value - LIMB_ORIENTATION_HALF_WIDTH_DEGREES),
    end: normalizedNumber(value + LIMB_ORIENTATION_HALF_WIDTH_DEGREES)
  };
}

function targetForRange(value) {
  var midpoint = value.start + clockwiseDistance(value.start, value.end) / 2;
  return snapTarget(midpoint);
}

function snapTarget(value) {
  var axis = Math.floor(normalizeDegrees(value) / LIMB_ORIENTATION_STEP_DEGREES + 0.5);
  return normalizedNumber(axis * LIMB_ORIENTATION_STEP_DEGREES);
}

function matchesWidth(actual, expected) {
  return Math.abs(actual - expected) <= RANGE_EPSILON;
}

function clockwiseDistance(start, end) {
  return normalizeDegrees(end - start);
}

function normalizedNumber(value) {
  return Math.round(normalizeDegrees(value) * 1.0e6) / 1.0e6;
}

function validBearing(value) {
  return typeof value === "number" && Number.isFinite(value)
    && value >= 0 && value < 360;
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function phaseAngle(phase) {
  return PHASE_ANGLES[PHASES.indexOf(phase)];
}

function directionName(value) {
  if (value < 23 || value >= 338) return "Toward zenith";
  if (value < 68) return "Upper right";
  if (value < 113) return "Right";
  if (value < 158) return "Lower right";
  if (value < 203) return "Toward nadir";
  if (value < 248) return "Lower left";
  if (value < 293) return "Left";
  return "Upper left";
}
