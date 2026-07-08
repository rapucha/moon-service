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
  var sunPassDetails = sunSamples.length < 2
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
  var skyDomeDetails = hasSkyDomeSamples(samples)
    ? expandablePicture(
      "Sky dome",
      "Sun and Moon positions at the suggested time",
      skyDomeChart(samples, timezone, countryCode))
    : null;

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
    includeForeground: false
  });
}

function bodyAltitudeChart(samples, timezone, countryCode, chartContext, options) {
  var points = chartSamples(samples);
  if (points.length < 2) {
    return null;
  }
  var lightBandPoints = Array.isArray(options.lightBandSamples)
    ? chartSamples(options.lightBandSamples)
    : points;
  if (lightBandPoints.length < 2) {
    lightBandPoints = points;
  }

  return element("div", { className: "moon-chart-scroll" },
    altitudeChartSvg(points, lightBandPoints, timezone, countryCode, "desktop", chartContext, options),
    altitudeChartSvg(points, lightBandPoints, timezone, countryCode, "mobile", chartContext, options));
}

function altitudeChartSvg(sourcePoints, lightBandSourcePoints, timezone, countryCode, mode, chartContext, options) {
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

  var bands = lightBandSegments(lightBandPoints);
  var timeTicks = altitudeHourTicks(firstTime, lastTime, left, chartWidth, timezone, bottom + 29);
  var azimuthLabels = azimuthRailLabels(points, mode);
  var visibleMarkers = visibleAltitudeMarkers(points, mode);
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

function sunPathSamples(samples) {
  return samples.filter(function (sample) {
    return hasSunPathPosition(sample) && sample.sunAltitudeDegrees >= 0;
  }).map(function (sample) {
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
  });
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
  var size = best
    ? SUN_BEST_MARKER_SIZE
    : (suggested ? SUN_ALTERNATE_MARKER_SIZE : SUN_PATH_MARKER_SIZE);
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

function skyDomeChart(samples, timezone, countryCode) {
  var points = skyDomeSamples(samples);
  if (points.length < 1) {
    return null;
  }

  var selected = selectedSkyPoint(points);
  var centerAzimuth = angularMidpoint(selected.moonAzimuthDegrees, selected.sunAzimuthDegrees);
  var projection = skyProjection(centerAzimuth);
  var moonTrack = points.map(function (point) {
    return Object.assign({}, point, projection(point.moonAltitudeDegrees, point.moonAzimuthDegrees));
  });
  var sunTrack = points.map(function (point) {
    return Object.assign({}, point, projection(point.sunAltitudeDegrees, point.sunAzimuthDegrees));
  });
  var selectedMoon = projection(selected.moonAltitudeDegrees, selected.moonAzimuthDegrees);
  var selectedSun = projection(selected.sunAltitudeDegrees, selected.sunAzimuthDegrees);
  var observer = { x: 210, y: 232 };
  var separation = angularSeparationDegrees(
    selected.moonAltitudeDegrees,
    selected.moonAzimuthDegrees,
    selected.sunAltitudeDegrees,
    selected.sunAzimuthDegrees);

  return element("div", { className: "sky-dome-frame" },
    svgElement("svg", {
      className: "sky-dome-chart",
      viewBox: "0 0 420 280",
      role: "img",
      ariaLabel: "Quasi-dome sky position chart for the Sun and Moon at " + formatTime(selected.at, timezone, countryCode)
    },
      svgElement("rect", { className: "sky-dome-background", x: 0, y: 0, width: 420, height: 280, rx: 8 }),
      svgElement("path", { className: "sky-dome-shell", d: "M 48 226 C 75 119, 126 58, 210 48 C 294 58, 345 119, 372 226 Z" }),
      svgElement("ellipse", { className: "sky-dome-horizon", cx: 210, cy: 226, rx: 162, ry: 25 }),
      svgElement("path", { className: "sky-dome-ring", d: "M 80 202 C 135 150, 285 150, 340 202" }),
      svgElement("path", { className: "sky-dome-ring", d: "M 116 164 C 156 122, 264 122, 304 164" }),
      svgElement("path", { className: "sky-dome-ring", d: "M 154 123 C 179 99, 241 99, 266 123" }),
      svgElement("path", { className: "sky-dome-meridian", d: "M 210 48 C 199 107, 198 174, 210 226" }),
      svgElement("path", { className: "sky-dome-meridian", d: "M 48 226 C 145 183, 275 183, 372 226" }),
      svgElement("text", { className: "sky-dome-label", x: 210, y: 253, textAnchor: "middle" }, compassDirection(centerAzimuth)),
      svgElement("text", { className: "sky-dome-label", x: 72, y: 237, textAnchor: "middle" }, compassDirection(centerAzimuth - 75)),
      svgElement("text", { className: "sky-dome-label", x: 348, y: 237, textAnchor: "middle" }, compassDirection(centerAzimuth + 75)),
      svgElement("path", { className: "sky-track is-sun", d: skyTrackPath(sunTrack) }),
      svgElement("path", { className: "sky-track is-moon", d: skyTrackPath(moonTrack) }),
      sunTrack.map(function (point) {
        return svgElement("circle", { className: "sky-track-dot is-sun", cx: round1(point.x), cy: round1(point.y), r: 3 });
      }),
      moonTrack.map(function (point) {
        return svgElement("circle", { className: "sky-track-dot is-moon", cx: round1(point.x), cy: round1(point.y), r: 3 });
      }),
      svgElement("line", { className: "sky-separation-ray", x1: observer.x, y1: observer.y, x2: round1(selectedSun.x), y2: round1(selectedSun.y) }),
      svgElement("line", { className: "sky-separation-ray", x1: observer.x, y1: observer.y, x2: round1(selectedMoon.x), y2: round1(selectedMoon.y) }),
      svgElement("path", { className: "sky-separation-arc", d: angleArcPath(observer, selectedSun, selectedMoon, 46) }),
      svgElement("circle", { className: "sky-observer-dot", cx: observer.x, cy: observer.y, r: 3.5 }),
      svgElement("circle", { className: "sky-body is-sun", cx: round1(selectedSun.x), cy: round1(selectedSun.y), r: 10 }),
      svgElement("circle", { className: "sky-body is-moon", cx: round1(selectedMoon.x), cy: round1(selectedMoon.y), r: 8 }),
      svgElement("text", { className: "sky-body-label is-sun", x: round1(selectedSun.x) + 12, y: round1(selectedSun.y) - 10 }, "Sun " + compassDirection(selected.sunAzimuthDegrees) + " " + degrees(selected.sunAltitudeDegrees)),
      svgElement("text", { className: "sky-body-label is-moon", x: round1(selectedMoon.x) + 12, y: round1(selectedMoon.y) - 10 }, "Moon " + compassDirection(selected.moonAzimuthDegrees) + " " + degrees(selected.moonAltitudeDegrees)),
      svgElement("text", { className: "sky-separation-label", x: 24, y: 30 }, degrees(separation) + " separation"),
      svgElement("text", { className: "sky-dome-label", x: 24, y: 52 }, formatTime(selected.at, timezone, countryCode))
    ));
}

function hasSkyDomeSamples(samples) {
  return skyDomeSamples(samples).length > 0;
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

function skyProjection(centerAzimuth) {
  var centerX = 210;
  var horizonY = 226;
  var radiusX = 162;
  var domeHeight = 178;

  return function (altitudeDegrees, azimuthDegrees) {
    var relativeAzimuth = shortestAngleDelta(centerAzimuth, azimuthDegrees);
    var clampedAzimuth = clamp(relativeAzimuth, -90, 90);
    var clampedAltitude = clamp(altitudeDegrees, 0, 90);
    var altitudeRatio = clampedAltitude / 90;
    var narrowing = 0.48 + ((1 - altitudeRatio) * 0.52);
    var azimuthRadians = toRadians(clampedAzimuth);
    return {
      x: centerX + Math.sin(azimuthRadians) * radiusX * narrowing,
      y: horizonY - (altitudeRatio * domeHeight) + ((1 - Math.cos(azimuthRadians)) * 13)
    };
  };
}

function skyTrackPath(points) {
  if (points.length === 0) {
    return "";
  }
  return points.map(function (point, index) {
    return (index === 0 ? "M " : "L ") + round1(point.x) + " " + round1(point.y);
  }).join(" ");
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

function angularMidpoint(startDegrees, endDegrees) {
  return normalizeDegrees(startDegrees + shortestAngleDelta(startDegrees, endDegrees) / 2);
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
