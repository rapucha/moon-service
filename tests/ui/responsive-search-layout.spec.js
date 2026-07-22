import { expect, test } from "@playwright/test";

const resolvedResponse = {
  status: "ok",
  location: {
    kind: "real_location",
    id: "prague-cz",
    displayName: "Prague, Czechia",
    timezone: "Europe/Prague",
    countryCode: "CZ"
  },
  forecastHorizonDays: 7,
  candidateWindowsEvaluated: 0,
  opportunities: [],
  emptyReason: {
    text: "No useful Moon window passed the current scoring threshold."
  }
};

const ambiguousResponse = {
  status: "ambiguous_location",
  candidates: [
    {
      kind: "real_location",
      id: "prague-cz",
      displayName: "Prague, Czechia",
      timezone: "Europe/Prague",
      countryCode: "CZ"
    }
  ]
};

test.beforeEach(async ({ page }) => {
  await page.addInitScript(() => {
    window.localStorage.setItem("moonService.recentSearches.v1", JSON.stringify([{
      displayName: "Prague, Czechia",
      slug: "prague-cz",
      timezone: "Europe/Prague"
    }]));
  });

  await page.route("**/api/opportunities**", async route => {
    const url = new URL(route.request().url());
    const query = url.searchParams.get("q");
    const body = query === "Springfield"
      ? ambiguousResponse
      : query === "Unavailable"
        ? { status: "temporarily_unavailable", message: "Try again shortly." }
        : resolvedResponse;
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(body)
    });
  });
});

test("keeps Recent searches accessible without placing it ahead of mobile results", async ({ page }, testInfo) => {
  await page.goto("/search");

  const recent = page.locator("#recent-searches");
  const summary = recent.locator("summary");
  const workspace = page.locator(".workspace");
  const isMobile = testInfo.project.name === "mobile";

  await expect(recent).toHaveJSProperty("open", !isMobile);
  await expect(page.getByRole("heading", { name: "Privacy and caveats" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Data sources and alpha use" })).toHaveCount(0);

  if (isMobile) {
    await expect(summary).toBeVisible();
    const recentBox = await recent.boundingBox();
    const workspaceBox = await workspace.boundingBox();
    expect(recentBox).not.toBeNull();
    expect(workspaceBox).not.toBeNull();
    expect(recentBox.y).toBeLessThan(workspaceBox.y);
    await summary.focus();
    await page.keyboard.press("Enter");
    await expect(recent).toHaveJSProperty("open", true);
    await page.keyboard.press("Enter");
    await expect(recent).toHaveJSProperty("open", false);
    await page.keyboard.press("Enter");
    await expect(recent).toHaveJSProperty("open", true);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  } else {
    await expect(summary).toBeHidden();
    const recentBox = await recent.boundingBox();
    const workspaceBox = await workspace.boundingBox();
    expect(recentBox).not.toBeNull();
    expect(workspaceBox).not.toBeNull();
    expect(recentBox.x).toBeLessThan(workspaceBox.x);
  }

  await expect(recent).toContainText("Stored only in this browser.");
  await expect(recent.locator(".provider-credit")).toBeVisible();
  await recent.getByRole("button", { name: /Prague, Czechia/ }).click();

  await expect(page).toHaveURL(/locationId=prague-cz/);
  await expect(page.getByRole("heading", { name: "Prague, Czechia" })).toBeVisible();
  await expect(page.locator("#result-provider-credit")).toBeVisible();
  await expect(page.locator("#result-obstruction-note")).toBeVisible();
  await expect(page.getByRole("link", { name: "Open share link" })).toBeVisible();

  await recent.getByRole("button", { name: "Clear" }).click();
  await expect(recent).toContainText("No recent searches in this browser.");

  if (isMobile) {
    await page.reload();
    await expect(recent).toHaveJSProperty("open", false);
  }
});

test("keeps ambiguous selection and unavailable-result flows", async ({ page }) => {
  await page.goto("/search");

  await page.getByLabel("City or town").fill("Springfield");
  await page.getByRole("button", { name: "Find" }).click();
  await expect(page.getByRole("heading", { name: "Choose a location" })).toBeVisible();
  await expect(page.locator("#result-provider-credit")).toBeVisible();
  await expect(page.locator("#result-obstruction-note")).toBeHidden();

  await page.locator(".candidate-list").getByRole("button", { name: /Prague, Czechia/ }).click();
  await expect(page).toHaveURL(/locationId=prague-cz/);
  await expect(page.getByRole("link", { name: "Open share link" })).toBeVisible();

  await page.getByLabel("City or town").fill("Unavailable");
  await page.getByRole("button", { name: "Find" }).click();
  await expect(page.getByRole("heading", { name: "Lookup temporarily unavailable" })).toBeVisible();
  await expect(page.locator("#result-provider-credit")).toBeHidden();
  await expect(page.locator("#result-obstruction-note")).toBeHidden();
});
