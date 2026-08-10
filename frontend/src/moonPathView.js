import { element, svgElement } from "./dom.js";
import {
  clamp,
  degrees,
  formatHourTick,
  formatTime,
  readableToken,
  round1
} from "./format.js";
import {
  azimuthMaskGaps,
  azimuthRailLabels,
  compassDirection
} from "./angularPreferenceControls.js";
import { lightBandSegments } from "./moonPathLightBands.js";
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";
import { altitudeForegroundArtwork } from "./moonPathSilhouettes.js";
import { rankedPlanningSkyDome } from "./skyDomeView.js";

var DESKTOP_ALTITUDE_WIDTH = 730;
var DESKTOP_PLOT_WIDTH = 672;
var MOBILE_ALTITUDE_WIDTH = 320;
var MOBILE_PLOT_WIDTH = 272;
var SUN_SAMPLE_MARKER_IMAGE_URL = "/sun-marker-aperture-flare.svg";
var SUN_BEST_MARKER_SIZE = 42;
var SUN_ALTERNATE_MARKER_SIZE = 28;
var SUN_PATH_MARKER_SIZE = 14;

export function moonPathPanel(opportunity, timezone, countryCode, chartContext) {
  return renderMoonPathPanel(
    opportunity,
    timezone,
    countryCode,
    chartContext,
    "Moon path",
    "Moon pass",
    true,
    function (samples) {
      return expandablePicture(
        "Sky dome",
        "Sun and Moon positions at the suggested time",
        rankedPlanningSkyDome(samples, timezone, countryCode, opportunity.moon || {}));
    },
    null);
}

export function renderMoonPathPanel(
  opportunity,
  timezone,
  countryCode,
  chartContext,
  heading,
  sunChartSubject,
  includeSunPass,
  skyDomeDetailsFor,
  footerContent
) {
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
  var passTimeDomain = timeDomainForSamples(samples);
  var sunSamples = sunPathSamples(samples);
  var sunPassDetails = !includeSunPass || sunSamples.length < 1
    ? null
    : expandablePicture(
      "Sun pass",
      "Sun altitude and direction across the same Moon pass",
      chartBlock("Sun altitude", sunAltitudeChart(
        sunSamples,
        timezone,
        countryCode,
        chartContext,
        sunChartSubject,
        passTimeDomain,
        samples)));
  var skyDomeDetails = skyDomeDetailsFor(samples);

  return element("section", { className: "moon-path-panel" },
    element("div", { className: "moon-path-header" },
      element("h4", {}, heading),
      element("p", {}, description)),
    summary,
    element("div", { className: "moon-path-charts" },
      chartBlock("Moon altitude", altitudeChart(
        samples,
        timezone,
        countryCode,
        chartContext,
        opportunity.moon || {},
        chartSubject,
        (opportunity.moonPass || {}).azimuthMatchIntervals))),
    element("div", { className: "sky-picture-list" },
      sunPassDetails,
      skyDomeDetails),
    footerContent);
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

export function expandablePicture(label, description, content) {
  if (!content) {
    return null;
  }
  return element("details", { className: "sky-picture-details" },
    element("summary", {},
      element("span", { className: "sky-picture-title" }, label),
      element("span", { className: "sky-picture-description" }, description)),
    element("div", { className: "sky-picture-content" }, content));
}

function chartBlock(label, chart) {
  if (!chart) {
    return null;
  }
  return element("div", { className: "moon-chart moon-chart-" + roleClass(label) },
    element("span", { className: "moon-chart-label" }, label),
    chart);
}

function altitudeChart(samples, timezone, countryCode, chartContext, moon, chartSubject, intervals) {
  return bodyAltitudeChart(samples, timezone, countryCode, chartContext, {
    body: "moon",
    subject: "Moon",
    chartSubject: chartSubject,
    moon: moon,
    azimuthMatchIntervals: intervals,
    includeForeground: true
  });
}

function sunAltitudeChart(samples, timezone, countryCode, chartContext, chartSubject, timeDomain, lightBandSamples) {
  return bodyAltitudeChart(samples, timezone, countryCode, chartContext, {
    body: "sun",
    subject: "Sun",
    chartSubject: chartSubject,
    timeDomain: timeDomain,
    lightBandSamples: lightBandSamples,
    azimuthSamples: sunAzimuthSamples(lightBandSamples),
    includeForeground: false
  });
}

function bodyAltitudeChart(samples, timezone, countryCode, chartContext, options) {
  var points = chartSamples(samples);
  var hasExternalTimeDomain = options.timeDomain
    && Number.isFinite(options.timeDomain.firstTime)
    && Number.isFinite(options.timeDomain.lastTime)
    && options.timeDomain.lastTime > options.timeDomain.firstTime;
  if (points.length < 2 && !(points.length === 1 && hasExternalTimeDomain)) {
    return null;
  }
  var lightBandPoints = Array.isArray(options.lightBandSamples)
    ? chartSamples(options.lightBandSamples)
    : points;
  if (lightBandPoints.length < 2) {
    lightBandPoints = points;
  }
  var azimuthPoints = Array.isArray(options.azimuthSamples)
    ? chartSamples(options.azimuthSamples)
    : points;
  if (azimuthPoints.length < 2) {
    azimuthPoints = points;
  }

  return element("div", { className: "moon-chart-scroll" },
    altitudeChartSvg(points, lightBandPoints, azimuthPoints, timezone, countryCode, "desktop", chartContext, options),
    altitudeChartSvg(points, lightBandPoints, azimuthPoints, timezone, countryCode, "mobile", chartContext, options));
}

function altitudeChartSvg(sourcePoints, lightBandSourcePoints, azimuthSourcePoints, timezone, countryCode, mode, chartContext, options) {
  var height = 390;
  var left = 34;
  var railTop = 20;
  var railHeight = 32;
  var top = 70;
  var bottom = 326;
  var firstTime = sourcePoints[0].time;
  var lastTime = sourcePoints[sourcePoints.length - 1].time;
  if (options.timeDomain
    && Number.isFinite(options.timeDomain.firstTime)
    && Number.isFinite(options.timeDomain.lastTime)) {
    firstTime = Math.min(firstTime, options.timeDomain.firstTime);
    lastTime = Math.max(lastTime, options.timeDomain.lastTime);
  }
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
  var lightBandPoints = lightBandSourcePoints === sourcePoints
    ? points
    : lightBandSourcePoints.map(function (sourcePoint) {
      return Object.assign({}, sourcePoint, {
        x: left + ((sourcePoint.time - firstTime) / timeSpan) * chartWidth
      });
    });
  var azimuthPoints = azimuthSourcePoints === sourcePoints
    ? points
    : azimuthSourcePoints.map(function (sourcePoint) {
      return Object.assign({}, sourcePoint, {
        x: left + ((sourcePoint.time - firstTime) / timeSpan) * chartWidth
      });
    });

  var bands = lightBandSegments(lightBandPoints);
  var timeTicks = altitudeHourTicks(firstTime, lastTime, left, chartWidth, timezone, bottom + 29);
  var azimuthLabels = azimuthRailLabels(azimuthPoints, mode);
  var visibleMarkers = visibleAltitudeMarkers(points, mode, options.body);
  var maskGaps = options.body === "moon"
    ? azimuthMaskGaps(options.azimuthMatchIntervals, firstTime, lastTime)
    : null;
  var maskRects = (maskGaps || []).map(function (gap) {
    return {
      start: gap.start,
      end: gap.end,
      x: left + ((gap.start - firstTime) / timeSpan) * chartWidth,
      width: ((gap.end - gap.start) / timeSpan) * chartWidth
    };
  });
  var markerImageUrl = options.body === "moon"
    ? moonPhaseImageDataUrl(
      (options.moon || {}).phaseAngleDegrees,
      64,
      (options.moon || {}).brightLimbTiltDegrees,
      (options.moon || {}).northPoleTiltDegrees)
    : SUN_SAMPLE_MARKER_IMAGE_URL;

  return svgElement("svg", {
    className: "altitude-chart altitude-chart-" + mode + " " + roleClass(options.subject) + "-altitude-chart",
    viewBox: "0 0 " + width + " " + height,
    role: "img",
    ariaLabel: (mode === "mobile"
      ? options.subject + " altitude and azimuth across the " + options.chartSubject + "; chart fits the card width"
      : options.subject + " altitude and azimuth across the " + options.chartSubject + "; chart fills the card width")
        + (maskGaps === null ? "" : "; dimmed portions fall outside the Moon-direction preference")
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
      return azimuthRailLabel(label, railTop + 17);
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
    options.includeForeground
      ? altitudeForegroundArtwork(left, top, bottom, chartWidth, mode, firstTime, ceiling, chartHeight)
      : null,
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
    visibleMarkers.map(function (point) {
      return bodyAltitudeMarker(point, markerImageUrl, options.body);
    }),
    maskRects.map(function (rect) {
      return svgElement("rect", {
        className: "azimuth-preference-excluded",
        x: round1(rect.x),
        y: top,
        width: round1(rect.width),
        height: chartHeight,
        "data-start-at": new Date(rect.start).toISOString(),
        "data-end-at": new Date(rect.end).toISOString()
      },
        svgElement("title", {}, "Outside the selected Moon-direction preference"));
    })
  );
}

function visibleAltitudeMarkers(points, mode, body) {
  if (body === "sun") {
    return visibleSunAltitudeMarkers(points);
  }

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

function visibleSunAltitudeMarkers(points) {
  var suggestedMarkers = points.filter(function (point) {
    return point.role === "suggested";
  });
  var visible = suggestedMarkers.slice();

  points.forEach(function (point) {
    if (point.role === "suggested" || isTooCloseToAnySunMarker(point, visible)) {
      return;
    }
    visible.push(point);
  });

  return visible.sort(function (a, b) {
    return a.sequence - b.sequence;
  });
}

function isTooCloseToAnySunMarker(point, others) {
  return others.some(function (other) {
    var minimumDistance = (sunAltitudeMarkerSize(point) + sunAltitudeMarkerSize(other)) / 2;
    return point !== other && markerDistance(point, other) < minimumDistance;
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

function sunPathSamples(samples) {
  return samples.filter(function (sample) {
    return hasSunPathPosition(sample) && sample.sunAltitudeDegrees >= 0;
  }).map(sunPathSample);
}

function sunAzimuthSamples(samples) {
  return (Array.isArray(samples) ? samples : []).filter(hasSunPathPosition).map(sunPathSample);
}

function sunPathSample(sample) {
  return {
    at: sample.at,
    altitudeDegrees: sample.sunAltitudeDegrees,
    azimuthDegrees: sample.sunAzimuthDegrees,
    sunAltitudeDegrees: sample.sunAltitudeDegrees,
    sunAzimuthDegrees: sample.sunAzimuthDegrees,
    lightBucket: sample.lightBucket,
    role: sample.role,
    markerLabel: sample.markerLabel
  };
}

function moonPathSamples(path) {
  var samples = Array.isArray(path.samples) ? path.samples : [path.start, path.suggested, path.end];
  return samples.filter(hasPosition).slice().sort(function (a, b) {
    return new Date(a.at).getTime() - new Date(b.at).getTime();
  });
}

function timeDomainForSamples(samples) {
  var times = samples.map(function (sample) {
    return new Date(sample.at).getTime();
  }).filter(Number.isFinite);
  if (times.length < 2) {
    return null;
  }
  return {
    firstTime: Math.min.apply(null, times),
    lastTime: Math.max.apply(null, times)
  };
}

function altitudeHourTicks(firstTime, lastTime, left, chartWidth, timezone, labelY) {
  var span = Math.max(1, lastTime - firstTime);
  var minimumGap = 46;
  var cursor = firstLocalHourAtOrAfter(firstTime, timezone);
  var ticks = [];

  while (cursor <= lastTime) {
    var x = left + ((cursor - firstTime) / span) * chartWidth;
    if (ticks.length === 0 || x - ticks[ticks.length - 1].x >= minimumGap) {
      ticks.push({
        at: new Date(cursor).toISOString(),
        x: x,
        anchor: tickTextAnchor(x, left, left + chartWidth),
        role: "hour",
        y: labelY
      });
    }
    cursor += 60 * 60 * 1000;
  }

  return ticks;
}

function azimuthRailLabel(label, y) {
  var arrowX = -12;
  var textX = 2;
  var textAnchor = "start";
  if (label.anchor === "start") {
    arrowX = 0;
    textX = 14;
  } else if (label.anchor === "end") {
    arrowX = 0;
    textX = -14;
    textAnchor = "end";
  }

  return svgElement("g", {
    className: "azimuth-rail-label",
    transform: "translate(" + round1(label.x) + " " + y + ")"
  },
    svgElement("g", {
      className: "azimuth-rail-arrow",
      transform: "translate(" + arrowX + " -1) rotate(" + round1(label.azimuthDegrees) + ")"
    },
      svgElement("polygon", { points: "0,-8 5,6 0,3 -5,6" })),
    svgElement("text", {
      x: textX,
      y: 4,
      textAnchor: textAnchor
    }, label.text));
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
  var size = suggested ? (best ? 34 : 22) : 10.5;
  var ringRadius = size / 2 - 1;
  var haloRadius = best ? 20 : 13;
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
    suggested ? svgElement("circle", { className: "moon-sample-marker-halo", cx: 0, cy: 0, r: haloRadius }) : null,
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

function bodyAltitudeMarker(point, imageUrl, body) {
  if (body === "sun") {
    return sunAltitudeMarker(point);
  }
  return altitudeMarker(point, moonAltitudeMarkerImageUrl(point, imageUrl));
}

function moonAltitudeMarkerImageUrl(point, fallbackImageUrl) {
  if (!Number.isFinite(point.moonPhaseAngleDegrees)) {
    return fallbackImageUrl;
  }
  return moonPhaseImageDataUrl(
    point.moonPhaseAngleDegrees,
    64,
    point.brightLimbTiltDegrees,
    point.northPoleTiltDegrees) || fallbackImageUrl;
}

function sunAltitudeMarker(point) {
  var suggested = point.role === "suggested";
  var best = point.markerLabel === "Best";
  var size = sunAltitudeMarkerSize(point);
  var className = "sun-path-marker sun-sample-marker is-" + roleClass(point.role) + (best ? " is-best" : "");
  var positionText = degrees(point.altitudeDegrees)
    + " altitude, "
    + degrees(point.azimuthDegrees)
    + " azimuth "
    + compassDirection(point.azimuthDegrees);
  var title = suggested
    ? (point.markerLabel || "Suggested") + " Sun position, " + positionText
    : "Sun position sample, " + positionText;

  return svgElement("g", {
    className: className,
    role: "img",
    ariaLabel: title,
    "data-sequence": point.sequence,
    "data-at": point.at,
    "data-sun-altitude-degrees": round1(point.altitudeDegrees),
    "data-sun-azimuth-degrees": round1(point.azimuthDegrees),
    "data-marker-resource": SUN_SAMPLE_MARKER_IMAGE_URL,
    "data-marker-size": size,
    transform: "translate(" + round1(point.x) + " " + round1(point.y) + ")"
  },
    svgElement("title", {}, title),
    suggested && point.markerLabel
      ? svgElement("text", { className: "sun-path-marker-label", x: 0, y: -24, textAnchor: "middle" }, point.markerLabel)
      : null,
    svgElement("image", {
      className: "sun-sample-marker-image",
      href: SUN_SAMPLE_MARKER_IMAGE_URL,
      x: -size / 2,
      y: -size / 2,
      width: size,
      height: size,
      preserveAspectRatio: "xMidYMid meet"
    })
  );
}

function sunAltitudeMarkerSize(point) {
  if (point.markerLabel === "Best") {
    return SUN_BEST_MARKER_SIZE;
  }
  return point.role === "suggested" ? SUN_ALTERNATE_MARKER_SIZE : SUN_PATH_MARKER_SIZE;
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

function chartSamples(samples) {
  return samples.map(function (sample) {
    var isNow = sample.role === "now";
    return {
      at: sample.at,
      time: new Date(sample.at).getTime(),
      altitudeDegrees: sample.altitudeDegrees,
      azimuthDegrees: sample.azimuthDegrees,
      sunAltitudeDegrees: sample.sunAltitudeDegrees,
      sunAzimuthDegrees: sample.sunAzimuthDegrees,
      lightBucket: sample.lightBucket,
      role: isNow ? "suggested" : sample.role || "path",
      markerLabel: isNow ? "Now" : sample.markerLabel,
      moonPhaseAngleDegrees: sample.moonPhaseAngleDegrees,
      brightLimbTiltDegrees: sample.brightLimbTiltDegrees,
      northPoleTiltDegrees: sample.northPoleTiltDegrees
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

function hasSunPathPosition(point) {
  return point
    && point.at
    && Number.isFinite(point.sunAltitudeDegrees)
    && Number.isFinite(point.sunAzimuthDegrees);
}

function roleClass(role) {
  return String(role || "path").replace(/[^a-z0-9_-]/gi, "").toLowerCase() || "path";
}
