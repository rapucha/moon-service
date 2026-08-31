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
  var bestSample = event.shadowSamples.find(function (sample) { return sample.at === bestAt; });
  var maximumIsBest = bestAt === event.maximumAt;
  var headingId = "special-moon-event-" + safeId(event.id);
  var bestLabel = maximumIsBest ? "Best · Maximum" : "Best visible";
  var summaryCanvas = eclipseCanvas(bestSample, subtype.title + " at " + bestLabel, 156,
    "special-moon-summary-canvas");

  return element("details", {
    className: "special-moon-event-card",
    ariaLabelledby: headingId
  },
  element("summary", { className: "special-moon-event-summary" },
    element("h4", { id: headingId },
      summaryCanvas,
      element("span", { className: "special-moon-summary-copy" },
        element("span", { className: "special-moon-event-kind" }, "Lunar eclipse"),
        element("span", { className: "special-moon-event-title" }, subtype.title),
        element("span", { className: "special-moon-event-date" },
          localDate(bestAt, location)),
        element("span", { className: "special-moon-event-best" },
          bestLabel + " · " + formatTime(bestAt, location.timezone, location.countryCode)),
        element("span", { className: "special-moon-event-position" },
          moonPosition(display.moon, event.preferenceAssessment))))),
  element("div", { className: "special-moon-event-details" },
    element("p", { className: "special-moon-event-description" }, subtype.description),
    stageStrip(event, location),
    eventFacts(event, location),
    maximumVisible(event) ? null : element("p", { className: "special-moon-horizon-note" },
      "The objective maximum is below the model horizon."),
    phaseVisibility(event.phases, location),
    element("p", { className: "special-moon-weather" }, weatherText(event.weather)),
    element("p", { className: "special-moon-events-caveat" },
      "Visibility uses a level astronomical horizon and does not account for terrain, buildings, or trees.")));
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
      var bestText = best
        ? (sample.at === event.maximumAt ? "Best · Maximum" : "Best visible")
        : "";
      var label = stageLabel(event, sample.at);
      return element("figure", {
        className: "special-moon-stage" + (best ? " is-best" : "")
      },
      eclipseCanvas(sample, label + " eclipse stage", 128, "special-moon-stage-canvas"),
      element("figcaption", {},
        element("strong", {}, label),
        element("span", {}, formatTime(sample.at, location.timezone, location.countryCode)),
        bestText ? element("span", { className: "special-moon-stage-best" }, bestText) : null));
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
  if (event.localVisibility.displayInterval.suggestedAt === instant
      && event.maximumAt !== instant) labels.push("Best visible");
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

function safeId(value) {
  return String(value).replace(/[^a-zA-Z0-9_-]/g, "-");
}
