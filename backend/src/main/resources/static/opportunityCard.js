import { element } from "./dom.js";
import {
  degrees,
  durationText,
  formatDateTimeWithZone,
  readableToken
} from "./format.js";
import { moonPathPanel } from "./moonPathView.js";
import { scoreBlock, scoreDetails } from "./scoreView.js";

export function moonPassCard(pass, entries, index, timezone, countryCode, chartContext) {
  var primaryEntry = entries[0];
  var primary = primaryEntry.opportunity;

  return element("article", { className: "opportunity-card moon-pass-card" + (index === 0 ? " is-primary" : "") },
    element("header", { className: "opportunity-header" },
      element("div", { className: "opportunity-title" },
        element("p", { className: "rank-label" }, index === 0 ? "Best match" : "Option " + (index + 1)),
        element("h3", {}, passSummaryText(entries.length))),
      scoreBlock(primary.score)
    ),
    passRecommendations(entries, timezone, countryCode),
    passIntervalContext(pass, primary, timezone, countryCode),
    moonPathPanel(moonPassPathOpportunity(pass, entries, primary), timezone, countryCode, chartContext),
    opportunityActions(primary),
    scoreDetails(primary.components || {})
  );
}

function moonPassPathOpportunity(pass, entries, primary) {
  var ordered = entries.slice().sort(compareOpportunityStarts);
  var passPath = (pass || {}).path || {};
  var samples = combinedPassSamples(passPath, ordered, primary);
  if (samples.length < 2) {
    return primary;
  }
  var start = samples[0];
  var end = samples[samples.length - 1];
  var recommendationSummaries = ordered.map(function (entry) {
    var suggested = (entry.opportunity.moonPath || {}).suggested;
    return hasPathPosition(suggested)
      ? { label: recommendationLabel(entry.opportunity.windowKind), point: suggested }
      : null;
  }).filter(Boolean);

  return Object.assign({}, primary, {
    moonPath: {
      description: "Altitude over time, with horizon direction on the top rail",
      chartSubject: "Moon pass",
      summaryClass: recommendationSummaries.length > 1 ? "is-pass" : "",
      hideSummary: true,
      summary: [
        { label: "Start", point: start },
        recommendationSummaries,
        { label: "End", point: end }
      ],
      start: start,
      suggested: (primary.moonPath || {}).suggested || start,
      end: end,
      samples: samples
    }
  });
}

function combinedPassSamples(passPath, orderedEntries, primary) {
  var suggestedLabelsByTime = new Map();
  var primarySuggested = ((primary || {}).moonPath || {}).suggested || {};
  var samplesByTime = new Map();

  passPathSamples(passPath).forEach(function (sample) {
    addPathSample(samplesByTime, sample);
  });
  if (samplesByTime.size < 2) {
    return [];
  }

  orderedEntries.forEach(function (entry) {
    var path = entry.opportunity.moonPath || {};
    if (hasPathPosition(path.suggested)) {
      var moon = entry.opportunity.moon || {};
      suggestedLabelsByTime.set(path.suggested.at, path.suggested.at === primarySuggested.at ? "Best" : "Alt");
      addPathSample(samplesByTime, Object.assign({}, path.suggested, {
        moonPhaseAngleDegrees: moon.phaseAngleDegrees,
        brightLimbTiltDegrees: moon.brightLimbTiltDegrees,
        northPoleTiltDegrees: moon.northPoleTiltDegrees
      }));
    }
  });

  var times = Array.from(samplesByTime.keys()).sort(function (a, b) {
    return a - b;
  });
  if (times.length < 2) {
    return [];
  }
  var firstTime = times[0];
  var lastTime = times[times.length - 1];

  return times.map(function (time) {
    var sample = Object.assign({}, samplesByTime.get(time));
    if (suggestedLabelsByTime.has(sample.at)) {
      sample.role = "suggested";
      sample.markerLabel = suggestedLabelsByTime.get(sample.at);
    } else if (time === firstTime) {
      sample.role = "start";
    } else if (time === lastTime) {
      sample.role = "end";
    } else {
      sample.role = "path";
    }
    return sample;
  });
}

function passPathSamples(path) {
  var samples = Array.isArray(path.samples) ? path.samples : [];
  return samples.concat([path.start, path.end]);
}

function addPathSample(samplesByTime, sample) {
  if (!hasPathPosition(sample)) {
    return;
  }
  var time = new Date(sample.at).getTime();
  if (!Number.isFinite(time)) {
    return;
  }
  samplesByTime.set(time, Object.assign({}, samplesByTime.get(time) || {}, sample));
}

function hasPathPosition(point) {
  return point
    && point.at
    && Number.isFinite(point.altitudeDegrees)
    && Number.isFinite(point.azimuthDegrees);
}

function moonPassTitle(pass, primary, timezone, countryCode) {
  var startsAt = (pass || {}).startsAt || (primary || {}).startsAt;
  var endsAt = (pass || {}).endsAt || (primary || {}).endsAt;
  if (!startsAt || !endsAt) {
    return "";
  }
  return formatDateTimeWithZone(startsAt, timezone, countryCode)
    + " to "
    + formatDateTimeWithZone(endsAt, timezone, countryCode);
}

function passIntervalContext(pass, primary, timezone, countryCode) {
  var title = moonPassTitle(pass, primary, timezone, countryCode);
  if (!title) {
    return null;
  }
  return element("div", { className: "pass-context-row", ariaLabel: "Moon pass interval" },
    element("dl", { className: "pass-context" },
      element("dt", {}, "Moon pass"),
      element("dd", {}, title)));
}

function passRecommendations(entries, timezone, countryCode) {
  var className = "pass-choices" + (entries.length === 1 ? " is-single" : "");
  return element("section", { className: className, ariaLabel: "Recommendations in this Moon pass" },
    entries.map(function (entry, index) {
      return passRecommendation(entry, index === 0, entries.length === 1, timezone, countryCode);
    }));
}

function passRecommendation(entry, isBest, isSingleRecommendation, timezone, countryCode) {
  var opportunity = entry.opportunity;
  var rawRank = entry.index + 1;
  var moon = opportunity.moon || {};
  var sun = opportunity.sun || {};
  var weather = opportunity.weather || {};
  var exposureBalance = opportunity.exposureBalance || {};

  return element("article", { className: "pass-choice-card" + (isBest ? " is-best" : "") },
    element("header", { className: "pass-choice-header" },
      element("div", {},
        element("div", { className: "choice-meta" },
          element("span", { className: "choice-badge" + (isBest ? " is-best" : " is-alt") }, isBest ? "Best" : "Alternative"),
          element("span", { className: "choice-rank" }, candidateRankText(rawRank, opportunity.score))),
        element("h4", {}, formatDateTimeWithZone(opportunity.suggestedAt, timezone, countryCode))),
      element("span", { className: "pass-choice-kind" }, recommendationLabel(opportunity.windowKind))),
    element("dl", { className: "pass-metric-grid" },
      metricFact("Moon altitude", moonPositionText(moon)),
      metricFact("Window", durationText(opportunity.startsAt, opportunity.endsAt)),
      metricFact("Light bucket", lightBucketBadge(sun.lightBucket)),
      metricFact("Sun altitude", degrees(sun.altitudeDegrees)),
      metricFact("Sky", weather.summary || readableToken(weather.segmentKind) || "Forecast unavailable"),
      isSingleRecommendation ? null : metricSpacer()),
    exposureBalance.text
      ? element("dl", { className: "pass-photo-hint" },
        element("dt", {}, "Photo hint"),
        element("dd", {}, exposureBalance.label
          ? readableToken(exposureBalance.label) + ": " + exposureBalance.text
          : exposureBalance.text))
      : null,
    opportunity.reason
      ? element("details", { className: "pass-choice-explanation" },
        element("summary", {}, "Why this candidate ranked here"),
        element("p", {}, opportunity.reason))
      : null);
}

function candidateRankText(rank, score) {
  return "Rank " + rank + " · " + (Number.isFinite(score) ? "score " + score : "score unavailable");
}

function metricSpacer() {
  return element("div", { className: "pass-metric is-spacer", "aria-hidden": "true" });
}

function passSummaryText(count) {
  if (count === 1) {
    return "1 candidate window in this Moon pass";
  }
  return count + " candidate windows in this Moon pass";
}

function metricFact(label, value) {
  return element("div", { className: "pass-metric" },
    element("dt", {}, label),
    element("dd", {}, value || "Unavailable"));
}

function lightBucketBadge(bucket) {
  var text = readableToken(bucket);
  return element("span", { className: "light-bucket is-" + roleClass(bucket) }, text || "Unavailable");
}

function moonPositionText(moon) {
  var altitude = degrees((moon || {}).altitudeDegrees);
  var direction = compassDirection((moon || {}).azimuthDegrees);
  return direction ? altitude + " " + direction : altitude;
}

function compassDirection(value) {
  if (!Number.isFinite(value)) {
    return "";
  }
  var directions = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
  var normalized = ((value % 360) + 360) % 360;
  return directions[Math.round(normalized / 22.5) % directions.length];
}

function compareOpportunityStarts(a, b) {
  var aStarted = new Date(a.opportunity.startsAt).getTime();
  var bStarted = new Date(b.opportunity.startsAt).getTime();
  if (Number.isFinite(aStarted) && Number.isFinite(bStarted) && aStarted !== bStarted) {
    return aStarted - bStarted;
  }
  return a.index - b.index;
}

function recommendationLabel(kind) {
  if (String(kind || "").startsWith("moonrise")) {
    return "Moonrise side";
  }
  if (String(kind || "").startsWith("moonset")) {
    return "Moonset side";
  }
  return "Recommendation";
}

function roleClass(role) {
  return String(role || "unknown").replace(/[^a-z0-9_-]/gi, "").toLowerCase() || "unknown";
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
