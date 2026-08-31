import { element } from "./dom.js";
import { fullMoonCard, lunarEclipseCard } from "./lunarEclipseCard.js";
import { specialMoonEventsEnabled } from "./opportunityPreferences.js";

var EVENT_PATH = "/api/moon-events";
var INSTANT_PATTERN = /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?Z$/;
var SUBTYPES = ["penumbral", "partial", "total"];
var VISIBILITY_STATUSES = ["fully_visible", "partly_visible", "not_visible"];
var APPLICABLE_FILTERS = ["altitudeDegrees", "azimuthDegrees"];
var REQUEST_FILTERS = APPLICABLE_FILTERS.concat([
  "time", "namedPhases", "brightLimbOrientationDegrees"
]);

export function createMoonEventView(results) {
  var activeRequest = null;
  var currentResponse = null;
  var requestNumber = 0;
  var sectionNode = null;
  document.getElementById("preference-form")?.addEventListener("change", function (event) {
    if (!(event.target instanceof HTMLInputElement)
        || event.target.id !== "preference-special-events") return;
    if (event.target.checked && currentResponse && results.querySelector(".result-summary")) {
      render(currentResponse);
    } else {
      hide();
    }
  });

  return { render: render, cancel: cancel };

  function render(ordinaryResponse) {
    hide();
    currentResponse = ordinaryResponse;
    var location = ordinaryResponse?.location;
    var normalized = ordinaryResponse?.normalizedActiveFilters;
    if (normalized === undefined && ordinaryResponse?.appliedPreferenceVersion === undefined) {
      normalized = {};
    }
    if (!specialMoonEventsEnabled() || location?.kind !== "real_location"
        || typeof location.id !== "string" || !location.id
        || !validTimeZone(location.timezone) || !objectValue(normalized)) {
      return;
    }

    var section = eventSection();
    sectionNode = section.node;
    var ordinaryContent = results.querySelector(
      ".current-moon-card, .opportunity-list, .status-panel, .rejected-details");
    results.insertBefore(section.node, ordinaryContent);
    var preferences = { version: 1 };
    REQUEST_FILTERS.forEach(function (key) {
      if (Object.prototype.hasOwnProperty.call(normalized, key)) preferences[key] = normalized[key];
    });
    var request = {
      controller: new AbortController(),
      id: ++requestNumber,
      location: location,
      preferences: preferences,
      section: section
    };
    activeRequest = request;

    fetch(EVENT_PATH, {
      method: "POST",
      headers: { "Accept": "application/json", "Content-Type": "application/json" },
      body: JSON.stringify({ locationId: location.id, preferences: preferences }),
      cache: "no-store",
      signal: request.controller.signal
    }).then(function (response) {
      if (!response.ok) throw new Error("Moon event request failed");
      return response.json();
    }).then(function (payload) {
      if (!isCurrent(request)) return;
      if (!validResponse(payload, request)) throw new Error("Malformed Moon event response");
      renderSuccess(request, payload);
      activeRequest = null;
    }).catch(function () {
      if (!isCurrent(request)) return;
      request.section.node.setAttribute("aria-busy", "false");
      request.section.status.textContent = "Special Moon events are temporarily unavailable. "
        + "Moon opportunities are unchanged.";
      request.section.content.replaceChildren();
      activeRequest = null;
    });
  }

  function cancel() {
    hide();
    currentResponse = null;
  }

  function hide() {
    requestNumber += 1;
    if (activeRequest) activeRequest.controller.abort();
    activeRequest = null;
    if (sectionNode) sectionNode.remove();
    sectionNode = null;
  }

  function isCurrent(request) {
    return activeRequest === request && request.id === requestNumber
      && request.section.node.isConnected;
  }
}

function eventSection() {
  var status = element("p", {
    className: "special-moon-events-status",
    role: "status",
    "aria-live": "polite"
  }, "Checking special Moon events…");
  var content = element("div", {
    className: "special-moon-events-content",
    "aria-live": "off"
  });
  var node = element("section", {
    className: "result-panel special-moon-events",
    ariaLabelledby: "special-moon-events-title",
    "aria-busy": "true",
    "aria-live": "off"
  },
  element("h3", { id: "special-moon-events-title" }, "Special Moon events"),
  status,
  content);
  return { node: node, status: status, content: content };
}

function renderSuccess(request, payload) {
  request.section.node.setAttribute("aria-busy", "false");
  if (payload.events.length === 0) {
    request.section.status.textContent =
      "No lunar eclipse or near-perigee full Moon is available for this location "
      + "in the next 18 months.";
    request.section.content.replaceChildren();
    return;
  }
  request.section.status.textContent = payload.events.length === 1
    ? "1 special Moon event found." : payload.events.length + " special Moon events found.";
  request.section.content.replaceChildren(
    element("div", { className: "special-moon-event-list" },
      payload.events.map(function (event) {
        return event.kind === "lunar_eclipse"
          ? lunarEclipseCard(event, payload.location)
          : fullMoonCard(event, payload.location);
      }))
  );
}

function validResponse(payload, request) {
  return objectValue(payload) && payload.status === "ok"
    && validInstant(payload.generatedAt) && validInterval(payload)
    && validLocation(payload.location, request.location)
    && payload.appliedPreferenceVersion === 1
    && objectValue(payload.normalizedActiveFilters)
    && samePreferences(payload.normalizedActiveFilters, request.preferences)
    && Array.isArray(payload.events)
    && payload.events.every(function (event) {
      return validEvent(event, request.preferences, payload.startsAt, payload.endsAt);
    });
}

function validLocation(location, expected) {
  return objectValue(location) && location.id === expected.id
    && location.kind === "real_location" && typeof location.displayName === "string"
    && location.timezone === expected.timezone && location.countryCode === expected.countryCode
    && validTimeZone(location.timezone) && typeof location.countryCode === "string";
}

function samePreferences(actual, requested) {
  var actualKeys = Object.keys(actual).sort();
  var requestedKeys = Object.keys(requested).filter(function (key) {
    return key !== "version";
  }).sort();
  return sameValue(actualKeys, requestedKeys) && actualKeys.every(function (key) {
    return sameValue(actual[key], requested[key]);
  });
}

function sameValue(left, right) {
  if (left === right) return true;
  if (Array.isArray(left) || Array.isArray(right)) {
    return Array.isArray(left) && Array.isArray(right) && left.length === right.length
      && left.every(function (value, index) { return sameValue(value, right[index]); });
  }
  if (!objectValue(left) || !objectValue(right)) return false;
  var leftKeys = Object.keys(left).sort();
  var rightKeys = Object.keys(right).sort();
  return sameValue(leftKeys, rightKeys) && leftKeys.every(function (key) {
    return sameValue(left[key], right[key]);
  });
}

function validEvent(event, preferences, responseStartsAt, responseEndsAt) {
  if (!objectValue(event) || typeof event.id !== "string" || event.id.length === 0) return false;
  if (event.kind === "lunar_eclipse") {
    return validLunarEclipse(event, preferences, responseEndsAt);
  }
  return event.kind === "full_moon"
    && validFullMoon(event, preferences, responseStartsAt, responseEndsAt);
}

function validLunarEclipse(event, preferences, responseEndsAt) {
  return objectValue(event) && typeof event.id === "string" && event.id.length > 0
    && SUBTYPES.includes(event.subtype)
    && orderedInstants(event.startsAt, event.maximumAt, event.endsAt)
    && finiteBetween(event.umbralObscurationPercent, 0, 100)
    && Array.isArray(event.phases) && event.phases.length > 0
    && event.phases.every(validPhase) && validMoon(event.moonAtMaximum)
    && validEventVisibility(event.localVisibility, event, responseEndsAt)
    && validAssessment(event.preferenceAssessment, preferences)
    && validWeather(event.weather) && validShadowSamples(event);
}

function validFullMoon(event, preferences, responseStartsAt, responseEndsAt) {
  if (!validInstant(event.peakAt) || !Array.isArray(event.qualifiers)
      || event.qualifiers.length !== 1 || !validNearPerigee(event.qualifiers[0])) return false;
  if (event.localViewing === undefined) {
    return event.weather === undefined
      && instantValue(event.peakAt) >= instantValue(responseStartsAt)
      && instantValue(event.peakAt) < instantValue(responseEndsAt)
      && validAssessment(event.preferenceAssessment, preferences, true);
  }
  return validFullMoonViewing(event.localViewing, event.peakAt, responseStartsAt, responseEndsAt)
    && validAssessment(event.preferenceAssessment, preferences)
    && validWeather(event.weather);
}

function validNearPerigee(qualifier) {
  if (!objectValue(qualifier) || qualifier.kind !== "near_perigee"
      || qualifier.definitionVersion !== 1 || !finiteBetween(qualifier.closeness, 0.9, 1)
      || !Number.isFinite(qualifier.distanceKilometersAtPeak)
      || !Number.isFinite(qualifier.perigeeDistanceKilometers)
      || !Number.isFinite(qualifier.apogeeDistanceKilometers)
      || qualifier.perigeeDistanceKilometers <= 0
      || qualifier.perigeeDistanceKilometers >= qualifier.apogeeDistanceKilometers
      || !finiteBetween(qualifier.distanceKilometersAtPeak,
        qualifier.perigeeDistanceKilometers, qualifier.apogeeDistanceKilometers)) return false;
  var expected = (qualifier.apogeeDistanceKilometers - qualifier.distanceKilometersAtPeak)
    / (qualifier.apogeeDistanceKilometers - qualifier.perigeeDistanceKilometers);
  return Math.abs(expected - qualifier.closeness) <= 1e-9;
}

function validFullMoonViewing(viewing, peakAt, responseStartsAt, responseEndsAt) {
  if (!objectValue(viewing) || !Array.isArray(viewing.intervals)
      || viewing.intervals.length === 0 || !validInterval(viewing.selectedInterval)
      || !validDisplayInterval(viewing.displayInterval, responseEndsAt)) return false;
  var peak = instantValue(peakAt);
  var intervalsValid = viewing.intervals.every(function (interval, index, intervals) {
    return validInterval(interval) && instantValue(interval.startsAt) >= peak - 86_400_000
      && instantValue(interval.endsAt) <= peak + 86_400_000
      && (index === 0 || instantValue(intervals[index - 1].endsAt)
        <= instantValue(interval.startsAt));
  });
  var selected = viewing.selectedInterval;
  var display = viewing.displayInterval;
  return intervalsValid && viewing.intervals.some(function (interval) {
    return sameInterval(interval, selected);
  }) && instantValue(selected.startsAt) < instantValue(responseEndsAt)
    && instantValue(selected.endsAt) > instantValue(responseStartsAt)
    && containsInterval(selected, display)
    && instantValue(display.startsAt) === Math.max(
      instantValue(selected.startsAt), instantValue(responseStartsAt))
    && instantValue(display.endsAt) === Math.min(
      instantValue(selected.endsAt), instantValue(responseEndsAt));
}

function validPhase(phase) {
  return objectValue(phase) && SUBTYPES.includes(phase.kind)
    && validInterval(phase) && validVisibility(phase.localVisibility, phase, true);
}

function validEventVisibility(visibility, event, responseEndsAt) {
  return validVisibility(visibility, event, false)
    && validInterval(visibility.selectedInterval)
    && visibility.intervals.some(function (interval) {
      return sameInterval(interval, visibility.selectedInterval);
    })
    && validDisplayInterval(visibility.displayInterval, responseEndsAt)
    && containsInterval(visibility.selectedInterval, visibility.displayInterval);
}

function validVisibility(visibility, objective, allowNotVisible) {
  if (!objectValue(visibility) || !VISIBILITY_STATUSES.includes(visibility.status)
      || !Array.isArray(visibility.intervals)) return false;
  var intervalsValid = visibility.intervals.every(function (interval, index, intervals) {
    return validInterval(interval) && containsInterval(objective, interval)
      && (index === 0 || instantValue(intervals[index - 1].endsAt) <= instantValue(interval.startsAt));
  });
  if (!intervalsValid) return false;
  var fullyVisible = visibility.intervals.length === 1
    && sameInterval(visibility.intervals[0], objective);
  if (visibility.status === "fully_visible") return fullyVisible;
  if (visibility.status === "partly_visible") {
    return visibility.intervals.length > 0 && !fullyVisible;
  }
  return allowNotVisible && visibility.intervals.length === 0;
}

function validDisplayInterval(interval, responseEndsAt) {
  return validInterval(interval) && validInstant(interval.suggestedAt)
    && instantValue(interval.suggestedAt) >= instantValue(interval.startsAt)
    && instantValue(interval.suggestedAt) <= instantValue(interval.endsAt)
    && instantValue(interval.endsAt) <= instantValue(responseEndsAt)
    && (instantValue(interval.endsAt) < instantValue(responseEndsAt)
      || instantValue(interval.suggestedAt) < instantValue(interval.endsAt))
    && validMoon(interval.moon) && objectValue(interval.sun)
    && finiteBetween(interval.sun.altitudeDegrees, -90, 90)
    && typeof interval.sun.lightBucket === "string" && interval.sun.lightBucket.length > 0;
}

function validAssessment(assessment, preferences, unavailable) {
  if (!objectValue(assessment) || !Array.isArray(assessment.filters)) return false;
  var active = APPLICABLE_FILTERS.filter(function (key) {
    return Object.prototype.hasOwnProperty.call(preferences, key);
  });
  if (assessment.filters.length !== active.length) return false;
  var rowsValid = assessment.filters.every(function (filter, index) {
    return objectValue(filter) && filter.filter === active[index]
      && (unavailable ? filter.status === "not_applicable"
        : filter.status === "matches" || filter.status === "does_not_match");
  });
  if (!rowsValid) return false;
  if (active.length === 0) return assessment.overall === "no_active_preferences";
  if (unavailable) return assessment.overall === "not_applicable";
  var allMatch = assessment.filters.every(function (filter) { return filter.status === "matches"; });
  return assessment.overall === (allMatch ? "matches" : "does_not_match");
}

function validShadowSamples(event) {
  if (!Array.isArray(event.shadowSamples) || event.shadowSamples.length === 0) return false;
  var expected = new Set([event.maximumAt, event.localVisibility.displayInterval.suggestedAt]);
  event.phases.forEach(function (phase) {
    expected.add(phase.startsAt);
    expected.add(phase.endsAt);
  });
  return event.shadowSamples.length === expected.size
    && event.shadowSamples.every(function (sample, index, samples) {
      return validShadowSample(sample) && expected.has(sample.at)
        && (index === 0 || instantValue(samples[index - 1].at) < instantValue(sample.at));
    });
}

function validShadowSample(sample) {
  if (!objectValue(sample) || !validInstant(sample.at) || !objectValue(sample.moon)
      || !validMoon(sample.moon) || !objectValue(sample.shadow)) return false;
  var pole = sample.moon.northPoleTiltDegrees;
  var shadow = sample.shadow;
  return (pole === null || finiteBetween(pole, 0, 360, false))
    && Number.isFinite(shadow.centerRightMoonRadii)
    && Number.isFinite(shadow.centerUpMoonRadii)
    && Number.isFinite(shadow.umbraRadiusMoonRadii) && shadow.umbraRadiusMoonRadii > 0
    && Number.isFinite(shadow.penumbraRadiusMoonRadii)
    && shadow.penumbraRadiusMoonRadii > shadow.umbraRadiusMoonRadii;
}

function validWeather(weather) {
  if (!objectValue(weather)) return false;
  if (weather.status === "available") {
    return validInstant(weather.forecastHourStartsAt)
      && typeof weather.summary === "string" && weather.summary.trim().length > 0
      && Number.isInteger(weather.cloudCoverPercent)
      && finiteBetween(weather.cloudCoverPercent, 0, 100)
      && Number.isInteger(weather.precipitationProbabilityPercent)
      && finiteBetween(weather.precipitationProbabilityPercent, 0, 100);
  }
  return weather.status === "outside_forecast_horizon"
    || weather.status === "temporarily_unavailable";
}

function validMoon(moon) {
  return objectValue(moon) && finiteBetween(moon.altitudeDegrees, -90, 90)
    && finiteBetween(moon.azimuthDegrees, 0, 360, false);
}

function validInterval(interval) {
  return objectValue(interval) && validInstant(interval.startsAt) && validInstant(interval.endsAt)
    && instantValue(interval.startsAt) < instantValue(interval.endsAt);
}

function containsInterval(outer, inner) {
  return instantValue(outer.startsAt) <= instantValue(inner.startsAt)
    && instantValue(inner.endsAt) <= instantValue(outer.endsAt);
}

function sameInterval(left, right) {
  return left.startsAt === right.startsAt && left.endsAt === right.endsAt;
}

function orderedInstants(start, middle, end) {
  return validInstant(start) && validInstant(middle) && validInstant(end)
    && instantValue(start) <= instantValue(middle) && instantValue(middle) <= instantValue(end);
}

function validInstant(value) {
  var parsed = typeof value === "string" && INSTANT_PATTERN.test(value)
    ? new Date(value) : null;
  return parsed !== null && Number.isFinite(parsed.getTime())
    && parsed.toISOString().slice(0, 19) === value.slice(0, 19);
}

function instantValue(value) {
  return new Date(value).getTime();
}

function finiteBetween(value, minimum, maximum, inclusiveMaximum) {
  return typeof value === "number" && Number.isFinite(value) && value >= minimum
    && (inclusiveMaximum === false ? value < maximum : value <= maximum);
}

function validTimeZone(value) {
  try {
    new Intl.DateTimeFormat(undefined, { timeZone: value }).format();
    return typeof value === "string" && value.length > 0;
  } catch (error) {
    return false;
  }
}

function objectValue(value) {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}
