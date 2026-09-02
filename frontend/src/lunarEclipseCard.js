import { compassDirection } from "./angularPreferenceRules.js";
import { element } from "./dom.js";
import {
  degrees,
  formatDateTimeWithZone,
  formatTime,
  readableToken,
  round1
} from "./format.js";
import { drawLunarEclipse } from "./lunarEclipseRenderer.js";
import { fact } from "./terms.js";

var INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
var SUBTYPES = {
  penumbral: {
    title: "Penumbral lunar eclipse",
    description: "Earth's penumbra dims the full Moon, but the change can be subtle."
  },
  partial: {
    title: "Partial lunar eclipse",
    description: "Earth's shadow makes a dark bite in the full Moon, which can make it look like an unusual crescent."
  },
  total: {
    title: "Total lunar eclipse",
    description: "The Moon passes fully into Earth's umbra and may appear red."
  }
};
export function lunarEclipseCard(event, location) {
  var subtype = SUBTYPES[event.subtype];
  var display = event.localVisibility.displayInterval;
  var bestAt = display.suggestedAt;
  var maximumIsBest = bestAt === event.maximumAt;
  var headingId = "special-moon-event-" + safeId(event.id);
  var bestLabel = maximumIsBest ? "Maximum" : "Best visible";
  var pathSlot = element("div", { className: "special-moon-path-slot" });

  var card = /** @type {HTMLDetailsElement} */ (element("details", {
    className: "special-moon-event-card",
    ariaLabelledby: headingId
  },
  element("summary", { className: "special-moon-event-summary" },
    element("h4", { id: headingId },
      element("span", { className: "special-moon-summary-copy" },
        element("span", { className: "special-moon-event-title" }, subtype.title),
        element("span", { className: "special-moon-event-date" },
          localDate(bestAt, location)),
        element("span", { className: "special-moon-event-best" },
          bestLabel + " · " + formatTime(bestAt, location.timezone, location.countryCode)),
        element("span", { className: "special-moon-event-position" },
          moonPosition(display.moon, event.preferenceAssessment))))),
  element("div", { className: "special-moon-event-details" },
    element("p", { className: "special-moon-event-description" }, subtype.description),
    pathSlot,
    stageStrip(event, location),
    eventFacts(event, location),
    maximumVisible(event) ? null : element("p", { className: "special-moon-horizon-note" },
      "The objective maximum is below the model horizon."),
    phaseVisibility(event.phases, location),
    element("p", { className: "special-moon-weather" }, weatherText(event.weather)),
    element("p", { className: "special-moon-events-caveat" },
      "Visibility uses a level astronomical horizon and does not account for terrain, buildings, or trees."))));
  renderMoonEventPathOnFirstOpen(card, pathSlot, event, location);
  return card;
}

export function fullMoonCard(event, location) {
  var viewing = event.localViewing;
  var display = viewing?.displayInterval;
  var bestAt = display?.suggestedAt;
  var qualifier = event.qualifiers[0];
  var headingId = "special-moon-event-" + safeId(event.id);
  var bestLabel = bestAt === event.peakAt ? "Full Moon" : "Best visible";
  var unavailable = "Not visible from " + location.displayName + " during the searched dates.";
  var pathSlot = display ? element("div", { className: "special-moon-path-slot" }) : null;

  var card = /** @type {HTMLDetailsElement} */ (element("details", {
    className: "special-moon-event-card",
    ariaLabelledby: headingId
  },
  element("summary", { className: "special-moon-event-summary" },
  element("h4", { id: headingId },
    element("span", { className: "special-moon-summary-copy" },
      element("span", { className: "special-moon-event-title" }, "Supermoon"),
      element("span", { className: "special-moon-event-date" },
        localDate(bestAt || event.peakAt, location)),
      display
        ? element("span", { className: "special-moon-event-best" },
          bestLabel + " · " + formatTime(bestAt, location.timezone, location.countryCode))
        : element("span", { className: "special-moon-event-date" }, unavailable),
      display ? element("span", { className: "special-moon-event-position" },
        moonPosition(display.moon, event.preferenceAssessment)) : null))),
  element("div", { className: "special-moon-event-details" },
    element("p", { className: "special-moon-event-description" },
      "A full Moon near perigee under Moon Service definition 1. “Supermoon” is an informal term."),
    pathSlot,
    fullMoonFacts(event, qualifier, location),
    display ? element("p", { className: "special-moon-weather" }, weatherText(event.weather)) : null,
    display ? element("p", { className: "special-moon-events-caveat" },
      "Visibility uses a level astronomical horizon and does not account for terrain, buildings, or trees.") : null)));
  if (pathSlot) renderMoonEventPathOnFirstOpen(card, pathSlot, event, location);
  return card;
}

function renderMoonEventPathOnFirstOpen(card, pathSlot, event, location) {
  function renderPath() {
    if (!card.open) return;
    card.removeEventListener("toggle", renderPath);
    import("./moonEventPath.js").then(function (pathModule) {
      pathSlot.replaceWith(pathModule.moonEventPathPanel(event, location));
    }).catch(function () {
      pathSlot.replaceChildren(element("p", { className: "special-moon-path-error" },
        "Moon path could not be shown."));
    });
  }
  card.addEventListener("toggle", renderPath);
}

export function validMoonEventPath(viewing, eventKind, requiredInstants) {
  if (!objectValue(viewing) || !objectValue(viewing.displayInterval)
      || !objectValue(viewing.moonPath) || !Array.isArray(viewing.moonPath.samples)
      || viewing.moonPath.samples.length < 2
      || (eventKind !== "lunar_eclipse" && eventKind !== "full_moon")
      || !Array.isArray(requiredInstants) || requiredInstants.length === 0
      || !requiredInstants.every(validInstant)) return false;
  var display = viewing.displayInterval;
  var samples = viewing.moonPath.samples;
  var instantKeys = samples.map(function (sample) {
    return validPathSample(sample, eventKind) ? instantOrderKey(sample.at) : null;
  });
  var requiredKeys = requiredInstants.map(instantOrderKey);
  return instantKeys.every(function (key) { return key !== null; })
    && validInstant(display.startsAt) && validInstant(display.suggestedAt)
    && validInstant(display.endsAt)
    && samples.some(function (sample) { return sample.at === display.suggestedAt; })
    && instantKeys[0] <= instantOrderKey(display.startsAt)
    && instantKeys[instantKeys.length - 1] >= instantOrderKey(display.endsAt)
    && requiredInstants.every(function (requiredAt, index) {
      return samples.some(function (sample) { return sample.at === requiredAt; })
        === (instantKeys[0] <= requiredKeys[index]
          && requiredKeys[index] <= instantKeys[instantKeys.length - 1]);
    })
    && instantKeys.every(function (key, index) {
      return index === 0 || key > instantKeys[index - 1];
    });
}

function validPathSample(sample, eventKind) {
  if (!objectValue(sample) || !validInstant(sample.at)
      || !finiteBetween(sample.altitudeDegrees, -90, 90)
      || !finiteBetween(sample.azimuthDegrees, 0, 360, false)
      || !finiteBetween(sample.moonPhaseAngleDegrees, 0, 360)
      || !nullableDegrees(sample.brightLimbTiltDegrees)
      || !nullableDegrees(sample.northPoleTiltDegrees)
      || !finiteBetween(sample.sunAltitudeDegrees, -90, 90)
      || !finiteBetween(sample.sunAzimuthDegrees, 0, 360, false)
      || typeof sample.lightBucket !== "string" || sample.lightBucket.length === 0) return false;
  return eventKind === "lunar_eclipse"
    ? validShadow(sample.shadow) : sample.shadow === undefined;
}

function stageStrip(event, location) {
  return element("section", {
    className: "special-moon-stages",
    ariaLabelledby: "special-moon-stages-" + safeId(event.id)
  },
  element("h5", { id: "special-moon-stages-" + safeId(event.id) }, "Eclipse stages"),
  element("div", { className: "special-moon-stage-list" },
    event.shadowSamples.map(function (sample) {
      var best = sample.at === event.localVisibility.displayInterval.suggestedAt;
      var bestText = best && sample.at !== event.maximumAt ? "Best visible" : "";
      var label = stageLabel(event, sample.at) || bestText;
      return element("figure", {
        className: "special-moon-stage" + (best ? " is-best" : "")
      },
      eclipseCanvas(sample, label + " eclipse stage", 128, "special-moon-stage-canvas"),
      element("figcaption", {},
        element("strong", {}, label),
        element("span", {}, formatTime(sample.at, location.timezone, location.countryCode)),
        bestText && label !== bestText
          ? element("span", { className: "special-moon-stage-best" }, bestText) : null));
    })));
}

function eventFacts(event, location) {
  var display = event.localVisibility.displayInterval;
  return element("dl", { className: "special-moon-event-facts" },
    fact("Objective maximum", localTime(event.maximumAt, location)),
    fact("Visible window", intervalText(display, location)),
    fact("Best local time", localTime(display.suggestedAt, location)),
    fact("Moon position", moonPosition(display.moon, event.preferenceAssessment)),
    fact("Ambient light", readableToken(display.sun.lightBucket)),
    fact("Umbral obscuration", round1(event.umbralObscurationPercent) + "%"));
}

function fullMoonFacts(event, qualifier, location) {
  var display = event.localViewing?.displayInterval;
  var facts = [
    fact("Exact full Moon", localTime(event.peakAt, location)),
    fact("Distance at peak", distanceText(qualifier.distanceKilometersAtPeak)),
    fact("Near-perigee closeness", round1(qualifier.closeness * 100)
      + "% · Moon Service definition: at least 90%")
  ];
  if (display) {
    facts.push(
      fact("Visible window", intervalText(display, location)),
      fact("Best local time", localTime(display.suggestedAt, location)),
      fact("Moon position", moonPosition(display.moon, event.preferenceAssessment)),
      fact("Ambient light", readableToken(display.sun.lightBucket))
    );
  }
  return element("dl", { className: "special-moon-event-facts" }, facts);
}

function phaseVisibility(phases, location) {
  return element("section", { className: "special-moon-phases" },
    element("h5", {}, "Visibility by phase"),
    element("ul", { className: "special-moon-phase-list" }, phases.map(function (phase) {
      return element("li", {},
        element("strong", {}, readableToken(phase.kind)),
        element("span", {}, readableToken(phase.localVisibility.status)),
        phase.localVisibility.intervals.length === 0
          ? element("span", { className: "special-moon-phase-empty" }, "No visible interval")
          : element("ul", {}, phase.localVisibility.intervals.map(function (interval) {
            return element("li", {}, intervalText(interval, location));
          })));
    })));
}

function eclipseCanvas(sample, label, size, className) {
  var orientation = sample.moon.northPoleTiltDegrees === null
    ? ". Moon surface shown north-up because orientation is unavailable."
    : ".";
  var canvas = /** @type {HTMLCanvasElement} */ (element("canvas", {
    className: className,
    width: size,
    height: size,
    role: "img",
    ariaLabel: label + orientation
  }, label));
  drawLunarEclipse(canvas, sample);
  return canvas;
}

function stageLabel(event, instant) {
  var labels = [];
  event.phases.forEach(function (phase) {
    if (phase.startsAt === instant) labels.push(readableToken(phase.kind) + " begins");
    if (phase.endsAt === instant) labels.push(readableToken(phase.kind) + " ends");
  });
  if (event.maximumAt === instant) labels.push("Maximum");
  return labels.join(" · ");
}

function maximumVisible(event) {
  return event.localVisibility.intervals.some(function (interval) {
    return contains(interval, event.maximumAt);
  });
}

function weatherText(weather) {
  if (weather.status === "available") {
    return "Forecast at the best time: " + weather.summary.replace(/[.\s]+$/, "") + ".";
  }
  return weather.status === "outside_forecast_horizon"
    ? "Weather forecast is not available yet."
    : "Weather forecast is temporarily unavailable.";
}

function localDate(value, location) {
  try {
    return new Intl.DateTimeFormat(navigator.languages?.[0] || navigator.language, {
      dateStyle: "medium",
      timeZone: location.timezone
    }).format(new Date(value));
  } catch (error) {
    return value;
  }
}

function localTime(value, location) {
  return formatDateTimeWithZone(value, location.timezone, location.countryCode);
}

function intervalText(interval, location) {
  return localTime(interval.startsAt, location) + " – " + localTime(interval.endsAt, location);
}

function distanceText(value) {
  return new Intl.NumberFormat(navigator.languages?.[0] || navigator.language, {
    maximumFractionDigits: 0
  }).format(value) + " km";
}

function moonPosition(moon, assessment) {
  return [
    positionValue(degrees(moon.altitudeDegrees) + " altitude", "altitudeDegrees", assessment),
    " · ",
    positionValue(degrees(moon.azimuthDegrees) + " " + compassDirection(moon.azimuthDegrees),
      "azimuthDegrees", assessment)
  ];
}

function positionValue(text, filterName, assessment) {
  var mismatch = assessment.filters.some(function (filter) {
    return filter.filter === filterName && filter.status === "does_not_match";
  });
  if (!mismatch) return text;
  var tooltip = filterName === "altitudeDegrees"
    ? "Outside your altitude preference." : "Outside your direction preference.";
  return element("span", {
    className: "tooltip special-moon-position-warning",
    title: tooltip,
    "data-tooltip": tooltip,
    tabindex: "0",
    role: "note",
    ariaLabel: text + ". " + tooltip
  }, text);
}

function contains(interval, instant) {
  return new Date(interval.startsAt).getTime() <= new Date(instant).getTime()
    && new Date(instant).getTime() <= new Date(interval.endsAt).getTime();
}

function validShadow(shadow) {
  return objectValue(shadow)
    && Number.isFinite(shadow.centerRightMoonRadii)
    && Number.isFinite(shadow.centerUpMoonRadii)
    && Number.isFinite(shadow.umbraRadiusMoonRadii) && shadow.umbraRadiusMoonRadii > 0
    && Number.isFinite(shadow.penumbraRadiusMoonRadii)
    && shadow.penumbraRadiusMoonRadii > shadow.umbraRadiusMoonRadii;
}

function nullableDegrees(value) {
  return value === null || finiteBetween(value, 0, 360, false);
}

function validInstant(value) {
  var parsed = typeof value === "string" && INSTANT_PATTERN.test(value)
    ? new Date(value) : null;
  return parsed !== null && Number.isFinite(parsed.getTime())
    && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
}

function instantOrderKey(value) {
  var dot = value.indexOf(".");
  var fraction = dot < 0 ? "" : value.slice(dot + 1, -1);
  return value.slice(0, 19) + fraction.padEnd(9, "0");
}

function finiteBetween(value, minimum, maximum, inclusiveMaximum) {
  return typeof value === "number" && Number.isFinite(value) && value >= minimum
    && (inclusiveMaximum === false ? value < maximum : value <= maximum);
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function safeId(value) {
  return String(value).replace(/[^a-zA-Z0-9_-]/g, "-");
}
