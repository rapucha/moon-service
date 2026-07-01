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

var ALTITUDE_PIXELS_PER_MINUTE = 1.16;
var MOBILE_ALTITUDE_WIDTH = 320;
var MOBILE_PLOT_WIDTH = 272;

export function moonPathPanel(opportunity, timezone, countryCode, chartContext) {
  var path = opportunity.moonPath || {};
  var samples = moonPathSamples(path);
  if (samples.length < 2) {
    return null;
  }

  return element("section", { className: "moon-path-panel" },
    element("div", { className: "moon-path-header" },
      element("h4", {}, "Moon path"),
      element("p", {}, "Start, suggested, and end positions across the window")),
    element("div", { className: "moon-path-summary" },
      moonPathPoint("Start", path.start, timezone, countryCode),
      moonPathPoint("Suggested", path.suggested, timezone, countryCode),
      moonPathPoint("End", path.end, timezone, countryCode)),
    element("div", { className: "moon-path-charts" },
      chartBlock("Altitude", altitudeChart(samples, timezone, countryCode, chartContext, opportunity.moon || {})),
      chartBlock("Azimuth", azimuthChart(samples))));
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
  return element("div", { className: "moon-chart" },
    element("span", { className: "moon-chart-label" }, label),
    chart);
}

function altitudeChart(samples, timezone, countryCode, chartContext, moon) {
  var points = chartSamples(samples);
  if (points.length < 2) {
    return null;
  }

  return element("div", { className: "moon-chart-scroll" },
    altitudeChartSvg(points, timezone, countryCode, "desktop", chartContext, moon),
    altitudeChartSvg(points, timezone, countryCode, "mobile", chartContext, moon));
}

function altitudeChartSvg(sourcePoints, timezone, countryCode, mode, chartContext, moon) {
  var height = 390;
  var left = 34;
  var right = 14;
  var top = 26;
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
  var arcChartWidth = Math.max(1, (timeSpan / 60000) * ALTITUDE_PIXELS_PER_MINUTE);
  var chartWidth = mode === "mobile"
    ? Math.max(1, (timeSpan / mobileReferenceDurationMs) * MOBILE_PLOT_WIDTH)
    : arcChartWidth;
  var width = mode === "mobile" ? MOBILE_ALTITUDE_WIDTH : Math.ceil(left + chartWidth + right);
  var plotEndX = left + chartWidth;
  var chartHeight = bottom - top;
  var points = sourcePoints.map(function (sourcePoint) {
    var point = Object.assign({}, sourcePoint);
    point.x = left + ((point.time - firstTime) / timeSpan) * chartWidth;
    point.y = bottom - (clamp(point.altitudeDegrees, 0, ceiling) / ceiling) * chartHeight;
    return point;
  });

  var bands = lightBandSegments(points);
  var timeTicks = altitudeHourTicks(points, timezone, bottom + 29);
  var path = smoothAltitudePath(points);

  return svgElement("svg", {
    className: "altitude-chart altitude-chart-" + mode,
    viewBox: "0 0 " + width + " " + height,
    role: "img",
    ariaLabel: mode === "mobile"
      ? "Moon altitude across the opportunity window; chart fits the card width"
      : "Moon altitude across the opportunity window; chart width follows window duration",
    style: mode === "desktop" ? "width: " + width + "px" : null
  },
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
    svgElement("line", { className: "chart-gridline", x1: left, y1: bottom, x2: round1(plotEndX), y2: bottom }),
    svgElement("line", { className: "chart-gridline", x1: left, y1: top, x2: round1(plotEndX), y2: top }),
    svgElement("text", { className: "chart-axis-label", x: 4, y: bottom + 4 }, "0°"),
    svgElement("text", { className: "chart-axis-label", x: 4, y: top + 4 }, ceiling + "°"),
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
    svgElement("path", { className: "chart-path", d: path.d }),
    keyPathMarkers(points).map(function (point) {
      return altitudeMarker(point, moon);
    })
  );
}

function azimuthChart(samples) {
  var points = chartSamples(samples);
  if (points.length < 2) {
    return null;
  }

  var width = 190;
  var height = 150;
  var centerX = 95;
  var centerY = 72;
  var radius = 48;
  var ringPoints = points.map(function (point) {
    var plotted = polarPoint(point.azimuthDegrees, centerX, centerY, radius);
    plotted.role = point.role;
    return plotted;
  });
  var sector = "M " + centerX + " " + centerY + " L " + ringPoints.map(function (point) {
    return round1(point.x) + " " + round1(point.y);
  }).join(" L ") + " Z";
  var sweep = ringPoints.map(function (point, index) {
    return (index === 0 ? "M " : "L ") + round1(point.x) + " " + round1(point.y);
  }).join(" ");

  return svgElement("svg", {
    className: "azimuth-chart",
    viewBox: "0 0 " + width + " " + height,
    role: "img",
    ariaLabel: "Moon azimuth sweep across cardinal directions"
  },
    svgElement("title", {}, "Moon direction sweep across the opportunity window"),
    svgElement("circle", { className: "compass-ring", cx: centerX, cy: centerY, r: radius }),
    svgElement("line", { className: "compass-axis", x1: centerX, y1: centerY - radius, x2: centerX, y2: centerY + radius }),
    svgElement("line", { className: "compass-axis", x1: centerX - radius, y1: centerY, x2: centerX + radius, y2: centerY }),
    svgElement("text", { className: "compass-label", x: centerX, y: 16, textAnchor: "middle" }, "N"),
    svgElement("text", { className: "compass-label", x: centerX + radius + 18, y: centerY + 4, textAnchor: "middle" }, "E"),
    svgElement("text", { className: "compass-label", x: centerX, y: centerY + radius + 24, textAnchor: "middle" }, "S"),
    svgElement("text", { className: "compass-label", x: centerX - radius - 18, y: centerY + 4, textAnchor: "middle" }, "W"),
    svgElement("path", { className: "compass-sector", d: sector }),
    svgElement("path", { className: "chart-path", d: sweep }),
    keyPathMarkers(ringPoints).map(function (point) {
      return svgElement("circle", {
        className: "chart-dot is-" + roleClass(point.role),
        cx: round1(point.x),
        cy: round1(point.y),
        r: point.role === "suggested" ? 4.5 : 3.4
      });
    })
  );
}

function moonPathSamples(path) {
  var samples = Array.isArray(path.samples) ? path.samples : [path.start, path.suggested, path.end];
  return samples.filter(hasPosition).slice().sort(function (a, b) {
    return new Date(a.at).getTime() - new Date(b.at).getTime();
  });
}

function keyPathMarkers(points) {
  return points.filter(function (point) {
    return point.role === "start" || point.role === "suggested" || point.role === "end";
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

function smoothAltitudePath(points) {
  if (points.length === 0) {
    return { type: "empty", d: "" };
  }
  if (points.length === 1) {
    return { type: "point", d: "M " + round1(points[0].x) + " " + round1(points[0].y) };
  }
  if (points.length === 2) {
    return { type: "line", d: linearPath(points) };
  }

  var tangents = monotoneTangents(points);
  var commands = ["M " + round1(points[0].x) + " " + round1(points[0].y)];
  points.slice(0, -1).forEach(function (point, index) {
    var next = points[index + 1];
    var dx = next.x - point.x;
    if (dx <= 0) {
      commands.push(" L " + round1(next.x) + " " + round1(next.y));
      return;
    }
    var control1X = point.x + dx / 3;
    var control1Y = point.y + tangents[index] * dx / 3;
    var control2X = next.x - dx / 3;
    var control2Y = next.y - tangents[index + 1] * dx / 3;
    commands.push(" C "
      + round1(control1X) + " " + round1(control1Y) + ", "
      + round1(control2X) + " " + round1(control2Y) + ", "
      + round1(next.x) + " " + round1(next.y));
  });

  return {
    type: "monotone-cubic",
    d: commands.join("")
  };
}

function linearPath(points) {
  return points.map(function (point, index) {
    return (index === 0 ? "M " : "L ") + round1(point.x) + " " + round1(point.y);
  }).join(" ");
}

function monotoneTangents(points) {
  var slopes = [];
  var tangents = [];

  points.slice(0, -1).forEach(function (point, index) {
    var next = points[index + 1];
    var dx = Math.max(1, next.x - point.x);
    slopes.push((next.y - point.y) / dx);
  });

  tangents[0] = slopes[0];
  tangents[points.length - 1] = slopes[slopes.length - 1];

  for (var index = 1; index < points.length - 1; index += 1) {
    var before = slopes[index - 1];
    var after = slopes[index];
    tangents[index] = before * after <= 0 ? 0 : (before + after) / 2;
  }

  slopes.forEach(function (slope, index) {
    if (slope === 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      return;
    }

    var first = tangents[index] / slope;
    var second = tangents[index + 1] / slope;
    if (first < 0 || second < 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      return;
    }

    var sum = first * first + second * second;
    if (sum > 9) {
      var scale = 3 / Math.sqrt(sum);
      tangents[index] = scale * first * slope;
      tangents[index + 1] = scale * second * slope;
    }
  });

  return tangents;
}

function altitudeMarker(point, moon) {
  if (point.role !== "suggested") {
    return svgElement("circle", {
      className: "chart-dot is-" + roleClass(point.role),
      cx: round1(point.x),
      cy: round1(point.y),
      r: 3.6
    });
  }

  var size = 29;
  var imageUrl = moonPhaseImageDataUrl(moon.phaseAngleDegrees, 64);
  return svgElement("g", {
    className: "moon-marker is-suggested",
    transform: "translate(" + round1(point.x) + " " + round1(point.y) + ")"
  },
    svgElement("title", {}, "Suggested Moon position, " + degrees(point.altitudeDegrees) + " altitude"),
    svgElement("circle", { className: "moon-marker-halo", cx: 0, cy: 0, r: 17 }),
    imageUrl
      ? svgElement("image", {
        className: "moon-marker-image",
        href: imageUrl,
        x: -size / 2,
        y: -size / 2,
        width: size,
        height: size,
        preserveAspectRatio: "xMidYMid meet"
      })
      : svgElement("circle", { className: "chart-dot is-suggested", cx: 0, cy: 0, r: 7.5 }),
    svgElement("circle", { className: "moon-marker-ring", cx: 0, cy: 0, r: size / 2 })
  );
}

function lightBandTitle(band, timezone, countryCode) {
  return (readableToken(band.lightBucket) || "Light bucket")
    + ", "
    + formatTime(band.startsAt, timezone, countryCode)
    + " to "
    + formatTime(band.endsAt, timezone, countryCode);
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
      role: sample.role || "path"
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

function polarPoint(azimuthDegrees, centerX, centerY, radius) {
  var radians = normalizeDegrees(azimuthDegrees) * Math.PI / 180;
  return {
    x: centerX + Math.sin(radians) * radius,
    y: centerY - Math.cos(radians) * radius
  };
}
