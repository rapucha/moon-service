import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const fixture = JSON.parse(readFileSync(new URL("./fixtures/moon-pass-response.json", import.meta.url), "utf8"));

test.beforeEach(async ({ page }) => {
  await page.route("**/api/opportunities**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(fixture)
    });
  });
});

test("renders grouped Moon pass cards", async ({ page }) => {
  await page.goto("/search?locationId=moon-service-3067696");

  await expect(page.locator(".moon-pass-card")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".opportunity-title h3"))
    .toHaveText("Two useful low-Moon windows");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".opportunity-title h3"))
    .toHaveText("One useful low-Moon window");
  await expect(page.locator(".moon-pass-card").first().locator(".pass-context-row"))
    .toContainText("Moon pass");
  await expect(page.locator(".moon-pass-card").first().locator(".pass-context-row"))
    .toContainText("to");
  await expect(page.locator(".moon-pass-card").first().locator(".pass-context-row"))
    .toContainText(/GMT|CET|CEST/);
  await expect(page.locator(".moon-pass-card").first().locator(".pass-choice-card")).toHaveCount(2);
  await expect(page.locator(".pass-choices.is-single")).toHaveCount(1);
  await expect(page.locator(".pass-choices.is-single .pass-choice-card")).toHaveCount(1);
  await expect(page.locator(".key-facts")).toHaveCount(0);
  await expect(page.locator(".metric-columns")).toHaveCount(0);
  await expect(page.locator(".moon-path-panel")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".chart-tick.is-suggested")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".moon-path-foreground-layer")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".moon-path-foreground.is-far")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".moon-path-foreground.is-mid")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".moon-path-foreground.is-near")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator("symbol[id^='moon-path-symbol-generic-tree-wavy']")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator("[data-moon-path-symbol='generic-tree-wavy']").first())
    .toBeAttached();
  await expect(page.locator(".moon-pass-card").first().locator(".moon-path-foreground-layer").first())
    .toHaveAttribute("aria-hidden", "true");
  await expect(page.locator(".moon-pass-card").first().locator("[data-moon-path-artwork]").first())
    .toBeAttached();
  await expect(page.locator(".moon-pass-card").first().locator(".moon-altitude-chart.altitude-chart-desktop .sun-sample-marker"))
    .toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".moon-altitude-chart.altitude-chart-desktop .sun-path-marker"))
    .toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-picture-details")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-picture-details summary"))
    .toHaveText([
      "Sun passSun altitude and direction across the same Moon pass",
      "Sky domeSun and Moon positions at the suggested time"
    ]);
  expect(await page.locator(".moon-pass-card").first().locator(".sky-picture-details")
    .evaluateAll(details => details.map(detail => detail instanceof HTMLDetailsElement && detail.open))).toEqual([false, false]);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-picture-details").first().locator(".sky-picture-content"))
    .toBeHidden();
  await page.locator(".moon-pass-card").first().locator(".sky-picture-details").first().locator("summary").click();
  await expect(page.locator(".moon-pass-card").first().locator(".sky-picture-details").first().locator(".sky-picture-content"))
    .toBeVisible();
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z']"))
    .toHaveAttribute("data-sun-altitude-degrees", "32");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z']"))
    .toHaveAttribute("data-marker-resource", "/sun-marker-aperture-flare.svg");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z']"))
    .toHaveAttribute("data-marker-size", "42");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z'] image.sun-sample-marker-image"))
    .toHaveAttribute("href", "/sun-marker-aperture-flare.svg");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z'] image.sun-sample-marker-image"))
    .toHaveAttribute("width", "42");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T06:40:00Z'] title"))
    .toHaveText("Best Sun position, 32.0° altitude, 102.0° azimuth ESE");
  await expect(page.locator(".moon-pass-card").first().locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-04T02:12:00Z']"))
    .toHaveCount(0);
  await expect(page.locator(".moon-pass-card").nth(1).locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-10T08:00:00Z']"))
    .toHaveAttribute("data-sun-azimuth-degrees", "145");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-10T08:00:00Z']"))
    .toHaveAttribute("data-marker-size", "14");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-10T08:00:00Z'] image.sun-sample-marker-image"))
    .toHaveAttribute("width", "14");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-10T08:00:00Z'] title"))
    .toHaveText("Sun position sample, 30.0° altitude, 145.0° azimuth SE");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".sun-altitude-chart.altitude-chart-desktop .sun-path-marker[data-at='2026-07-10T14:29:00Z'] title"))
    .toHaveText("Best Sun position, 42.0° altitude, 258.0° azimuth WSW");

  const xAxisAlignment = await page.locator(".moon-pass-card").first().evaluate(card => {
    return ["desktop", "mobile"].map(mode => {
      const moonChart = card.querySelector(`.moon-altitude-chart.altitude-chart-${mode}`);
      const sunChart = card.querySelector(`.sun-altitude-chart.altitude-chart-${mode}`);
      const moonMarker = moonChart?.querySelector(".moon-sample-marker[data-at='2026-07-04T06:40:00Z']");
      const sunMarker = sunChart?.querySelector(".sun-path-marker[data-at='2026-07-04T06:40:00Z']");
      const markerX = marker => {
        const transform = marker?.getAttribute("transform") || "";
        const match = transform.match(/translate\(([-0-9.]+)\s+[-0-9.]+\)/);
        return match ? Number(match[1]) : Number.NaN;
      };
      const timeTicks = chart => Array.from(chart?.querySelectorAll(".chart-time-label") || []).map(label => ({
        text: label.textContent,
        x: Number(label.getAttribute("x"))
      }));
      const lightBands = chart => Array.from(chart?.querySelectorAll(".light-band") || []).map(band => ({
        className: band.getAttribute("class"),
        x: Number(band.getAttribute("x")),
        width: Number(band.getAttribute("width")),
        title: band.querySelector("title")?.textContent
      }));
      return {
        moonRailWidth: Number(moonChart?.querySelector(".azimuth-rail-bg")?.getAttribute("width")),
        sunRailWidth: Number(sunChart?.querySelector(".azimuth-rail-bg")?.getAttribute("width")),
        moonMarkerX: markerX(moonMarker),
        sunMarkerX: markerX(sunMarker),
        moonTimeTicks: timeTicks(moonChart),
        sunTimeTicks: timeTicks(sunChart),
        moonLightBands: lightBands(moonChart),
        sunLightBands: lightBands(sunChart)
      };
    });
  });
  for (const charts of xAxisAlignment) {
    expect(charts.sunRailWidth).toBe(charts.moonRailWidth);
    expect(charts.sunMarkerX).toBe(charts.moonMarkerX);
    expect(charts.sunTimeTicks).toEqual(charts.moonTimeTicks);
    expect(charts.sunLightBands).toEqual(charts.moonLightBands);
  }

  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-chart")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-track")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-track-dot")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-separation-ray")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-sun")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-moon")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-cardinal-marker .sky-cardinal-label"))
    .toHaveText(["N", "E", "S", "W"]);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-cardinal-arrow")).toHaveCount(4);
  expect(await page.locator(".moon-pass-card").first().locator(".sky-cardinal-arrow")
    .evaluateAll(arrows => arrows.map(arrow => arrow.getAttribute("transform"))))
    .toEqual(["rotate(0)", "rotate(90)", "rotate(180)", "rotate(270)"]);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-north-axis")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-north-arrow")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-a"))
    .toHaveAttribute("data-start-azimuth", "142");
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-a"))
    .toHaveAttribute("data-end-azimuth", "322");
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-b"))
    .toHaveAttribute("data-start-azimuth", "232");
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-b"))
    .toHaveAttribute("data-end-azimuth", "52");
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-a"))
    .toHaveAttribute("d", / C /);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-dome-meridian.is-grid-b"))
    .toHaveAttribute("d", / C /);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-azimuth-projection")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-azimuth-projection-line")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-azimuth-projection-guide")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-azimuth-projection-arrow")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body-label")).toHaveCount(0);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-sun .sky-body-image"))
    .toHaveAttribute("href", "/sun-marker-aperture-flare.svg");
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-moon .sky-body-image"))
    .toHaveAttribute("href", /^data:image\/png;base64,/);

  const domeGeometry = await page.locator(".moon-pass-card").first().locator(".sky-dome-chart").evaluate(chart => {
    const separationLabel = chart.querySelector(".sky-separation-label");
    const separationArc = chart.querySelector(".sky-separation-arc");
    const timeLabel = chart.querySelector(".sky-dome-label");
    const observer = chart.querySelector(".sky-observer-dot");
    const horizon = chart.querySelector(".sky-dome-horizon");
    const observerX = Number(observer?.getAttribute("cx"));
    const observerY = Number(observer?.getAttribute("cy"));
    const radiusX = Number(horizon?.getAttribute("rx"));
    const radiusY = Number(horizon?.getAttribute("ry"));
    const horizonBounds = horizon?.getBoundingClientRect();
    const cardinalBounds = Object.fromEntries(["n", "e", "s", "w"].map(direction => {
      const bounds = chart.querySelector(`.sky-cardinal-marker.is-${direction} .sky-cardinal-arrow`)
        ?.getBoundingClientRect();
      return [direction, bounds];
    }));
    const projections = ["sun", "moon"].map(role => {
      const group = chart.querySelector(`.sky-azimuth-projection.is-${role}`);
      const line = group?.querySelector(".sky-azimuth-projection-line");
      const guide = group?.querySelector(".sky-azimuth-projection-guide");
      const body = chart.querySelector(`.sky-body.is-${role}`);
      const transform = body?.getAttribute("transform") || "";
      const bodyPosition = transform.match(/translate\(([-0-9.]+)\s+([-0-9.]+)\)/);
      const endX = Number(line?.getAttribute("x2"));
      const endY = Number(line?.getAttribute("y2"));
      return {
        altitudeDegrees: Number(body?.getAttribute("data-altitude-degrees")),
        distanceFromObserver: Math.hypot(
          Number(bodyPosition?.[1]) - observerX,
          Number(bodyPosition?.[2]) - observerY),
        startsAtObserver: Number(line?.getAttribute("x1")) === observerX
          && Number(line?.getAttribute("y1")) === observerY,
        guideStartsAtBody: Number(guide?.getAttribute("x1")) === Number(bodyPosition?.[1])
          && Number(guide?.getAttribute("y1")) === Number(bodyPosition?.[2]),
        guideIsVertical: Number(guide?.getAttribute("x1")) === Number(guide?.getAttribute("x2")),
        guideEndEllipseDistance: (
          (Number(guide?.getAttribute("x2")) - observerX) / radiusX
        ) ** 2 + (
          (Number(guide?.getAttribute("y2")) - observerY) / radiusY
        ) ** 2,
        normalizedLength: Math.sqrt(
          ((endX - observerX) / radiusX) ** 2
          + ((endY - observerY) / radiusY) ** 2)
      };
    });
    const meridians = Array.from(chart.querySelectorAll(".sky-dome-meridian")).map(path => {
      const coordinates = (path.getAttribute("d")?.match(/-?\d+(?:\.\d+)?/g) || []).map(Number);
      const startX = coordinates[0];
      const startY = coordinates[1];
      const endX = coordinates[coordinates.length - 2];
      const endY = coordinates[coordinates.length - 1];
      const ellipseDistance = (x, y) => ((x - observerX) / radiusX) ** 2
        + ((y - observerY) / radiusY) ** 2;
      const curveLength = path instanceof SVGGeometryElement ? path.getTotalLength() : 0;
      return {
        startX,
        endX,
        startEllipseDistance: ellipseDistance(startX, startY),
        endEllipseDistance: ellipseDistance(endX, endY),
        startsAtObserver: startX === observerX && startY === observerY,
        passesZenith: coordinates.some((coordinate, index) => coordinate === 210 && coordinates[index + 1] === 48),
        curveLengthExcess: curveLength
          - Math.hypot(startX - 210, startY - 48)
          - Math.hypot(endX - 210, endY - 48)
      };
    });
    return {
      observerX,
      observerY,
      horizonX: Number(horizon?.getAttribute("cx")),
      horizonY: Number(horizon?.getAttribute("cy")),
      separationX: Number(separationLabel?.getAttribute("x")),
      separationY: Number(separationLabel?.getAttribute("y")),
      separationArcRadius: Number(separationArc?.getAttribute("data-radius")),
      timeX: Number(timeLabel?.getAttribute("x")),
      timeY: Number(timeLabel?.getAttribute("y")),
      cardinalGaps: {
        north: Number(horizonBounds?.top) - Number(cardinalBounds.n?.bottom),
        east: Number(cardinalBounds.e?.left) - Number(horizonBounds?.right),
        south: Number(cardinalBounds.s?.top) - Number(horizonBounds?.bottom),
        west: Number(horizonBounds?.left) - Number(cardinalBounds.w?.right)
      },
      projections,
      meridians
    };
  });
  expect(domeGeometry.observerX).toBe(domeGeometry.horizonX);
  expect(domeGeometry.observerY).toBe(domeGeometry.horizonY);
  expect(domeGeometry.separationX).toBe(domeGeometry.timeX);
  expect(domeGeometry.separationY).toBeLessThan(domeGeometry.timeY);
  expect(domeGeometry.separationY).toBeLessThan(48);
  const nearestBodyDistance = Math.min(...domeGeometry.projections.map(body => body.distanceFromObserver));
  expect(domeGeometry.separationArcRadius).toBeGreaterThan(44);
  expect(domeGeometry.separationArcRadius / nearestBodyDistance).toBeGreaterThanOrEqual(0.67);
  expect(domeGeometry.separationArcRadius).toBeLessThan(nearestBodyDistance);
  for (const gap of Object.values(domeGeometry.cardinalGaps)) {
    expect(gap).toBeGreaterThan(0.5);
  }
  for (const meridian of domeGeometry.meridians) {
    expect(meridian.startEllipseDistance).toBeCloseTo(1, 2);
    expect(meridian.endEllipseDistance).toBeCloseTo(1, 2);
    expect(meridian.startsAtObserver).toBe(false);
    expect(meridian.passesZenith).toBe(true);
    expect(meridian.curveLengthExcess).toBeGreaterThan(5);
  }
  expect(Math.abs(domeGeometry.meridians[0].startX - domeGeometry.meridians[1].endX))
    .toBeGreaterThan(10);
  expect(Math.abs(domeGeometry.meridians[0].endX - domeGeometry.meridians[1].startX))
    .toBeGreaterThan(10);
  for (const projection of domeGeometry.projections) {
    expect(projection.startsAtObserver).toBe(true);
    expect(projection.guideStartsAtBody).toBe(true);
    expect(projection.guideIsVertical).toBe(true);
    expect(projection.guideEndEllipseDistance).toBeLessThan(1);
    expect(projection.normalizedLength).toBeCloseTo(1, 2);
  }

  const azimuthRail = await page.locator(".moon-pass-card").first().locator(".moon-altitude-chart.altitude-chart-desktop").evaluate(chart => {
    const rail = chart.querySelector(".azimuth-rail-bg");
    const labels = Array.from(chart.querySelectorAll(".azimuth-rail-label"));
    const railX = Number(rail?.getAttribute("x"));
    const railWidth = Number(rail?.getAttribute("width"));
    const labelX = label => {
      const transform = label.getAttribute("transform") || "";
      const match = transform.match(/translate\(([-0-9.]+)\s+[-0-9.]+\)/);
      return match ? Number(match[1]) : Number.NaN;
    };
    return {
      railX,
      railWidth,
      firstLabelX: labelX(labels[0]),
      lastLabelX: labelX(labels[labels.length - 1])
    };
  });
  expect(azimuthRail.firstLabelX - azimuthRail.railX).toBeGreaterThanOrEqual(10);
  expect((azimuthRail.railX + azimuthRail.railWidth) - azimuthRail.lastLabelX).toBeGreaterThanOrEqual(10);

  const azimuthArrows = await page.locator(".moon-pass-card").first().evaluate(card => {
    return [".moon-altitude-chart.altitude-chart-desktop", ".sun-altitude-chart.altitude-chart-desktop"]
      .map(selector => {
        const chart = card.querySelector(selector);
        const labels = Array.from(chart?.querySelectorAll(".azimuth-rail-label") || []);
        return {
          labelCount: labels.length,
          arrowCount: labels.reduce((count, label) => count + label.querySelectorAll(".azimuth-rail-arrow").length, 0),
          rotations: labels.map(label => label.querySelector(".azimuth-rail-arrow")?.getAttribute("transform") || "")
        };
      });
  });
  for (const chart of azimuthArrows) {
    expect(chart.arrowCount).toBe(chart.labelCount);
    expect(chart.labelCount).toBeGreaterThan(0);
    expect(chart.rotations.every(transform => /rotate\([-0-9.]+\)/.test(transform))).toBe(true);
  }
});
