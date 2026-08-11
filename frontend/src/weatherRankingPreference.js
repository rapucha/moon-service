var STORAGE_KEY = "moonService.weatherRanking.v1";
var DEFAULT_MODE = "balanced";
var MODES = [DEFAULT_MODE, "prefer_clear", "ignore_weather"];
var LABELS = {
  balanced: "Moon Service recommendation",
  prefer_clear: "Prefer clear skies",
  ignore_weather: "Don't use weather in ranking"
};

var WEATHER_RANKING_MEMORY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";
var WEATHER_RANKING_UNSUPPORTED_NOTICE = "Saved preferences were discarded because their format is not supported.";

export function createWeatherRankingPreference(form, onStorageNotice) {
  var storage = browserStorage();
  var mode = load();

  return {
    apply: apply,
    decorateRequest: decorateRequest,
    isNonDefault: function () { return mode !== DEFAULT_MODE; },
    render: render,
    renderSummary: renderSummary,
    reset: reset
  };

  function apply() {
    mode = selectedMode(form);
    persist();
  }

  function decorateRequest(body) {
    if (mode !== DEFAULT_MODE) {
      body.weatherRanking = mode;
    }
  }

  function render() {
    var input = /** @type {HTMLInputElement|null} */ (
      form.querySelector("[name='weather-ranking'][value='" + mode + "']")
    );
    if (input) {
      input.checked = true;
    }
  }

  function renderSummary(summary) {
    var active = mode !== DEFAULT_MODE;
    summary.hidden = !active;
    summary.textContent = active ? " · " + weatherRankingLabel(mode) : "";
  }

  function reset() {
    mode = DEFAULT_MODE;
    persist();
  }

  function load() {
    if (!storage) {
      return DEFAULT_MODE;
    }
    var stored;
    try {
      stored = storage.getItem(STORAGE_KEY);
    } catch (error) {
      storage = null;
      notify(WEATHER_RANKING_MEMORY_NOTICE);
      return DEFAULT_MODE;
    }
    if (stored === null) {
      return DEFAULT_MODE;
    }
    if (stored === DEFAULT_MODE) {
      removeStored(WEATHER_RANKING_MEMORY_NOTICE);
      return DEFAULT_MODE;
    }
    if (validMode(stored)) {
      return stored;
    }
    removeStored(WEATHER_RANKING_UNSUPPORTED_NOTICE);
    notify(WEATHER_RANKING_UNSUPPORTED_NOTICE);
    return DEFAULT_MODE;
  }

  function persist() {
    if (!storage) {
      return;
    }
    try {
      if (mode === DEFAULT_MODE) {
        storage.removeItem(STORAGE_KEY);
      } else {
        storage.setItem(STORAGE_KEY, mode);
      }
    } catch (error) {
      storage = null;
      notify(WEATHER_RANKING_MEMORY_NOTICE);
    }
  }

  function removeStored(failureNotice) {
    try {
      storage.removeItem(STORAGE_KEY);
    } catch (error) {
      storage = null;
      notify(failureNotice);
    }
  }

  function browserStorage() {
    try {
      return window.localStorage;
    } catch (error) {
      notify(WEATHER_RANKING_MEMORY_NOTICE);
      return null;
    }
  }

  function notify(message) {
    if (typeof onStorageNotice === "function") {
      onStorageNotice(message);
    }
  }
}

export function weatherRankingFromResponse(payload) {
  return validMode(payload?.appliedWeatherRanking)
    ? payload.appliedWeatherRanking
    : DEFAULT_MODE;
}

export function weatherRankingLabel(mode) {
  return validMode(mode) ? LABELS[mode] : LABELS[DEFAULT_MODE];
}

function selectedMode(form) {
  var selected = /** @type {HTMLInputElement|null} */ (
    form.querySelector("[name='weather-ranking']:checked")
  );
  return validMode(selected?.value) ? selected.value : DEFAULT_MODE;
}

function validMode(value) {
  return typeof value === "string" && MODES.includes(value);
}
