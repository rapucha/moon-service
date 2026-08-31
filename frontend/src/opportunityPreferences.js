import {
  createAngularPreferenceControls,
  normalizeAngularPreferences
} from "./angularPreferenceControls.js";
import { preferenceApiPathFor } from "./api.js";
import { element } from "./dom.js";
import {
  createMoonAppearanceControls,
  normalizeMoonAppearancePreferences
} from "./moonAppearanceControls.js";
import { createWeatherRankingPreference } from "./weatherRankingPreference.js";
var STORAGE_KEY = "moonService.opportunityPreferences.v1";
var VERSION = 1;
var CLOCK_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
var LIGHT_BUCKETS = ["daylight", "golden_hour", "civil_twilight", "nautical_twilight", "night"];
var MEMORY_ONLY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";
var showSpecialMoonEvents = true;

export function specialMoonEventsEnabled() {
  return showSpecialMoonEvents;
}

export function createOpportunityPreferences(options) {
  var details = options.details;
  var form = options.form;
  var resultRegion = options.resultRegion;
  var narrowLayout = options.narrowLayout;
  var clockEditor = form.querySelector("#preference-clock-editor");
  var clockStart = /** @type {HTMLInputElement} */ (form.querySelector("[data-clock-start]"));
  var clockEnd = /** @type {HTMLInputElement} */ (form.querySelector("[data-clock-end]"));
  var lightEditor = form.querySelector("#preference-light-editor");
  var formStatus = form.querySelector("#preference-form-status");
  var specialMoonEvents = createSpecialMoonEventsControl(form);
  var storageNotice = "";
  var weatherRanking = createWeatherRankingPreference(form, function (notice) {
    storageNotice = notice;
  });
  var angularControls = createAngularPreferenceControls(form);
  var appearanceControls = createMoonAppearanceControls(form);
  var storage = getStorage();
  if (!storage) {
    storageNotice = MEMORY_ONLY_NOTICE;
  }
  var state = emptyState();
  var response = null;

  loadState();
  showSpecialMoonEvents = state.showSpecialMoonEvents;
  renderForm();
  renderResult();

  specialMoonEvents.addEventListener("change", updateSpecialMoonEvents);
  form.addEventListener("change", syncTimeEditors);
  form.addEventListener("submit", applyForm);
  details.querySelector("#preference-reset").addEventListener("click", resetAll);

  return {
    requestFor: requestFor,
    planningRequestFor: planningRequestFor,
    beginSearch: clearResponse,
    renderResponse: renderResponse
  };

  function requestFor(request, signal) {
    var active = activePreferences(state);
    var activeCount = activeFilterCount(active);
    if (activeCount === 0 && !weatherRanking.isNonDefault()) {
      return null;
    }
    var body = /** @type {Record<string, any>} */ ({});
    if (activeCount > 0) {
      body.preferences = active;
    }
    weatherRanking.decorateRequest(body);
    var locationKey = request.locationId ? "locationId" : "q";
    body[locationKey] = request[locationKey];
    return {
      path: preferenceApiPathFor(request),
      options: /** @type {RequestInit} */ ({
        method: "POST",
        cache: "no-store",
        headers: { "Accept": "application/json", "Content-Type": "application/json" },
        body: JSON.stringify(body),
        signal: signal
      })
    };
  }

  function planningRequestFor(locationId, signal) {
    var snapshot = JSON.parse(JSON.stringify(activePreferences(state)));
    return {
      path: "/api/opportunities/planning",
      options: /** @type {RequestInit} */ ({
        method: "POST",
        cache: "no-store",
        headers: { "Accept": "application/json", "Content-Type": "application/json" },
        body: JSON.stringify({ locationId: locationId, preferences: snapshot }),
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
      if (activeFilterCount(state) > 0 || !state.showSpecialMoonEvents) {
        var stored = { ...state };
        if (stored.showSpecialMoonEvents) delete stored.showSpecialMoonEvents;
        current.setItem(STORAGE_KEY, JSON.stringify(stored));
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
    weatherRanking.apply();
    commit(parsed.state, true);
  }

  function renderForm() {
    angularControls.render(state);
    appearanceControls.render(state);
    weatherRanking.render();
    specialMoonEvents.checked = state.showSpecialMoonEvents;
    var mode = state.time ? state.time.mode : "none";
    form.querySelector("[name='preference-time-mode'][value='" + mode + "']").checked = true;
    var window = mode === "local_clock"
      ? state.time.window
      : { start: "18:00", end: "23:00" };
    clockStart.value = window.start;
    clockEnd.value = window.end;
    var selected = mode === "light_bucket" ? state.time.buckets : ["golden_hour"];
    lightEditor.querySelectorAll("input").forEach(function (input) {
      input.checked = selected.includes(input.value);
    });
    syncTimeEditors();
  }

  function syncTimeEditors() {
    var mode = form.querySelector("[name='preference-time-mode']:checked").value;
    clockEditor.hidden = mode !== "local_clock";
    lightEditor.hidden = mode !== "light_bucket";
  }

  function renderResult() {
    setNotice(resultRegion.querySelector("#preference-storage-notice"), storageNotice);
    var timezoneNote = details.querySelector("#preference-timezone-note");
    timezoneNote.hidden = state.time?.mode !== "local_clock";
    timezoneNote.textContent = typeof response?.location?.timezone === "string"
      ? "Clock window uses " + response.location.timezone + "."
      : "Clock window uses the searched location’s timezone.";
    setNotice(resultRegion.querySelector("#preference-ignored-notice"),
      response && response.ignoredPreferenceFieldCount > 0
      ? ignoredText(response)
      : "");
    var total = activeFilterCount(activePreferences(state));
    details.querySelector("#hard-preference-count").textContent =
      total === 0 ? "None active" : total + " active";
    weatherRanking.renderSummary(details.querySelector("#weather-ranking-summary"));
  }

  function resetAll() {
    formStatus.textContent = "";
    weatherRanking.reset();
    commit(emptyState(), false);
    if (narrowLayout.matches) {
      details.querySelector("summary").focus();
    } else {
      angularControls.focusFirst();
    }
  }

  function updateSpecialMoonEvents() {
    state.showSpecialMoonEvents = specialMoonEvents.checked;
    showSpecialMoonEvents = state.showSpecialMoonEvents;
    persist();
    renderResult();
  }

  function commit(next, closeDisclosure) {
    state = next;
    showSpecialMoonEvents = state.showSpecialMoonEvents;
    response = null;
    persist();
    if (!closeDisclosure) renderForm();
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
  var specialMoonEvents = /** @type {HTMLInputElement} */ (
    form.querySelector("#preference-special-events"));
  next.showSpecialMoonEvents = specialMoonEvents.checked;
  var angular = angularControls.read();
  if (angular.error) {
    return angular;
  }
  Object.assign(next, angular.state);

  var mode = form.querySelector("[name='preference-time-mode']:checked").value;
  if (mode === "local_clock") {
    var clockStart = /** @type {HTMLInputElement} */ (form.querySelector("[data-clock-start]"));
    var clockEnd = /** @type {HTMLInputElement} */ (form.querySelector("[data-clock-end]"));
    var window = {
      start: clockStart.value,
      end: clockEnd.value
    };
    if (!validClockWindow(window)) {
      return formError("The clock window needs different start and end times in HH:mm format.",
        clockStart);
    }
    next.time = { mode: mode, window: window };
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
  if (value.showSpecialMoonEvents !== undefined) {
    if (typeof value.showSpecialMoonEvents !== "boolean") {
      return null;
    }
    next.showSpecialMoonEvents = value.showSpecialMoonEvents;
  }
  return next;
}

function normalizeTime(value) {
  if (!objectValue(value)) {
    return null;
  }
  if (value.mode === "local_clock" && validClockWindow(value.window)) {
    return {
      mode: value.mode,
      window: { start: value.window.start, end: value.window.end }
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

function emptyState() { return { version: VERSION, showSpecialMoonEvents: true }; }

function activeFilterCount(value) {
  return Number(Boolean(value.altitudeDegrees))
    + Number(Boolean(value.azimuthDegrees))
    + Number(Boolean(value.time))
    + Number(Boolean(value.namedPhases))
    + Number(Boolean(value.brightLimbOrientationDegrees));
}

function activePreferences(value) {
  var active = { ...value };
  delete active.showSpecialMoonEvents;
  if (active.namedPhases?.length === 1 && active.namedPhases[0] === "full_moon") {
    delete active.brightLimbOrientationDegrees;
  }
  return active;
}

function createSpecialMoonEventsControl(form) {
  var input = /** @type {HTMLInputElement} */ (element("input", {
    id: "preference-special-events",
    type: "checkbox"
  }));
  var control = element("label", { className: "preference-choice", htmlFor: input.id },
    input,
    element("span", {}, "Show lunar eclipses"));
  form.querySelector(".preference-context-note").before(control);
  return input;
}

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
