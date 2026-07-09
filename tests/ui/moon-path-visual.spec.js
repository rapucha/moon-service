import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const fixture = JSON.parse(readFileSync(new URL("./fixtures/moon-pass-response.json", import.meta.url), "utf8"));
const curveFixture = JSON.parse(readFileSync(new URL("./fixtures/moon-pass-curve-corpus.json", import.meta.url), "utf8"));
const citedPassId = "prague-cz-pass-2026-07-10T232104Z";
const citedCardIndex = curveFixture.diagnostics.curveCorpus.cases.findIndex(curveCase => curveCase.passId === citedPassId);

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

test("keeps the selected-time sky dome visually stable", async ({ page }) => {
  await page.goto("/search?locationId=moon-service-3067696");

  const card = page.locator(".moon-pass-card").first();
  const skyDomeDetails = card.locator(".sky-picture-details").filter({ hasText: "Sky dome" });
  await skyDomeDetails.locator("summary").click();
  await expect(skyDomeDetails.locator(".sky-dome-chart")).toBeVisible();

  await expect(skyDomeDetails.locator(".sky-dome-chart")).toHaveScreenshot("representative-sky-dome.png", {
    animations: "disabled",
    caret: "hide",
    maxDiffPixelRatio: 0.002
  });
});

test("keeps the cited western sky dome on its surface", async ({ page }) => {
  await page.unroute("**/api/opportunities**");
  await page.route("**/api/opportunities**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(curveFixture)
    });
  });
  await page.goto("/search?locationId=curve-corpus-prague");

  const card = page.locator(".moon-pass-card").nth(citedCardIndex);
  const skyDomeDetails = card.locator(".sky-picture-details").filter({ hasText: "Sky dome" });
  await skyDomeDetails.locator("summary").click();
  await expect(skyDomeDetails.locator(".sky-dome-chart")).toBeVisible();

  await expect(skyDomeDetails.locator(".sky-dome-chart")).toHaveScreenshot("cited-western-sky-dome.png", {
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
