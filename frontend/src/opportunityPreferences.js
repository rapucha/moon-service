var STORAGE_KEY = "moonService.opportunityPreferences.v1";
var VERSION = 1;
var MAX_WINDOWS = 8;
var CLOCK_PATTERN = /^(?:[01]\d|2[0-3]):[0-5]\d$/;
var LIGHT_BUCKETS = ["daylight", "golden_hour", "civil_twilight", "nautical_twilight", "night"];
var MEMORY_ONLY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";

export function createOpportunityPreferences(options) {
  var details = options.details;
  var form = options.form;
  var resultRegion = options.resultRegion;
  var narrowLayout = options.narrowLayout;
  var altitudeEnabled = form.querySelector("#preference-altitude-enabled");
  var altitudeFields = form.querySelector("#preference-altitude-fields");
  var altitudeMinimum = form.querySelector("#preference-altitude-minimum");
  var altitudeMaximum = form.querySelector("#preference-altitude-maximum");
  var clockEditor = form.querySelector("#preference-clock-editor");
  var clockRows = form.querySelector("#preference-clock-rows");
  var addWindow = form.querySelector("#preference-add-window");
  var lightEditor = form.querySelector("#preference-light-editor");
  var formStatus = form.querySelector("#preference-form-status");
  var rowTemplate = /** @type {HTMLTemplateElement} */ (document.querySelector("#preference-clock-row-template"));
  var filterTemplate = /** @type {HTMLTemplateElement} */ (document.querySelector("#active-filter-template"));
  var activeList = resultRegion.querySelector("#active-filter-list");
  var storage = getStorage();
  var storageNotice = storage ? "" : MEMORY_ONLY_NOTICE;
  var state = emptyState();
  var response = null;

  loadState();
  renderForm();
  renderResult();

  form.addEventListener("change", syncEditors);
  addWindow.addEventListener("click", function () {
    if (clockRows.children.length < MAX_WINDOWS) {
      appendClockRow({ start: "18:00", end: "23:00" });
      updateClockButtons();
      /** @type {HTMLInputElement} */ (clockRows.lastElementChild.querySelector("[data-clock-start]")).focus();
    }
  });
  form.addEventListener("submit", applyForm);
  resultRegion.querySelector("#preference-reset").addEventListener("click", resetAll);

  return {
    requestFor: requestFor,
    beginSearch: clearResponse,
    renderResponse: renderResponse
  };

  function requestFor(request, signal) {
    if (!active(state)) {
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
      if (active(state)) {
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
    var parsed = readForm(form);
    if (parsed.error) {
      formStatus.textContent = parsed.error;
      parsed.focus.focus();
      return;
    }
    formStatus.textContent = "";
    commit(parsed.state, true);
  }

  function renderForm() {
    var altitude = state.altitudeDegrees || { minimum: 0, maximum: 90 };
    altitudeEnabled.checked = Boolean(state.altitudeDegrees);
    altitudeMinimum.value = altitude.minimum;
    altitudeMaximum.value = altitude.maximum;

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
    syncEditors();
    updateClockButtons();
  }

  function syncEditors() {
    altitudeFields.hidden = !altitudeEnabled.checked;
    altitudeEnabled.setAttribute("aria-expanded", String(altitudeEnabled.checked));
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
    var restoreResultFocus = activeList.contains(document.activeElement)
      || resultRegion.querySelector("#preference-reset") === document.activeElement;
    setNotice(resultRegion.querySelector("#preference-storage-notice"), storageNotice);
    var normalized = response && response.appliedPreferenceVersion === VERSION
      ? normalizeState(response.normalizedActiveFilters, false)
      : null;
    var displayedState = normalized || state;
    var filters = filterEntries(displayedState);
    resultRegion.querySelector("#active-preference-summary").hidden = filters.length === 0;
    activeList.replaceChildren();
    filters.forEach(function (filter) {
      var item = /** @type {HTMLElement} */ (filterTemplate.content.firstElementChild.cloneNode(true));
      item.querySelector("span").textContent = filter.label;
      var button = item.querySelector("button");
      button.setAttribute("aria-label", "Remove " + filter.label);
      button.addEventListener("click", function () {
        removeFilter(filter);
      });
      activeList.append(item);
    });
    var timezoneNote = resultRegion.querySelector("#preference-timezone-note");
    timezoneNote.hidden = displayedState.time?.mode !== "local_clock";
    timezoneNote.textContent = "Applied before ranking. Clock windows use "
      + (typeof response?.location?.timezone === "string"
        ? response.location.timezone
        : "the searched location’s timezone") + ".";
    setNotice(resultRegion.querySelector("#preference-excluded-notice"),
      response && Number.isFinite(response.excludedSampleCount)
      ? "Candidate samples excluded before ranking: " + response.excludedSampleCount + "."
      : "");
    setNotice(resultRegion.querySelector("#preference-ignored-notice"),
      response && response.ignoredPreferenceFieldCount > 0
      ? ignoredText(response)
      : "");
    resultRegion.querySelector("#preference-empty-notice").hidden = !(response && response.emptyReason
      && response.emptyReason.code === "no_opportunities_match_preferences");
    var total = filterEntries(state).length;
    details.querySelector("#preference-count").textContent =
      total === 0 ? "None active" : total + " active";
    if (restoreResultFocus) {
      focusAfterChange();
    }
  }

  function resetAll() {
    commit(emptyState(), false);
  }

  function removeFilter(filter) {
    if (filter.kind === "altitude") {
      delete state.altitudeDegrees;
    } else {
      delete state.time;
    }
    commit(state, false);
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

  function focusAfterChange() {
    var target = resultRegion.querySelector(".active-filter-remove")
      || (narrowLayout.matches ? details.querySelector("summary") : altitudeEnabled);
    target.focus();
  }

}

function readForm(form) {
  var next = emptyState();
  var minimum = form.querySelector("#preference-altitude-minimum");
  var maximum = form.querySelector("#preference-altitude-maximum");
  if (form.querySelector("#preference-altitude-enabled").checked) {
    var low = Number(minimum.value);
    var high = Number(maximum.value);
    if (minimum.value === "" || maximum.value === "" || !validAltitude(low, high)) {
      return formError("Use an altitude range from 0° to 90°, with minimum not above maximum.",
        minimum);
    }
    next.altitudeDegrees = { minimum: low, maximum: high };
  }

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
  return { state: next };
}

function formError(message, focus) { return { error: message, focus: focus }; }

function normalizeState(value, requireVersion) {
  if (!objectValue(value) || (requireVersion && value.version !== VERSION)) {
    return null;
  }
  var next = emptyState();
  if (value.altitudeDegrees !== undefined) {
    if (!objectValue(value.altitudeDegrees)
        || !validAltitude(value.altitudeDegrees.minimum, value.altitudeDegrees.maximum)) {
      return null;
    }
    var altitude = value.altitudeDegrees;
    next.altitudeDegrees = { minimum: altitude.minimum, maximum: altitude.maximum };
  }
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

function filterEntries(value) {
  var filters = [];
  if (value.altitudeDegrees) {
    filters.push({
      kind: "altitude",
      label: "Moon altitude " + numberText(value.altitudeDegrees.minimum)
        + "°–" + numberText(value.altitudeDegrees.maximum) + "°"
    });
  }
  if (value.time) {
    var label = value.time.mode === "local_clock"
      ? "Local time " + value.time.windows.map(function (window) {
        return window.start + "–" + window.end;
      }).join(", ")
      : "Light: " + value.time.buckets.map(lightLabel).join(", ");
    filters.push({ kind: "time", label: label });
  }
  return filters;
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

function emptyState() { return { version: VERSION }; }

function active(value) { return Boolean(value.altitudeDegrees || value.time); }

function validAltitude(minimum, maximum) {
  return typeof minimum === "number" && Number.isFinite(minimum)
    && typeof maximum === "number" && Number.isFinite(maximum)
    && minimum >= 0 && maximum <= 90 && minimum <= maximum;
}

function validClockWindow(window) {
  return objectValue(window) && typeof window.start === "string" && typeof window.end === "string"
    && CLOCK_PATTERN.test(window.start) && CLOCK_PATTERN.test(window.end) && window.start !== window.end;
}

function objectValue(value) { return value !== null && typeof value === "object" && !Array.isArray(value); }

function numberText(value) { return String(value); }

function lightLabel(value) {
  var label = value.replaceAll("_", " ");
  return label.charAt(0).toUpperCase() + label.slice(1);
}

function getStorage() {
  try {
    return window.localStorage;
  } catch (error) {
    return null;
  }
}
