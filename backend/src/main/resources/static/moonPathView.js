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
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";

var DESKTOP_ALTITUDE_WIDTH = 730;
var DESKTOP_PLOT_WIDTH = 672;
var MOBILE_ALTITUDE_WIDTH = 320;
var MOBILE_PLOT_WIDTH = 272;

var SILHOUETTE_SEQUENCE_WIDTH = 320;

// Moon path foreground tuning:
// - Heights are apparent altitude degrees so the silhouettes scale with the chart ceiling.
// - Smaller layer duration means faster parallax drift; opacity keeps the Moon path dominant.
// - Add landmark, seasonal, or city-specific shapes by registering a builder in
//   SILHOUETTE_SHAPE_BUILDERS and referencing that shape key from a layer's figures.
// - See "Moon Path Foreground Animation" in docs/ui-spec.md for parameter semantics.
var SILHOUETTE_HEIGHT_DEGREES = {
  hill: 2.2,
  house: 3,
  tree: 4.5,
  midRise: 5.5,
  church: 6.8,
  tallTower: 11.7
};

var SILHOUETTE_LAYERS = [
  {
    id: "far",
    className: "is-far",
    opacity: 0.1,
    durationSeconds: 54,
    delaySeconds: -21,
    offsetX: 126,
    figures: [
      { shape: "hill", x: 0, width: 166, height: "hill" },
      { shape: "blockBuilding", x: 88, width: 15, height: "midRise", windowColumns: 2, windowRows: 2 },
      { shape: "tree", x: 184, height: "tree" },
      { shape: "house", x: 228, width: 16, height: "house" },
      { shape: "tallBuilding", x: 286, width: 9, height: "tallTower", windowColumns: 1, windowRows: 6 }
    ]
  },
  {
    id: "mid",
    className: "is-mid",
    opacity: 0.15,
    durationSeconds: 34,
    delaySeconds: -11,
    offsetX: 48,
    figures: [
      { shape: "hill", x: 0, width: 146, height: "hill" },
      { shape: "blockBuilding", x: 82, width: 20, height: "midRise", windowColumns: 2, windowRows: 3 },
      { shape: "church", x: 166, width: 34, height: "church" },
      { shape: "tree", x: 254, height: "tree" },
      { shape: "house", x: 300, width: 18, height: "house" }
    ]
  },
  {
    id: "near",
    className: "is-near",
    opacity: 0.22,
    durationSeconds: 24,
    delaySeconds: 0,
    offsetX: 0,
    figures: [
      { shape: "hill", x: 0, width: 130, height: "hill" },
      { shape: "house", x: 82, width: 20, height: "house" },
      { shape: "tree", x: 150, height: "tree" },
      { shape: "blockBuilding", x: 224, width: 22, height: "midRise", windowColumns: 2, windowRows: 3 },
      { shape: "tallBuilding", x: 276, width: 10, height: "tallTower", windowColumns: 1, windowRows: 6 }
    ]
  }
];

var SILHOUETTE_SHAPE_BUILDERS = {
  hill: foregroundHillFigure,
  house: foregroundHouseFigure,
  tree: foregroundTreeFigure,
  blockBuilding: foregroundBlockBuildingFigure,
  tallBuilding: foregroundTallBuildingFigure,
  church: foregroundChurchFigure
};

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

function lightBandSegments(points) {
  return points.slice(0, -1).reduce(function (bands, point, index) {
    var next = points[index + 1];
    var bucket = point.lightBucket || next.lightBucket || "unknown";
    var segment = {
      x: point.x,
      endX: next.x,
      width: Math.max(0, next.x - point.x),
      startsAt: point.at,
      endsAt: next.at,
      lightBucket: bucket
    };
    if (segment.width <= 0) {
      return bands;
    }

    var previous = bands[bands.length - 1];
    if (previous && previous.lightBucket === bucket) {
      previous.endX = segment.endX;
      previous.width = Math.max(0, previous.endX - previous.x);
      previous.endsAt = segment.endsAt;
    } else {
      bands.push(segment);
    }
    return bands;
  }, []);
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
    var x = left + chartWidth * ratio;
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

function altitudeForegroundArtwork(left, top, bottom, chartWidth, mode, firstTime, ceiling, chartHeight) {
  var idSuffix = mode + "-" + Math.abs(Math.round(firstTime)).toString(36);
  var clipId = "moon-path-foreground-clip-" + idSuffix;
  var layerSequences = SILHOUETTE_LAYERS.map(function (layer) {
    return {
      layer: layer,
      sequenceId: "moon-path-foreground-" + layer.id + "-" + idSuffix
    };
  });
  var sequenceWidth = SILHOUETTE_SEQUENCE_WIDTH;
  var repetitions = Math.ceil(chartWidth / sequenceWidth) + 2;
  var scale = foregroundAngleScale(ceiling, chartHeight);

  return [
    svgElement("defs", {},
      svgElement("clipPath", { id: clipId },
        svgElement("rect", {
          x: left,
          y: top,
          width: round1(chartWidth),
          height: bottom - top
        })),
      layerSequences.map(function (entry) {
        return svgElement("g", { id: entry.sequenceId }, foregroundLayerShapes(entry.layer, bottom, scale));
      })),
    svgElement("g", {
      className: "moon-path-foreground-layer",
      "aria-hidden": "true",
      "clip-path": "url(#" + clipId + ")"
    },
      layerSequences.map(function (entry) {
        return svgElement("g", {
          className: "moon-path-foreground " + entry.layer.className,
          style: foregroundLayerStyle(entry.layer)
        },
          foregroundSequenceUses(entry.sequenceId, left, repetitions, sequenceWidth));
      }))
  ];
}

function foregroundAngleScale(ceiling, chartHeight) {
  return function (degreesValue) {
    return round1((degreesValue / Math.max(1, ceiling)) * chartHeight);
  };
}

function foregroundLayerStyle(layer) {
  return "--moon-path-foreground-opacity: " + layer.opacity
    + "; --moon-path-foreground-duration: " + layer.durationSeconds + "s"
    + "; --moon-path-foreground-delay: " + layer.delaySeconds + "s"
    + "; --moon-path-foreground-shift: -" + SILHOUETTE_SEQUENCE_WIDTH + "px";
}

function foregroundSequenceUses(sequenceId, left, repetitions, sequenceWidth) {
  var uses = [];
  for (var index = 0; index < repetitions; index += 1) {
    uses.push(svgElement("use", {
      className: "moon-path-foreground-use",
      "data-moon-path-artwork": "true",
      href: "#" + sequenceId,
      transform: "translate(" + round1(left + (index * sequenceWidth)) + " 0)"
    }));
  }
  return uses;
}

function foregroundLayerShapes(layer, baseline, scale) {
  return layer.figures.map(function (figure) {
    return foregroundFigureShapes(figure, layer.offsetX || 0, baseline, scale);
  }).flat();
}

function foregroundFigureShapes(figure, layerOffsetX, baseline, scale) {
  var builder = SILHOUETTE_SHAPE_BUILDERS[figure.shape];
  if (!builder) {
    return [];
  }
  return builder(figure, layerOffsetX + (figure.x || 0), baseline, foregroundFigureHeight(figure, scale));
}

function foregroundFigureHeight(figure, scale) {
  var degreesValue = Number.isFinite(figure.heightDegrees)
    ? figure.heightDegrees
    : SILHOUETTE_HEIGHT_DEGREES[figure.height];
  return scale(Number.isFinite(degreesValue) ? degreesValue : 0);
}

function foregroundHillFigure(figure, x, baseline, height) {
  return foregroundHill(x, baseline, figure.width || 120, height);
}

function foregroundHouseFigure(figure, x, baseline, height) {
  return foregroundHouse(x, baseline, figure.width || 20, height);
}

function foregroundTreeFigure(figure, x, baseline, height) {
  return foregroundTree(x, baseline, height);
}

function foregroundBlockBuildingFigure(figure, x, baseline, height) {
  return foregroundBlockBuilding(
    x,
    baseline,
    figure.width || 20,
    height,
    figure.windowColumns || 2,
    figure.windowRows || 2);
}

function foregroundTallBuildingFigure(figure, x, baseline, height) {
  return foregroundTallBuilding(
    x,
    baseline,
    figure.width || 10,
    height,
    figure.windowColumns || 1,
    figure.windowRows || 5);
}

function foregroundChurchFigure(figure, x, baseline, height) {
  return foregroundChurch(x, baseline, figure.width || 34, height);
}

function foregroundHill(x, baseline, width, height) {
  return artworkPath("moon-path-foreground-hill", "M" + p(x) + " " + p(baseline)
    + "c" + p(width * 0.22) + " " + p(-height * 0.75)
    + " " + p(width * 0.42) + " " + p(-height * 1.08)
    + " " + p(width * 0.62) + " " + p(-height * 0.86)
    + " " + p(width * 0.18) + " " + p(height * 0.2)
    + " " + p(width * 0.24) + " " + p(height * 0.86)
    + " " + p(width * 0.38) + " " + p(height * 0.86)
    + "h" + p(-width) + "z");
}

function foregroundHouse(x, baseline, width, height) {
  var roofHeight = Math.max(4, height * 0.36);
  var bodyHeight = height - roofHeight;
  var bodyY = baseline - bodyHeight;
  return [
    artworkRect("moon-path-foreground-shape", x, bodyY, width, bodyHeight, 1),
    artworkPath("moon-path-foreground-shape", "M" + p(x - 3) + " " + p(bodyY)
      + "L" + p(x + (width / 2)) + " " + p(baseline - height)
      + "L" + p(x + width + 3) + " " + p(bodyY) + "z"),
    foregroundWindows(x + (width * 0.2), bodyY + (bodyHeight * 0.22), width * 0.6, bodyHeight * 0.44, 2, 1)
  ].flat();
}

function foregroundTree(x, baseline, height) {
  var crownWidth = Math.max(16, height * 1.05);
  var crownHeight = Math.max(10, height * 0.62);
  var crownLeft = x - (crownWidth / 2);
  var crownTop = baseline - height;
  var crownStartY = crownTop + (crownHeight * 0.62);
  var trunkWidth = Math.max(3, height * 0.12);
  var trunkHeight = height * 0.52;
  return [
    artworkPath("moon-path-foreground-shape", "M" + p(crownLeft) + " " + p(crownStartY)
      + "c0 " + p(-crownHeight * 0.2)
      + " " + p(crownWidth * 0.14) + " " + p(-crownHeight * 0.34)
      + " " + p(crownWidth * 0.3) + " " + p(-crownHeight * 0.32)
      + " " + p(crownWidth * 0.06) + " " + p(-crownHeight * 0.22)
      + " " + p(crownWidth * 0.28) + " " + p(-crownHeight * 0.3)
      + " " + p(crownWidth * 0.44) + " " + p(-crownHeight * 0.14)
      + " " + p(crownWidth * 0.16) + " " + p(crownHeight * 0.04)
      + " " + p(crownWidth * 0.23) + " " + p(crownHeight * 0.18)
      + " " + p(crownWidth * 0.24) + " " + p(crownHeight * 0.34)
      + " " + p(crownWidth * 0.17) + " " + p(crownHeight * 0.04)
      + " " + p(crownWidth * 0.28) + " " + p(crownHeight * 0.2)
      + " " + p(crownWidth * 0.28) + " " + p(crownHeight * 0.38)
      + " 0 " + p(crownHeight * 0.24)
      + " " + p(-crownWidth * 0.2) + " " + p(crownHeight * 0.38)
      + " " + p(-crownWidth * 0.42) + " " + p(crownHeight * 0.36)
      + "h" + p(-crownWidth * 0.18)
      + "c" + p(-crownWidth * 0.18) + " 0"
      + " " + p(-crownWidth * 0.28) + " " + p(-crownHeight * 0.14)
      + " " + p(-crownWidth * 0.28) + " " + p(-crownHeight * 0.62) + "z"),
    artworkRect("moon-path-foreground-shape", x - (trunkWidth / 2), baseline - trunkHeight, trunkWidth, trunkHeight, 1)
  ];
}

function foregroundBlockBuilding(x, baseline, width, height, windowColumns, windowRows) {
  return [
    artworkRect("moon-path-foreground-shape", x, baseline - height, width, height, 1),
    foregroundWindows(x + (width * 0.18), baseline - (height * 0.84), width * 0.64, height * 0.62, windowColumns, windowRows)
  ].flat();
}

function foregroundTallBuilding(x, baseline, width, height, windowColumns, windowRows) {
  return [
    artworkRect("moon-path-foreground-shape", x, baseline - height, width, height, 1),
    foregroundWindows(x + (width * 0.24), baseline - (height * 0.88), width * 0.52, height * 0.68, windowColumns, windowRows)
  ].flat();
}

function foregroundChurch(x, baseline, width, height) {
  var bodyHeight = height * 0.5;
  var towerWidth = width * 0.3;
  var towerHeight = height * 0.76;
  var spireTop = baseline - height;
  var towerX = x + (width * 0.58);
  var bodyY = baseline - bodyHeight;
  var towerY = baseline - towerHeight;
  return [
    artworkRect("moon-path-foreground-shape", x, bodyY, width * 0.7, bodyHeight, 1),
    artworkPath("moon-path-foreground-shape", "M" + p(x - 2) + " " + p(bodyY)
      + "L" + p(x + (width * 0.35)) + " " + p(baseline - (height * 0.66))
      + "L" + p(x + (width * 0.72)) + " " + p(bodyY) + "z"),
    artworkRect("moon-path-foreground-shape", towerX, towerY, towerWidth, towerHeight, 1),
    artworkPath("moon-path-foreground-shape", "M" + p(towerX - 2) + " " + p(towerY)
      + "L" + p(towerX + (towerWidth / 2)) + " " + p(spireTop)
      + "L" + p(towerX + towerWidth + 2) + " " + p(towerY) + "z"),
    foregroundWindows(x + (width * 0.15), bodyY + (bodyHeight * 0.24), width * 0.34, bodyHeight * 0.46, 2, 1),
    foregroundWindows(towerX + (towerWidth * 0.28), towerY + (towerHeight * 0.22), towerWidth * 0.44, towerHeight * 0.36, 1, 2)
  ].flat();
}

function foregroundWindows(x, y, width, height, columns, rows) {
  var windows = [];
  var columnGap = width / Math.max(1, columns);
  var rowGap = height / Math.max(1, rows);
  var windowWidth = Math.max(1.4, Math.min(3.4, columnGap * 0.42));
  var windowHeight = Math.max(1.8, Math.min(4.4, rowGap * 0.38));

  for (var row = 0; row < rows; row += 1) {
    for (var column = 0; column < columns; column += 1) {
      windows.push(artworkRect("moon-path-foreground-window",
        x + (columnGap * column) + ((columnGap - windowWidth) / 2),
        y + (rowGap * row) + ((rowGap - windowHeight) / 2),
        windowWidth,
        windowHeight,
        0.7));
    }
  }
  return windows;
}

function artworkPath(className, d) {
  return svgElement("path", {
    className: className,
    "data-moon-path-artwork": "true",
    d: d
  });
}

function artworkRect(className, x, y, width, height, rx) {
  return svgElement("rect", {
    className: className,
    "data-moon-path-artwork": "true",
    x: round1(x),
    y: round1(y),
    width: round1(width),
    height: round1(height),
    rx: rx
  });
}

function p(value) {
  return String(round1(value));
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
