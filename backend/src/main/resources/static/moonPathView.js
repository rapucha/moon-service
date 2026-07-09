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
var SUN_BEST_MARKER_SIZE = 42;
var SUN_ALTERNATE_MARKER_SIZE = 28;
var SUN_PATH_MARKER_SIZE = 14;

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
  var passTimeDomain = timeDomainForSamples(samples);
  var sunSamples = sunPathSamples(samples);
  var sunPassDetails = sunSamples.length < 1
    ? null
    : expandablePicture(
      "Sun pass",
      "Sun altitude and direction across the same Moon pass",
      chartBlock("Sun altitude", sunAltitudeChart(
        sunSamples,
        timezone,
        countryCode,
        chartContext,
        "Moon pass",
        passTimeDomain,
        samples)));
  var skyDomeDetails = expandablePicture(
    "Sky dome",
    "Sun and Moon positions at the suggested time",
    skyDomeChart(samples, timezone, countryCode, opportunity.moon || {}));

  return element("section", { className: "moon-path-panel" },
    element("div", { className: "moon-path-header" },
      element("h4", {}, "Moon path"),
      element("p", {}, description)),
    summary,
    element("div", { className: "moon-path-charts" },
      chartBlock("Moon altitude", altitudeChart(samples, timezone, countryCode, chartContext, opportunity.moon || {}, chartSubject))),
    element("div", { className: "sky-picture-list" },
      sunPassDetails,
      skyDomeDetails));
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

function expandablePicture(label, description, content) {
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

function altitudeChart(samples, timezone, countryCode, chartContext, moon, chartSubject) {
  return bodyAltitudeChart(samples, timezone, countryCode, chartContext, {
    body: "moon",
    subject: "Moon",
    chartSubject: chartSubject,
    moon: moon,
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
  var markerImageUrl = options.body === "moon"
    ? moonPhaseImageDataUrl((options.moon || {}).phaseAngleDegrees, 64)
    : SUN_SAMPLE_MARKER_IMAGE_URL;

  return svgElement("svg", {
    className: "altitude-chart altitude-chart-" + mode + " " + roleClass(options.subject) + "-altitude-chart",
    viewBox: "0 0 " + width + " " + height,
    role: "img",
    ariaLabel: mode === "mobile"
      ? options.subject + " altitude and azimuth across the " + options.chartSubject + "; chart fits the card width"
      : options.subject + " altitude and azimuth across the " + options.chartSubject + "; chart fills the card width"
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

function azimuthRailLabels(points, mode) {
  var first = points[0];
  var last = points[points.length - 1];
  var span = Math.max(1, last.time - first.time);
  var left = first.x;
  var chartWidth = Math.max(1, last.x - first.x);
  var edgeInset = Math.min(AZIMUTH_RAIL_LABEL_EDGE_INSET, chartWidth / 2);
  var count = mode === "mobile" ? 4 : 5;
  var labels = [];
  var previousText = "";

  for (var index = 0; index < count; index += 1) {
    var ratio = count === 1 ? 0 : index / (count - 1);
    var time = first.time + span * ratio;
    var azimuth = interpolatedAzimuth(points, time);
    var text = compassDirection(azimuth);
    if (!text || (text === previousText && index > 0 && index < count - 1)) {
      continue;
    }
    var x = clamp(
      left + chartWidth * ratio,
      left + edgeInset,
      left + chartWidth - edgeInset
    );
    labels.push({
      text: text,
      azimuthDegrees: azimuth,
      x: x,
      anchor: tickTextAnchor(x, left, left + chartWidth)
    });
    previousText = text;
  }

  return labels;
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
  return altitudeMarker(point, imageUrl);
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

function skyDomeChart(samples, timezone, countryCode, moon) {
  var points = skyDomeSamples(samples);
  if (points.length < 1) {
    return null;
  }

  var selected = selectedSkyPoint(points);
  if (selected.sunAltitudeDegrees < 0) {
    return null;
  }

  var projection = skyProjection();
  var selectedMoon = Object.assign(
    {
      altitudeDegrees: selected.moonAltitudeDegrees,
      azimuthDegrees: selected.moonAzimuthDegrees
    },
    projection(selected.moonAltitudeDegrees, selected.moonAzimuthDegrees));
  var selectedSun = Object.assign(
    {
      altitudeDegrees: selected.sunAltitudeDegrees,
      azimuthDegrees: selected.sunAzimuthDegrees
    },
    projection(selected.sunAltitudeDegrees, selected.sunAzimuthDegrees));
  var moonPlanePoint = { x: selectedMoon.planeX, y: selectedMoon.planeY };
  var sunPlanePoint = { x: selectedSun.planeX, y: selectedSun.planeY };
  var moonAzimuthPoint = { x: selectedMoon.horizonX, y: selectedMoon.horizonY };
  var sunAzimuthPoint = { x: selectedSun.horizonX, y: selectedSun.horizonY };
  var observer = { x: 210, y: 226 };
  var separation = angularSeparationDegrees(
    selected.moonAltitudeDegrees,
    selected.moonAzimuthDegrees,
    selected.sunAltitudeDegrees,
    selected.sunAzimuthDegrees);
  var separationArcRadius = skySeparationArcRadius(observer, selectedSun, selectedMoon);
  var separationArc = angleArcPath(observer, selectedSun, selectedMoon, separationArcRadius);
  var moonImageUrl = moonPhaseImageDataUrl(moon.phaseAngleDegrees, 64);
  var selectedTime = formatTime(selected.at, timezone, countryCode);
  var accessibleLabel = "Sun and Moon sky position at " + selectedTime
    + "; Sun " + degrees(selected.sunAltitudeDegrees) + " altitude, "
    + degrees(selected.sunAzimuthDegrees) + " azimuth " + compassDirection(selected.sunAzimuthDegrees)
    + "; Moon " + degrees(selected.moonAltitudeDegrees) + " altitude, "
    + degrees(selected.moonAzimuthDegrees) + " azimuth " + compassDirection(selected.moonAzimuthDegrees)
    + "; " + degrees(separation) + " angular separation";

  return element("div", { className: "sky-dome-frame" },
    svgElement("svg", {
      className: "sky-dome-chart",
      viewBox: "0 0 420 280",
      role: "img",
      ariaLabel: accessibleLabel
    },
      svgElement("rect", { className: "sky-dome-background", x: 0, y: 0, width: 420, height: 280, rx: 8 }),
      svgElement("path", { className: "sky-dome-shell", d: "M 48 226 C 75 119, 126 58, 210 48 C 294 58, 345 119, 372 226 Z" }),
      svgElement("ellipse", { className: "sky-dome-horizon", cx: 210, cy: 226, rx: 162, ry: 25 }),
      svgElement("path", { className: "sky-dome-ring", d: "M 80 202 C 135 150, 285 150, 340 202" }),
      svgElement("path", { className: "sky-dome-ring", d: "M 116 164 C 156 122, 264 122, 304 164" }),
      svgElement("path", { className: "sky-dome-ring", d: "M 154 123 C 179 99, 241 99, 266 123" }),
      svgElement("path", {
        className: "sky-dome-meridian is-grid-a",
        "data-start-azimuth": 142,
        "data-end-azimuth": 322,
        d: skyMeridianPath(projection, 142)
      }),
      svgElement("path", {
        className: "sky-dome-meridian is-grid-b",
        "data-start-azimuth": 232,
        "data-end-azimuth": 52,
        d: skyMeridianPath(projection, 232)
      }),
      skyCardinalMarker("N", 210, 192, 0, 0, -12, "middle"),
      skyCardinalMarker("E", 381, 226, 90, 12, 4, "start"),
      skyCardinalMarker("S", 210, 260, 180, 0, 18, "middle"),
      skyCardinalMarker("W", 39, 226, 270, -12, 4, "end"),
      svgElement("line", { className: "sky-separation-ray is-sun", x1: observer.x, y1: observer.y, x2: round1(selectedSun.x), y2: round1(selectedSun.y) }),
      svgElement("line", { className: "sky-separation-ray is-moon", x1: observer.x, y1: observer.y, x2: round1(selectedMoon.x), y2: round1(selectedMoon.y) }),
      svgElement("path", {
        className: "sky-separation-arc",
        d: separationArc,
        "data-radius": round1(separationArcRadius)
      }),
      svgElement("text", {
        className: "sky-separation-label",
        x: 24,
        y: 30
      }, degrees(separation) + " separation"),
      skySeparationLabelArrows(observer, selectedSun, selectedMoon, moonImageUrl),
      skyAzimuthProjection(observer, selectedSun, sunPlanePoint, sunAzimuthPoint, "sun", "Sun azimuth direction on the horizon"),
      skyAzimuthProjection(observer, selectedMoon, moonPlanePoint, moonAzimuthPoint, "moon", "Moon azimuth direction on the horizon"),
      svgElement("circle", { className: "sky-observer-dot", cx: observer.x, cy: observer.y, r: 3.5 }),
      skyBodyImage(
        selectedSun,
        SUN_SAMPLE_MARKER_IMAGE_URL,
        42,
        "sun",
        "Sun, " + degrees(selected.sunAltitudeDegrees) + " altitude, " + degrees(selected.sunAzimuthDegrees) + " azimuth"),
      skyBodyImage(
        selectedMoon,
        moonImageUrl,
        28,
        "moon",
        "Moon, " + degrees(selected.moonAltitudeDegrees) + " altitude, " + degrees(selected.moonAzimuthDegrees) + " azimuth"),
      svgElement("text", { className: "sky-dome-label", x: 24, y: 52 }, selectedTime)
    ));
}

function skyDomeSamples(samples) {
  return samples.filter(function (sample) {
    return hasPosition(sample) && hasSunPathPosition(sample);
  }).map(function (sample) {
    return {
      at: sample.at,
      time: new Date(sample.at).getTime(),
      moonAltitudeDegrees: sample.altitudeDegrees,
      moonAzimuthDegrees: sample.azimuthDegrees,
      sunAltitudeDegrees: sample.sunAltitudeDegrees,
      sunAzimuthDegrees: sample.sunAzimuthDegrees,
      role: sample.role,
      markerLabel: sample.markerLabel
    };
  }).filter(function (sample) {
    return Number.isFinite(sample.time);
  });
}

function selectedSkyPoint(points) {
  return points.find(function (point) {
    return point.role === "suggested" && point.markerLabel === "Best";
  }) || points.find(function (point) {
    return point.role === "suggested";
  }) || points[Math.floor(points.length / 2)];
}

function skyProjection() {
  var centerX = 210;
  var horizonY = 226;
  var radiusX = 162;
  var radiusY = 25;
  var zenithY = 48;

  return function (altitudeDegrees, azimuthDegrees) {
    var altitudeRatio = clamp(altitudeDegrees, 0, 90) / 90;
    var azimuthRadians = toRadians(normalizeDegrees(azimuthDegrees));
    var horizonX = centerX + Math.sin(azimuthRadians) * radiusX;
    var horizonPointY = horizonY - Math.cos(azimuthRadians) * radiusY;
    var radialRatio = 1 - altitudeRatio;
    var planeX = centerX + (horizonX - centerX) * radialRatio;
    var planeY = horizonY + (horizonPointY - horizonY) * radialRatio;
    return {
      x: planeX,
      y: planeY + (zenithY - horizonY) * altitudeRatio,
      planeX: planeX,
      planeY: planeY,
      horizonX: horizonX,
      horizonY: horizonPointY
    };
  };
}

function skyAzimuthProjection(origin, body, planePoint, horizonPoint, role, title) {
  return svgElement("g", {
    className: "sky-azimuth-projection is-" + role,
    role: "img",
    ariaLabel: title
  },
    svgElement("title", {}, title),
    svgElement("line", {
      className: "sky-azimuth-projection-guide",
      x1: round1(body.x),
      y1: round1(body.y),
      x2: round1(planePoint.x),
      y2: round1(planePoint.y)
    }),
    svgElement("line", {
      className: "sky-azimuth-projection-line",
      x1: round1(origin.x),
      y1: round1(origin.y),
      x2: round1(horizonPoint.x),
      y2: round1(horizonPoint.y)
    }),
    svgElement("polygon", {
      className: "sky-azimuth-projection-arrow",
      points: arrowHeadPoints(origin, horizonPoint, 7)
    }));
}

function skyMeridianPath(projection, startAzimuthDegrees) {
  var start = projection(0, startAzimuthDegrees);
  var zenith = projection(90, startAzimuthDegrees);
  var end = projection(0, startAzimuthDegrees + 180);
  var startSide = start.x < zenith.x ? -1 : 1;
  var endSide = end.x < zenith.x ? -1 : 1;
  var startOuterControl = {
    x: start.x + startSide * 10,
    y: start.y + (zenith.y - start.y) * 0.35
  };
  var startZenithControl = { x: zenith.x + startSide * 40, y: zenith.y };
  var endZenithControl = { x: zenith.x + endSide * 40, y: zenith.y };
  var endOuterControl = {
    x: end.x + endSide * 10,
    y: end.y + (zenith.y - end.y) * 0.35
  };
  return "M " + round1(start.x) + " " + round1(start.y)
    + " C " + round1(startOuterControl.x) + " " + round1(startOuterControl.y)
    + " " + round1(startZenithControl.x) + " " + round1(startZenithControl.y)
    + " " + round1(zenith.x) + " " + round1(zenith.y)
    + " C " + round1(endZenithControl.x) + " " + round1(endZenithControl.y)
    + " " + round1(endOuterControl.x) + " " + round1(endOuterControl.y)
    + " " + round1(end.x) + " " + round1(end.y);
}

function skyCardinalMarker(label, x, y, azimuthDegrees, textX, textY, textAnchor) {
  return svgElement("g", {
    className: "sky-cardinal-marker is-" + label.toLowerCase(),
    transform: "translate(" + x + " " + y + ")"
  },
    svgElement("g", {
      className: "sky-cardinal-arrow",
      transform: "rotate(" + azimuthDegrees + ")"
    },
      svgElement("polygon", { points: "0,-8 5,6 0,3 -5,6" })),
    svgElement("text", {
      className: "sky-cardinal-label",
      x: textX,
      y: textY,
      textAnchor: textAnchor
    }, label));
}

function skyBodyImage(point, imageUrl, size, role, title) {
  return svgElement("g", {
    className: "sky-body is-" + role,
    role: "img",
    ariaLabel: title,
    "data-altitude-degrees": round1(point.altitudeDegrees),
    "data-azimuth-degrees": round1(point.azimuthDegrees),
    transform: "translate(" + round1(point.x) + " " + round1(point.y) + ")"
  },
    svgElement("title", {}, title),
    svgElement("image", {
      className: "sky-body-image",
      href: imageUrl,
      x: -size / 2,
      y: -size / 2,
      width: size,
      height: size,
      preserveAspectRatio: "xMidYMid meet"
    }));
}

function arrowHeadPoints(start, end, size) {
  var dx = end.x - start.x;
  var dy = end.y - start.y;
  var length = Math.sqrt((dx * dx) + (dy * dy));
  var unitX = length < 1 ? 0 : dx / length;
  var unitY = length < 1 ? 1 : dy / length;
  var baseX = end.x - unitX * size;
  var baseY = end.y - unitY * size;
  var sideX = -unitY * size * 0.55;
  var sideY = unitX * size * 0.55;
  return round1(end.x) + "," + round1(end.y)
    + " " + round1(baseX + sideX) + "," + round1(baseY + sideY)
    + " " + round1(baseX - sideX) + "," + round1(baseY - sideY);
}

function skySeparationLabelArrows(sourceOrigin, sunPoint, moonPoint, moonImageUrl) {
  var labelOrigin = { x: 152, y: 30 };
  var sunEnd = compactVectorEnd(sourceOrigin, sunPoint, labelOrigin, 13);
  var moonEnd = compactVectorEnd(sourceOrigin, moonPoint, labelOrigin, 13);
  var sunBodyCenter = compactVectorEnd(sourceOrigin, sunPoint, labelOrigin, 19);
  var moonBodyCenter = compactVectorEnd(sourceOrigin, moonPoint, labelOrigin, 19);
  var arcRadius = 7;
  return svgElement("g", {
    className: "sky-separation-label-arrows",
    "aria-hidden": "true"
  },
    svgElement("path", {
      className: "sky-separation-label-arc",
      d: angleArcPath(labelOrigin, sunEnd, moonEnd, arcRadius),
      "data-radius": arcRadius
    }),
    skySeparationLabelArrow(labelOrigin, sunEnd, "sun"),
    skySeparationLabelArrow(labelOrigin, moonEnd, "moon"),
    skySeparationLabelBody(sunBodyCenter, SUN_SAMPLE_MARKER_IMAGE_URL, 9, "sun"),
    skySeparationLabelBody(moonBodyCenter, moonImageUrl, 8, "moon"));
}

function compactVectorEnd(sourceOrigin, sourcePoint, targetOrigin, targetLength) {
  var sourceDx = sourcePoint.x - sourceOrigin.x;
  var sourceDy = sourcePoint.y - sourceOrigin.y;
  var sourceLength = Math.max(1, Math.hypot(sourceDx, sourceDy));
  return {
    x: targetOrigin.x + (sourceDx / sourceLength) * targetLength,
    y: targetOrigin.y + (sourceDy / sourceLength) * targetLength
  };
}

function skySeparationLabelArrow(labelOrigin, end, role) {
  return svgElement("g", { className: "sky-separation-label-arrow is-" + role },
    svgElement("line", {
      className: "sky-separation-label-arrow-line",
      x1: labelOrigin.x,
      y1: labelOrigin.y,
      x2: round1(end.x),
      y2: round1(end.y)
    }),
    svgElement("polygon", {
      className: "sky-separation-label-arrow-head",
      points: arrowHeadPoints(labelOrigin, end, 4)
    }));
}

function skySeparationLabelBody(center, imageUrl, size, role) {
  return svgElement("image", {
    className: "sky-separation-label-body is-" + role,
    href: imageUrl,
    x: round1(center.x - size / 2),
    y: round1(center.y - size / 2),
    width: size,
    height: size,
    preserveAspectRatio: "xMidYMid meet"
  });
}

function skySeparationArcRadius(origin, firstPoint, secondPoint) {
  var firstDistance = Math.hypot(firstPoint.x - origin.x, firstPoint.y - origin.y);
  var secondDistance = Math.hypot(secondPoint.x - origin.x, secondPoint.y - origin.y);
  return clamp(Math.min(firstDistance, secondDistance) * 0.68, 58, 104);
}

function angleArcPath(origin, firstPoint, secondPoint, radius) {
  var firstAngle = Math.atan2(firstPoint.y - origin.y, firstPoint.x - origin.x);
  var secondAngle = Math.atan2(secondPoint.y - origin.y, secondPoint.x - origin.x);
  var delta = normalizeRadians(secondAngle - firstAngle);
  var start = pointAtAngle(origin, firstAngle, radius);
  var end = pointAtAngle(origin, firstAngle + delta, radius);
  return "M " + round1(start.x) + " " + round1(start.y)
    + " A " + radius + " " + radius + " 0 0 " + (delta >= 0 ? 1 : 0) + " "
    + round1(end.x) + " " + round1(end.y);
}

function pointAtAngle(origin, angle, radius) {
  return {
    x: origin.x + Math.cos(angle) * radius,
    y: origin.y + Math.sin(angle) * radius
  };
}

function shortestAngleDelta(startDegrees, endDegrees) {
  return normalizeDegrees(endDegrees - startDegrees + 180) - 180;
}

function angularSeparationDegrees(firstAltitude, firstAzimuth, secondAltitude, secondAzimuth) {
  var firstAltitudeRadians = toRadians(firstAltitude);
  var secondAltitudeRadians = toRadians(secondAltitude);
  var azimuthDeltaRadians = toRadians(shortestAngleDelta(firstAzimuth, secondAzimuth));
  var cosine = Math.sin(firstAltitudeRadians) * Math.sin(secondAltitudeRadians)
    + Math.cos(firstAltitudeRadians) * Math.cos(secondAltitudeRadians) * Math.cos(azimuthDeltaRadians);
  return toDegrees(Math.acos(clamp(cosine, -1, 1)));
}

function normalizeRadians(value) {
  while (value <= -Math.PI) {
    value += Math.PI * 2;
  }
  while (value > Math.PI) {
    value -= Math.PI * 2;
  }
  return value;
}

function toRadians(value) {
  return value * Math.PI / 180;
}

function toDegrees(value) {
  return value * 180 / Math.PI;
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

function hasSunPathPosition(point) {
  return point
    && point.at
    && Number.isFinite(point.sunAltitudeDegrees)
    && Number.isFinite(point.sunAzimuthDegrees);
}

function roleClass(role) {
  return String(role || "path").replace(/[^a-z0-9_-]/gi, "").toLowerCase() || "path";
}
