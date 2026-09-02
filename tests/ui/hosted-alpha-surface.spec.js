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
  /** @type {string[]} */
  const localRequestPaths = [];
  let lastLocalScriptRequestNumber = 0;
  /** @type {(message: string) => void} */
  let reportClientFailure = function () {};
  /** @type {Promise<string>} */
  const clientFailure = new Promise(resolve => { reportClientFailure = resolve; });

  page.on("request", request => {
    const requestUrl = new URL(request.url());
    if (requestUrl.origin !== localOrigin) return;
    localRequestPaths.push(requestUrl.pathname);
    if (request.resourceType() === "script") {
      lastLocalScriptRequestNumber = localRequestPaths.length;
    }
  });
  page.on("response", response => {
    const responseUrl = new URL(response.url());
    if (responseUrl.origin === localOrigin
        && response.request().resourceType() === "script"
        && !response.ok()) {
      const failure = response.status() + " " + responseUrl.pathname;
      failedJavaScriptResponses.push(failure);
      reportClientFailure("Hosted Search JavaScript failed: " + failure);
    }
  });
  page.on("pageerror", error => {
    pageErrors.push(error.message);
    reportClientFailure("Hosted Search page error: " + error.message);
  });

  const searchResponse = await page.goto("/search");
  if (!searchResponse) {
    throw new Error("Search did not return a document response");
  }

  expect(searchResponse.ok()).toBe(true);
  expect(searchResponse.headers()["cross-origin-opener-policy"]).toBe("same-origin");
  const initialized = expect(page.locator("#opportunity-preferences"))
    .toHaveAttribute("open", "", { timeout: 15_000 }).then(() => "");
  const clientFailureMessage = await Promise.race([initialized, clientFailure]);
  if (clientFailureMessage) throw new Error(clientFailureMessage);
  await expect(page.getByRole("radio", { name: "Moon Service recommendation" })).toBeChecked();
  expect(lastLocalScriptRequestNumber).toBeLessThanOrEqual(40);
  expect(localRequestPaths).not.toContain("/moonEventPath.js");
  expect(failedJavaScriptResponses).toEqual([]);
  expect(pageErrors).toEqual([]);
});
