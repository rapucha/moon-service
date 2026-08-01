import { sharePathFor } from "./api.js";
import { element } from "./dom.js";
import { candidateMeta, formatDateTime } from "./format.js";
import { moonPassCard } from "./opportunityCard.js";
import { fact } from "./terms.js";

var UTC_INSTANT_PATTERN = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;
var IMPACT_FILTERS = [
  { key: "altitudeDegrees", label: "Moon altitude" },
  { key: "azimuthDegrees", label: "Moon direction" },
  { key: "time", label: "Time & light" },
  { key: "namedPhases", label: "Moon shape" },
  { key: "brightLimbOrientationDegrees", label: "Bright-limb orientation" }
];

export function createResponseView(results, callbacks) {
  callbacks = callbacks || {};
  var cameraSetup = null;
  var current = null;

  return {
    renderIntro: renderIntro,
    renderLoading: renderLoading,
    renderInvalid: renderInvalid,
    renderResponse: renderResponse,
    refresh: refresh,
    setCameraSetup: function (next) { cameraSetup = next; }
  };

  function renderResponse(payload, request, statusCode) {
    current = { payload: payload, request: request, statusCode: statusCode };
    switch (payload.status) {
      case "ok":
        renderOk(payload, request, true);
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

  function refresh() {
    if (!current || current.payload.status !== "ok" || !cameraSetup) return;
    var groups = opportunityGroups(Array.isArray(current.payload.opportunities)
      ? current.payload.opportunities : []);
    results.querySelectorAll(".moon-pass-card").forEach(function (card, index) {
      var moon = groups[index]?.entries[0]?.opportunity?.moon;
      cameraSetup.replaceEstimate(card, moon, card.querySelector(".moon-path-panel"));
    });
  }

  function renderIntro() {
    replaceResults(
      element("section", { className: "state-panel intro-state" },
        element("div", { className: "state-header" },
          element("p", { className: "eyebrow" }, "Ready"),
          element("h3", {}, "Search a city or town"),
          element("p", {}, "Top-ranked forecast candidates will appear here with Moon position, ambient light, weather, and caveats.")),
        element("dl", { className: "intro-grid" },
          fact("Location", "City or town"),
          fact("Storage", "Recent searches and hard limits in this browser"),
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

  function renderOk(payload, request, notifyResolvedLocation) {
    var location = payload.location || {};
    var timezone = location.timezone || "UTC";
    var countryCode = location.countryCode || "";
    var opportunities = Array.isArray(payload.opportunities) ? payload.opportunities : [];
    var groups = opportunityGroups(opportunities);
    var children = [
      resultSummary(payload, request, groups.length, opportunities.length)
    ];

    if (notifyResolvedLocation && location.kind === "real_location"
        && location.displayName && callbacks.onResolvedLocation) {
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
    var card = moonPassCard(group.pass, group.entries, index, timezone, countryCode, chartContext);
    if (cameraSetup) {
      cameraSetup.replaceEstimate(
        card, group.entries[0].opportunity.moon, card.querySelector(".moon-path-panel"));
    }
    return card;
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

  function resultSummary(payload, request, passCount, candidateCount) {
    var location = payload.location || {};
    var sharePath = sharePathFor(request);
    var shareUrl = window.location.origin + sharePath;
    var passText = passCount === 1 ? "1 ranked Moon pass" : passCount + " ranked Moon passes";
    var candidateText = candidateCount === 1 ? "1 candidate window" : candidateCount + " candidate windows";

    return element("section", { className: "result-panel result-summary", ariaLabelledby: "result-title" },
      element("div", { className: "summary-topline" },
        element("div", {},
          element("p", { className: "eyebrow" }, "Resolved location"),
          element("h3", { id: "result-title" }, location.displayName || "Resolved location"),
          element("p", { className: "summary-count" }, passText + " · " + candidateText)),
        element("div", { className: "share-tools" },
          element("button", { type: "button", className: "copy-button", "data-share-url": shareUrl }, "Copy link"),
          element("a", { href: sharePath }, "Open share link"))
      ));
  }

  function emptyOpportunities(payload) {
    var reason = payload.emptyReason && payload.emptyReason.text
      ? payload.emptyReason.text
      : "No useful Moon window passed the current scoring threshold in this forecast period.";
    var horizon = payload.forecastHorizonDays
      + (payload.forecastHorizonDays === 1 ? " day" : " days");
    return element("details", { className: "status-panel warning" },
      element("summary", {},
        element("span", {
          className: "eyebrow tooltip",
          title: "No candidate window matched this search.",
          "data-tooltip": "No candidate window matched this search."
        }, "No match"),
        " — No opportunities found in the next " + horizon),
      element("p", {}, reason),
      preferenceImpactDetails(payload));
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
              + (window.reason || window.reasonCode || "Rejected by scoring filters."));
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

function preferenceImpactDetails(payload) {
  var normalized = payload.normalizedActiveFilters;
  var impact = payload.preferenceImpact;
  var location = payload.location;
  if (!objectValue(normalized) || !objectValue(impact)
      || typeof location?.timezone !== "string"
      || !Number.isInteger(impact.unfilteredOpportunityCount)
      || impact.unfilteredOpportunityCount < 0
      || !Array.isArray(impact.filters)) {
    return null;
  }
  var active = IMPACT_FILTERS.filter(function (filter) {
    return Boolean(normalized[filter.key]);
  });
  if (active.length === 0 || Object.keys(normalized).length !== active.length
      || impact.filters.length !== active.length) {
    return null;
  }
  var rows = impact.filters.map(function (item, index) {
    var filter = active[index];
    var count = item?.matchingOpportunityCount;
    var next = theoreticalMatchText(item, location);
    if (!objectValue(item) || item.filter !== filter.key
        || !Number.isInteger(count) || count < 0
        || count > impact.unfilteredOpportunityCount || !next) {
      return null;
    }
    return {
      label: filter.label,
      count: count,
      reduction: impact.unfilteredOpportunityCount - count,
      next: next
    };
  });
  if (rows.includes(null)) {
    return null;
  }
  var greatest = Math.max(0, ...rows.map(function (row) {
    return row.reduction;
  }));
  var baseline = impact.unfilteredOpportunityCount;
  return element("div", { className: "preference-impact" },
    element("p", {}, "Without preferences: " + baseline
      + (baseline === 1 ? " opportunity." : " opportunities.")),
    element("dl", { className: "detail-grid" },
      rows.map(function (row) {
        var count = row.count + (row.count === 1 ? " opportunity" : " opportunities");
        var largest = greatest > 0 && row.reduction === greatest
          ? " · Largest reduction"
          : "";
        return element("div", {},
          element("dt", {}, row.label),
          element("dd", {}, [
            element("span", {}, count + " · " + row.reduction + " fewer" + largest),
            element("br", {}),
            element("span", {}, row.next)
          ]));
      })),
    element("p", {}, "Each preference is evaluated by itself with the others off."));
}

function theoreticalMatchText(item, location) {
  if (!objectValue(item) || !Number.isInteger(item.lookAheadDays) || item.lookAheadDays <= 0) {
    return "";
  }
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
    ? new Date(value)
    : null;
  return parsed !== null && Number.isFinite(parsed.getTime())
    && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
