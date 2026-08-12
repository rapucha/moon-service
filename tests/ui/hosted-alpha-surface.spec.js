import { expect, test } from "@playwright/test";

test("loads the Search page through the hosted-alpha surface", async ({ page, baseURL }) => {
  if (!baseURL) {
    throw new Error("Playwright baseURL is required");
  }

  const localOrigin = new URL(baseURL).origin;
  /** @type {string[]} */
  const failedJavaScriptResponses = [];
  /** @type {string[]} */
  const pageErrors = [];

  page.on("response", response => {
    const responseUrl = new URL(response.url());
    if (responseUrl.origin === localOrigin
        && response.request().resourceType() === "script"
        && !response.ok()) {
      failedJavaScriptResponses.push(response.status() + " " + responseUrl.pathname);
    }
  });
  page.on("pageerror", error => pageErrors.push(error.message));

  const searchResponse = await page.goto("/search");
  if (!searchResponse) {
    throw new Error("Search did not return a document response");
  }

  expect(searchResponse.ok()).toBe(true);
  expect(searchResponse.headers()["cross-origin-opener-policy"]).toBe("same-origin");
  await expect(page.getByRole("radio", { name: "Moon Service recommendation" })).toBeChecked();
  expect(failedJavaScriptResponses).toEqual([]);
  expect(pageErrors).toEqual([]);
});
