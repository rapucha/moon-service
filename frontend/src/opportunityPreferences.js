import {
  createAngularPreferenceControls,
  normalizeAngularPreferences
} from "./angularPreferenceControls.js";
import {
  createMoonAppearanceControls,
  normalizeMoonAppearancePreferences
} from "./moonAppearanceControls.js";
import { formatDateTime } from "./format.js";

var STORAGE_KEY = "moonService.opportunityPreferences.v1";
var VERSION = 1;
var UTC_INSTANT_PATTERN = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;
var MAX_WINDOWS = 8;
var CLOCK_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
var LIGHT_BUCKETS = ["daylight", "golden_hour", "civil_twilight", "nautical_twilight", "night"];
var MEMORY_ONLY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";
var IMPACT_LABELS = {
  altitudeDegrees: "Moon altitude",
  azimuthDegrees: "Moon direction",
  time: "Availability",
  namedPhases: "Named Moon phase",
  brightLimbOrientationDegrees: "Bright-limb orientation"
};

export function createOpportunityPreferences(options) {
  var details = options.details;
  var form = options.form;
  var resultRegion = options.resultRegion;
  var narrowLayout = options.narrowLayout;
  var clockEditor = form.querySelector("#preference-clock-editor");
  var clockRows = form.querySelector("#preference-clock-rows");
  var addWindow = form.querySelector("#preference-add-window");
  var lightEditor = form.querySelector("#preference-light-editor");
  var formStatus = form.querySelector("#preference-form-status");
  var emptyNotice = resultRegion.querySelector("#preference-empty-notice");
  var emptyNoticeText = emptyNotice.textContent.trim();
  var rowTemplate = /** @type {HTMLTemplateElement} */ (document.querySelector("#preference-clock-row-template"));
  var angularControls = createAngularPreferenceControls(form);
  var appearanceControls = createMoonAppearanceControls(form);
  var storage = getStorage();
  var storageNotice = storage ? "" : MEMORY_ONLY_NOTICE;
  var state = emptyState();
  var response = null;

  loadState();
  renderForm();
  renderResult();

  form.addEventListener("change", syncTimeEditors);
  addWindow.addEventListener("click", function () {
    if (clockRows.children.length < MAX_WINDOWS) {
      appendClockRow({ start: "18:00", end: "23:00" });
      updateClockButtons();
      /** @type {HTMLInputElement} */ (clockRows.lastElementChild.querySelector("[data-clock-start]")).focus();
    }
  });
  form.addEventListener("submit", applyForm);
  details.querySelector("#preference-reset").addEventListener("click", resetAll);

  return {
    requestFor: requestFor,
    beginSearch: clearResponse,
    renderResponse: renderResponse
  };

  function requestFor(request, signal) {
    if (activeFilterCount(state) === 0) {
      return null;
    }
    var body = { preferences: state };
    var locationKey = request.locationId ? "locationId" : "q";
    body[locationKey] = request[locationKey];
    return {
      path: "/api/opportunities",
      options: /** @type {RequestInit} */ ({
        method: "POST",
        cache: "no-store",
        headers: { "Accept": "application/json", "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: signal
      })
    };
  }

  function renderResponse(payload) {
    response = payload && payload.status === "ok" ? payload : null;
    renderResult();
  }

  function clearResponse() {
    response = null;
    renderResult();
  }

  function loadState() {
    if (!storage) {
      return;
    }
    var raw = withStorage(current => current.getItem(STORAGE_KEY));
    if (!storage || raw === null) {
      return;
    }
    var normalized;
    try {
      normalized = normalizeState(JSON.parse(raw), true);
    } catch (error) {
      normalized = null;
    }
    if (!normalized) {
      discardStored();
      return;
    }
    state = normalized;
    persist();
  }

  function discardStored() {
    withStorage(current => current.removeItem(STORAGE_KEY));
    if (storage) {
      storageNotice = "Saved preferences were discarded because their format is not supported.";
    }
  }

  function persist() {
    if (!storage) {
      return;
    }
    withStorage(function (current) {
      if (activeFilterCount(state) > 0) {
        current.setItem(STORAGE_KEY, JSON.stringify(state));
      } else {
        current.removeItem(STORAGE_KEY);
      }
    });
  }

  function withStorage(action) {
    try {
      return action(storage);
    } catch (error) {
      storage = null;
      storageNotice = MEMORY_ONLY_NOTICE;
      return null;
    }
  }

  function applyForm(event) {
    event.preventDefault();
    var parsed = readForm(form, angularControls, appearanceControls);
    if (parsed.error) {
      formStatus.textContent = parsed.error;
      parsed.focus.focus();
      return;
    }
    formStatus.textContent = "";
    commit(parsed.state, true);
  }

  function renderForm() {
    angularControls.render(state);
    appearanceControls.render(state);
    var mode = state.time ? state.time.mode : "none";
    form.querySelector("[name='preference-time-mode'][value='" + mode + "']").checked = true;
    clockRows.replaceChildren();
    var windows = mode === "local_clock"
      ? state.time.windows
      : [{ start: "18:00", end: "23:00" }];
    windows.forEach(appendClockRow);
    var selected = mode === "light_bucket" ? state.time.buckets : ["civil_twilight"];
    lightEditor.querySelectorAll("input").forEach(function (input) {
      input.checked = selected.includes(input.value);
    });
    syncTimeEditors();
    updateClockButtons();
  }

  function syncTimeEditors() {
    var mode = form.querySelector("[name='preference-time-mode']:checked").value;
    clockEditor.hidden = mode !== "local_clock";
    lightEditor.hidden = mode !== "light_bucket";
  }

  function appendClockRow(window) {
    var row = /** @type {HTMLElement} */ (rowTemplate.content.firstElementChild.cloneNode(true));
    /** @type {HTMLInputElement} */ (row.querySelector("[data-clock-start]")).value = window.start;
    /** @type {HTMLInputElement} */ (row.querySelector("[data-clock-end]")).value = window.end;
    row.querySelector(".preference-remove-window").addEventListener("click", function () {
      row.remove();
      updateClockButtons();
      addWindow.focus();
    });
    clockRows.append(row);
  }

  function updateClockButtons() {
    var rows = clockRows.querySelectorAll(".preference-clock-row");
    rows.forEach(function (row, index) {
      var number = String(index + 1);
      row.querySelector("[data-clock-start]")
        .setAttribute("aria-label", "Local clock window " + number + " start");
      row.querySelector("[data-clock-end]")
        .setAttribute("aria-label", "Local clock window " + number + " end");
      var remove = row.querySelector(".preference-remove-window");
      remove.hidden = rows.length === 1;
      remove.setAttribute("aria-label", "Remove local clock window " + number);
    });
    addWindow.disabled = rows.length >= MAX_WINDOWS;
  }

  function renderResult() {
    setNotice(resultRegion.querySelector("#preference-storage-notice"), storageNotice);
    var timezoneNote = details.querySelector("#preference-timezone-note");
    timezoneNote.hidden = state.time?.mode !== "local_clock";
    timezoneNote.textContent = typeof response?.location?.timezone === "string"
      ? "Clock windows use " + response.location.timezone + "."
      : "Clock windows use the searched location’s timezone.";
    setNotice(resultRegion.querySelector("#preference-excluded-notice"),
      response ? impactText(response, activeFilters(state)) : "");
    setNotice(resultRegion.querySelector("#preference-ignored-notice"),
      response && response.ignoredPreferenceFieldCount > 0
      ? ignoredText(response)
      : "");
    var filteredEmpty = response && response.emptyReason
      && response.emptyReason.code === "no_opportunities_match_preferences";
    setNotice(emptyNotice, filteredEmpty ? emptyNoticeText : "");
    var total = activeFilterCount(state);
    details.querySelector("#preference-count").textContent =
      total === 0 ? "None active" : total + " active";
  }

  function resetAll() {
    formStatus.textContent = "";
    commit(emptyState(), false);
    if (narrowLayout.matches) {
      details.querySelector("summary").focus();
    } else {
      angularControls.focusFirst();
    }
  }

  function commit(next, closeDisclosure) {
    state = next;
    response = null;
    persist();
    renderForm();
    renderResult();
    if (closeDisclosure && narrowLayout.matches) {
      details.open = false;
      details.querySelector("summary").focus();
    }
    options.onApply();
  }

}

function readForm(form, angularControls, appearanceControls) {
  var next = emptyState();
  var angular = angularControls.read();
  if (angular.error) {
    return angular;
  }
  Object.assign(next, angular.state);

  var mode = form.querySelector("[name='preference-time-mode']:checked").value;
  if (mode === "local_clock") {
    var windows = Array.from(form.querySelectorAll(".preference-clock-row")).map(function (row) {
      return {
        start: row.querySelector("[data-clock-start]").value,
        end: row.querySelector("[data-clock-end]").value
      };
    });
    var invalid = windows.findIndex(function (window) {
      return !validClockWindow(window);
    });
    if (invalid >= 0) {
      return formError("Each clock window needs different start and end times in HH:mm format.",
        form.querySelectorAll("[data-clock-start]")[invalid]);
    }
    next.time = { mode: mode, windows: windows };
  } else if (mode === "light_bucket") {
    var buckets = Array.from(form.querySelectorAll("#preference-light-editor input:checked"))
      .map(function (input) { return input.value; });
    if (buckets.length === 0) {
      return formError("Choose at least one ambient-light bucket.",
        form.querySelector("#preference-light-editor input"));
    }
    next.time = { mode: mode, buckets: buckets };
  }
  var appearance = appearanceControls.read();
  if (appearance.error) {
    return appearance;
  }
  Object.assign(next, appearance.state);
  return { state: next };
}

function formError(message, focus) { return { error: message, focus: focus }; }

function normalizeState(value, requireVersion) {
  if (!objectValue(value) || (requireVersion && value.version !== VERSION)) {
    return null;
  }
  var next = emptyState();
  var angular = normalizeAngularPreferences(value);
  var appearance = normalizeMoonAppearancePreferences(value);
  if (!angular || !appearance) {
    return null;
  }
  Object.assign(next, angular, appearance);
  if (value.time !== undefined) {
    var time = normalizeTime(value.time);
    if (!time) {
      return null;
    }
    next.time = time;
  }
  return next;
}

function normalizeTime(value) {
  if (!objectValue(value)) {
    return null;
  }
  if (value.mode === "local_clock" && Array.isArray(value.windows)
      && value.windows.length >= 1 && value.windows.length <= MAX_WINDOWS
      && value.windows.every(validClockWindow)) {
    return {
      mode: value.mode,
      windows: value.windows.map(window => ({ start: window.start, end: window.end }))
    };
  }
  if (value.mode === "light_bucket" && Array.isArray(value.buckets)
      && value.buckets.length >= 1 && value.buckets.every(function (bucket) {
        return LIGHT_BUCKETS.includes(bucket);
      })) {
    return {
      mode: value.mode,
      buckets: LIGHT_BUCKETS.filter(function (bucket) {
        return value.buckets.includes(bucket);
      })
    };
  }
  return null;
}

function setNotice(node, text) {
  node.textContent = text;
  node.hidden = !text;
}

function impactText(payload, expectedFilters) {
  var { preferenceImpact: impact, location } = payload;
  if (!objectValue(impact)
      || !Number.isInteger(impact.unfilteredOpportunityCount)
      || impact.unfilteredOpportunityCount < 0 || !Array.isArray(impact.filters)
      || impact.filters.length !== expectedFilters.length
      || typeof location?.timezone !== "string") {
    return "";
  }
  var rows = impact.filters.map(function (item, index) {
    var label = IMPACT_LABELS[item?.filter];
    var count = item?.matchingOpportunityCount;
    var next = theoreticalMatchText(item, location);
    if (!label || item.filter !== expectedFilters[index]
        || !Number.isInteger(count) || count < 0
        || count > impact.unfilteredOpportunityCount || !next) {
      return null;
    }
    return { label: label, count: count, reduction: impact.unfilteredOpportunityCount - count, next: next };
  });
  if (rows.includes(null)) return "";
  var greatest = Math.max(0, ...rows.map(row => row.reduction));
  return "Without preferences: " + impact.unfilteredOpportunityCount
    + (impact.unfilteredOpportunityCount === 1 ? " opportunity. " : " opportunities. ")
    + rows.map(function (row) {
      var largest = greatest > 0 && row.reduction === greatest ? "; largest reduction" : "";
      return row.label + " alone: " + row.count
        + (row.count === 1 ? " opportunity (" : " opportunities (")
        + row.reduction + " fewer" + largest + "). " + row.next;
    }).join(" ")
    + " Each preference is evaluated by itself with the others off.";
}

function theoreticalMatchText(item, location) {
  if (!objectValue(item) || !Number.isInteger(item.lookAheadDays) || item.lookAheadDays <= 0) return "";
  if (item.status === "next_match" && validInstant(item.nextMatchAt)) {
    return "Next theoretical match without weather: "
      + formatDateTime(item.nextMatchAt, location.timezone, location.countryCode)
      + " " + location.timezone + ".";
  }
  return item.status === "not_found" && item.nextMatchAt === undefined
    ? "No theoretical match without weather in the next " + item.lookAheadDays + " days."
    : "";
}

function validInstant(value) {
  var parsed = typeof value === "string" && UTC_INSTANT_PATTERN.test(value)
    ? new Date(value) : null;
  return parsed !== null && Number.isFinite(parsed.getTime())
    && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
}

function ignoredText(payload) {
  var count = payload.ignoredPreferenceFieldCount;
  var noun = count === 1 ? "field." : "fields.";
  var paths = Array.isArray(payload.ignoredPreferenceFields)
    ? payload.ignoredPreferenceFields
    : [];
  var more = payload.additionalIgnoredPreferenceFieldCount;
  var pathText = paths.length > 0 ? " Paths: " + paths.join(", ") + "." : "";
  var moreText = more > 0 ? " " + more + " more were not listed." : "";
  return "The server ignored " + count + " unsupported preference " + noun + pathText + moreText;
}

function emptyState() { return { version: VERSION }; }

function activeFilters(value) {
  return Object.keys(IMPACT_LABELS).filter(filter => Boolean(value[filter]));
}

function activeFilterCount(value) { return activeFilters(value).length; }

function validClockWindow(window) {
  return objectValue(window) && typeof window.start === "string" && typeof window.end === "string"
    && CLOCK_PATTERN.test(window.start) && CLOCK_PATTERN.test(window.end) && window.start !== window.end;
}

function objectValue(value) { return value !== null && typeof value === "object" && !Array.isArray(value); }

function getStorage() {
  try {
    return window.localStorage;
  } catch (error) {
    return null;
  }
}
