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
  await expect(page.locator(".moon-pass-card").first().locator(".altitude-chart-desktop .sun-sample-marker"))
    .toHaveCount(2);
  await expect(page.locator(".moon-pass-card").first().locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-04T06:40:00Z']"))
    .toHaveAttribute("data-sun-altitude-degrees", "32");
  await expect(page.locator(".moon-pass-card").first().locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-04T06:40:00Z'] title"))
    .toHaveText("Sun position sample, 32.0° altitude, 102.0° azimuth ESE");
  await expect(page.locator(".moon-pass-card").first().locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-04T02:12:00Z']"))
    .toHaveCount(0);
  await expect(page.locator(".moon-pass-card").nth(1).locator(".altitude-chart-desktop .sun-sample-marker"))
    .toHaveCount(3);
  await expect(page.locator(".moon-pass-card").nth(1).locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-10T08:00:00Z']"))
    .toHaveAttribute("data-sun-azimuth-degrees", "145");
  await expect(page.locator(".moon-pass-card").nth(1).locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-10T08:00:00Z'] title"))
    .toHaveText("Sun position sample, 30.0° altitude, 145.0° azimuth SE");

  const saturatedSunMarker = await page.locator(".moon-pass-card").first()
    .locator(".altitude-chart-desktop .sun-sample-marker[data-at='2026-07-04T06:40:00Z']")
    .evaluate(marker => {
      const transform = marker.getAttribute("transform") || "";
      const match = transform.match(/translate\(([-0-9.]+)\s+([-0-9.]+)\)/);
      const gridlineY = Array.from(marker.closest("svg")?.querySelectorAll(".chart-gridline") || [])
        .map(line => Number(line.getAttribute("y1")))
        .filter(Number.isFinite);
      return {
        y: match ? Number(match[2]) : Number.NaN,
        topY: Math.min(...gridlineY)
      };
    });
  expect(saturatedSunMarker.y).toBe(saturatedSunMarker.topY);

  const azimuthRail = await page.locator(".moon-pass-card").first().locator(".altitude-chart-desktop").evaluate(chart => {
    const rail = chart.querySelector(".azimuth-rail-bg");
    const labels = Array.from(chart.querySelectorAll(".azimuth-rail-label"));
    const railX = Number(rail?.getAttribute("x"));
    const railWidth = Number(rail?.getAttribute("width"));
    return {
      railX,
      railWidth,
      firstLabelX: Number(labels[0]?.getAttribute("x")),
      lastLabelX: Number(labels[labels.length - 1]?.getAttribute("x"))
    };
  });
  expect(azimuthRail.firstLabelX - azimuthRail.railX).toBeGreaterThanOrEqual(10);
  expect((azimuthRail.railX + azimuthRail.railWidth) - azimuthRail.lastLabelX).toBeGreaterThanOrEqual(10);
});
