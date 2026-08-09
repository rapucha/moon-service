import { compassDirection } from "./angularPreferenceControls.js";
import { element } from "./dom.js";
import {
  degrees,
  formatDateTime,
  formatTime,
  readableToken
} from "./format.js";
import { currentMoonPathPanel } from "./moonPathView.js";
import { currentSnapshotSkyDome } from "./skyDomeView.js";

var CARD_ID = "current-moon-card";

export function createCurrentMoonView(payload, timezone, countryCode) {
  var location = payload?.location || {};
  var currentMoon = payload?.currentMoon;
  if (location.kind !== "real_location" || typeof payload?.asOf !== "string"
      || !objectValue(currentMoon) || !objectValue(currentMoon.moon)
      || !objectValue(currentMoon.sun)) {
    return null;
  }

  var snapshotTime = localTimeText(payload.asOf, timezone, countryCode);
  var dome = currentSnapshotSkyDome(
    payload.asOf, currentMoon.moon, currentMoon.sun, snapshotTime);
  return currentMoonCard(payload.asOf, currentMoon, timezone, countryCode, dome);
}

function currentMoonCard(asOf, currentMoon, timezone, countryCode, dome) {
  var activePass = currentMoon.horizonState === "above_or_on_horizon"
    && objectValue(currentMoon.activePass)
    ? currentMoon.activePass
    : null;
  var chartContext = activePass
    ? { mobileReferenceDurationMs: intervalDuration(activePass) }
    : {};
  var summary = currentMoonSummary(
    asOf, currentMoon, activePass, timezone, countryCode);

  return element("details", {
    id: CARD_ID,
    className: "result-panel current-moon-card"
  },
    element("summary", { id: "current-moon-title" },
      element("span", { className: "sky-picture-title" }, "Moon now"),
      " — ",
      element("span", { className: "sky-picture-description" }, collapsedPosition(currentMoon))),
    activePass
      ? currentMoonPathPanel(currentMoon, timezone, countryCode, chartContext, dome, summary)
      : currentSkyDomePicture(dome, summary)
  );
}

function currentMoonSummary(asOf, currentMoon, activePass, timezone, countryCode) {
  var moon = currentMoon.moon;
  var direction = compassDirection(moon.azimuthDegrees);
  var phase = readableToken(moon.phaseName);
  var position = activePass
    ? "the Moon is " + degrees(moon.altitudeDegrees) + " high"
    : "the Moon is " + degrees(Math.abs(moon.altitudeDegrees)) + " below the horizon";
  var copy = "Status for " + localDateTimeText(asOf, timezone, countryCode) + ": "
    + position + (direction ? " toward " + direction : "") + "."
    + (phase ? " Phase: " + phase + "." : "")
    + (activePass
      ? " Moonrise: " + boundaryText(activePass.startBoundary, timezone, countryCode)
        + ". Moonset: " + boundaryText(activePass.endBoundary, timezone, countryCode) + "."
      : "");
  return element("p", { className: "summary-count current-moon-summary" }, copy);
}

function boundaryText(boundary, timezone, countryCode) {
  if (boundary?.status === "found" && typeof boundary.at === "string") {
    return localDateTimeText(boundary.at, timezone, countryCode);
  }
  return boundary?.status === "not_found_within_range"
    ? "Not found within 26 hours"
    : "Unavailable";
}

function collapsedPosition(currentMoon) {
  if (currentMoon.horizonState === "below_horizon") {
    return "Below horizon";
  }
  return Math.round(currentMoon.moon.altitudeDegrees) + "° high";
}

function localDateTimeText(value, timezone, countryCode) {
  return localTimestampText(formatDateTime(value, timezone, countryCode), value, timezone);
}

function localTimeText(value, timezone, countryCode) {
  return localTimestampText(formatTime(value, timezone, countryCode), value, timezone);
}

function localTimestampText(formatted, value, timezone) {
  var offset = utcOffsetText(value, timezone);
  return formatted + " local time" + (offset ? " (" + offset + ")" : "");
}

function utcOffsetText(value, timezone) {
  if (!value || !timezone) {
    return "";
  }
  try {
    var zone = new Intl.DateTimeFormat("en-US", {
      hour: "numeric",
      timeZone: timezone,
      timeZoneName: "longOffset"
    }).formatToParts(new Date(value)).find(function (part) {
      return part.type === "timeZoneName";
    });
    var match = /^(?:GMT|UTC)(?:([+-])(\d{1,2}):?(\d{2})?)?$/.exec(zone?.value || "");
    if (!match) {
      return "";
    }
    if (!match[1]) {
      return "UTC+00:00";
    }
    return "UTC" + (match[1] === "-" ? "−" : "+")
      + match[2].padStart(2, "0") + ":" + (match[3] || "00");
  } catch (error) {
    return "";
  }
}

function currentSkyDomePicture(dome, summary) {
  if (!dome) {
    return summary;
  }
  return element("section", {
    className: "sky-picture-content current-sky-content",
    ariaLabel: "Sky now"
  },
    dome,
    summary);
}

function intervalDuration(activePass) {
  var start = new Date(activePass.representedStartsAt).getTime();
  var end = new Date(activePass.representedEndsAt).getTime();
  return Number.isFinite(start) && Number.isFinite(end) && end > start ? end - start : 0;
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
