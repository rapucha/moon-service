import { compassDirection } from "./angularPreferenceRules.js";
import { element } from "./dom.js";
import { degrees, formatDateTime, readableToken } from "./format.js";

var UTC_INSTANT_PATTERN = /^(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d{1,9}))?Z$/;

export function createPlanningView(results) {
  return {
    renderRecovery: renderRecovery,
    renderLoading: renderLoading,
    renderResponse: renderResponse,
    renderNetworkError: renderNetworkError
  };

  function renderRecovery(onActivate) {
    var description = element("p", { id: "planning-recovery-description" },
      "Search ahead using Moon position, local time, and ambient light. Weather is not considered.");
    var button = element("button",
      { type: "button", className: "copy-button", "aria-describedby": description.id },
      "Find the next matching Moon date");
    button.addEventListener("click", function () {
      if (button.disabled) {
        return;
      }
      button.disabled = true;
      onActivate();
    });
    results.append(element("section",
      { className: "status-panel action-state planning-recovery", ariaLabel: "Planning recovery" },
      description, button));
  }

  function renderLoading() {
    results.replaceChildren(element("section", { className: "state-panel loading-state" },
      element("div", { className: "state-header" },
        element("p", { className: "eyebrow" }, "Planning"),
        element("h3", {}, "Searching for the next matching Moon date"),
        element("p", {}, "Checking Moon position, local time, and ambient light.")),
      element("div", { className: "loading-bar", ariaLabel: "Loading" },
        element("span", {}))));
  }

  function renderResponse(payload, statusCode, expectedLocationId) {
    if (statusCode !== 200 || !objectValue(payload) || payload.status !== "ok") {
      renderPlanningError(payload, statusCode);
      return;
    }
    var context = planningContext(payload, expectedLocationId);
    if (!context) {
      renderMalformed();
      return;
    }
    if (payload.nextPlanningWindow === null) {
      if (validEmptyReason(payload.emptyReason)) {
        renderEmpty(payload, context);
      } else {
        renderMalformed();
      }
      return;
    }
    var planningWindow = validPlanningWindow(payload.nextPlanningWindow, context);
    if (!planningWindow || payload.emptyReason !== undefined) {
      renderMalformed();
      return;
    }
    renderSuccess(context, planningWindow);
  }

  function renderSuccess(context, planningWindow) {
    var moon = planningWindow.moon;
    var sun = planningWindow.sun;
    var brightLimb = moon.brightLimbTiltDegrees === null
      ? "Not defined for this Moon phase."
      : degrees(moon.brightLimbTiltDegrees) + " clockwise from local zenith";
    results.replaceChildren(element("article", {
      className: "opportunity-card is-primary planning-date-card",
      ariaLabelledby: "planning-date-title"
    },
    element("div", { className: "opportunity-title" },
      element("p", { className: "eyebrow" }, "Suggested local date and time"),
      element("h3", { id: "planning-date-title" },
        formatDateTime(planningWindow.suggestedAt, context.timezone)),
      element("p", { className: "reason" }, context.location.displayName)),
    element("dl", { className: "pass-metric-grid" },
      metric("Timezone", context.timezone),
      metric("Window start", formatDateTime(planningWindow.startsAt, context.timezone)),
      metric("Window end", formatDateTime(planningWindow.endsAt, context.timezone)),
      metric("Moon altitude", degrees(moon.altitudeDegrees)),
      metric("Moon direction", degrees(moon.azimuthDegrees) + " " + compassDirection(moon.azimuthDegrees)),
      metric("Moon phase", readableToken(moon.phaseName)),
      metric("Bright limb", brightLimb),
      metric("Sun altitude", degrees(sun.altitudeDegrees)),
      metric("Ambient light", readableToken(sun.lightBucket)))));
  }

  function renderEmpty(payload, context) {
    results.replaceChildren(element("section", { className: "status-panel warning" },
      element("p", { className: "eyebrow" }, "Planning result"),
      element("h3", {}, "No matching Moon date"),
      element("p", {}, payload.emptyReason.text),
      element("dl", { className: "detail-grid" },
        metric("Planning horizon", payload.planningHorizonDays
          + (payload.planningHorizonDays === 1 ? " day" : " days")),
        metric("Planning through", formatDateTime(payload.endsAt, context.timezone)))));
  }

  function renderNetworkError() {
    renderStatus("Planning could not be reached", "The next matching Moon date could not be requested.", "warning");
  }

  function renderPlanningError(payload, statusCode) {
    var message = objectValue(payload) && typeof payload.message === "string"
      ? payload.message
      : "The planning service returned a response this page does not understand.";
    var tone = statusCode >= 500 || payload?.status === "rate_limited"
      || payload?.status === "temporarily_unavailable" ? "warning" : "error";
    renderStatus("Next matching Moon date unavailable", message, tone);
  }

  function renderMalformed() { renderStatus("Unexpected planning response",
    "The planning service returned incomplete or inconsistent result details.", "error"); }

  function renderStatus(title, message, tone) {
    results.replaceChildren(element("section", { className: "status-panel " + tone },
      element("p", { className: "eyebrow" }, "Planning status"),
      element("h3", {}, title),
      element("p", {}, message)));
  }
}

function planningContext(payload, expectedLocationId) {
  var location = payload.location;
  var horizon = payload.planningHorizonDays;
  var generatedAt = instantMilliseconds(payload.generatedAt);
  var startsAt = instantMilliseconds(payload.startsAt);
  var endsAt = instantMilliseconds(payload.endsAt);
  var ignoredFields = payload.ignoredPreferenceFields;
  var ignoredCount = payload.ignoredPreferenceFieldCount;
  var additionalIgnoredCount = payload.additionalIgnoredPreferenceFieldCount;
  if (!objectValue(location) || location.id !== expectedLocationId
      || location.kind !== "real_location"
      || typeof location.displayName !== "string" || !location.displayName
      || typeof location.timezone !== "string" || !location.timezone
      || typeof location.countryCode !== "string"
      || generatedAt === null || generatedAt !== startsAt
      || !Number.isSafeInteger(horizon) || horizon <= 0
      || startsAt === null || endsAt === null
      || endsAt - startsAt !== horizon * 24 * 60 * 60 * 1000
      || payload.appliedPreferenceVersion !== 1
      || !objectValue(payload.normalizedActiveFilters)
      || !Array.isArray(ignoredFields)
      || !ignoredFields.every(function (field) { return typeof field === "string"; })
      || !Number.isSafeInteger(ignoredCount) || ignoredCount < 0
      || !Number.isSafeInteger(additionalIgnoredCount) || additionalIgnoredCount < 0
      || ignoredCount !== ignoredFields.length + additionalIgnoredCount) {
    return null;
  }
  return {
    startsAt: startsAt,
    endsAt: endsAt,
    timezone: location.timezone,
    location: location
  };
}

function validPlanningWindow(value, context) {
  if (!objectValue(value) || value.localTimeZone !== context.timezone
      || typeof value.id !== "string" || !value.id
      || typeof value.windowKind !== "string" || !value.windowKind
      || !objectValue(value.moon) || !objectValue(value.sun)) {
    return null;
  }
  var startsAt = instantMilliseconds(value.startsAt);
  var suggestedAt = instantMilliseconds(value.suggestedAt);
  var endsAt = instantMilliseconds(value.endsAt);
  var moon = value.moon;
  var sun = value.sun;
  if (startsAt === null || suggestedAt === null || endsAt === null
      || startsAt < context.startsAt || startsAt >= endsAt
      || startsAt > suggestedAt || suggestedAt > endsAt
      || suggestedAt >= context.endsAt || endsAt > context.endsAt
      || ![moon.altitudeDegrees, moon.azimuthDegrees, moon.illuminationPercent,
        moon.phaseAngleDegrees, sun.altitudeDegrees].every(Number.isFinite)
      || (moon.brightLimbTiltDegrees !== null && !Number.isFinite(moon.brightLimbTiltDegrees))
      || typeof moon.phaseName !== "string" || !moon.phaseName
      || typeof sun.lightBucket !== "string" || !sun.lightBucket) {
    return null;
  }
  return value;
}

function validEmptyReason(value) {
  return objectValue(value) && value.code === "no_planning_date"
    && typeof value.text === "string" && Boolean(value.text.trim());
}

function instantMilliseconds(value) {
  var parsed = typeof value === "string" && UTC_INSTANT_PATTERN.test(value)
    ? new Date(value) : null;
  return parsed && Number.isFinite(parsed.getTime())
      && parsed.toISOString().slice(0, 19) === value.slice(0, 19)
    ? parsed.getTime()
    : null;
}

function metric(label, value) {
  return element("div", { className: "pass-metric" },
    element("dt", {}, label),
    element("dd", {}, value));
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
