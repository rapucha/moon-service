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
  await page.goto("/search?locationId=curve-corpus-prague");

  const cards = page.locator(".moon-pass-card");
  await expect(cards).toHaveCount(curveCases.length);
  await expect(page.locator(".moon-path-panel")).toHaveCount(curveCases.length);

  for (const [index, curveCase] of curveCases.entries()) {
    const card = cards.nth(index);
    const expected = markerExpectations.get(curveCase.passId);

    await expect(card.locator(".chart-path")).toHaveCount(0);
    await expect(card.locator(".altitude-chart-desktop .moon-sample-marker")).toHaveCount(expected.desktopVisibleCount);
    await expect(card.locator(".altitude-chart-mobile .moon-sample-marker")).toHaveCount(expected.mobileVisibleCount);
    await expect(card.locator(".altitude-chart-desktop .moon-sample-marker.is-suggested")).toHaveCount(curveCase.recommendationCount);
    await expect(card.locator(".altitude-chart-desktop .moon-sample-marker-label")).toHaveText(expected.labels);
    await expect(card.locator(".pass-choice-card")).toHaveCount(curveCase.recommendationCount);

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
      const desktop = chartDiagnostics(card, ".altitude-chart-desktop", cases[index]);
      const mobile = chartDiagnostics(card, ".altitude-chart-mobile", cases[index]);
      return {
        id: cases[index].id,
        passId: cases[index].passId,
        classification: cases[index].classification,
        desktop,
        mobile
      };
    });

    function chartDiagnostics(card, chartSelector, curveCase) {
      const markers = Array.from(card.querySelectorAll(chartSelector + " .moon-sample-marker"));
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
      return {
        markerCount: samples.length,
        suggestedCount: samples.filter(point => point.suggested).length,
        protectedSequences: protectedSamples.map(point => point.sequence),
        backtracks: xBacktrackCount(samples),
        ordinaryClosePairs: closePairCount(ordinarySamples, ordinarySamples, chartSelector.includes("mobile") ? 13 : 18),
        ordinaryNearProtected: closePairCount(ordinarySamples, protectedSamples, chartSelector.includes("mobile") ? 17 : 24),
        outOfBounds: samples.filter(point => point.x < bounds.minX || point.x > bounds.maxX || point.y < bounds.minY || point.y > bounds.maxY).length
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
    expect(item.desktop.suggestedCount, item.id).toBe(expected.labels.length);
    expect(item.mobile.suggestedCount, item.id).toBe(expected.labels.length);
    expect(item.desktop.protectedSequences, item.id).toEqual(expected.protectedSequences);
    expect(item.mobile.protectedSequences, item.id).toEqual(expected.protectedSequences);
    expect(item.desktop.backtracks, item.id).toBe(0);
    expect(item.mobile.backtracks, item.id).toBe(0);
    expect(item.desktop.ordinaryClosePairs, item.id).toBe(0);
    expect(item.mobile.ordinaryClosePairs, item.id).toBe(0);
    expect(item.desktop.ordinaryNearProtected, item.id).toBe(0);
    expect(item.mobile.ordinaryNearProtected, item.id).toBe(0);
    expect(item.desktop.outOfBounds, item.id).toBe(0);
    expect(item.mobile.outOfBounds, item.id).toBe(0);
  }
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
    const lastSequence = Math.max(0, samples.length - 1);

    return [passId, {
      sourceCount: samples.length,
      desktopVisibleCount: desktopVisible.length,
      mobileVisibleCount: mobileVisible.length,
      protectedSequences: samples
        .map((sample, index) => ({ sample, index }))
        .filter(({ sample, index }) => sample.role === "suggested" || index === 0 || index === lastSequence)
        .map(({ index }) => index),
      labels
    }];
  }));
}

function visibleMarkers(samples, mode, mobileReferenceDurationMs) {
  const points = chartPoints(samples, mode, mobileReferenceDurationMs);
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

function chartPoints(samples, mode, mobileReferenceDurationMs) {
  const sourcePoints = samples.map(sample => ({
    at: sample.at,
    time: new Date(sample.at).getTime(),
    altitudeDegrees: sample.altitudeDegrees,
    azimuthDegrees: sample.azimuthDegrees,
    role: sample.role || "path"
  })).filter(sample => Number.isFinite(sample.time)
    && Number.isFinite(sample.altitudeDegrees)
    && Number.isFinite(sample.azimuthDegrees));
  const firstTime = sourcePoints[0].time;
  const lastTime = sourcePoints[sourcePoints.length - 1].time;
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
