import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const sourceFixture = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
));
const fixture = tenCandidateFixture(sourceFixture);

test.beforeEach(async ({ page }) => {
  await page.route("**/api/opportunities**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(fixture)
    });
  });
});

test("renders ten ranked candidates as responsive pass groups", async ({ page }) => {
  await page.goto("/search?locationId=ten-candidate-test");

  await expect(page.locator(".pass-choice-card")).toHaveCount(10);
  await expect(page.locator(".moon-pass-card")).toHaveCount(5);
  await expect(page.locator(".summary-count")).toHaveText("5 ranked Moon passes");
  await expect(page.locator(".rank-label")).toHaveText([
    "Best match",
    "Option 2",
    "Option 3",
    "Option 4",
    "Option 5"
  ]);
  await expect(page.locator(".opportunity-title h3")).toHaveText(
    Array(5).fill("Two ranked Moon candidates")
  );
  await expect(page.locator(".score-value")).toHaveText(["90", "89", "88", "87", "86"]);
  await expect(page.locator(".choice-rank")).toHaveText([
    "Rank 1 · score 90",
    "Rank 6 · score 85",
    "Rank 2 · score 89",
    "Rank 7 · score 84",
    "Rank 3 · score 88",
    "Rank 8 · score 83",
    "Rank 4 · score 87",
    "Rank 9 · score 82",
    "Rank 5 · score 86",
    "Rank 10 · score 81"
  ]);
  await expect(page.locator(".choice-badge.is-best")).toHaveCount(5);
  await expect(page.locator(".choice-badge.is-alt")).toHaveCount(5);
  await expect(page.locator(".pass-photo-hint")).toHaveCount(10);
  await expect(page.locator(".pass-choice-explanation")).toHaveCount(10);
  await expect(page.locator(".pass-choice-card").first()).toContainText("partly cloudy");
  await expect(page.locator(".pass-choice-card").first()).toContainText(
    "Ambient light should support foreground detail"
  );
  await expect(page.locator(".pass-choice-card").first()).toContainText(
    "Fixture recommendation for UI smoke checks."
  );
  await expect(page.locator(".pass-choice-card").last()).toContainText("clear");
  await expect(page.locator(".pass-choice-card").last()).toContainText(
    "foreground light is limited"
  );
  await expect(page.locator(".pass-choice-card").last()).toContainText(
    "Fixture alternate recommendation for UI smoke checks."
  );

  const lastCard = page.locator(".moon-pass-card").last();
  await lastCard.scrollIntoViewIfNeeded();
  await expect(lastCard).toBeVisible();

  const horizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(horizontalOverflow).toBeLessThanOrEqual(1);
});

function tenCandidateFixture(source) {
  const opportunities = Array.from({ length: 10 }, function (_, index) {
    const passIndex = index % 5;
    const templateIndex = index < 5 ? 0 : 1;
    const opportunity = JSON.parse(JSON.stringify(source.opportunities[templateIndex]));
    opportunity.id = "ten-candidate-" + (index + 1);
    opportunity.score = 90 - index;
    opportunity.moonPass.id = "ten-candidate-pass-" + (passIndex + 1);
    return opportunity;
  });

  return Object.assign({}, source, {
    candidateWindowsEvaluated: 24,
    opportunities: opportunities
  });
}
