(function () {
  "use strict";

  var RECENT_KEY = "moonService.recentSearches.v1";
  var MAX_RECENT = 5;
  var CONTROL_CHARACTER_PATTERN = /[\u0000-\u001F\u007F-\u009F\u061C\u200E\u200F\u202A-\u202E\u2066-\u2069]/u;

  var form = document.getElementById("search-form");
  var input = document.getElementById("location-input");
  var formFeedback = document.getElementById("form-feedback");
  var results = document.getElementById("results");
  var recentList = document.getElementById("recent-list");
  var clearRecent = document.getElementById("clear-recent");
  var activeRequest = null;

  form.addEventListener("submit", function (event) {
    event.preventDefault();
    search(input.value, { updateUrl: true });
  });

  clearRecent.addEventListener("click", function () {
    writeRecent([]);
    renderRecent();
  });

  window.addEventListener("popstate", function () {
    runLookup(lookupFromUrl(), { updateUrl: false });
  });

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
      renderIntro();
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
      renderInvalid(validationMessage);
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
      renderInvalid(validationMessage);
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
    results.setAttribute("aria-busy", "true");
    renderLoading(request.label);

    fetch(apiPathFor(request), {
      headers: {
        "Accept": "application/json"
      },
      signal: activeRequest.signal
    })
      .then(function (response) {
        return response.json()
          .catch(function () {
            return fallbackPayload(response.status);
          })
          .then(function (payload) {
            renderResponse(payload || fallbackPayload(response.status), request, response.status);
          });
      })
      .catch(function (error) {
        if (error.name !== "AbortError") {
          renderResponse({
            status: "temporarily_unavailable",
            message: "The lookup could not be reached. Try again shortly."
          }, request, 503);
        }
      })
      .finally(function () {
        results.setAttribute("aria-busy", "false");
        activeRequest = null;
      });
  }

  function apiPathFor(request) {
    if (request.locationId) {
      return "/api/opportunities?locationId=" + encodeURIComponent(request.locationId);
    }
    return "/api/opportunities?q=" + encodeURIComponent(request.q);
  }

  function fallbackPayload(statusCode) {
    if (statusCode === 429) {
      return {
        status: "rate_limited",
        message: "Too many requests. Please try again shortly."
      };
    }
    if (statusCode === 400) {
      return {
        status: "invalid_request",
        message: "The location query could not be used."
      };
    }
    return {
      status: "temporarily_unavailable",
      message: "The lookup is temporarily unavailable."
    };
  }

  function renderResponse(payload, request, statusCode) {
    switch (payload.status) {
      case "ok":
        renderOk(payload, request);
        break;
      case "ambiguous_location":
        renderAmbiguous(payload, request.label);
        break;
      case "location_not_found":
        renderStatus("No place found", payload.message || "Try a city or town, optionally with a country.", "warning", payload.suggestions);
        break;
      case "invalid_request":
        renderStatus("Check the location", payload.message || "Enter a city or town.", "error", errorsToSuggestions(payload));
        break;
      case "temporarily_unavailable":
        renderStatus("Lookup temporarily unavailable", payload.message || "Try again shortly.", "warning");
        break;
      case "rate_limited":
        renderStatus("Too many requests", payload.message || "Please wait before trying again.", "warning", retrySuggestion(payload));
        break;
      default:
        renderStatus("Unexpected response", "The backend returned a response this page does not understand.", statusCode >= 500 ? "warning" : "error");
    }
  }

  function renderIntro() {
    replaceResults(
      element("div", { className: "empty-state" },
        element("h2", {}, "Search a city or town"),
        element("p", {}, "Results will show ranked Moon windows with light, weather, score, and caveat details."))
    );
  }

  function renderLoading(query) {
    replaceResults(
      element("div", { className: "empty-state" },
        element("h2", {}, "Looking up " + query),
        element("p", {}, "Resolving the location, checking Moon windows, and reading the forecast."))
    );
  }

  function renderInvalid(message) {
    renderStatus("Check the location", message, "error");
  }

  function renderOk(payload, request) {
    var location = payload.location || {};
    var timezone = location.timezone || "UTC";
    var opportunities = Array.isArray(payload.opportunities) ? payload.opportunities : [];
    var children = [
      resultSummary(payload, request, opportunities.length)
    ];

    if (location.kind === "real_location" && location.displayName) {
      saveRecent(location, request);
      input.value = location.displayName;
    }

    if (opportunities.length === 0) {
      children.push(emptyOpportunities(payload));
    } else {
      children.push(element("div", { className: "opportunity-list" },
        opportunities.map(function (opportunity, index) {
          return opportunityCard(opportunity, index, timezone);
        })
      ));
    }

    if (Array.isArray(payload.messages) && payload.messages.length > 0) {
      children.push(messagesList(payload.messages));
    }

    if (Array.isArray(payload.rejected) && payload.rejected.length > 0) {
      children.push(rejectedDetails(payload.rejected, timezone));
    }

    replaceResults(children);
  }

  function resultSummary(payload, request, opportunityCount) {
    var location = payload.location || {};
    var sharePath = sharePathFor(request);
    var shareUrl = window.location.origin + sharePath;
    var forecastText = payload.forecastHorizonDays ? payload.forecastHorizonDays + "-day forecast" : "Forecast window";
    var evaluatedText = Number.isFinite(payload.candidateWindowsEvaluated)
      ? payload.candidateWindowsEvaluated + " windows evaluated"
      : "Ranked opportunities";

    return element("section", { className: "result-panel", ariaLabelledby: "result-title" },
      element("div", { className: "result-header" },
        element("div", {},
          element("h2", { id: "result-title" }, location.displayName || "Resolved location"),
          element("p", {}, opportunityCount === 1 ? "1 ranked Moon opportunity" : opportunityCount + " ranked Moon opportunities"),
          element("div", { className: "result-meta" },
            element("span", { className: "pill" }, forecastText),
            element("span", { className: "pill" }, evaluatedText),
            element("span", { className: "pill" }, location.timezone || "Timezone unavailable"))
        ),
        element("div", { className: "share-tools" },
          element("button", { type: "button", className: "copy-button", "data-share-url": shareUrl }, "Copy link"),
          element("a", { href: shareUrl }, sharePath))
      )
    );
  }

  function sharePathFor(request) {
    if (request.locationId) {
      return "/search?locationId=" + encodeURIComponent(request.locationId);
    }
    return "/search?q=" + encodeURIComponent(request.q);
  }

  function emptyOpportunities(payload) {
    var reason = payload.emptyReason && payload.emptyReason.text
      ? payload.emptyReason.text
      : "No useful Moon window passed the current scoring threshold in this forecast period.";
    return element("section", { className: "status-panel warning" },
      element("h2", {}, "No ranked windows"),
      element("p", {}, reason));
  }

  function opportunityCard(opportunity, index, timezone) {
    var moon = opportunity.moon || {};
    var sun = opportunity.sun || {};
    var weather = opportunity.weather || {};
    var exposureBalance = opportunity.exposureBalance || {};
    var components = opportunity.components || {};

    return element("article", { className: "opportunity-card" },
      element("header", {},
        element("div", {},
          element("h3", {}, "Window " + (index + 1) + ": " + formatWindow(opportunity, timezone)),
          element("p", { className: "reason" }, opportunity.reason || "Ranked Moon opportunity.")),
        element("span", { className: "pill score" }, scoreText(opportunity.score))
      ),
      element("dl", { className: "fact-grid" },
        fact("Suggested", formatDateTime(opportunity.suggestedAt, timezone)),
        fact("Moon", degrees(moon.altitudeDegrees) + " alt, " + degrees(moon.azimuthDegrees) + " az"),
        fact("Illumination", percent(moon.illuminationPercent)),
        fact("Light", readableToken(sun.lightBucket) + " at " + degrees(sun.altitudeDegrees)),
        fact("Weather", weather.summary || readableToken(weather.segmentKind) || "Forecast unavailable"),
        fact("Cloud", cloudText(weather)),
        fact("Precip", precipitationText(weather)),
        fact("Visibility", visibilityText(weather))
      ),
      exposureBalance.text
        ? element("p", { className: "exposure" }, exposureBalance.label ? readableToken(exposureBalance.label) + ": " + exposureBalance.text : exposureBalance.text)
        : null,
      scoreDetails(components)
    );
  }

  function fact(label, value) {
    return element("div", {},
      element("dt", {}, label),
      element("dd", {}, value || "Unavailable"));
  }

  function scoreDetails(components) {
    var entries = [
      ["Moon altitude", components.moonAltitudeFit],
      ["Sun light", components.sunLightFit],
      ["Moon phase", components.moonIlluminationFit],
      ["Weather", components.weatherFit],
      ["Confidence", components.forecastConfidence]
    ].filter(function (entry) {
      return Number.isFinite(entry[1]);
    });

    if (entries.length === 0) {
      return null;
    }

    return element("details", {},
      element("summary", {}, "Score details"),
      element("dl", { className: "detail-grid" },
        entries.map(function (entry) {
          return fact(entry[0], String(entry[1]));
        })
      )
    );
  }

  function renderAmbiguous(payload, query) {
    var candidates = Array.isArray(payload.candidates) ? payload.candidates : [];
    replaceResults(
      element("section", { className: "status-panel" },
        element("h2", {}, "Choose a location"),
        element("p", {}, "Several places matched " + query + ". Pick one to search that place."),
        element("div", { className: "candidate-list" },
          candidates.map(function (candidate) {
            var button = element("button", { type: "button" },
              element("span", { className: "candidate-name" }, candidate.displayName || candidate.id || "Unnamed location"),
              element("span", { className: "candidate-meta" }, candidateMeta(candidate))
            );
            button.addEventListener("click", function () {
              searchLocationId(candidate.id, candidate.displayName || candidate.id || query, { updateUrl: true });
            });
            return button;
          })
        )
      )
    );
  }

  function renderStatus(title, message, tone, suggestions) {
    var items = Array.isArray(suggestions) ? suggestions : [];
    replaceResults(
      element("section", { className: "status-panel " + (tone || "") },
        element("h2", {}, title),
        element("p", {}, message),
        items.length > 0
          ? element("ul", { className: "suggestions" }, items.map(function (item) {
            return element("li", {}, item);
          }))
          : null)
    );
  }

  function errorsToSuggestions(payload) {
    if (!Array.isArray(payload.errors)) {
      return [];
    }
    return payload.errors.map(function (error) {
      return error.text || error.message || error.code;
    }).filter(Boolean);
  }

  function retrySuggestion(payload) {
    if (payload.retryAfterSeconds) {
      return ["Try again in about " + payload.retryAfterSeconds + " seconds."];
    }
    return [];
  }

  function messagesList(messages) {
    return element("ul", { className: "messages" },
      messages.map(function (message) {
        return element("li", {}, message.text || message.code || "Additional lookup note.");
      })
    );
  }

  function rejectedDetails(rejected, timezone) {
    return element("details", {},
      element("summary", {}, "Rejected windows"),
      element("ul", { className: "messages" },
        rejected.slice(0, 5).map(function (window) {
          return element("li", {},
            formatDateTime(window.startsAt, timezone) + " - " + formatDateTime(window.endsAt, timezone) + ": " + (window.reason || "Rejected by scoring filters."))
        })
      )
    );
  }

  function saveRecent(location, request) {
    var entry = {
      displayName: location.displayName,
      slug: acceptedLocationId(location, request),
      timezone: location.timezone
    };
    var current = readRecent().filter(function (item) {
      return item.slug !== entry.slug && item.displayName !== entry.displayName;
    });
    current.unshift(entry);
    writeRecent(current.slice(0, MAX_RECENT));
    renderRecent();
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

  function acceptedLocationId(location, request) {
    if (request.locationId) {
      return request.locationId;
    }
    if (location.id && location.id.indexOf("openmeteo:") === 0) {
      return "openmeteo-" + location.id.substring("openmeteo:".length);
    }
    return location.id || "";
  }

  function readRecent() {
    try {
      var raw = window.localStorage.getItem(RECENT_KEY);
      var parsed = raw ? JSON.parse(raw) : [];
      if (!Array.isArray(parsed)) {
        return [];
      }
      return parsed
        .filter(function (entry) {
          return entry && typeof entry.displayName === "string" && entry.displayName.trim();
        })
        .slice(0, MAX_RECENT);
    } catch (error) {
      return [];
    }
  }

  function writeRecent(entries) {
    try {
      window.localStorage.setItem(RECENT_KEY, JSON.stringify(entries));
    } catch (error) {
      // Browser storage is optional for this page.
    }
  }

  document.addEventListener("click", function (event) {
    if (!event.target.matches("[data-share-url]")) {
      return;
    }

    var button = event.target;
    var shareUrl = button.getAttribute("data-share-url");
    copyText(shareUrl).then(function () {
      var original = button.textContent;
      button.textContent = "Copied";
      window.setTimeout(function () {
        button.textContent = original;
      }, 1500);
    });
  });

  function copyText(value) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      return navigator.clipboard.writeText(value);
    }
    window.prompt("Copy share link", value);
    return Promise.resolve();
  }

  function replaceResults(children) {
    if (!Array.isArray(children)) {
      children = [children];
    }
    results.replaceChildren.apply(results, children.filter(Boolean));
  }

  function element(tagName, attributes) {
    var node = document.createElement(tagName);
    var children = Array.prototype.slice.call(arguments, 2);
    attributes = attributes || {};

    Object.keys(attributes).forEach(function (name) {
      var value = attributes[name];
      if (value === null || value === undefined) {
        return;
      }
      if (name === "className") {
        node.className = value;
      } else if (name === "textContent") {
        node.textContent = value;
      } else if (name === "htmlFor") {
        node.htmlFor = value;
      } else if (name === "ariaLabelledby") {
        node.setAttribute("aria-labelledby", value);
      } else {
        node.setAttribute(name, value);
      }
    });

    appendChildren(node, children);
    return node;
  }

  function appendChildren(node, children) {
    children.flat().forEach(function (child) {
      if (child === null || child === undefined) {
        return;
      }
      if (typeof child === "string" || typeof child === "number") {
        node.appendChild(document.createTextNode(String(child)));
      } else {
        node.appendChild(child);
      }
    });
  }

  function formatWindow(opportunity, timezone) {
    return formatDateTime(opportunity.startsAt, timezone) + " to " + formatTime(opportunity.endsAt, timezone);
  }

  function formatDateTime(value, timezone) {
    if (!value) {
      return "Unavailable";
    }
    try {
      return new Intl.DateTimeFormat(undefined, {
        dateStyle: "medium",
        timeStyle: "short",
        timeZone: timezone || "UTC"
      }).format(new Date(value));
    } catch (error) {
      return value;
    }
  }

  function formatTime(value, timezone) {
    if (!value) {
      return "Unavailable";
    }
    try {
      return new Intl.DateTimeFormat(undefined, {
        timeStyle: "short",
        timeZone: timezone || "UTC"
      }).format(new Date(value));
    } catch (error) {
      return value;
    }
  }

  function degrees(value) {
    return Number.isFinite(value) ? value.toFixed(1) + " deg" : "unavailable";
  }

  function percent(value) {
    return Number.isFinite(value) ? Math.round(value) + "%" : "unavailable";
  }

  function scoreText(value) {
    return Number.isFinite(value) ? value + "/100" : "Score";
  }

  function readableToken(value) {
    return String(value || "").replace(/_/g, " ");
  }

  function cloudText(weather) {
    if (Number.isFinite(weather.cloudCoverMeanPercent) && Number.isFinite(weather.cloudCoverMaxPercent)) {
      return Math.round(weather.cloudCoverMeanPercent) + "% mean, " + Math.round(weather.cloudCoverMaxPercent) + "% max";
    }
    if (Number.isFinite(weather.cloudCoverMaxPercent)) {
      return Math.round(weather.cloudCoverMaxPercent) + "% max";
    }
    return "unavailable";
  }

  function precipitationText(weather) {
    var parts = [];
    if (Number.isFinite(weather.precipitationProbabilityMaxPercent)) {
      parts.push(Math.round(weather.precipitationProbabilityMaxPercent) + "% risk");
    }
    if (Number.isFinite(weather.precipitationMm)) {
      parts.push(weather.precipitationMm.toFixed(1) + " mm");
    }
    return parts.length > 0 ? parts.join(", ") : "unavailable";
  }

  function visibilityText(weather) {
    if (!Number.isFinite(weather.visibilityMinMeters)) {
      return "unavailable";
    }
    return (weather.visibilityMinMeters / 1000).toFixed(1) + " km";
  }

  function candidateMeta(candidate) {
    var parts = [];
    if (candidate.kind === "fictional_location") {
      parts.push("fictional");
    } else if (candidate.kind) {
      parts.push(candidate.kind.replace(/_/g, " "));
    }
    if (candidate.countryCode) {
      parts.push(candidate.countryCode);
    }
    if (candidate.timezone) {
      parts.push(candidate.timezone);
    }
    return parts.join(" | ");
  }
}());
