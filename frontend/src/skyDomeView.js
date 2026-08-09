import { compassDirection } from "./angularPreferenceControls.js";
import { element, svgElement } from "./dom.js";
import { clamp, degrees, formatTime, normalizeDegrees, round1 } from "./format.js";
import { moonPhaseImageDataUrl } from "./moonPhaseView.js";

var SUN_SAMPLE_MARKER_IMAGE_URL = "/sun-marker-aperture-flare.svg";

export function rankedPlanningSkyDome(samples, timezone, countryCode, moon) {
  var points = skyDomeSamples(samples);
  if (points.length < 1) {
    return null;
  }
  var selected = selectedSkyPoint(points);
  if (selected.sunAltitudeDegrees < 0) {
    return null;
  }
  var selectedTime = formatTime(selected.at, timezone, countryCode);
  return renderSkyDome(
    selected,
    moon,
    skyProjection(0),
    selectedTime,
    "at " + selectedTime,
    280);
}

export function currentSnapshotSkyDome(asOf, moon, sun, snapshotTime) {
  var points = skyDomeSamples([{
    at: asOf,
    altitudeDegrees: moon?.altitudeDegrees,
    azimuthDegrees: moon?.azimuthDegrees,
    sunAltitudeDegrees: sun?.altitudeDegrees,
    sunAzimuthDegrees: sun?.azimuthDegrees,
    role: "now"
  }]);
  if (points.length < 1) {
    return null;
  }
  var selected = points[0];
  var belowHorizon = [
    selected.sunAltitudeDegrees < 0 ? "Sun" : null,
    selected.moonAltitudeDegrees < 0 ? "Moon" : null
  ].filter(Boolean).join(" and ");
  return renderSkyDome(
    selected,
    moon,
    skyProjection(-12),
    "Snapshot: " + snapshotTime,
    "at snapshot time " + snapshotTime
      + (belowHorizon ? "; " + belowHorizon + " below horizon" : ""),
    320);
}

function renderSkyDome(selected, moon, projection, selectedTime, accessibleTime, height) {
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
  var moonImageUrl = moonPhaseImageDataUrl(
    moon.phaseAngleDegrees,
    64,
    moon.brightLimbTiltDegrees,
    moon.northPoleTiltDegrees);
  var accessibleLabel = "Sun and Moon sky position " + accessibleTime
    + "; Sun " + degrees(selected.sunAltitudeDegrees) + " altitude, "
    + degrees(selected.sunAzimuthDegrees) + " azimuth " + compassDirection(selected.sunAzimuthDegrees)
    + "; Moon " + degrees(selected.moonAltitudeDegrees) + " altitude, "
    + degrees(selected.moonAzimuthDegrees) + " azimuth " + compassDirection(selected.moonAzimuthDegrees)
    + "; " + degrees(separation) + " angular separation";

  return element("div", { className: "sky-dome-frame" },
    svgElement("svg", {
      className: "sky-dome-chart",
      viewBox: "0 0 420 " + height,
      role: "img",
      ariaLabel: accessibleLabel
    },
      svgElement("rect", { className: "sky-dome-background", x: 0, y: 0, width: 420, height: height, rx: 8 }),
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
    return sample && sample.at
      && Number.isFinite(sample.altitudeDegrees) && Number.isFinite(sample.azimuthDegrees)
      && Number.isFinite(sample.sunAltitudeDegrees) && Number.isFinite(sample.sunAzimuthDegrees);
  }).map(function (sample) {
    return {
      at: sample.at,
      moonAltitudeDegrees: sample.altitudeDegrees,
      moonAzimuthDegrees: sample.azimuthDegrees,
      sunAltitudeDegrees: sample.sunAltitudeDegrees,
      sunAzimuthDegrees: sample.sunAzimuthDegrees,
      role: sample.role,
      markerLabel: sample.markerLabel
    };
  }).filter(function (sample) {
    return Number.isFinite(new Date(sample.at).getTime());
  });
}

function selectedSkyPoint(points) {
  return points.find(function (point) {
    return point.role === "suggested" && point.markerLabel === "Best";
  }) || points.find(function (point) {
    return point.role === "suggested";
  }) || points[Math.floor(points.length / 2)];
}

function skyProjection(minimumAltitudeDegrees) {
  var centerX = 210;
  var horizonY = 226;
  var radiusX = 162;
  var radiusY = 25;
  var zenithY = 48;

  return function (altitudeDegrees, azimuthDegrees) {
    var altitudeRatio = clamp(altitudeDegrees, minimumAltitudeDegrees, 90) / 90;
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
