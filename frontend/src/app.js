import { apiPathFor, fallbackPayload } from "./api.js";
import { element } from "./dom.js";
import { createOpportunityPreferences } from "./opportunityPreferences.js";
import { readRecent, saveRecentLocation, writeRecent } from "./recentSearches.js";
import { createResponseView } from "./responseView.js";

var CONTROL_CHARACTER_PATTERN = /[\u0000-\u001F\u007F-\u009F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/u;

var form = /** @type {HTMLFormElement} */ (document.getElementById("search-form"));
var input = /** @type {HTMLInputElement} */ (document.getElementById("location-input"));
var formFeedback = /** @type {HTMLElement} */ (document.getElementById("form-feedback"));
var results = /** @type {HTMLElement} */ (document.getElementById("results"));
var recentSearches = /** @type {HTMLDetailsElement} */ (document.getElementById("recent-searches"));
var recentList = /** @type {HTMLElement} */ (document.getElementById("recent-list"));
var clearRecent = /** @type {HTMLButtonElement} */ (document.getElementById("clear-recent"));
var resultProviderCredit = /** @type {HTMLElement} */ (document.getElementById("result-provider-credit"));
var resultObstructionNote = /** @type {HTMLElement} */ (document.getElementById("result-obstruction-note"));
var preferenceDetails = /** @type {HTMLDetailsElement} */ (document.getElementById("opportunity-preferences"));
var preferenceForm = /** @type {HTMLFormElement} */ (document.getElementById("preference-form"));
var preferenceResultRegion = /** @type {HTMLElement} */ (document.getElementById("preference-result-region"));
var submitButton = /** @type {HTMLButtonElement} */ (form.querySelector("button[type='submit']"));
var narrowSearchLayout = window.matchMedia("(max-width: 680px)");
var activeRequest = null;

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
  if (locationId) {
    return { locationId: locationId, label: locationId };
  }
  if (query) {
    return { q: query, label: query };
  }
  return null;
}

function runLookup(request, options) {
  if (!request) {
    input.value = "";
    updateResultNotes("");
    preferences.beginSearch();
    responseView.renderIntro();
    return;
  }
  if (request.locationId) {
    searchLocationId(request.locationId, request.label, options);
  } else {
    search(request.q, options);
  }
}

function normalizeQuery(value) {
  return String(value || "").trim().replace(/\s+/g, " ");
}

function search(rawQuery, options) {
  var query = normalizeQuery(rawQuery);
  var validationMessage = validateQuery(query);

  input.value = query;
  formFeedback.textContent = validationMessage || "";

  if (validationMessage) {
    updateResultNotes("");
    preferences.beginSearch();
    responseView.renderInvalid(validationMessage);
    return;
  }

  if (options.updateUrl) {
    window.history.pushState({ q: query }, "", "/search?q=" + encodeURIComponent(query));
  }

  fetchOpportunities({ q: query, label: query });
}

function searchLocationId(rawLocationId, displayName, options) {
  var locationId = normalizeQuery(rawLocationId);
  var label = normalizeQuery(displayName) || locationId;
  var validationMessage = validateLocationId(locationId);

  input.value = label;
  formFeedback.textContent = validationMessage || "";

  if (validationMessage) {
    updateResultNotes("");
    preferences.beginSearch();
    responseView.renderInvalid(validationMessage);
    return;
  }

  if (options.updateUrl) {
    window.history.pushState(
      { locationId: locationId },
      "",
      "/search?locationId=" + encodeURIComponent(locationId));
  }

  fetchOpportunities({ locationId: locationId, label: label });
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
  updateResultNotes("");
  preferences.beginSearch();
  responseView.renderLoading(request.label);

  fetch(searchRequest.path, searchRequest.options)
    .then(function (response) {
      return response.json()
        .catch(function () {
          return fallbackPayload(response.status);
        })
        .then(function (payload) {
          updateResultNotes(payload && payload.status);
          preferences.renderResponse(payload);
          responseView.renderResponse(payload || fallbackPayload(response.status), request, response.status);
        });
    })
    .catch(function (error) {
      if (error.name !== "AbortError") {
        updateResultNotes("");
        preferences.renderResponse(null);
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

function syncSearchDisclosures(mediaQuery) {
  recentSearches.open = !mediaQuery.matches;
  preferenceDetails.open = !mediaQuery.matches;
}

function updateResultNotes(status) {
  resultProviderCredit.hidden = status !== "ok" && status !== "ambiguous_location";
  resultObstructionNote.hidden = status !== "ok";
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
