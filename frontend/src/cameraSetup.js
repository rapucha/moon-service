import { element } from "./dom.js";

var STORAGE_KEY = "moonService.cameraSetup.v1";
var VERSION = 1;
var MOON_DIAMETER_DEGREES = 0.52;
var FORMATS = {
  digital_full_frame: digitalFormat("Full-frame digital", 36.0, 3 / 2),
  digital_aps_c: digitalFormat("APS-C digital", 23.5, 3 / 2),
  digital_micro_four_thirds: digitalFormat("Micro Four Thirds digital", 17.3, 4 / 3),
  digital_medium_44x33: digitalFormat("Medium format 44×33 digital", 43.8, 4 / 3),
  film: filmFormat("Film")
};
var FORMAT_GROUPS = [
  { label: "Digital", values: [
    "digital_full_frame", "digital_aps_c", "digital_micro_four_thirds", "digital_medium_44x33"
  ] },
  { label: "Film", values: ["film"] }
];
var MP_SUGGESTIONS = [6, 8, 10, 12, 16, 20, 24, 26, 30, 33, 36, 40, 42, 45, 50, 61, 80, 100, 102, 150];
var FOCAL_SUGGESTIONS = [
  10, 11, 12, 14, 15, 16, 17, 18, 20, 21, 24, 28, 30, 31, 35, 40, 43, 45,
  50, 55, 60, 70, 77, 80, 85, 100, 105, 120, 135, 150, 180, 200, 250, 270,
  300, 400, 450, 500, 600, 800, 1000, 1200
];
var TELECONVERTERS = [1, 1.4, 1.7, 2];
var DEFAULT_SETUP = {
  version: VERSION,
  captureFormat: "digital_full_frame",
  outputMegapixels: 24,
  focalLengthMm: 300,
  teleconverterMultiplier: 1
};
var MEMORY_NOTICE = "Camera setup storage is unavailable. Changes last only on this page.";

export function createCameraSetup(options) {
  var form = options.form;
  var storageNotice = options.storageNotice;
  var storage = getStorage();
  var setup = loadSetup(storage, storageNotice);
  var fields = buildEditor(form);
  var invalidFields = new Set();

  renderAll();
  form.addEventListener("submit", function (event) { event.preventDefault(); });
  form.addEventListener("input", function (event) {
    var input = event.target;
    if (!(input instanceof HTMLInputElement)) return;
    if (input.name === "camera-output-megapixels") {
      commitNumber("outputMegapixels", input.value, "Enter a positive output MP value.");
    } else if (input.name === "camera-focal-length") {
      commitNumber("focalLengthMm", input.value, "Enter a positive marked focal length.");
    }
  });
  form.addEventListener("change", function (event) {
    var select = event.target;
    if (!(select instanceof HTMLSelectElement)) return;
    var next = Object.assign({}, setup);
    if (select.name === "camera-capture-format" && Object.hasOwn(FORMATS, select.value)) {
      next.captureFormat = select.value;
      if (FORMATS[select.value].medium === "film") {
        invalidFields.delete("outputMegapixels");
        fields.outputMegapixels.value = String(setup.outputMegapixels);
      }
    } else if (select.name === "camera-teleconverter") {
      var multiplier = Number(select.value);
      if (!TELECONVERTERS.includes(multiplier)) return;
      next.teleconverterMultiplier = multiplier;
    } else {
      return;
    }
    commit(next);
  });
  options.details.querySelector("[data-camera-reset]").addEventListener("click", function () {
    setup = Object.assign({}, DEFAULT_SETUP);
    invalidFields.clear();
    persist();
    renderAll();
    options.onChange();
  });

  return {
    estimateFor: function (moon) { return cameraEstimateDisclosure(moon, setup); },
    replaceEstimate: function (container, moon, before) {
      var previous = container.querySelector(".camera-estimate");
      var next = cameraEstimateDisclosure(moon, setup);
      if (!next) {
        if (previous) previous.remove();
      } else if (previous) {
        next.open = previous.open;
        previous.replaceWith(next);
      } else {
        container.insertBefore(next, before || null);
      }
    }
  };

  function commitNumber(name, raw, message) {
    var value = raw.trim() === "" ? NaN : Number(raw);
    if (!Number.isFinite(value) || value <= 0) {
      invalidFields.add(name);
      showValidation(message);
      return;
    }
    invalidFields.delete(name);
    var next = Object.assign({}, setup);
    next[name] = value;
    commit(next);
  }

  function commit(next) {
    var normalized = normalizeSetup(next);
    if (!normalized) return;
    setup = normalized;
    persist();
    renderDependent();
    showValidation();
    options.onChange();
  }

  function persist() {
    if (saveSetup(storage, setup)) {
      storageNotice.textContent = "";
      storageNotice.hidden = true;
    } else {
      storage = null;
      storageNotice.textContent = MEMORY_NOTICE;
      storageNotice.hidden = false;
    }
  }

  function renderAll() {
    fields.captureFormat.value = setup.captureFormat;
    fields.outputMegapixels.value = String(setup.outputMegapixels);
    fields.focalLengthMm.value = String(setup.focalLengthMm);
    fields.teleconverter.value = String(setup.teleconverterMultiplier);
    renderDependent();
    showValidation();
  }

  function renderDependent() {
    var format = FORMATS[setup.captureFormat];
    fields.resolutionField.hidden = format.medium !== "digital";
    fields.resolutionHelp.hidden = format.medium !== "digital";
    var focalForEstimate = setup.focalLengthMm * setup.teleconverterMultiplier;
    var hasTeleconverter = setup.teleconverterMultiplier !== 1;
    fields.focalUsedRow.hidden = !hasTeleconverter;
    fields.focalUsed.textContent = hasTeleconverter
      ? "Focal length used for this estimate: " + formatNumber(focalForEstimate) + " mm ("
        + formatNumber(setup.focalLengthMm) + " mm × "
        + formatNumber(setup.teleconverterMultiplier) + ")."
      : "";
    if (setup.focalLengthMm <= 4) {
      fields.focalWarning.textContent = "This marked focal length is very small.";
      fields.focalWarning.hidden = false;
    } else if (setup.focalLengthMm > 5000) {
      fields.focalWarning.textContent = "This marked focal length is really big.";
      fields.focalWarning.hidden = false;
    } else {
      fields.focalWarning.textContent = "";
      fields.focalWarning.hidden = true;
    }
  }

  function showValidation(message) {
    markInvalid(fields.outputMegapixels, invalidFields.has("outputMegapixels"));
    markInvalid(fields.focalLengthMm, invalidFields.has("focalLengthMm"));
    fields.validation.textContent = invalidFields.size === 0 ? "" : (message
      || "Correct the numeric value; the last valid setup is still in use.");
    fields.validation.hidden = invalidFields.size === 0;
  }
}

function markInvalid(control, invalid) {
  if (invalid) control.setAttribute("aria-invalid", "true");
  else control.removeAttribute("aria-invalid");
}

function buildEditor(form) {
  var captureFormat = element("select", { name: "camera-capture-format", "aria-describedby": "camera-format-help" },
    FORMAT_GROUPS.map(function (group) {
      return element("optgroup", { label: group.label }, group.values.map(function (value) {
        return element("option", { value: value }, FORMATS[value].label);
      }));
    }));
  var outputMegapixels = element("input", {
    name: "camera-output-megapixels", type: "number", step: "any", inputmode: "decimal",
    list: "camera-mp-suggestions", required: "", "aria-describedby": "camera-mp-help camera-validation"
  });
  var focalLengthMm = element("input", {
    name: "camera-focal-length", type: "number", step: "any", inputmode: "decimal",
    list: "camera-focal-suggestions", required: "",
    "aria-describedby": "camera-focal-help camera-focal-warning camera-validation"
  });
  var teleconverter = element("select", { name: "camera-teleconverter" },
    TELECONVERTERS.map(function (value) {
      return element("option", { value: value }, value === 1 ? "None (1×)" : value + "×");
    }));
  var resolutionField = field("Output resolution (MP)", outputMegapixels);
  var resolutionHelp = element("p", {
    id: "camera-mp-help", className: "preference-help preference-help-unindented"
  }, "Choose a suggestion or type any positive final output MP.");
  var focalUsed = element("output", { id: "camera-focal-used", "aria-live": "polite" });
  var focalUsedRow = element("p", {}, focalUsed);
  var focalWarning = element("p", {
    id: "camera-focal-warning", className: "preference-notice warning", role: "status"
  });
  var validation = element("p", {
    id: "camera-validation", className: "preference-form-status", role: "status", "aria-live": "polite"
  });
  focalWarning.hidden = true;
  validation.hidden = true;
  form.replaceChildren(
    field("Capture format", captureFormat),
    element("p", { id: "camera-format-help", className: "preference-help preference-help-unindented" },
      "Digital uses output MP. Film reports physical image size on the film original in millimetres, independent of film size."),
    resolutionField,
    resolutionHelp,
    field("Marked focal length (mm)", focalLengthMm),
    element("p", { id: "camera-focal-help", className: "preference-help preference-help-unindented" },
      "Choose a suggestion or type any positive focal length marked on the lens."),
    focalWarning,
    field("Teleconverter", teleconverter),
    focalUsedRow,
    validation,
    suggestionList("camera-mp-suggestions", MP_SUGGESTIONS),
    suggestionList("camera-focal-suggestions", FOCAL_SUGGESTIONS)
  );
  return {
    captureFormat: captureFormat,
    resolutionField: resolutionField,
    resolutionHelp: resolutionHelp,
    outputMegapixels: outputMegapixels,
    focalLengthMm: focalLengthMm,
    teleconverter: teleconverter,
    focalUsed: focalUsed,
    focalUsedRow: focalUsedRow,
    focalWarning: focalWarning,
    validation: validation
  };
}

function field(label, control) {
  return element("p", {}, element("label", { className: "camera-field" }, label, control));
}
function suggestionList(id, values) {
  return element("datalist", { id: id }, values.map(function (value) {
    return element("option", { value: value });
  }));
}

function cameraEstimateDisclosure(moon, setup) {
  var estimate = cameraEstimate(moon, setup);
  if (!estimate) return null;
  var digital = estimate.format.medium === "digital";
  return element("details", { className: "camera-estimate" },
    element("summary", {}, element("span", { className: "sky-picture-title" }, "Camera estimate"),
      " — ", element("span", { className: "sky-picture-description" }, "Illuminated Moon thickness")),
    element("div", { className: "sky-picture-content" },
      element("p", {}, estimate.contextText),
      element("dl", { className: "detail-grid" }, digital
        ? [fact("Illuminated angle", estimate.angularText), fact("Maximum thickness", estimate.pixelText)]
        : [fact("Full Moon diameter", estimate.diameterText),
          fact("Illuminated thickness", estimate.thicknessText)]),
      element("p", {}, digital
        ? "This is the widest illuminated thickness. A crescent tapers to zero at its horns. It estimates capture sampling; resizing changes the pixel result. It does not predict visibility, optical resolution, or exposure. A multi-shot pixel-shift mode may not register a moving Moon successfully."
        : "This is the widest illuminated thickness. A crescent tapers to zero at its horns. It describes physical image size on the film original, not visibility, optical resolution, film resolving power, exposure, scanning, or printing."),
      element("p", {}, "Assumes rectilinear projection or a Moon near the image center; off-axis fisheye scale varies by lens projection.")));
}

function cameraEstimate(moon, setup) {
  var normalized = normalizeSetup(setup);
  var illumination = moon && Number.isFinite(moon.illuminationPercent)
    ? moon.illuminationPercent / 100 : NaN;
  if (!normalized || !Number.isFinite(illumination) || illumination < 0 || illumination > 1) return null;
  var format = FORMATS[normalized.captureFormat];
  var focalForEstimate = normalized.focalLengthMm * normalized.teleconverterMultiplier;
  var diameterMm = focalForEstimate * 2 * Math.tan(MOON_DIAMETER_DEGREES * Math.PI / 360);
  var thicknessMm = diameterMm * illumination;
  var estimate = {
    format: format,
    angularText: formatAngle(MOON_DIAMETER_DEGREES * illumination),
    contextText: estimateContext(normalized, format, focalForEstimate)
  };
  if (format.medium === "digital") {
    var horizontalPixels = Math.sqrt(normalized.outputMegapixels) * 1000
      * Math.sqrt(format.aspectRatio);
    estimate.pixelText = formatPixels(thicknessMm * horizontalPixels / format.widthMm);
  } else {
    estimate.diameterText = formatMillimetres(diameterMm);
    estimate.thicknessText = formatMillimetres(thicknessMm);
  }
  return estimate;
}

function estimateContext(setup, format, focalForEstimate) {
  var focal = setup.teleconverterMultiplier === 1
    ? formatNumber(focalForEstimate) + " mm"
    : formatNumber(setup.focalLengthMm) + " mm × " + formatNumber(setup.teleconverterMultiplier)
      + " = " + formatNumber(focalForEstimate) + " mm for this estimate";
  return format.medium === "digital"
    ? "At " + focal + " with " + format.label + " and " + formatNumber(setup.outputMegapixels) + " MP:"
    : "At " + focal + " on the film original:";
}

function fact(label, value) { return element("div", {}, element("dt", {}, label), element("dd", {}, value)); }
function formatAngle(value) { return value.toFixed(value < 0.1 ? 3 : 2) + "°"; }
function formatPixels(value) {
  if (value === 0) return "0 px";
  if (value > 0 && value < 1) return "<1 px";
  return Math.round(value) + " px";
}
function formatMillimetres(value) {
  if (value === 0) return "0 mm";
  if (value > 0 && value < 0.01) return "<0.01 mm";
  return value.toFixed(2) + " mm";
}
function formatNumber(value) {
  var rounded = Number(value.toFixed(4));
  return rounded === 0 && value !== 0 ? value.toExponential(2) : rounded.toString();
}

function loadSetup(storage, notice) {
  if (!storage) return unavailable(notice);
  var raw;
  try {
    raw = storage.getItem(STORAGE_KEY);
  } catch (error) {
    return unavailable(notice);
  }
  if (raw === null) return Object.assign({}, DEFAULT_SETUP);
  try {
    var setup = normalizeSetup(JSON.parse(raw));
    if (setup) return setup;
  } catch (error) {
    // Invalid JSON and invalid objects use the same silent reset.
  }
  try {
    storage.removeItem(STORAGE_KEY);
    notice.textContent = "";
    notice.hidden = true;
  } catch (error) {
    return unavailable(notice);
  }
  return Object.assign({}, DEFAULT_SETUP);
}

function unavailable(notice) {
  notice.textContent = MEMORY_NOTICE;
  notice.hidden = false;
  return Object.assign({}, DEFAULT_SETUP);
}

function saveSetup(storage, setup) {
  if (!storage) return false;
  try {
    storage.setItem(STORAGE_KEY, JSON.stringify(setup));
    return true;
  } catch (error) {
    return false;
  }
}

function normalizeSetup(value) {
  if (!value || Object.keys(value).length !== 5 || value.version !== VERSION
      || !Object.hasOwn(FORMATS, value.captureFormat)
      || !positiveNumber(value.outputMegapixels) || !positiveNumber(value.focalLengthMm)
      || !TELECONVERTERS.includes(value.teleconverterMultiplier)) return null;
  return {
    version: VERSION,
    captureFormat: value.captureFormat,
    outputMegapixels: value.outputMegapixels,
    focalLengthMm: value.focalLengthMm,
    teleconverterMultiplier: value.teleconverterMultiplier
  };
}

function positiveNumber(value) { return Number.isFinite(value) && value > 0; }
function digitalFormat(label, widthMm, aspectRatio) {
  return { label: label, medium: "digital", widthMm: widthMm, aspectRatio: aspectRatio };
}
function filmFormat(label) {
  return { label: label, medium: "film" };
}
function getStorage() {
  try { return window.localStorage; } catch (error) { return null; }
}
