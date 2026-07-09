import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const fixture = JSON.parse(readFileSync(new URL("./fixtures/moon-pass-curve-corpus.json", import.meta.url), "utf8"));
const curveCases = fixture.diagnostics.curveCorpus.cases;
const markerExpectations = markerExpectationsByPassId(fixture);

test.beforeEach(async ({ page }) => {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.route("**/api/opportunities**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(fixture)
    });
  });
});

test("renders provider-free curve corpus for review", async ({ page }, testInfo) => {
  test.setTimeout(60_000);

  await page.goto("/search?locationId=curve-corpus-prague");

  const cards = page.locator(".moon-pass-card");
  await expect(cards).toHaveCount(curveCases.length);
  await expect(page.locator(".moon-path-panel")).toHaveCount(curveCases.length);

  for (const [index, curveCase] of curveCases.entries()) {
    const card = cards.nth(index);
    const expected = markerExpectations.get(curveCase.passId);

    await expect(card.locator(".chart-path")).toHaveCount(0);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-desktop .moon-sample-marker")).toHaveCount(expected.desktopVisibleCount);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-mobile .moon-sample-marker")).toHaveCount(expected.mobileVisibleCount);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-desktop .sun-sample-marker")).toHaveCount(0);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-mobile .sun-sample-marker")).toHaveCount(0);
    await expect(card.locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker")).toHaveCount(expected.desktopSunVisibleCount);
    await expect(card.locator(".sun-altitude-chart.altitude-chart-mobile .sun-path-marker")).toHaveCount(expected.mobileSunVisibleCount);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-desktop .moon-sample-marker.is-suggested")).toHaveCount(curveCase.recommendationCount);
    await expect(card.locator(".moon-altitude-chart.altitude-chart-desktop .moon-sample-marker-label")).toHaveText(expected.labels);
    await expect(card.locator(".sky-picture-details")).toHaveCount(expected.skyDomeVisible ? 2 : 1);
    await expect(card.locator(".sky-dome-chart")).toHaveCount(expected.skyDomeVisible ? 1 : 0);
    await expect(card.locator(".pass-choice-card")).toHaveCount(curveCase.recommendationCount);

    if (expected.skyDomeVisible) {
      const domeGeometry = await card.locator(".sky-dome-chart").evaluate(chart => {
        const observer = chart.querySelector(".sky-observer-dot");
        const horizon = chart.querySelector(".sky-dome-horizon");
        const shell = chart.querySelector(".sky-dome-shell");
        const centerX = Number(observer?.getAttribute("cx"));
        const centerY = Number(observer?.getAttribute("cy"));
        const radiusX = Number(horizon?.getAttribute("rx"));
        const radiusY = Number(horizon?.getAttribute("ry"));
        return ["sun", "moon"].map(role => {
          const body = chart.querySelector(`.sky-body.is-${role}`);
          const transform = body?.getAttribute("transform") || "";
          const position = transform.match(/translate\(([-0-9.]+)\s+([-0-9.]+)\)/);
          const x = Number(position?.[1]);
          const y = Number(position?.[2]);
          const altitudeRatio = Number(body?.getAttribute("data-altitude-degrees")) / 90;
          const line = chart.querySelector(`.sky-azimuth-projection.is-${role} .sky-azimuth-projection-line`);
          const guide = chart.querySelector(`.sky-azimuth-projection.is-${role} .sky-azimuth-projection-guide`);
          const endX = Number(line?.getAttribute("x2"));
          const endY = Number(line?.getAttribute("y2"));
          const guideEndX = Number(guide?.getAttribute("x2"));
          const guideEndY = Number(guide?.getAttribute("y2"));
          const point = new DOMPoint(x, y);
          const insideHorizon = ((x - centerX) / radiusX) ** 2 + ((y - centerY) / radiusY) ** 2 <= 1;
          return {
            bodyOnDome: (shell instanceof SVGGeometryElement && shell.isPointInFill(point)) || insideHorizon,
            bodyPositionError: Math.max(
              Math.abs(x - (endX + (centerX - endX) * altitudeRatio)),
              Math.abs(y - (endY + (48 - endY) * altitudeRatio))
            ),
            guideIsVertical: Number(guide?.getAttribute("x1")) === guideEndX,
            guideEndEllipseDistance: ((guideEndX - centerX) / radiusX) ** 2
              + ((guideEndY - centerY) / radiusY) ** 2,
            arrowEndEllipseDistance: ((endX - centerX) / radiusX) ** 2
              + ((endY - centerY) / radiusY) ** 2
          };
        });
      });
      for (const body of domeGeometry) {
        expect(body.bodyOnDome, curveCase.passId).toBe(true);
        expect(body.bodyPositionError, curveCase.passId).toBeLessThanOrEqual(0.25);
        expect(body.guideIsVertical, curveCase.passId).toBe(true);
        expect(body.guideEndEllipseDistance, curveCase.passId).toBeLessThan(1);
        expect(body.arrowEndEllipseDistance, curveCase.passId).toBeCloseTo(1, 2);
      }
    }

    if (process.env.MOON_SERVICE_CAPTURE_CURVE_REVIEW === "true") {
      await card.screenshot({
        path: testInfo.outputPath(`${index + 1}-${curveCase.id}-${testInfo.project.name}.png`)
      });
    }
  }
});

test("keeps corpus dot markers within basic SVG invariants", async ({ page }) => {
  await page.goto("/search?locationId=curve-corpus-prague");

  const diagnostics = await page.locator(".moon-pass-card").evaluateAll((cards, cases) => {
    return cards.map((card, index) => {
      const desktop = chartDiagnostics(
        card,
        ".moon-altitude-chart.altitude-chart-desktop",
        ".sun-altitude-chart.altitude-chart-desktop",
        cases[index]);
      const mobile = chartDiagnostics(
        card,
        ".moon-altitude-chart.altitude-chart-mobile",
        ".sun-altitude-chart.altitude-chart-mobile",
        cases[index]);
      return {
        id: cases[index].id,
        passId: cases[index].passId,
        classification: cases[index].classification,
        desktop,
        mobile
      };
    });

    function chartDiagnostics(card, chartSelector, sunChartSelector, curveCase) {
      const markers = Array.from(card.querySelectorAll(chartSelector + " .moon-sample-marker"));
      const sunMarkers = Array.from(card.querySelectorAll(sunChartSelector + " .sun-path-marker"));
      if (markers.length === 0) {
        throw new Error("Missing dot markers for " + curveCase.id + " " + chartSelector);
      }
      const samples = markers.map(marker => {
        const transform = marker.getAttribute("transform") || "";
        const match = transform.match(/translate\(([-0-9.]+)\s+([-0-9.]+)\)/);
        if (!match) {
          throw new Error("Missing marker transform for " + curveCase.id);
        }
        return {
          sequence: Number(marker.getAttribute("data-sequence")),
          x: Number(match[1]),
          y: Number(match[2]),
          suggested: marker.classList.contains("is-suggested")
        };
      }).sort((a, b) => a.sequence - b.sequence);
      const lastSequence = Math.max(...samples.map(point => point.sequence));
      const protectedSamples = samples.filter(point => isProtectedMarker(point, lastSequence));
      const ordinarySamples = samples.filter(point => !isProtectedMarker(point, lastSequence));
      const bounds = chartSelector.includes("mobile")
        ? { minX: 34, maxX: 306, minY: 70, maxY: 326 }
        : { minX: 34, maxX: 706, minY: 70, maxY: 326 };
      const sunSamples = sunMarkers.map(marker => {
        const transform = marker.getAttribute("transform") || "";
        const match = transform.match(/translate\(([-0-9.]+)\s+([-0-9.]+)\)/);
        if (!match) {
          throw new Error("Missing Sun marker transform for " + curveCase.id);
        }
        const markerImage = marker.querySelector("image.sun-sample-marker-image");
        return {
          sequence: Number(marker.getAttribute("data-sequence")),
          x: Number(match[1]),
          y: Number(match[2]),
          altitudeDegrees: Number(marker.getAttribute("data-sun-altitude-degrees")),
          azimuthDegrees: Number(marker.getAttribute("data-sun-azimuth-degrees")),
          imageHref: markerImage?.getAttribute("href") || "",
          imageWidth: Number(markerImage?.getAttribute("width")),
          markerSize: Number(marker.getAttribute("data-marker-size")),
          suggested: marker.classList.contains("is-suggested"),
          best: marker.classList.contains("is-best")
        };
      }).sort((a, b) => a.sequence - b.sequence);
      return {
        markerCount: samples.length,
        sunMarkerCount: sunSamples.length,
        suggestedCount: samples.filter(point => point.suggested).length,
        protectedSequences: protectedSamples.map(point => point.sequence),
        backtracks: xBacktrackCount(samples),
        sunBacktracks: xBacktrackCount(sunSamples),
        ordinaryClosePairs: closePairCount(ordinarySamples, ordinarySamples, chartSelector.includes("mobile") ? 13 : 18),
        ordinaryNearProtected: closePairCount(ordinarySamples, protectedSamples, chartSelector.includes("mobile") ? 17 : 24),
        outOfBounds: samples.filter(point => point.x < bounds.minX || point.x > bounds.maxX || point.y < bounds.minY || point.y > bounds.maxY).length,
        sunOutOfBounds: sunSamples.filter(point => point.x < bounds.minX || point.x > bounds.maxX || point.y < bounds.minY || point.y > bounds.maxY).length,
        sunMissingData: sunSamples.filter(point => !Number.isFinite(point.altitudeDegrees) || !Number.isFinite(point.azimuthDegrees)).length,
        sunMissingImage: sunSamples.filter(point => point.imageHref !== "/sun-marker-aperture-flare.svg").length,
        sunMarkerSizeMismatch: sunSamples.filter(point => {
          const expectedSize = point.best ? 42 : (point.suggested ? 28 : 14);
          return point.markerSize !== expectedSize || point.imageWidth !== expectedSize;
        }).length,
        sunOverlapCount: markerOverlapCount(sunSamples),
        sunAlternateCount: sunSamples.filter(point => point.suggested && !point.best).length
      };
    }

    function isProtectedMarker(point, lastSequence) {
      return point.suggested || point.sequence === 0 || point.sequence === lastSequence;
    }

    function closePairCount(points, others, minimumDistance) {
      let closePairs = 0;
      for (const [pointIndex, point] of points.entries()) {
        for (const [otherIndex, other] of others.entries()) {
          if (points === others && otherIndex <= pointIndex) {
            continue;
          }
          const dx = point.x - other.x;
          const dy = point.y - other.y;
          if (Math.sqrt((dx * dx) + (dy * dy)) < minimumDistance) {
            closePairs += 1;
          }
        }
      }
      return closePairs;
    }

    function markerOverlapCount(points) {
      let overlaps = 0;
      for (const [pointIndex, point] of points.entries()) {
        for (const other of points.slice(pointIndex + 1)) {
          const dx = point.x - other.x;
          const dy = point.y - other.y;
          const minimumDistance = (point.markerSize + other.markerSize) / 2;
          if (Math.sqrt((dx * dx) + (dy * dy)) < minimumDistance) {
            overlaps += 1;
          }
        }
      }
      return overlaps;
    }

    function xBacktrackCount(points) {
      return points.slice(1).reduce((count, point, index) => {
        const previous = points[index];
        return point.x + 0.5 < previous.x ? count + 1 : count;
      }, 0);
    }

  }, curveCases);

  if (process.env.MOON_SERVICE_CURVE_REPORT === "true") {
    console.log(JSON.stringify(diagnostics, null, 2));
  }

  for (const item of diagnostics) {
    const expected = markerExpectations.get(item.passId);
    expect(item.desktop.markerCount, item.id).toBe(expected.desktopVisibleCount);
    expect(item.mobile.markerCount, item.id).toBe(expected.mobileVisibleCount);
    expect(item.desktop.sunMarkerCount, item.id).toBe(expected.desktopSunVisibleCount);
    expect(item.mobile.sunMarkerCount, item.id).toBe(expected.mobileSunVisibleCount);
    expect(item.desktop.suggestedCount, item.id).toBe(expected.labels.length);
    expect(item.mobile.suggestedCount, item.id).toBe(expected.labels.length);
    expect(item.desktop.protectedSequences, item.id).toEqual(expected.protectedSequences);
    expect(item.mobile.protectedSequences, item.id).toEqual(expected.protectedSequences);
    expect(item.desktop.backtracks, item.id).toBe(0);
    expect(item.mobile.backtracks, item.id).toBe(0);
    expect(item.desktop.sunBacktracks, item.id).toBe(0);
    expect(item.mobile.sunBacktracks, item.id).toBe(0);
    expect(item.desktop.ordinaryClosePairs, item.id).toBe(0);
    expect(item.mobile.ordinaryClosePairs, item.id).toBe(0);
    expect(item.desktop.ordinaryNearProtected, item.id).toBe(0);
    expect(item.mobile.ordinaryNearProtected, item.id).toBe(0);
    expect(item.desktop.outOfBounds, item.id).toBe(0);
    expect(item.mobile.outOfBounds, item.id).toBe(0);
    expect(item.desktop.sunOutOfBounds, item.id).toBe(0);
    expect(item.mobile.sunOutOfBounds, item.id).toBe(0);
    expect(item.desktop.sunMissingData, item.id).toBe(0);
    expect(item.mobile.sunMissingData, item.id).toBe(0);
    expect(item.desktop.sunMissingImage, item.id).toBe(0);
    expect(item.mobile.sunMissingImage, item.id).toBe(0);
    expect(item.desktop.sunMarkerSizeMismatch, item.id).toBe(0);
    expect(item.mobile.sunMarkerSizeMismatch, item.id).toBe(0);
    expect(item.desktop.sunOverlapCount, item.id).toBe(0);
    expect(item.mobile.sunOverlapCount, item.id).toBe(0);
  }

  expect(diagnostics.reduce((count, item) => count + item.desktop.sunAlternateCount, 0)).toBeGreaterThan(0);
});

function markerExpectationsByPassId(payload) {
  const groups = new Map();
  const mobileReferenceDurationMs = maxOpportunityDurationMs(payload.opportunities || []);

  for (const [index, opportunity] of payload.opportunities.entries()) {
    const passId = opportunity.moonPass?.id || opportunity.id || String(index);
    if (!groups.has(passId)) {
      groups.set(passId, []);
    }
    groups.get(passId).push({ opportunity, index });
  }

  return new Map(Array.from(groups.entries()).map(([passId, entries]) => {
    const ordered = entries.slice().sort(compareOpportunityStarts);
    const primary = entries[0]?.opportunity || {};
    const primarySuggested = primary.moonPath?.suggested || {};
    const samplesByTime = new Map();
    const labelsByAt = new Map();

    for (const sample of passPathSamples(ordered[0]?.opportunity?.moonPass?.path || {})) {
      addPathSample(samplesByTime, sample);
    }

    for (const entry of ordered) {
      const suggested = entry.opportunity.moonPath?.suggested;
      if (hasPathPosition(suggested)) {
        labelsByAt.set(suggested.at, suggested.at === primarySuggested.at ? "Best" : "Alt");
        addPathSample(samplesByTime, suggested);
      }
    }

    const times = Array.from(samplesByTime.keys()).sort((a, b) => a - b);
    const firstTime = times[0];
    const lastTime = times[times.length - 1];
    const samples = times.map(time => {
      const sample = { ...samplesByTime.get(time) };
      if (labelsByAt.has(sample.at)) {
        sample.role = "suggested";
        sample.markerLabel = labelsByAt.get(sample.at);
      } else if (time === firstTime) {
        sample.role = "start";
      } else if (time === lastTime) {
        sample.role = "end";
      } else {
        sample.role = "path";
      }
      return sample;
    });
    const labels = samples
      .filter(sample => sample.role === "suggested")
      .map(sample => sample.markerLabel);
    const desktopVisible = visibleMarkers(samples, "desktop", mobileReferenceDurationMs);
    const mobileVisible = visibleMarkers(samples, "mobile", mobileReferenceDurationMs);
    const desktopSunVisible = visibleMarkers(sunPathSamples(samples), "desktop", mobileReferenceDurationMs, samples, "sun");
    const mobileSunVisible = visibleMarkers(sunPathSamples(samples), "mobile", mobileReferenceDurationMs, samples, "sun");
    const lastSequence = Math.max(0, samples.length - 1);

    return [passId, {
      sourceCount: samples.length,
      desktopVisibleCount: desktopVisible.length,
      mobileVisibleCount: mobileVisible.length,
      desktopSunVisibleCount: desktopSunVisible.length,
      mobileSunVisibleCount: mobileSunVisible.length,
      skyDomeVisible: Number.isFinite(primarySuggested.sunAltitudeDegrees)
        && Number.isFinite(primarySuggested.sunAzimuthDegrees)
        && primarySuggested.sunAltitudeDegrees >= 0,
      protectedSequences: samples
        .map((sample, index) => ({ sample, index }))
        .filter(({ sample, index }) => sample.role === "suggested" || index === 0 || index === lastSequence)
        .map(({ index }) => index),
      labels
    }];
  }));
}

function visibleMarkers(samples, mode, mobileReferenceDurationMs, timeDomainSamples = samples, body = "moon") {
  const points = chartPoints(samples, mode, mobileReferenceDurationMs, timeDomainSamples);
  if (body === "sun") {
    return visibleSunMarkers(points);
  }
  const ordinaryMinimumDistance = mode === "mobile" ? 13 : 18;
  const protectedMinimumDistance = mode === "mobile" ? 17 : 24;
  const lastSequence = points.length - 1;
  const protectedMarkers = points.filter(point => isProtectedPoint(point, lastSequence));
  const visible = [];
  const keptOrdinary = [];

  for (const point of points) {
    if (isProtectedPoint(point, lastSequence)) {
      visible.push(point);
    } else if (!isTooCloseToAny(point, protectedMarkers, protectedMinimumDistance)
      && !isTooCloseToAny(point, keptOrdinary, ordinaryMinimumDistance)) {
      visible.push(point);
      keptOrdinary.push(point);
    }
  }

  return visible.sort((a, b) => a.sequence - b.sequence);
}

function visibleSunMarkers(points) {
  const visible = points.filter(point => point.role === "suggested");

  for (const point of points) {
    if (point.role === "suggested" || visible.some(other => sunMarkersOverlap(point, other))) {
      continue;
    }
    visible.push(point);
  }

  return visible.sort((a, b) => a.sequence - b.sequence);
}

function sunMarkersOverlap(first, second) {
  const firstSize = first.markerLabel === "Best" ? 42 : (first.role === "suggested" ? 28 : 14);
  const secondSize = second.markerLabel === "Best" ? 42 : (second.role === "suggested" ? 28 : 14);
  return markerDistance(first, second) < (firstSize + secondSize) / 2;
}

function sunPathSamples(samples) {
  return samples.filter(sample => sample
    && sample.at
    && Number.isFinite(sample.sunAltitudeDegrees)
    && Number.isFinite(sample.sunAzimuthDegrees)
    && sample.sunAltitudeDegrees >= 0
  ).map(sample => ({
    at: sample.at,
    altitudeDegrees: sample.sunAltitudeDegrees,
    azimuthDegrees: sample.sunAzimuthDegrees,
    sunAltitudeDegrees: sample.sunAltitudeDegrees,
    sunAzimuthDegrees: sample.sunAzimuthDegrees,
    role: sample.role || "path",
    markerLabel: sample.markerLabel
  }));
}

function chartPoints(samples, mode, mobileReferenceDurationMs, timeDomainSamples) {
  const sourcePoints = samples.map(sample => ({
    at: sample.at,
    time: new Date(sample.at).getTime(),
    altitudeDegrees: sample.altitudeDegrees,
    azimuthDegrees: sample.azimuthDegrees,
    sunAltitudeDegrees: sample.sunAltitudeDegrees,
    sunAzimuthDegrees: sample.sunAzimuthDegrees,
    role: sample.role || "path",
    markerLabel: sample.markerLabel
  })).filter(sample => Number.isFinite(sample.time)
    && Number.isFinite(sample.altitudeDegrees)
    && Number.isFinite(sample.azimuthDegrees));
  const domainTimes = timeDomainSamples
    .map(sample => new Date(sample.at).getTime())
    .filter(Number.isFinite);
  const firstTime = Math.min(sourcePoints[0].time, ...domainTimes);
  const lastTime = Math.max(sourcePoints[sourcePoints.length - 1].time, ...domainTimes);
  const timeSpan = Math.max(1, lastTime - firstTime);
  const maxAltitude = sourcePoints.reduce((max, point) => Math.max(max, point.altitudeDegrees), 0);
  const ceiling = Math.min(90, Math.max(12, Math.ceil((maxAltitude + 1) / 5) * 5));
  const chartWidth = mode === "mobile"
    ? Math.max(1, (timeSpan / Math.max(timeSpan, mobileReferenceDurationMs)) * 272)
    : 672;

  return sourcePoints.map((sourcePoint, index) => ({
    ...sourcePoint,
    sequence: index,
    x: 34 + ((sourcePoint.time - firstTime) / timeSpan) * chartWidth,
    y: 326 - (Math.min(Math.max(sourcePoint.altitudeDegrees, 0), ceiling) / ceiling) * (326 - 70)
  }));
}

function isProtectedPoint(point, lastSequence) {
  return point.role === "suggested" || point.sequence === 0 || point.sequence === lastSequence;
}

function isTooCloseToAny(point, others, minimumDistance) {
  return others.some(other => point !== other && markerDistance(point, other) < minimumDistance);
}

function markerDistance(a, b) {
  const dx = round1(a.x) - round1(b.x);
  const dy = round1(a.y) - round1(b.y);
  return Math.sqrt((dx * dx) + (dy * dy));
}

function round1(value) {
  return Math.round(value * 10) / 10;
}

function maxOpportunityDurationMs(opportunities) {
  return opportunities.reduce((maxDuration, opportunity) => {
    const started = new Date(opportunity.startsAt).getTime();
    const ended = new Date(opportunity.endsAt).getTime();
    if (!Number.isFinite(started) || !Number.isFinite(ended) || ended <= started) {
      return maxDuration;
    }
    return Math.max(maxDuration, ended - started);
  }, 0);
}

function passPathSamples(path) {
  return (Array.isArray(path.samples) ? path.samples : []).concat([path.start, path.end]);
}

function addPathSample(samplesByTime, sample) {
  if (!hasPathPosition(sample)) {
    return;
  }
  const time = new Date(sample.at).getTime();
  if (Number.isFinite(time)) {
    samplesByTime.set(time, sample);
  }
}

function hasPathPosition(point) {
  return point
    && point.at
    && Number.isFinite(point.altitudeDegrees)
    && Number.isFinite(point.azimuthDegrees);
}

function compareOpportunityStarts(a, b) {
  const aStarted = new Date(a.opportunity.startsAt).getTime();
  const bStarted = new Date(b.opportunity.startsAt).getTime();
  if (Number.isFinite(aStarted) && Number.isFinite(bStarted) && aStarted !== bStarted) {
    return aStarted - bStarted;
  }
  return a.index - b.index;
}
