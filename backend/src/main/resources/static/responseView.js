import { sharePathFor } from "./api.js";
import { element } from "./dom.js";
import { candidateMeta, formatDateTime } from "./format.js";
import { moonPassCard } from "./opportunityCard.js";
import { fact } from "./terms.js";

export function createResponseView(results, callbacks) {
  callbacks = callbacks || {};

  return {
    renderIntro: renderIntro,
    renderLoading: renderLoading,
    renderInvalid: renderInvalid,
    renderResponse: renderResponse
  };

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
      element("section", { className: "state-panel intro-state" },
        element("div", { className: "state-header" },
          element("p", { className: "eyebrow" }, "Ready"),
          element("h3", {}, "Search a city or town"),
          element("p", {}, "Ranked windows will appear here with Moon position, ambient light, weather, and caveats.")),
        element("dl", { className: "intro-grid" },
          fact("Location", "City or town"),
          fact("Storage", "Browser recent list only"),
          fact("Output", "Shareable result page")))
    );
  }

  function renderLoading(query) {
    replaceResults(
      element("section", { className: "state-panel loading-state" },
        element("div", { className: "state-header" },
          element("p", { className: "eyebrow" }, "Working"),
          element("h3", {}, "Looking up " + query),
          element("p", {}, "Resolving the location, checking Moon windows, and reading the forecast.")),
        element("div", { className: "loading-bar", ariaLabel: "Loading" },
          element("span", {})))
    );
  }

  function renderInvalid(message) {
    renderStatus("Check the location", message, "error");
  }

  function renderOk(payload, request) {
    var location = payload.location || {};
    var timezone = location.timezone || "UTC";
    var countryCode = location.countryCode || "";
    var opportunities = Array.isArray(payload.opportunities) ? payload.opportunities : [];
    var groups = opportunityGroups(opportunities);
    var children = [
      resultSummary(payload, request, groups.length)
    ];

    if (location.kind === "real_location" && location.displayName && callbacks.onResolvedLocation) {
      callbacks.onResolvedLocation(location, request);
    }

    if (opportunities.length === 0) {
      children.push(emptyOpportunities(payload));
    } else {
      var chartContext = {
        mobileReferenceDurationMs: maxOpportunityDurationMs(opportunities)
      };
      children.push(element("div", { className: "opportunity-list" },
        groups.map(function (group, index) {
          return opportunityGroup(group, index, timezone, countryCode, chartContext);
        })
      ));
    }

    if (Array.isArray(payload.messages) && payload.messages.length > 0) {
      children.push(messagesList(payload.messages));
    }

    if (Array.isArray(payload.rejected) && payload.rejected.length > 0) {
      children.push(rejectedDetails(payload.rejected, timezone, countryCode));
    }

    replaceResults(children);
  }

  function opportunityGroups(opportunities) {
    var groupsByPass = new Map();
    var groups = [];
    opportunities.forEach(function (opportunity, index) {
      var pass = opportunity.moonPass || {};
      var key = pass.id || opportunity.id || String(index);
      var group = groupsByPass.get(key);
      if (!group) {
        group = {
          pass: pass,
          entries: []
        };
        groupsByPass.set(key, group);
        groups.push(group);
      }
      group.entries.push({
        opportunity: opportunity,
        index: index
      });
    });
    return groups;
  }

  function opportunityGroup(group, index, timezone, countryCode, chartContext) {
    return moonPassCard(group.pass, group.entries, index, timezone, countryCode, chartContext);
  }

  function maxOpportunityDurationMs(opportunities) {
    return opportunities.reduce(function (maxDuration, opportunity) {
      var started = new Date(opportunity.startsAt).getTime();
      var ended = new Date(opportunity.endsAt).getTime();
      if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
        return maxDuration;
      }
      return Math.max(maxDuration, ended - started);
    }, 0);
  }

  function resultSummary(payload, request, passCount) {
    var location = payload.location || {};
    var sharePath = sharePathFor(request);
    var shareUrl = window.location.origin + sharePath;
    var forecastText = payload.forecastHorizonDays ? payload.forecastHorizonDays + "-day forecast" : "Forecast window";
    var evaluatedText = Number.isFinite(payload.candidateWindowsEvaluated)
      ? payload.candidateWindowsEvaluated + " windows evaluated"
      : "Ranked opportunities";

    return element("section", { className: "result-panel result-summary", ariaLabelledby: "result-title" },
      element("div", { className: "summary-topline" },
        element("div", {},
          element("p", { className: "eyebrow" }, "Resolved location"),
          element("h3", { id: "result-title" }, location.displayName || "Resolved location"),
          element("p", { className: "summary-count" }, passCount === 1 ? "1 ranked Moon pass" : passCount + " ranked Moon passes")),
        element("div", { className: "share-tools" },
          element("button", { type: "button", className: "copy-button", "data-share-url": shareUrl }, "Copy link"),
          element("a", { href: sharePath }, "Open share link"))
      ),
      element("dl", { className: "summary-grid" },
        fact("Forecast", forecastText),
        fact("Evaluated", evaluatedText),
        fact("Timezone", location.timezone || "Unavailable"),
        fact("Lookup", request.locationId ? "Selected location" : "Search query"))
    );
  }

  function emptyOpportunities(payload) {
    var reason = payload.emptyReason && payload.emptyReason.text
      ? payload.emptyReason.text
      : "No useful Moon window passed the current scoring threshold in this forecast period.";
    return element("section", { className: "status-panel warning" },
      element("p", {
        className: "eyebrow tooltip",
        title: "No candidate window met the current scoring threshold.",
        "data-tooltip": "No candidate window met the current scoring threshold."
      }, "No match"),
      element("h3", {}, "No ranked windows"),
      element("p", {}, reason));
  }

  function renderAmbiguous(payload, query) {
    var candidates = Array.isArray(payload.candidates) ? payload.candidates : [];
    replaceResults(
      element("section", { className: "status-panel action-state" },
        element("p", { className: "eyebrow" }, "Ambiguous match"),
        element("h3", {}, "Choose a location"),
        element("p", {}, "Several places matched " + query + ". Pick one to search that place."),
        element("div", { className: "candidate-list" },
          candidates.map(function (candidate) {
            var button = element("button", { type: "button" },
              element("span", { className: "candidate-name" }, candidate.displayName || candidate.id || "Unnamed location"),
              element("span", { className: "candidate-meta" }, candidateMeta(candidate))
            );
            button.addEventListener("click", function () {
              if (callbacks.onSelectLocation) {
                callbacks.onSelectLocation(candidate, query);
              }
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
        element("p", { className: "eyebrow" }, tone === "error" ? "Needs attention" : "Status"),
        element("h3", {}, title),
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
    return element("section", { className: "message-panel" },
      element("h3", {}, "Lookup notes"),
      element("ul", { className: "messages" },
        messages.map(function (message) {
          return element("li", {}, message.text || message.code || "Additional lookup note.");
        }))
    );
  }

  function rejectedDetails(rejected, timezone, countryCode) {
    return element("details", { className: "rejected-details" },
      element("summary", {}, "Rejected windows"),
      element("ul", { className: "messages" },
        rejected.slice(0, 5).map(function (window) {
          return element("li", {},
            formatDateTime(window.startsAt, timezone, countryCode)
              + " - "
              + formatDateTime(window.endsAt, timezone, countryCode)
              + ": "
              + (window.reason || "Rejected by scoring filters."));
        })
      )
    );
  }

  function replaceResults(children) {
    if (!Array.isArray(children)) {
      children = [children];
    }
    results.replaceChildren.apply(results, children.filter(Boolean));
  }
}
