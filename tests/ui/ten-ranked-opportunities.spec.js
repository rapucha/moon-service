import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const sourceFixture = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
));
const CALENDAR_FEED_HREF = "/calendars/opportunities.ics?locationId=moon-service-3067696";
const fixture = tenCandidateFixture(sourceFixture);
const displayedCandidateIndexes = [5, 0, 6, 1, 7, 2, 8, 3, 9, 4];

test.beforeEach(async ({ page }) => {
  await page.route("**/api/opportunities**", async route => {
    const locationId = new URL(route.request().url()).searchParams.get("locationId");
    const response = locationId === "invalid-calendar-links"
      ? invalidCalendarFixture(sourceFixture)
      : fixture;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(response)
    });
  });
});

test("renders ten ranked candidates as responsive pass groups", async ({ page }) => {
  await page.goto("/search?locationId=ten-candidate-test");

  await expect(page.locator(".pass-choice-card")).toHaveCount(10);
  await expect(page.locator(".moon-pass-card")).toHaveCount(5);
  await expect(page.locator(".summary-count")).toHaveText(
    "5 ranked Moon passes · 10 candidate windows"
  );
  const feedButtons = page.locator(".share-tools button");
  const origin = await page.evaluate(() => window.location.origin);
  await expect(feedButtons).toHaveText(["Copy Atom feed link", "Copy calendar feed link"]);
  await expect(feedButtons.nth(0)).toHaveAttribute(
    "data-share-url", origin + "/feeds/atom?locationId=moon-service-3067696");
  await expect(feedButtons.nth(1)).toHaveAttribute("data-share-url", origin + CALENDAR_FEED_HREF);
  await expect(page.locator(".share-tools a")).toHaveCount(0);
  await expect(page.locator(".rank-label")).toHaveText([
    "Best match",
    "Option 2",
    "Option 3",
    "Option 4",
    "Option 5"
  ]);
  await expect(page.locator(".opportunity-title h3")).toHaveText(
    Array(5).fill("2 candidate windows in this Moon pass")
  );
  await expect(page.locator(".score-value")).toHaveText(["90", "89", "88", "87", "86"]);
  await expect(page.locator(".choice-rank")).toHaveText([
    "Rank 6 · score 85",
    "Rank 1 · score 90",
    "Rank 7 · score 84",
    "Rank 2 · score 89",
    "Rank 8 · score 83",
    "Rank 3 · score 88",
    "Rank 9 · score 82",
    "Rank 4 · score 87",
    "Rank 10 · score 81",
    "Rank 5 · score 86"
  ]);
  await expect(page.locator(".choice-badge.is-best")).toHaveCount(5);
  await expect(page.locator(".choice-badge.is-alt")).toHaveCount(5);
  await expect(page.locator(".pass-photo-hint")).toHaveCount(10);
  await expect(page.locator(".pass-choice-explanation")).toHaveCount(10);
  const calendarLinks = page.getByRole("link", { name: "Download calendar event" });
  await expect(calendarLinks).toHaveCount(10);
  await expect(page.locator(".pass-choice-card").getByRole(
    "link", { name: "Download calendar event" }
  )).toHaveCount(10);
  expect(await calendarLinks.evaluateAll(links => links.map(link => link.getAttribute("href"))))
    .toEqual(displayedCandidateIndexes.map(index => fixture.opportunities[index].links.ics));
  expect(await page.locator(".pass-choice-card").evaluateAll(cards => cards.map(card =>
    card.querySelectorAll("a").length
  ))).toEqual(Array(10).fill(1));
  await calendarLinks.first().focus();
  await expect(calendarLinks.first()).toBeFocused();
  await expect(page.locator(".pass-choice-card").first()).toContainText("clear");
  await expect(page.locator(".pass-choice-card").first()).toContainText(
    "foreground light is limited"
  );
  await expect(page.locator(".pass-choice-card").first()).toContainText(
    "Fixture alternate recommendation for UI smoke checks."
  );
  await expect(page.locator(".pass-choice-card").last()).toContainText("partly cloudy");
  await expect(page.locator(".pass-choice-card").last()).toContainText(
    "Ambient light should support foreground detail"
  );
  await expect(page.locator(".pass-choice-card").last()).toContainText(
    "Fixture recommendation for UI smoke checks."
  );

  const lastCard = page.locator(".moon-pass-card").last();
  await lastCard.scrollIntoViewIfNeeded();
  await expect(lastCard).toBeVisible();

  const horizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(horizontalOverflow).toBeLessThanOrEqual(1);
});

test("omits calendar actions for absent, non-string, and blank links", async ({ page }) => {
  await page.goto("/search?locationId=invalid-calendar-links");

  await expect(page.locator(".pass-choice-card")).toHaveCount(3);
  await expect(page.getByRole("link", { name: "Download calendar event" })).toHaveCount(0);
});

function tenCandidateFixture(source) {
  const opportunities = Array.from({ length: 10 }, function (_, index) {
    const passIndex = index % 5;
    const templateIndex = index < 5 ? 0 : 1;
    const opportunity = JSON.parse(JSON.stringify(source.opportunities[templateIndex]));
    opportunity.id = "ten-candidate-" + (index + 1);
    opportunity.score = 90 - index;
    opportunity.moonPass.id = "ten-candidate-pass-" + (passIndex + 1);
    opportunity.links = {
      ics: "/o/backend-calendar-" + (index + 17)
        + ".ics?opaque=server-owned%2Fcandidate-" + (index + 1)
    };
    return opportunity;
  });

  return Object.assign({}, source, {
    candidateWindowsEvaluated: 24,
    links: { calendarFeed: CALENDAR_FEED_HREF },
    opportunities: opportunities
  });
}

function invalidCalendarFixture(source) {
  const response = JSON.parse(JSON.stringify(source));
  delete response.opportunities[0].links.ics;
  response.opportunities[1].links.ics = 42;
  response.opportunities[2].links.ics = " \t ";
  return response;
}
