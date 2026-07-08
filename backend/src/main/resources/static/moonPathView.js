import { element, svgElement } from "./dom.js";
import {
  clamp,
  degrees,
  formatHourTick,
  formatTime,
  normalizeDegrees,
  readableToken,
  round1
} from "./format.js";
import { lightBandSegments } from "./moonPathLightBands.js";
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";
import { altitudeForegroundArtwork } from "./moonPathSilhouettes.js";

var DESKTOP_ALTITUDE_WIDTH = 730;
var DESKTOP_PLOT_WIDTH = 672;
var MOBILE_ALTITUDE_WIDTH = 320;
var MOBILE_PLOT_WIDTH = 272;
var AZIMUTH_RAIL_LABEL_EDGE_INSET = 10;
var SUN_SAMPLE_MARKER_IMAGE_URL = "/sun-marker-aperture-flare.svg";
var SUN_SAMPLE_MARKER_SIZE = 42;

export function moonPathPanel(opportunity, timezone, countryCode, chartContext) {
  var path = opportunity.moonPath || {};
  var samples = moonPathSamples(path);
  if (samples.length < 2) {
    return null;
  }
  var summaryPoints = Array.isArray(path.summary)
    ? path.summary.flat().filter(Boolean)
    : [
      { label: "Start", point: path.start },
      { label: "Suggested", point: path.suggested },
      { label: "End", point: path.end }
    ];
  var summaryClass = "moon-path-summary" + (path.summaryClass ? " " + path.summaryClass : "");
  var description = path.description || "Start, suggested, and end positions across the window";
  var chartSubject = path.chartSubject || "opportunity window";
  var summary = path.hideSummary || summaryPoints.length === 0
    ? null
    : element("div", { className: summaryClass },
      summaryPoints.map(function (item) {
        return moonPathPoint(item.label, item.point, timezone, countryCode);
      }));

  return element("section", { className: "moon-path-panel" },
    element("div", { className: "moon-path-header" },
      element("h4", {}, "Moon path"),
      element("p", {}, description)),
    summary,
    element("div", { className: "moon-path-charts" },
      chartBlock("Altitude", altitudeChart(samples, timezone, countryCode, chartContext, opportunity.moon || {}, chartSubject))));
}

function moonPathPoint(label, point, timezone, countryCode) {
  if (!hasPosition(point)) {
    return null;
  }
  return element("div", { className: "moon-path-point" },
    element("span", { className: "moon-path-label" }, label),
    element("span", { className: "moon-path-time" }, formatTime(point.at, timezone, countryCode)),
    element("span", { className: "moon-path-position" },
      element("span", {}, "Alt " + degrees(point.altitudeDegrees)),
      element("span", {}, "Az " + degrees(point.azimuthDegrees))));
}

function chartBlock(label, chart) {
  if (!chart) {
    return null;
  }
  return element("div", { className: "moon-chart moon-chart-" + roleClass(label) },
    element("span", { className: "moon-chart-label" }, label),
    chart);
}

function altitudeChart(samples, timezone, countryCode, chartContext, moon, chartSubject) {
  var points = chartSamples(samples);
  if (points.length < 2) {
    return null;
  }

  return element("div", { className: "moon-chart-scroll" },
    altitudeChartSvg(points, timezone, countryCode, "desktop", chartContext, moon, chartSubject),
    altitudeChartSvg(points, timezone, countryCode, "mobile", chartContext, moon, chartSubject));
}

function altitudeChartSvg(sourcePoints, timezone, countryCode, mode, chartContext, moon, chartSubject) {
  var height = 390;
  var left = 34;
  var railTop = 20;
  var railHeight = 32;
  var top = 70;
  var bottom = 326;
  var firstTime = sourcePoints[0].time;
  var lastTime = sourcePoints[sourcePoints.length - 1].time;
  var timeSpan = Math.max(1, lastTime - firstTime);
  var maxAltitude = sourcePoints.reduce(function (max, point) {
    return Math.max(max, point.altitudeDegrees);
  }, 0);
  var ceiling = Math.min(90, Math.max(12, Math.ceil((maxAltitude + 1) / 5) * 5));
  var mobileReferenceDurationMs = chartContext && Number.isFinite(chartContext.mobileReferenceDurationMs)
    ? Math.max(timeSpan, chartContext.mobileReferenceDurationMs)
    : timeSpan;
  var chartWidth = mode === "mobile"
    ? Math.max(1, (timeSpan / mobileReferenceDurationMs) * MOBILE_PLOT_WIDTH)
    : DESKTOP_PLOT_WIDTH;
  var width = mode === "mobile" ? MOBILE_ALTITUDE_WIDTH : DESKTOP_ALTITUDE_WIDTH;
  var plotEndX = left + chartWidth;
  var chartHeight = bottom - top;
  var points = sourcePoints.map(function (sourcePoint, index) {
    var point = Object.assign({}, sourcePoint);
    point.sequence = index;
    point.x = left + ((point.time - firstTime) / timeSpan) * chartWidth;
    point.y = bottom - (clamp(point.altitudeDegrees, 0, ceiling) / ceiling) * chartHeight;
    return point;
  });

  var bands = lightBandSegments(points);
  var timeTicks = altitudeHourTicks(points, timezone, bottom + 29);
  var azimuthLabels = azimuthRailLabels(points, left, chartWidth, mode);
  var markerImageUrl = moonPhaseImageDataUrl(moon.phaseAngleDegrees, 64);
  var visibleMarkers = visibleAltitudeMarkers(points, mode);
  var sunMarkers = visibleSunMarkers(points, ceiling, bottom, chartHeight, mode);

  return svgElement("svg", {
    className: "altitude-chart altitude-chart-" + mode,
    viewBox: "0 0 " + width + " " + height,
    role: "img",
    ariaLabel: mode === "mobile"
      ? "Moon altitude and azimuth across the " + chartSubject + "; chart fits the card width"
      : "Moon altitude and azimuth across the " + chartSubject + "; chart fills the card width"
  },
    svgElement("rect", {
      className: "azimuth-rail-bg",
      x: left,
      y: railTop,
      width: round1(chartWidth),
      height: railHeight,
      rx: 6
    }),
    azimuthLabels.map(function (label) {
      return svgElement("text", {
        className: "azimuth-rail-label",
        x: round1(label.x),
        y: railTop + 21,
        textAnchor: label.anchor
      }, label.text);
    }),
    bands.map(function (band) {
      return svgElement("rect", {
        className: "light-band is-" + roleClass(band.lightBucket),
        x: round1(band.x),
        y: top,
        width: round1(band.width),
        height: chartHeight
      },
        svgElement("title", {}, lightBandTitle(band, timezone, countryCode)));
    }),
    altitudeForegroundArtwork(left, top, bottom, chartWidth, mode, firstTime, ceiling, chartHeight),
    svgElement("line", { className: "chart-gridline", x1: left, y1: bottom, x2: round1(plotEndX), y2: bottom }),
    svgElement("line", { className: "chart-gridline", x1: left, y1: top, x2: round1(plotEndX), y2: top }),
    svgElement("text", { className: "chart-axis-label", x: 4, y: bottom + 4 }, "0°"),
    svgElement("text", { className: "chart-axis-label", x: 4, y: top + 4 }, signedDegrees(ceiling)),
    timeTicks.map(function (tick) {
      return svgElement("line", {
        className: "chart-tick",
        x1: round1(tick.x),
        y1: bottom,
        x2: round1(tick.x),
        y2: bottom + 5
      });
    }),
    timeTicks.map(function (tick) {
      return svgElement("text", {
        className: "chart-time-label is-" + tick.role,
        x: round1(tick.x),
        y: tick.y,
        textAnchor: tick.anchor
      }, formatHourTick(tick.at, timezone, countryCode));
    }),
    sunMarkers.map(function (point) {
      return sunMarker(point);
    }),
    visibleMarkers.map(function (point) {
      return altitudeMarker(point, markerImageUrl);
    })
  );
}

function visibleAltitudeMarkers(points, mode) {
  var ordinaryMinimumDistance = mode === "mobile" ? 13 : 18;
  var protectedMinimumDistance = mode === "mobile" ? 17 : 24;
  var lastSequence = points.length - 1;
  var protectedMarkers = points.filter(function (point) {
    return isProtectedAltitudeMarker(point, lastSequence);
  });
  var visible = [];
  var keptOrdinary = [];

  points.forEach(function (point) {
    if (isProtectedAltitudeMarker(point, lastSequence)) {
      visible.push(point);
      return;
    }
    if (isTooCloseToAny(point, protectedMarkers, protectedMinimumDistance)) {
      return;
    }
    if (isTooCloseToAny(point, keptOrdinary, ordinaryMinimumDistance)) {
      return;
    }
    visible.push(point);
    keptOrdinary.push(point);
  });

  return visible.sort(function (a, b) {
    return a.sequence - b.sequence;
  });
}

function isProtectedAltitudeMarker(point, lastSequence) {
  return point.role === "suggested" || point.sequence === 0 || point.sequence === lastSequence;
}

function isTooCloseToAny(point, others, minimumDistance) {
  return others.some(function (other) {
    return point !== other && markerDistance(point, other) < minimumDistance;
  });
}

function markerDistance(a, b) {
  var dx = round1(a.x) - round1(b.x);
  var dy = round1(a.y) - round1(b.y);
  return Math.sqrt((dx * dx) + (dy * dy));
}

function moonPathSamples(path) {
  var samples = Array.isArray(path.samples) ? path.samples : [path.start, path.suggested, path.end];
  return samples.filter(hasPosition).slice().sort(function (a, b) {
    return new Date(a.at).getTime() - new Date(b.at).getTime();
  });
}

function altitudeHourTicks(points, timezone, labelY) {
  var first = points[0];
  var last = points[points.length - 1];
  var span = Math.max(1, last.time - first.time);
  var chartWidth = Math.max(1, last.x - first.x);
  var minimumGap = 46;
  var cursor = firstLocalHourAtOrAfter(first.time, timezone);
  var ticks = [];

  while (cursor <= last.time) {
    var x = first.x + ((cursor - first.time) / span) * chartWidth;
    if (ticks.length === 0 || x - ticks[ticks.length - 1].x >= minimumGap) {
      ticks.push({
        at: new Date(cursor).toISOString(),
        x: x,
        anchor: tickTextAnchor(x, first.x, last.x),
        role: "hour",
        y: labelY
      });
    }
    cursor += 60 * 60 * 1000;
  }

  return ticks;
}

function azimuthRailLabels(points, left, chartWidth, mode) {
  var first = points[0];
  var last = points[points.length - 1];
  var span = Math.max(1, last.time - first.time);
  var count = mode === "mobile" ? 4 : 5;
  var labels = [];
  var previousText = "";

  for (var index = 0; index < count; index += 1) {
    var ratio = count === 1 ? 0 : index / (count - 1);
    var time = first.time + span * ratio;
    var text = compassDirection(interpolatedAzimuth(points, time));
    if (!text || (text === previousText && index > 0 && index < count - 1)) {
      continue;
    }
    var x = clamp(
      left + chartWidth * ratio,
      left + AZIMUTH_RAIL_LABEL_EDGE_INSET,
      left + chartWidth - AZIMUTH_RAIL_LABEL_EDGE_INSET
    );
    labels.push({
      text: text,
      x: x,
      anchor: tickTextAnchor(x, left, left + chartWidth)
    });
    previousText = text;
  }

  return labels;
}

function interpolatedAzimuth(points, time) {
  if (time <= points[0].time) {
    return points[0].azimuthDegrees;
  }
  for (var index = 0; index < points.length - 1; index += 1) {
    var current = points[index];
    var next = points[index + 1];
    if (time <= next.time) {
      var span = Math.max(1, next.time - current.time);
      var ratio = clamp((time - current.time) / span, 0, 1);
      return interpolateAngle(current.azimuthDegrees, next.azimuthDegrees, ratio);
    }
  }
  return points[points.length - 1].azimuthDegrees;
}

function interpolateAngle(start, end, ratio) {
  var delta = normalizeDegrees(end - start + 180) - 180;
  return normalizeDegrees(start + delta * ratio);
}

function firstLocalHourAtOrAfter(time, timezone) {
  var minuteMillis = 60 * 1000;
  var cursor = Math.ceil(time / minuteMillis) * minuteMillis;
  var searchLimit = cursor + (60 * minuteMillis);

  while (cursor <= searchLimit) {
    if (localMinuteOfHour(cursor, timezone) === 0) {
      return cursor;
    }
    cursor += minuteMillis;
  }

  return Math.ceil(time / (60 * minuteMillis)) * 60 * minuteMillis;
}

function tickTextAnchor(x, firstX, lastX) {
  if (x - firstX < 22) {
    return "start";
  }
  if (lastX - x < 22) {
    return "end";
  }
  return "middle";
}

function localMinuteOfHour(time, timezone) {
  try {
    var parts = new Intl.DateTimeFormat("en-US", {
      minute: "numeric",
      timeZone: timezone || "UTC"
    }).formatToParts(new Date(time));
    var minute = parts.find(function (part) {
      return part.type === "minute";
    });
    return minute ? Number(minute.value) : new Date(time).getUTCMinutes();
  } catch (error) {
    return new Date(time).getUTCMinutes();
  }
}

function altitudeMarker(point, imageUrl) {
  var suggested = point.role === "suggested";
  var best = point.markerLabel === "Best";
  var size = suggested ? (best ? 34 : 28) : 10.5;
  var ringRadius = size / 2 - 1;
  var className = "moon-sample-marker is-" + roleClass(point.role) + (best ? " is-best" : "");
  var title = suggested
    ? (point.markerLabel || "Suggested") + " Moon position, " + degrees(point.altitudeDegrees) + " altitude"
    : "Moon position sample, " + degrees(point.altitudeDegrees) + " altitude";

  return svgElement("g", {
    className: className,
    role: "img",
    ariaLabel: title,
    "data-sequence": point.sequence,
    "data-at": point.at,
    transform: "translate(" + round1(point.x) + " " + round1(point.y) + ")"
  },
    svgElement("title", {}, title),
    suggested && point.markerLabel
      ? svgElement("text", { className: "moon-sample-marker-label", x: 0, y: -24, textAnchor: "middle" }, point.markerLabel)
      : null,
    suggested ? svgElement("circle", { className: "moon-sample-marker-halo", cx: 0, cy: 0, r: best ? 20 : 17 }) : null,
    imageUrl
      ? svgElement("image", {
        className: "moon-sample-marker-image",
        href: imageUrl,
        x: -size / 2,
        y: -size / 2,
        width: size,
        height: size,
        preserveAspectRatio: "xMidYMid meet"
      })
      : svgElement("circle", { className: "moon-sample-dot is-" + roleClass(point.role), cx: 0, cy: 0, r: size / 2 }),
    suggested ? svgElement("circle", { className: "moon-sample-marker-ring", cx: 0, cy: 0, r: ringRadius }) : null
  );
}

function visibleSunMarkers(points, ceiling, bottom, chartHeight, mode) {
  var minimumDistance = mode === "mobile" ? 32 : 40;
  var visible = [];

  points.forEach(function (point) {
    if (!Number.isFinite(point.sunAltitudeDegrees)
      || !Number.isFinite(point.sunAzimuthDegrees)
      || point.sunAltitudeDegrees < 0) {
      return;
    }

    var marker = {
      at: point.at,
      sequence: point.sequence,
      x: point.x,
      y: sunMarkerY(point.sunAltitudeDegrees, ceiling, bottom, chartHeight),
      altitudeDegrees: point.sunAltitudeDegrees,
      azimuthDegrees: point.sunAzimuthDegrees
    };
    if (isTooCloseToAny(marker, visible, minimumDistance)) {
      return;
    }
    visible.push(marker);
  });

  return visible;
}

function sunMarkerY(sunAltitudeDegrees, ceiling, bottom, chartHeight) {
  return bottom - (clamp(sunAltitudeDegrees, 0, ceiling) / ceiling) * chartHeight;
}

function sunMarker(point) {
  var title = "Sun position sample, "
    + degrees(point.altitudeDegrees)
    + " altitude, "
    + degrees(point.azimuthDegrees)
    + " azimuth "
    + compassDirection(point.azimuthDegrees);

  return svgElement("g", {
    className: "sun-sample-marker",
    role: "img",
    ariaLabel: title,
    "data-sequence": point.sequence,
    "data-at": point.at,
    "data-sun-altitude-degrees": round1(point.altitudeDegrees),
    "data-sun-azimuth-degrees": round1(point.azimuthDegrees),
    "data-marker-resource": SUN_SAMPLE_MARKER_IMAGE_URL,
    transform: "translate(" + round1(point.x) + " " + round1(point.y) + ")"
  },
    svgElement("title", {}, title),
    svgElement("image", {
      className: "sun-sample-marker-image",
      href: SUN_SAMPLE_MARKER_IMAGE_URL,
      x: -SUN_SAMPLE_MARKER_SIZE / 2,
      y: -SUN_SAMPLE_MARKER_SIZE / 2,
      width: SUN_SAMPLE_MARKER_SIZE,
      height: SUN_SAMPLE_MARKER_SIZE,
      preserveAspectRatio: "xMidYMid meet"
    })
  );
}

function lightBandTitle(band, timezone, countryCode) {
  return (readableToken(band.lightBucket) || "Light bucket")
    + ", "
    + formatTime(band.startsAt, timezone, countryCode)
    + " to "
    + formatTime(band.endsAt, timezone, countryCode);
}

function signedDegrees(value) {
  if (!Number.isFinite(value)) {
    return "unavailable";
  }
  return (value > 0 ? "+" : "") + value + "°";
}

function compassDirection(value) {
  if (!Number.isFinite(value)) {
    return "";
  }
  var directions = ["N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE", "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW"];
  return directions[Math.round(normalizeDegrees(value) / 22.5) % directions.length];
}

function chartSamples(samples) {
  return samples.map(function (sample) {
    return {
      at: sample.at,
      time: new Date(sample.at).getTime(),
      altitudeDegrees: sample.altitudeDegrees,
      azimuthDegrees: sample.azimuthDegrees,
      sunAltitudeDegrees: sample.sunAltitudeDegrees,
      sunAzimuthDegrees: sample.sunAzimuthDegrees,
      lightBucket: sample.lightBucket,
      role: sample.role || "path",
      markerLabel: sample.markerLabel
    };
  }).filter(function (sample) {
    return Number.isFinite(sample.time)
      && Number.isFinite(sample.altitudeDegrees)
      && Number.isFinite(sample.azimuthDegrees);
  });
}

function hasPosition(point) {
  return point
    && point.at
    && Number.isFinite(point.altitudeDegrees)
    && Number.isFinite(point.azimuthDegrees);
}

function roleClass(role) {
  return String(role || "path").replace(/[^a-z0-9_-]/gi, "").toLowerCase() || "path";
}
