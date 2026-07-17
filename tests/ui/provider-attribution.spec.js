import { expect, test } from "@playwright/test";

test("shows provider attribution and the noncommercial alpha boundary", async ({ page, baseURL }) => {
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

  const disclosure = page.locator("section[aria-labelledby='data-sources-title']");
  await expect(disclosure.getByRole("heading", { name: "Data sources and alpha use" })).toBeVisible();
  await expect(disclosure.getByRole("link", { name: "Weather data by Open-Meteo" }))
    .toHaveAttribute("href", "https://open-meteo.com/");
  await expect(disclosure.getByRole("link", { name: "GeoNames" }))
    .toHaveAttribute("href", "https://www.geonames.org/export/");
  await expect(disclosure.locator("a[href='https://open-meteo.com/en/licence']")).toHaveText("licence");
  await expect(disclosure.locator("a[href='https://open-meteo.com/en/terms']")).toHaveText("provider terms");
  await expect(disclosure.locator("a[href='https://open-meteo.com/en/pricing']")).toHaveText("provider plan");
  expect(await disclosure.locator("a").evaluateAll(links => links.map(link => link.getAttribute("rel"))))
    .toEqual(["noreferrer", "noreferrer", "noreferrer", "noreferrer", "noreferrer"]);
  await expect(disclosure).toContainText("Moon Service sends city searches and forecast coordinates from its backend to Open-Meteo.");
  await expect(disclosure).toContainText("Moon Service adds no visitor tracking.");
  await expect(disclosure).toContainText("This is a noncommercial tester alpha.");
  await expect(disclosure).toContainText("A commercial launch requires a separately approved provider plan.");
  expect([...externalOrigins]).toEqual([]);
});
