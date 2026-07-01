import { element } from "./dom.js";
import {
  cloudText,
  degrees,
  durationText,
  formatDateTime,
  formatDateTimeWithZone,
  formatWindow,
  precipitationText,
  readableToken,
  visibilityText
} from "./format.js";
import { moonPathPanel } from "./moonPathView.js";
import { moonPhaseSummary } from "./moonPhaseView.js";
import { scoreBlock, scoreDetails } from "./scoreView.js";
import { fact } from "./terms.js";

export function opportunityCard(opportunity, index, timezone, countryCode, chartContext) {
  var moon = opportunity.moon || {};
  var sun = opportunity.sun || {};
  var weather = opportunity.weather || {};
  var exposureBalance = opportunity.exposureBalance || {};
  var components = opportunity.components || {};

  return element("article", { className: "opportunity-card" + (index === 0 ? " is-primary" : "") },
    element("header", { className: "opportunity-header" },
      element("div", { className: "opportunity-title" },
        element("p", { className: "rank-label" }, index === 0 ? "Best match" : "Option " + (index + 1)),
        element("h3", {}, formatWindow(opportunity, timezone, countryCode)),
        element("p", { className: "reason" }, opportunity.reason || "Ranked Moon opportunity.")),
      scoreBlock(opportunity.score)
    ),
    element("dl", { className: "fact-grid key-facts" },
      fact("Suggested", formatDateTimeWithZone(opportunity.suggestedAt, timezone, countryCode)),
      fact("Duration", durationText(opportunity.startsAt, opportunity.endsAt)),
      fact("Moon altitude", degrees(moon.altitudeDegrees)),
      fact("Moon azimuth", degrees(moon.azimuthDegrees))
    ),
    moonPathPanel(opportunity, timezone, countryCode, chartContext),
    element("div", { className: "metric-columns" },
      metricGroup("Light", [
        fact("Bucket", readableToken(sun.lightBucket) || "Unavailable"),
        fact("Sun altitude", degrees(sun.altitudeDegrees)),
        fact("Moon phase", moonPhaseSummary(moon))
      ]),
      metricGroup("Weather", [
        fact("Summary", weather.summary || readableToken(weather.segmentKind) || "Forecast unavailable"),
        fact("Cloud", cloudText(weather)),
        fact("Precip", precipitationText(weather)),
        fact("Visibility", visibilityText(weather))
      ])
    ),
    exposureBalance.text
      ? element("p", { className: "exposure" }, exposureBalance.label ? readableToken(exposureBalance.label) + ": " + exposureBalance.text : exposureBalance.text)
      : null,
    opportunityActions(opportunity),
    scoreDetails(components)
  );
}

function metricGroup(title, facts) {
  return element("section", { className: "metric-group" },
    element("h4", {}, title),
    element("dl", { className: "fact-grid compact" }, facts));
}

function opportunityActions(opportunity) {
  var links = opportunity.links || {};
  var actions = [];

  if (links.ics && links.icsReady === true) {
    actions.push(element("a", { className: "secondary-action", href: links.ics }, "Download calendar event"));
  }

  if (actions.length === 0) {
    return null;
  }
  return element("div", { className: "opportunity-actions" }, actions);
}
