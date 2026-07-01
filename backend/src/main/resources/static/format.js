export function formatWindow(opportunity, timezone, countryCode) {
  var zone = timeZoneLabel(opportunity.startsAt, timezone);
  return formatDateTime(opportunity.startsAt, timezone, countryCode)
    + " to "
    + formatTime(opportunity.endsAt, timezone, countryCode)
    + (zone ? " " + zone : "");
}

export function formatDateTime(value, timezone, countryCode) {
  if (!value) {
    return "Unavailable";
  }
  try {
    return new Intl.DateTimeFormat(browserLocale(), {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: timezone || "UTC"
    }).format(new Date(value));
  } catch (error) {
    return value;
  }
}

export function formatDateTimeWithZone(value, timezone, countryCode) {
  var formatted = formatDateTime(value, timezone, countryCode);
  var zone = timeZoneLabel(value, timezone);
  return zone ? formatted + " " + zone : formatted;
}

export function formatTime(value, timezone, countryCode) {
  if (!value) {
    return "Unavailable";
  }
  try {
    return new Intl.DateTimeFormat(browserLocale(), {
      timeStyle: "short",
      timeZone: timezone || "UTC"
    }).format(new Date(value));
  } catch (error) {
    return value;
  }
}

function timeZoneLabel(value, timezone) {
  if (!value || !timezone) {
    return "";
  }
  try {
    var parts = new Intl.DateTimeFormat(browserLocale(), {
      hour: "numeric",
      timeZone: timezone,
      timeZoneName: "short"
    }).formatToParts(new Date(value));
    var zone = parts.find(function (part) {
      return part.type === "timeZoneName";
    });
    return zone ? zone.value : "";
  } catch (error) {
    return "";
  }
}

export function formatHourTick(value, timezone, countryCode) {
  if (!value) {
    return "";
  }
  try {
    var options = usesTwentyFourHourClock()
      ? {
        hour: "2-digit",
        minute: "2-digit",
        hourCycle: "h23",
        timeZone: timezone || "UTC"
      }
      : {
        hour: "numeric",
        timeZone: timezone || "UTC"
      };
    return new Intl.DateTimeFormat(browserLocale(), options).format(new Date(value));
  } catch (error) {
    return formatTime(value, timezone, countryCode);
  }
}

function usesTwentyFourHourClock() {
  try {
    var hourCycle = new Intl.DateTimeFormat(browserLocale(), {
      hour: "numeric"
    }).resolvedOptions().hourCycle;
    return hourCycle === "h23" || hourCycle === "h24";
  } catch (error) {
    return false;
  }
}

function browserLocale() {
  var locale = navigator.languages && navigator.languages.length > 0
    ? navigator.languages[0]
    : navigator.language;
  var candidate = String(locale || "").trim();
  return candidate && Intl.DateTimeFormat.supportedLocalesOf([candidate]).length > 0
    ? candidate
    : undefined;
}

export function durationText(startsAt, endsAt) {
  if (!startsAt || !endsAt) {
    return "Unavailable";
  }
  var started = new Date(startsAt).getTime();
  var ended = new Date(endsAt).getTime();
  if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
    return "Unavailable";
  }
  var minutes = Math.round((ended - started) / 60000);
  if (minutes < 60) {
    return minutes + " min";
  }
  var hours = Math.floor(minutes / 60);
  var remainder = minutes % 60;
  return remainder ? hours + " hr " + remainder + " min" : hours + " hr";
}

export function degrees(value) {
  return Number.isFinite(value) ? value.toFixed(1) + "°" : "unavailable";
}

export function round1(value) {
  return Math.round(value * 10) / 10;
}

export function clamp(value, min, max) {
  return Math.max(min, Math.min(max, value));
}

export function normalizeDegrees(value) {
  var normalized = value % 360;
  return normalized < 0 ? normalized + 360 : normalized;
}

export function percent(value) {
  return Number.isFinite(value) ? Math.round(value) + "%" : "unavailable";
}

export function readableToken(value) {
  var text = String(value || "").replace(/_/g, " ").trim();
  return text ? text.charAt(0).toUpperCase() + text.slice(1) : "";
}

export function cloudText(weather) {
  if (Number.isFinite(weather.cloudCoverMeanPercent) && Number.isFinite(weather.cloudCoverMaxPercent)) {
    return Math.round(weather.cloudCoverMeanPercent) + "% mean, " + Math.round(weather.cloudCoverMaxPercent) + "% max";
  }
  if (Number.isFinite(weather.cloudCoverMaxPercent)) {
    return Math.round(weather.cloudCoverMaxPercent) + "% max";
  }
  return "unavailable";
}

export function precipitationText(weather) {
  var parts = [];
  if (Number.isFinite(weather.precipitationProbabilityMaxPercent)) {
    parts.push(Math.round(weather.precipitationProbabilityMaxPercent) + "% risk");
  }
  if (Number.isFinite(weather.precipitationMm)) {
    parts.push(weather.precipitationMm.toFixed(1) + " mm");
  }
  return parts.length > 0 ? parts.join(", ") : "unavailable";
}

export function visibilityText(weather) {
  if (!Number.isFinite(weather.visibilityMinMeters)) {
    return "unavailable";
  }
  return (weather.visibilityMinMeters / 1000).toFixed(1) + " km";
}

export function candidateMeta(candidate) {
  var parts = [];
  if (candidate.kind === "fictional_location") {
    parts.push("fictional");
  } else if (candidate.kind) {
    parts.push(candidate.kind.replace(/_/g, " "));
  }
  if (candidate.countryCode) {
    parts.push(candidate.countryCode);
  }
  if (candidate.timezone) {
    parts.push(candidate.timezone);
  }
  return parts.join(" | ");
}
