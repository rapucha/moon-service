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
  await expect(page.locator(".moon-pass-card").first().locator(".sky-track.is-sun")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-track.is-moon")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-separation-ray")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-sun")).toHaveCount(1);
  await expect(page.locator(".moon-pass-card").first().locator(".sky-body.is-moon")).toHaveCount(1);

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
