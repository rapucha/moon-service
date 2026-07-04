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
  await expect(page.locator(".moon-pass-card").first().locator(".pass-choice-card")).toHaveCount(2);
  await expect(page.locator(".pass-choices.is-single")).toHaveCount(1);
  await expect(page.locator(".pass-choices.is-single .pass-choice-card")).toHaveCount(1);
  await expect(page.locator(".key-facts")).toHaveCount(0);
  await expect(page.locator(".metric-columns")).toHaveCount(0);
  await expect(page.locator(".moon-path-panel")).toHaveCount(2);
});
