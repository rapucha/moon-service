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

test("keeps a representative Moon path card visually stable", async ({ page }, testInfo) => {
  await page.goto("/search?locationId=moon-service-3067696");

  const card = page.locator(".moon-pass-card").first();
  await expect(card).toBeVisible();
  await expect(card.locator(".moon-path-panel")).toBeVisible();
  await expect(card.locator(visibleChartSelector(testInfo.project.name))).toBeVisible();

  await hideMoonPathArtwork(page);

  await expect(card).toHaveScreenshot("representative-moon-path-card.png", {
    animations: "disabled",
    caret: "hide",
    maxDiffPixelRatio: 0.002
  });
});

function visibleChartSelector(projectName) {
  return projectName === "mobile"
    ? ".moon-altitude-chart.altitude-chart-mobile"
    : ".moon-altitude-chart.altitude-chart-desktop";
}

async function hideMoonPathArtwork(page) {
  await page.addStyleTag({
    content: `
      .moon-path-silhouettes,
      .moon-path-landmark-scale,
      [data-moon-path-artwork] {
        visibility: hidden !important;
      }
    `
  });
}
