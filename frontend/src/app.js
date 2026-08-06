import { apiPathFor, fallbackPayload, searchPathFor, sharePathFor } from "./api.js";
import { createCameraSetup } from "./cameraSetup.js";
import { element } from "./dom.js";
import { createOpportunityPreferences } from "./opportunityPreferences.js";
import { createPlanningView } from "./planningView.js";
import { readRecent, saveRecentLocation, writeRecent } from "./recentSearches.js";
import { createResponseView } from "./responseView.js";

var CONTROL_CHARACTER_PATTERN = /[\u0000-\u001F\u007F-\u009F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/u;

var form = /** @type {HTMLFormElement} */ (document.getElementById("search-form"));
var input = /** @type {HTMLInputElement} */ (document.getElementById("location-input"));
var formFeedback = /** @type {HTMLElement} */ (document.getElementById("form-feedback"));
var results = /** @type {HTMLElement} */ (document.getElementById("results"));
var orderControl = /** @type {HTMLFieldSetElement} */ (document.getElementById("opportunity-order"));
var orderInputs = /** @type {NodeListOf<HTMLInputElement>} */ (
  orderControl.querySelectorAll("input[name='opportunity-order']"));
var recentSearches = /** @type {HTMLDetailsElement} */ (document.getElementById("recent-searches"));
var recentList = /** @type {HTMLElement} */ (document.getElementById("recent-list"));
var clearRecent = /** @type {HTMLButtonElement} */ (document.getElementById("clear-recent"));
var workspaceTitle = /** @type {HTMLElement} */ (document.getElementById("workspace-title"));
var workspaceHeading = /** @type {HTMLElement} */ (document.querySelector(".workspace-heading"));
var ordinaryWorkspaceMeta = /** @type {HTMLElement} */ (document.querySelector(".workspace-meta"));
var preferenceDetails = /** @type {HTMLDetailsElement} */ (document.getElementById("opportunity-preferences"));
var preferenceForm = /** @type {HTMLFormElement} */ (document.getElementById("preference-form"));
var preferenceResultRegion = /** @type {HTMLElement} */ (document.getElementById("preference-result-region"));
var cameraDetails = /** @type {HTMLDetailsElement} */ (document.getElementById("camera-setup"));
var cameraForm = /** @type {HTMLFormElement} */ (document.getElementById("camera-setup-form"));
var cameraStorageNotice = /** @type {HTMLElement} */ (document.getElementById("camera-storage-notice"));
var submitButton = /** @type {HTMLButtonElement} */ (form.querySelector("button[type='submit']"));
var narrowSearchLayout = window.matchMedia("(max-width: 680px)");
var activeRequest = null;
var activeOrder;
var resolvedRequest = null;
var ordinaryWorkspaceTitle = workspaceTitle.textContent;
var planningWeatherNotice = element("div", {
  className: "workspace-meta planning-weather-notice",
  ariaLabel: "Planning boundary"
}, element("span", {}, "Weather is not considered"));
planningWeatherNotice.hidden = true;
planningWeatherNotice.style.display = "none";
workspaceHeading.append(planningWeatherNotice);

var responseView = createResponseView(results, {
  onResolvedLocation: function (location, request) {
    saveRecentLocation(location, request);
    input.value = location.displayName;
    renderRecent();
  },
  onSelectLocation: function (candidate, query) {
    searchLocationId(candidate.id, candidate.displayName || candidate.id || query, { updateUrl: true });
  }
});
var planningView = createPlanningView(results);
var cameraSetup = createCameraSetup({
  details: cameraDetails,
  form: cameraForm,
  storageNotice: cameraStorageNotice,
  onChange: function () {
    responseView.refresh();
    planningView.refresh();
  }
});
responseView.setCameraSetup(cameraSetup);
planningView.setCameraSetup(cameraSetup);

var preferences = createOpportunityPreferences({
  details: preferenceDetails,
  form: preferenceForm,
  resultRegion: preferenceResultRegion,
  narrowLayout: narrowSearchLayout,
  onApply: function () {
    var request = lookupFromUrl();
    if (request) {
      runLookup(request, { updateUrl: false });
    }
  }
});

form.addEventListener("submit", function (event) {
  event.preventDefault();
  search(input.value, { updateUrl: true });
});

orderControl.addEventListener("change", function (event) {
  if (!(event.target instanceof HTMLInputElement)) return;
  activeOrder = event.target.value === "soonest" ? "soonest" : undefined;
  var request = lookupFromUrl();
  if (!request) {
    return;
  }
  request.order = activeOrder;
  window.history.pushState(request, "", searchPathFor(request));
  fetchOpportunities(request);
});

clearRecent.addEventListener("click", function () {
  writeRecent([]);
  renderRecent();
});

narrowSearchLayout.addEventListener("change", syncSearchDisclosures);

window.addEventListener("popstate", function () {
  runLookup(lookupFromUrl(), { updateUrl: false });
});

document.addEventListener("click", function (event) {
  var target = event.target instanceof Element ? event.target : null;
  var button = target ? target.closest("[data-share-url]") : null;
  if (!button) {
    return;
  }

  var shareUrl = button.getAttribute("data-share-url");
  copyText(shareUrl).then(function () {
    var original = button.textContent;
    button.textContent = "Copied";
    window.setTimeout(function () {
      button.textContent = original;
    }, 1500);
  });
});

syncSearchDisclosures(narrowSearchLayout);
renderRecent();
runLookup(lookupFromUrl(), { updateUrl: false });

function lookupFromUrl() {
  var params = new URLSearchParams(window.location.search);
  var locationId = normalizeQuery(params.get("locationId") || "");
  var query = normalizeQuery(params.get("q") || "");
  var order = params.has("order") ? params.get("order") || "" : undefined;
  if (locationId) {
    return { locationId: locationId, label: locationId, order: order };
  }
  if (query) {
    return { q: query, label: query, order: order };
  }
  return null;
}

function runLookup(request, options) {
  activeOrder = request?.order;
  syncOrderInputs();
  if (!request) {
    beginOrdinaryLookup(request);
    input.value = "";
    preferences.beginSearch();
    responseView.renderIntro();
    return;
  }
  if (request.locationId) {
    searchLocationId(request.locationId, request.label, options, request);
  } else {
    search(request.q, options, request);
  }
}

function syncOrderInputs() {
  var selected = activeOrder === "soonest" ? "soonest" : "best_match";
  orderInputs.forEach(function (input) {
    input.checked = input.value === selected;
  });
}

function normalizeQuery(value) {
  return String(value || "").trim().replace(/\s+/g, " ");
}

function search(rawQuery, options, existingRequest) {
  var query = normalizeQuery(rawQuery);
  var request = existingRequest || { q: query, label: query, order: activeOrder };
  beginOrdinaryLookup(request);
  var validationMessage = validateQuery(query);

  input.value = query;
  formFeedback.textContent = validationMessage || "";

  if (validationMessage) {
    preferences.beginSearch();
    responseView.renderInvalid(validationMessage);
    return;
  }

  if (options.updateUrl) {
    window.history.pushState(request, "", searchPathFor(request));
  }

  fetchOpportunities(request);
}

function searchLocationId(rawLocationId, displayName, options, existingRequest) {
  var locationId = normalizeQuery(rawLocationId);
  var label = normalizeQuery(displayName) || locationId;
  var request = existingRequest || { locationId: locationId, label: label, order: activeOrder };
  beginOrdinaryLookup(request);
  var validationMessage = validateLocationId(locationId);

  input.value = label;
  formFeedback.textContent = validationMessage || "";

  if (validationMessage) {
    preferences.beginSearch();
    responseView.renderInvalid(validationMessage);
    return;
  }

  if (options.updateUrl) {
    window.history.pushState(request, "", searchPathFor(request));
  }

  fetchOpportunities(request);
}

function validateQuery(query) {
  if (!query) {
    return "Enter a city or town.";
  }
  if (Array.from(query).length > 100) {
    return "Use 100 characters or fewer.";
  }
  if (CONTROL_CHARACTER_PATTERN.test(query)) {
    return "Remove unsupported control or formatting characters.";
  }
  return "";
}

function validateLocationId(locationId) {
  if (!locationId) {
    return "Choose a location.";
  }
  if (Array.from(locationId).length > 100) {
    return "Use 100 characters or fewer.";
  }
  if (CONTROL_CHARACTER_PATTERN.test(locationId)) {
    return "Remove unsupported control or formatting characters.";
  }
  return "";
}

function fetchOpportunities(request) {
  if (activeRequest) {
    activeRequest.abort();
  }

  activeRequest = new AbortController();
  var requestController = activeRequest;
  var searchRequest = searchRequestFor(request, requestController.signal);
  setSearchBusy(true);
  results.setAttribute("aria-busy", "true");
  preferences.beginSearch();
  responseView.renderLoading(request.label);

  fetch(searchRequest.path, searchRequest.options)
    .then(function (response) {
      return response.json()
        .catch(function () {
          return fallbackPayload(response.status);
        })
        .then(function (payload) {
          var recoveryLocationId = planningRecoveryLocationId(payload, response.status);
          preferences.renderResponse(payload);
          settleOrderControl(payload, request,
            responseView.renderResponse(payload || fallbackPayload(response.status), request, response.status));
          if (recoveryLocationId) {
            planningView.renderRecovery(function () {
              startPlanning(recoveryLocationId);
            });
          }
        });
    })
    .catch(function (error) {
      if (error.name !== "AbortError") {
        preferences.renderResponse(null);
        settleOrderControl(null, request);
        responseView.renderResponse({
          status: "temporarily_unavailable",
          message: "The lookup could not be reached. Try again shortly."
        }, request, 503);
      }
    })
    .finally(function () {
      if (activeRequest === requestController) {
        results.setAttribute("aria-busy", "false");
        setSearchBusy(false);
        activeRequest = null;
      }
    });
}

function startPlanning(locationId) {
  if (activeRequest) {
    return;
  }

  activeRequest = new AbortController();
  var requestController = activeRequest;
  var planningRequest = preferences.planningRequestFor(locationId, requestController.signal);
  orderControl.hidden = true;
  workspaceTitle.textContent = "Next matching Moon date";
  workspaceTitle.setAttribute("tabindex", "-1");
  ordinaryWorkspaceMeta.hidden = true;
  ordinaryWorkspaceMeta.style.display = "none";
  planningWeatherNotice.hidden = false;
  planningWeatherNotice.style.removeProperty("display");
  preferences.beginSearch();
  results.setAttribute("aria-busy", "true");
  planningView.renderLoading();
  workspaceTitle.focus();

  fetch(planningRequest.path, planningRequest.options)
    .then(function (response) {
      return response.json()
        .catch(function () {
          return null;
        })
        .then(function (payload) {
          if (activeRequest === requestController) {
            planningView.renderResponse(payload, response.status, locationId);
          }
        });
    })
    .catch(function (error) {
      if (error.name !== "AbortError" && activeRequest === requestController) {
        planningView.renderNetworkError();
      }
    })
    .finally(function () {
      if (activeRequest === requestController) {
        results.setAttribute("aria-busy", "false");
        activeRequest = null;
      }
    });
}

function beginOrdinaryLookup(request) {
  if (activeRequest) {
    activeRequest.abort();
  }
  orderControl.hidden = !sameLookup(request, resolvedRequest);
  workspaceTitle.textContent = ordinaryWorkspaceTitle;
  workspaceTitle.removeAttribute("tabindex");
  ordinaryWorkspaceMeta.hidden = false;
  ordinaryWorkspaceMeta.style.removeProperty("display");
  planningWeatherNotice.hidden = true;
  planningWeatherNotice.style.display = "none";
  results.setAttribute("aria-busy", "false");
  setSearchBusy(false);
}

function planningRecoveryLocationId(payload, statusCode) {
  var location = payload && payload.location;
  var locationId = location && location.id;
  return payload && statusCode === 200 && payload.status === "ok"
      && Array.isArray(payload.opportunities) && payload.opportunities.length === 0
      && location && location.kind === "real_location"
      && typeof location.displayName === "string" && Boolean(location.displayName.trim())
      && typeof location.timezone === "string" && Boolean(location.timezone.trim())
      && Number.isSafeInteger(payload.forecastHorizonDays) && payload.forecastHorizonDays > 0
      && Number.isSafeInteger(payload.candidateWindowsEvaluated)
      && payload.candidateWindowsEvaluated >= 0
      && typeof locationId === "string" && locationId === normalizeQuery(locationId)
      && !validateLocationId(locationId)
    ? locationId
    : null;
}

function searchRequestFor(request, signal) {
  var preferenceRequest = preferences.requestFor(request, signal);
  if (preferenceRequest) {
    return preferenceRequest;
  }
  return {
    path: apiPathFor(request),
    options: {
      headers: { "Accept": "application/json" },
      signal: signal
    }
  };
}

function settleOrderControl(payload, request, hasMultiplePasses) {
  var resolved = payload && payload.status === "ok" && payload.location?.kind === "real_location";
  orderControl.hidden = !resolved || !hasMultiplePasses;
  resolvedRequest = resolved && hasMultiplePasses ? request : null;
  if (resolved && request.order === "best_match") {
    activeOrder = undefined;
    window.history.replaceState({}, "", sharePathFor(request));
  }
}

function sameLookup(left, right) {
  if (!left || !right) return false;
  return left.locationId
    ? left.locationId === right.locationId
    : !right.locationId && left.q === right.q;
}

function syncSearchDisclosures(mediaQuery) {
  recentSearches.open = !mediaQuery.matches;
  cameraDetails.open = !mediaQuery.matches;
  preferenceDetails.open = !mediaQuery.matches;
}

function renderRecent() {
  var recent = readRecent();
  clearRecent.disabled = recent.length === 0;

  if (recent.length === 0) {
    recentList.replaceChildren(element("p", { className: "recent-empty" }, "No recent searches in this browser."));
    return;
  }

  recentList.replaceChildren.apply(recentList, recent.map(function (entry) {
    var button = element("button", { type: "button" },
      element("span", { className: "candidate-name" }, entry.displayName),
      element("span", { className: "candidate-meta" }, entry.timezone || entry.slug || "")
    );
    button.addEventListener("click", function () {
      if (entry.slug) {
        searchLocationId(entry.slug, entry.displayName, { updateUrl: true });
      } else {
        search(entry.displayName, { updateUrl: true });
      }
    });
    return button;
  }));
}

function copyText(value) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    return navigator.clipboard.writeText(value);
  }
  window.prompt("Copy share link", value);
  return Promise.resolve();
}

function setSearchBusy(isBusy) {
  if (submitButton) {
    submitButton.disabled = isBusy;
    submitButton.textContent = isBusy ? "Finding" : "Find";
  }
}
