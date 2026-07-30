import { compassDirection } from "./angularPreferenceRules.js";
import { element } from "./dom.js";
import { degrees, formatDateTime, readableToken } from "./format.js";
import { moonPathPanel } from "./moonPathView.js";

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
    var moonPass = planningWindow.moonPass;
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
      metric("Ambient light", readableToken(sun.lightBucket)),
      metric("Moon pass context", formatDateTime(moonPass.startsAt, context.timezone)
        + " to " + formatDateTime(moonPass.endsAt, context.timezone))),
    moonPathPanel(
      planningPathOpportunity(planningWindow),
      context.timezone,
      context.location.countryCode)));
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
  var generatedAt = instantKey(payload.generatedAt);
  var startsAt = instantKey(payload.startsAt);
  var endsAt = instantKey(payload.endsAt);
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
      || endsAt !== instantAfterDays(startsAt, horizon)
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
    hasAzimuthFilter: Object.prototype.hasOwnProperty.call(
      payload.normalizedActiveFilters, "azimuthDegrees"),
    timezone: location.timezone,
    location: location
  };
}

function validPlanningWindow(value, context) {
  if (!exactMembers(value, [
    "id", "windowKind", "startsAt", "suggestedAt", "endsAt",
    "localTimeZone", "moon", "sun", "moonPass"
  ]) || value.localTimeZone !== context.timezone
      || typeof value.id !== "string" || !value.id
      || typeof value.windowKind !== "string" || !value.windowKind
      || !exactMembers(value.moon, [
        "altitudeDegrees", "azimuthDegrees", "illuminationPercent",
        "phaseAngleDegrees", "brightLimbTiltDegrees",
        "northPoleTiltDegrees", "phaseName"
      ])
      || !exactMembers(value.sun, [
        "altitudeDegrees", "azimuthDegrees", "lightBucket"
      ])) {
    return null;
  }
  var startsAt = instantKey(value.startsAt);
  var suggestedAt = instantKey(value.suggestedAt);
  var endsAt = instantKey(value.endsAt);
  var moon = value.moon;
  var sun = value.sun;
  if (startsAt === null || suggestedAt === null || endsAt === null
      || startsAt < context.startsAt || startsAt >= endsAt
      || startsAt > suggestedAt || suggestedAt > endsAt
      || suggestedAt >= context.endsAt || endsAt > context.endsAt
      || ![moon.altitudeDegrees, moon.azimuthDegrees, moon.illuminationPercent,
        moon.phaseAngleDegrees, sun.altitudeDegrees, sun.azimuthDegrees].every(Number.isFinite)
      || (moon.brightLimbTiltDegrees !== null && !Number.isFinite(moon.brightLimbTiltDegrees))
      || (moon.northPoleTiltDegrees !== null && !Number.isFinite(moon.northPoleTiltDegrees))
      || typeof moon.phaseName !== "string" || !moon.phaseName
      || typeof sun.lightBucket !== "string" || !sun.lightBucket
      || !validMoonPass(value.moonPass, context, startsAt, endsAt)) {
    return null;
  }
  return value;
}

function validMoonPass(value, context, windowStartsAt, windowEndsAt) {
  var members = ["id", "startsAt", "endsAt", "path"];
  if (context.hasAzimuthFilter) {
    members.push("azimuthMatchIntervals");
  }
  if (!exactMembers(value, members)
      || typeof value.id !== "string" || !value.id
      || !exactMembers(value.path, ["start", "end", "samples"])
      || !Array.isArray(value.path.samples) || value.path.samples.length < 2) {
    return false;
  }
  var startsAt = instantKey(value.startsAt);
  var endsAt = instantKey(value.endsAt);
  var samples = value.path.samples;
  var times = samples.map(validPathPoint);
  if (startsAt === null || endsAt === null
      || startsAt < context.startsAt || startsAt >= endsAt || endsAt > context.endsAt
      || startsAt > windowStartsAt || windowEndsAt > endsAt
      || times.some(function (time) { return time === null; })
      || times[0] !== startsAt || times[times.length - 1] !== endsAt
      || samples[0].role !== "start" || samples[samples.length - 1].role !== "end"
      || samples.slice(1, -1).some(function (sample) { return sample.role !== "path"; })
      || times.some(function (time, index) { return index > 0 && time <= times[index - 1]; })
      || !samePathPoint(value.path.start, samples[0], "start")
      || !samePathPoint(value.path.end, samples[samples.length - 1], "end")) {
    return false;
  }
  return context.hasAzimuthFilter
    ? validAzimuthIntervals(value.azimuthMatchIntervals, startsAt, endsAt)
    : value.azimuthMatchIntervals === undefined;
}

function validPathPoint(value) {
  if (!exactMembers(value, [
    "at", "altitudeDegrees", "azimuthDegrees", "moonPhaseAngleDegrees",
    "brightLimbTiltDegrees", "northPoleTiltDegrees", "sunAltitudeDegrees",
    "sunAzimuthDegrees", "lightBucket", "role"
  ])
      || ![
        value.altitudeDegrees, value.azimuthDegrees, value.moonPhaseAngleDegrees,
        value.sunAltitudeDegrees, value.sunAzimuthDegrees
      ].every(Number.isFinite)
      || (value.brightLimbTiltDegrees !== null
        && !Number.isFinite(value.brightLimbTiltDegrees))
      || (value.northPoleTiltDegrees !== null
        && !Number.isFinite(value.northPoleTiltDegrees))
      || typeof value.lightBucket !== "string" || !value.lightBucket
      || !["start", "path", "end"].includes(value.role)) {
    return null;
  }
  return instantKey(value.at);
}

function samePathPoint(value, sample, role) {
  if (validPathPoint(value) === null || value.role !== role) {
    return false;
  }
  return Object.keys(value).every(function (key) {
    return value[key] === sample[key];
  });
}

function validAzimuthIntervals(value, passStartsAt, passEndsAt) {
  if (!Array.isArray(value) || value.length < 1) {
    return false;
  }
  var previousEnd = passStartsAt;
  return value.every(function (interval) {
    var startsAt = exactMembers(interval, ["startsAt", "endsAt"])
      ? instantKey(interval.startsAt) : null;
    var endsAt = startsAt === null ? null : instantKey(interval.endsAt);
    var valid = startsAt !== null && endsAt !== null
      && startsAt >= previousEnd && startsAt < endsAt && endsAt <= passEndsAt;
    previousEnd = endsAt;
    return valid;
  });
}

function planningPathOpportunity(planningWindow) {
  var moon = planningWindow.moon;
  var sun = planningWindow.sun;
  var moonPass = planningWindow.moonPass;
  var suggested = {
    at: planningWindow.suggestedAt,
    altitudeDegrees: moon.altitudeDegrees,
    azimuthDegrees: moon.azimuthDegrees,
    moonPhaseAngleDegrees: moon.phaseAngleDegrees,
    brightLimbTiltDegrees: moon.brightLimbTiltDegrees,
    northPoleTiltDegrees: moon.northPoleTiltDegrees,
    sunAltitudeDegrees: sun.altitudeDegrees,
    sunAzimuthDegrees: sun.azimuthDegrees,
    lightBucket: sun.lightBucket,
    role: "suggested",
    markerLabel: "Suggested"
  };
  var samplesByTime = new Map(moonPass.path.samples.map(function (sample) {
    return [instantKey(sample.at), sample];
  }));
  samplesByTime.set(instantKey(suggested.at), suggested);
  var samples = Array.from(samplesByTime.values()).sort(function (a, b) {
    return instantKey(a.at).localeCompare(instantKey(b.at));
  });
  return {
    moon: moon,
    moonPass: moonPass,
    moonPath: {
      description: "Altitude over time, with horizon direction on the top rail",
      chartSubject: "Moon pass",
      start: moonPass.path.start,
      suggested: suggested,
      end: moonPass.path.end,
      samples: samples
    }
  };
}

function validEmptyReason(value) {
  return objectValue(value) && value.code === "no_planning_date"
    && typeof value.text === "string" && Boolean(value.text.trim());
}

function instantKey(value) {
  var match = typeof value === "string" ? value.match(UTC_INSTANT_PATTERN) : null;
  var parsed = match ? new Date(match[1] + "Z") : null;
  return parsed && Number.isFinite(parsed.getTime())
      && parsed.toISOString().slice(0, 19) === match[1]
    ? match[1] + "." + (match[2] || "").padEnd(9, "0") + "Z"
    : null;
}

function instantAfterDays(key, days) {
  var separator = key.indexOf(".");
  var shifted = new Date(key.slice(0, separator) + "Z");
  shifted.setUTCDate(shifted.getUTCDate() + days);
  return Number.isFinite(shifted.getTime())
    ? shifted.toISOString().slice(0, 19) + key.slice(separator)
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

function exactMembers(value, members) {
  return objectValue(value)
    && Object.keys(value).length === members.length
    && members.every(function (member) {
      return Object.prototype.hasOwnProperty.call(value, member);
    });
}
