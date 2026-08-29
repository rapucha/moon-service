import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const PREFERENCE_KEY = "moonService.opportunityPreferences.v1";
const RECENT_KEY = "moonService.recentSearches.v1";
const ALTITUDE_PREFERENCES = {
  version: 1,
  altitudeDegrees: { minimum: 3.14, maximum: 18 }
};
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

test("keeps Best match canonical and uses keyboard order history without losing focus", async ({ page }) => {
  const calls = await captureApiCalls(page, function (call) {
    return call.order === "soonest" ? soonestResponse() : successfulResponse();
  });

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const group = page.getByRole("group", { name: "Order opportunities" });
  const bestMatch = page.getByRole("radio", { name: "Best match" });
  const soonest = page.getByRole("radio", { name: "Soonest" });
  await expect(group).toBeVisible();
  await expect(bestMatch).toBeChecked();
  expect(calls[0].method).toBe("GET");
  expect(apiParameters(calls[0])).toEqual([["q", "Prague"]]);
  await expect(page.locator(".summary-count"))
    .toHaveText("2 ranked Moon passes · 3 candidate windows");
  await expect(page.locator(".moon-pass-card .rank-label"))
    .toHaveText(["Best match", "Option 2"]);
  const bestMatchChoices = page.locator(".moon-pass-card").first().locator(".pass-choice-card");
  await expect(bestMatchChoices.locator(".choice-badge"))
    .toHaveText(["Alternative", "Best"]);
  await expect(bestMatchChoices.locator(".choice-rank"))
    .toHaveText(["Rank 2 · score 74", "Rank 1 · score 81"]);
  await expect(bestMatchChoices.locator(".pass-choice-explanation p")).toHaveText([
    "Fixture alternate recommendation for UI smoke checks.",
    "Fixture recommendation for UI smoke checks."
  ]);
  await expect(page.locator(".moon-pass-card").first().locator(".score-value")).toHaveText("81");
  await expect(page.locator(".pass-choice-explanation summary").first())
    .toHaveText("Why this candidate ranked here");

  await soonest.focus();
  await page.keyboard.press("Space");
  await waitForCallCount(calls, 2);
  await expect(page.locator("#results")).toHaveAttribute("aria-busy", "false");
  await expect(soonest).toBeFocused();
  await expect(soonest).toBeChecked();
  await expect(page).toHaveURL("/search?q=Prague&order=soonest");
  expect(apiParameters(calls[1])).toEqual([
    ["q", "Prague"],
    ["order", "soonest"]
  ]);
  await expectSoonestPresentation(page);

  await bestMatch.focus();
  await page.keyboard.press("Space");
  await waitForCallCount(calls, 3);
  await expect(bestMatch).toBeFocused();
  await expect(page).toHaveURL("/search?q=Prague");
  expect(apiParameters(calls[2])).toEqual([["q", "Prague"]]);

  await page.goBack();
  await waitForCallCount(calls, 4);
  await expect(soonest).toBeChecked();
  await expect(page).toHaveURL("/search?q=Prague&order=soonest");
  expect(apiParameters(calls[3])).toEqual([
    ["q", "Prague"],
    ["order", "soonest"]
  ]);

  await page.goForward();
  await waitForCallCount(calls, 5);
  await expect(bestMatch).toBeChecked();
  await expect(page).toHaveURL("/search?q=Prague");
});

test("keeps Soonest in active-preference POST, reset, apply, city, and recent flows", async ({ page }) => {
  await page.addInitScript(({ preferenceKey, preferences, recentKey }) => {
    window.localStorage.setItem(preferenceKey, JSON.stringify(preferences));
    window.localStorage.setItem(recentKey, JSON.stringify([{
      displayName: "Brno, Czechia",
      slug: "brno-cz",
      timezone: "Europe/Prague"
    }]));
  }, {
    preferenceKey: PREFERENCE_KEY,
    preferences: ALTITUDE_PREFERENCES,
    recentKey: RECENT_KEY
  });
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague&order=soonest");
  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");
  expect(apiParameters(calls[0])).toEqual([["order", "soonest"]]);
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: ALTITUDE_PREFERENCES
  });
  await expect(page.getByRole("radio", { name: "Soonest" })).toBeChecked();
  await openDetails(page, "#opportunity-preferences");
  const historyLength = await page.evaluate(() => window.history.length);
  await page.getByRole("button", { name: /Reset all/ }).click();
  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("GET");
  expect(apiParameters(calls[1])).toEqual([
    ["q", "Prague"],
    ["order", "soonest"]
  ]);
  expect(await page.evaluate(() => window.history.length)).toBe(historyLength);

  await page.getByLabel("Limit Moon altitude").check();
  await page.getByRole("button", { name: "Use these limits" }).click();
  await waitForCallCount(calls, 3);
  expect(calls[2].method).toBe("POST");
  expect(apiParameters(calls[2])).toEqual([["order", "soonest"]]);
  expect(calls[2].body.q).toBe("Prague");
  expect(calls[2].body.preferences).toMatchObject({
    version: 1,
    altitudeDegrees: { minimum: 10, maximum: 30 }
  });
  expect(await page.evaluate(() => window.history.length)).toBe(historyLength);

  await page.getByLabel("City or town").fill("Kyoto");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await waitForCallCount(calls, 4);
  expect(calls[3].method).toBe("POST");
  expect(calls[3].body.q).toBe("Kyoto");
  expect(apiParameters(calls[3])).toEqual([["order", "soonest"]]);
  await expect(page).toHaveURL("/search?q=Kyoto&order=soonest");

  await openDetails(page, "#recent-searches");
  await page.locator("#recent-list").getByRole("button", { name: /Brno, Czechia/ }).click();
  await waitForCallCount(calls, 5);
  expect(calls[4].method).toBe("POST");
  expect(calls[4].body.locationId).toBe("brno-cz");
  expect(apiParameters(calls[4])).toEqual([["order", "soonest"]]);
  await expect(page).toHaveURL("/search?locationId=brno-cz&order=soonest");

  expect(await page.evaluate(() => Object.keys(window.localStorage)
    .filter(key => key.toLowerCase().includes("order")))).toEqual([]);
});

test("preserves Soonest through ambiguity and hides the control until selection resolves", async ({ page }) => {
  const calls = await captureApiCalls(page, function (call) {
    if (new URL(call.url).searchParams.get("q") === "Springfield") {
      return {
        status: "ambiguous_location",
        candidates: [{
          kind: "real_location",
          id: "prague-cz",
          displayName: "Prague, Czechia",
          timezone: "Europe/Prague",
          countryCode: "CZ"
        }]
      };
    }
    return successfulResponse();
  });

  await page.goto("/search?q=Springfield&order=soonest");
  await waitForCallCount(calls, 1);
  await expect(page.locator("#opportunity-order")).toBeHidden();
  expect(apiParameters(calls[0])).toEqual([
    ["q", "Springfield"],
    ["order", "soonest"]
  ]);

  await page.locator(".candidate-list").getByRole("button", { name: /Prague, Czechia/ }).click();
  await waitForCallCount(calls, 2);
  expect(apiParameters(calls[1])).toEqual([
    ["locationId", "prague-cz"],
    ["order", "soonest"]
  ]);
  await expect(page).toHaveURL("/search?locationId=prague-cz&order=soonest");
  await expect(page.locator("#opportunity-order")).toBeVisible();
  await expect(page.getByRole("radio", { name: "Soonest" })).toBeChecked();
});

test("sends explicit Best match once and canonicalizes only after success", async ({ page }) => {
  var releaseResponse = function () {};
  const responseGate = new Promise(resolve => {
    releaseResponse = function () { resolve(); };
  });
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    calls.push(apiCall(route.request()));
    await responseGate;
    await fulfill(route, 200, successfulResponse());
  });

  await page.goto("/search?q=Prague&order=best_match");
  await waitForCallCount(calls, 1);
  expect(apiParameters(calls[0])).toEqual([
    ["q", "Prague"],
    ["order", "best_match"]
  ]);
  await expect(page).toHaveURL("/search?q=Prague&order=best_match");

  releaseResponse();
  await expect(page.getByRole("heading", { name: "Prague, Czechia" })).toBeVisible();
  await expect(page).toHaveURL("/search?q=Prague");
  await expect(page.getByRole("radio", { name: "Best match" })).toBeChecked();
});

for (const scenario of [
  {
    name: "present empty order through product GET",
    url: "/search?q=Prague&order=",
    expectedOrder: "",
    preferences: null,
    method: "GET"
  },
  {
    name: "unsupported order through active-preference POST",
    url: "/search?q=Prague&order=nearest",
    expectedOrder: "nearest",
    preferences: ALTITUDE_PREFERENCES,
    method: "POST"
  }
]) {
  test("sends " + scenario.name + " to the invalid-request flow", async ({ page }) => {
    if (scenario.preferences) {
      await page.addInitScript(({ key, value }) => {
        window.localStorage.setItem(key, JSON.stringify(value));
      }, { key: PREFERENCE_KEY, value: scenario.preferences });
    }
    const calls = await captureApiCalls(page, function () {
      return {
        statusCode: 400,
        payload: {
          status: "invalid_request",
          message: "Order must be best_match or soonest."
        }
      };
    });

    await page.goto(scenario.url);
    await waitForCallCount(calls, 1);
    const requestUrl = new URL(calls[0].url);
    expect(calls[0].method).toBe(scenario.method);
    expect(requestUrl.searchParams.has("order")).toBe(true);
    expect(requestUrl.searchParams.get("order")).toBe(scenario.expectedOrder);
    if (scenario.preferences) {
      expect(calls[0].body).toEqual({
        q: "Prague",
        preferences: scenario.preferences
      });
    }
    await expect(page.getByRole("heading", { name: "Check the location" })).toBeVisible();
    await expect(page.locator("#opportunity-order")).toBeHidden();
  });
}

test("hides order immediately for a new lookup and keeps selected Soonest in its request", async ({ page }) => {
  const calls = await captureApiCalls(page, function (call) {
    return new URL(call.url).searchParams.get("q") === "Unavailable"
      ? {
        statusCode: 503,
        payload: { status: "temporarily_unavailable", message: "Try again shortly." }
      }
      : successfulResponse();
  });

  await page.goto("/search?q=Prague&order=soonest");
  await waitForCallCount(calls, 1);
  await expect(page.locator("#opportunity-order")).toBeVisible();

  await page.getByLabel("City or town").fill("Unavailable");
  await page.getByRole("button", { name: "Find", exact: true }).click();
  await expect(page.locator("#opportunity-order")).toBeHidden();
  await waitForCallCount(calls, 2);
  expect(apiParameters(calls[1])).toEqual([
    ["q", "Unavailable"],
    ["order", "soonest"]
  ]);
  await expect(page.getByRole("heading", { name: "Lookup temporarily unavailable" }))
    .toBeVisible();
  await expect(page.locator("#opportunity-order")).toBeHidden();
});

test("hides order for one Moon pass and keeps its candidates chronological", async ({ page }) => {
  const response = successfulResponse();
  response.opportunities = response.opportunities.slice(0, 2);
  const calls = await captureApiCalls(page, function () { return response; });

  await page.goto("/search?q=Prague&order=soonest");
  await waitForCallCount(calls, 1);

  expect(apiParameters(calls[0])).toEqual([
    ["q", "Prague"],
    ["order", "soonest"]
  ]);
  await expect(page.getByRole("heading", { name: "Prague, Czechia" })).toBeVisible();
  await expect(page.locator(".rank-label")).toHaveText("Moon pass");
  await expect(page.locator(".pass-choice-card")).toHaveCount(2);
  await expect(page.locator(".pass-choice-explanation p")).toHaveText([
    "Fixture alternate recommendation for UI smoke checks.",
    "Fixture recommendation for UI smoke checks."
  ]);
  await expect(page.locator("#opportunity-order")).toBeHidden();
  await expect(page).toHaveURL("/search?q=Prague&order=soonest");
});

test("does not group candidates without a shared Moon pass ID", async ({ page }) => {
  const response = successfulResponse();
  response.opportunities = response.opportunities.slice(0, 2);
  response.opportunities.forEach(opportunity => {
    delete opportunity.moonPass.id;
    opportunity.id = "duplicate-opportunity-id";
  });
  const calls = await captureApiCalls(page, function () { return response; });

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  await expect(page.locator(".moon-pass-card")).toHaveCount(2);
  await expect(page.locator(".rank-label")).toHaveText(["Best match", "Option 2"]);
  await expect(page.locator("#opportunity-order")).toBeVisible();
});

test("documents that share links may contain order but never preferences", async ({ page }) => {
  await page.goto("/search");
  await expect(page.locator(".preference-context-note")).toContainText(
    "Share links include the location and may include the selected order. They never include preferences."
  );

  await page.goto("/about");
  await expect(page.locator("#privacy-and-providers")).toContainText(
    "Share links include the location and may include the selected order, but never include preferences."
  );
});

async function expectSoonestPresentation(page) {
  const cards = page.locator(".moon-pass-card");
  await expect(cards).toHaveCount(2);
  await expect(page.locator(".summary-count")).toHaveText("2 Moon passes · 4 candidate windows");
  await expect(cards.locator(".rank-label")).toHaveText(["Soonest", "Later pass 2"]);

  const laterChoices = cards.nth(1).locator(".pass-choice-card");
  await expect(laterChoices).toHaveCount(3);
  await expect(laterChoices.locator(".choice-badge"))
    .toHaveText(["Alternative", "Best", "Alternative"]);
  await expect(laterChoices.locator(".choice-rank"))
    .toHaveText(["Score 74", "Score 81", "Score 81"]);
  expect(await laterChoices.locator(".pass-choice-explanation p").allTextContents()).toEqual([
    "Earlier suggestion with a lower score.",
    "Higher score returned first among the tie.",
    "Equal score returned second among the tie."
  ]);
  await expect(cards.locator(".pass-choice-explanation summary"))
    .toHaveText([
      "Why this candidate scored this way",
      "Why this candidate scored this way",
      "Why this candidate scored this way",
      "Why this candidate scored this way"
    ]);
}

function soonestResponse() {
  const response = successfulResponse();
  const higher = response.opportunities[0];
  const lower = response.opportunities[1];
  const earliestPass = response.opportunities[2];
  const equal = clone(higher);

  higher.reason = "Higher score returned first among the tie.";
  equal.id = "fixture-pass-a-equal";
  equal.reason = "Equal score returned second among the tie.";
  equal.suggestedAt = "2026-07-04T06:50:00Z";
  equal.moonPath.suggested.at = equal.suggestedAt;
  lower.reason = "Earlier suggestion with a lower score.";
  earliestPass.reason = "Earliest pass suggestion.";
  earliestPass.suggestedAt = "2026-07-04T01:00:00Z";
  earliestPass.moonPath.suggested.at = earliestPass.suggestedAt;
  response.opportunities = [lower, higher, equal, earliestPass];
  return response;
}

function successfulResponse() {
  return clone(sourceFixture);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

async function captureApiCalls(page, responder) {
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const call = apiCall(route.request());
    calls.push(call);
    const result = responder ? responder(call) : successfulResponse();
    const statusCode = result.statusCode || 200;
    const payload = result.payload || result;
    await fulfill(route, statusCode, payload);
  });
  return calls;
}

function apiCall(request) {
  return {
    method: request.method(),
    url: request.url(),
    body: request.method() === "POST" ? request.postDataJSON() : null,
    order: new URL(request.url()).searchParams.get("order")
  };
}

function apiParameters(call) {
  return [...new URL(call.url).searchParams.entries()];
}

async function fulfill(route, status, payload) {
  await route.fulfill({
    status: status,
    contentType: "application/json",
    body: JSON.stringify(payload)
  });
}

async function waitForCallCount(calls, count) {
  await expect.poll(() => calls.length).toBe(count);
}

async function openDetails(page, selector) {
  const details = page.locator(selector);
  const open = await details.evaluate(element =>
    /** @type {HTMLDetailsElement} */ (element).open
  );
  if (!open) {
    await details.locator("summary").click();
  }
}
