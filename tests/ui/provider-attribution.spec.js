import { expect, test } from "@playwright/test";

const removedCommercialCopy = "This is a noncommercial tester alpha. "
  + "A commercial launch requires a separately approved provider plan.";

test("keeps compact provider credit on Search and full provider details on About", async ({ page, baseURL }) => {
  if (!baseURL) {
    throw new Error("Playwright baseURL is required");
  }

  const localOrigin = new URL(baseURL).origin;
  const externalOrigins = new Set();
  page.on("request", request => {
    const origin = new URL(request.url()).origin;
    if (origin !== localOrigin) {
      externalOrigins.add(origin);
    }
  });

  await page.goto("/search");

  await expect(page.getByRole("heading", { name: "Privacy and caveats" })).toHaveCount(0);
  await expect(page.getByRole("heading", { name: "Data sources and alpha use" })).toHaveCount(0);
  await expect(page.locator("body")).not.toContainText(removedCommercialCopy);

  const searchCredits = page.locator(".provider-credit");
  await expect(searchCredits).toHaveCount(2);
  for (const credit of await searchCredits.all()) {
    await expect(credit.locator("a[href='https://open-meteo.com/']"))
      .toHaveAttribute("href", "https://open-meteo.com/");
    await expect(credit.locator("a[href='https://www.geonames.org/export/']"))
      .toHaveAttribute("href", "https://www.geonames.org/export/");
    expect(await credit.locator("a").evaluateAll(links => links.map(link => link.getAttribute("rel"))))
      .toEqual(["noreferrer", "noreferrer"]);
  }

  await page.goto("/about");

  const privacy = page.locator("#privacy-and-providers");
  await expect(privacy).toContainText("Moon Service does not permanently store a location just because you search for it.");
  await expect(privacy).toContainText("If calibration feedback is enabled and you submit a report, Moon Service retains its city-level location and opportunity context until an operator deletes the report.");
  await expect(privacy).toContainText("Moon Service sends city queries and forecast coordinates from its backend to Open-Meteo.");
  await expect(privacy).toContainText("Its free API may keep backend request logs containing coordinates for up to 90 days.");
  await expect(privacy).toContainText("Moon Service adds no visitor tracking.");

  const sources = page.locator("#data-sources");
  await expect(sources.getByRole("link", { name: "Weather data by Open-Meteo" }))
    .toHaveAttribute("href", "https://open-meteo.com/");
  await expect(sources.getByRole("link", { name: "licence" }))
    .toHaveAttribute("href", "https://open-meteo.com/en/licence");
  await expect(sources.getByRole("link", { name: "terms" }))
    .toHaveAttribute("href", "https://open-meteo.com/en/terms");
  await expect(sources.getByRole("link", { name: "GeoNames" }))
    .toHaveAttribute("href", "https://www.geonames.org/export/");
  await expect(sources).toContainText("The free Open-Meteo API is rate-limited and has no uptime guarantee.");
  expect(await sources.locator("a").evaluateAll(links => links.map(link => link.getAttribute("rel"))))
    .toEqual(["noreferrer", "noreferrer", "noreferrer", "noreferrer"]);
  await expect(page.locator("body")).not.toContainText(removedCommercialCopy);
  expect([...externalOrigins]).toEqual([]);
});
