import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const WEATHER_KEY = "moonService.weatherRanking.v1";
const MEMORY_ONLY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";
const UNSUPPORTED_NOTICE = "Saved preferences were discarded because their format is not supported.";
const fixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));
const ALTITUDE_PREFERENCES = {
  version: 1,
  altitudeDegrees: { minimum: 11, maximum: 30 }
};
const UNFILTERED_ATOM_HREF = "/feeds/atom?locationId=moon-service-3067696";
const FILTERED_ATOM_HREF = "/feeds/atom?opaque=server-owned%2Fdo-not-rebuild";
const PREFERENCE_WARNING_ID = "preference-link-warning";
const PREFERENCE_WARNING_TEXT = "The Atom feed and calendar links on this page contain your selected location and photography filters. Anyone with one of these links can see that information, including your preferred observation times and viewing direction (altitude and azimuth). Do not share these links if those details are private.";

test("uses balanced GET without storing a weather choice and fits both layouts", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  expect(calls[0].method).toBe("GET");
  expect(calls[0].body).toBeNull();
  expect([...new URL(calls[0].url).searchParams.entries()]).toEqual([["q", "Prague"]]);
  const recommendation = page.getByRole("radio", { name: "Moon Service recommendation" });
  const recommendationHelp = "Puts low-Moon results with useful light and promising weather first. That is what Moon Service is built to find.";
  await expect(recommendation).toBeChecked();
  await expect(recommendation).toHaveAttribute("aria-describedby", "moon-service-recommendation-help");
  await expect(page.locator("#moon-service-recommendation-help")).toHaveText(recommendationHelp);
  const recommendationTip = page.locator("input[value=balanced] + span [role=note]");
  await expect(recommendationTip).toHaveAttribute("title", recommendationHelp);
  await expect(recommendationTip).toHaveAttribute("tabindex", "0");
  expect(await storedWeather(page)).toBeNull();
  await expect(page.getByText("Limits rule out results. Weather can change which result comes first.")).toBeVisible();
  await expect(page.getByRole("group", { name: "Weather in ranking" })).toBeVisible();
  await expectUnfilteredAtom(page);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveCount(0);
  const calendarDescriptions = await page.getByRole(
    "link", { name: "Download calendar event", exact: true }
  ).evaluateAll(links => links.map(link => link.getAttribute("aria-describedby")));
  expect(calendarDescriptions).toEqual(Array(fixture.opportunities.length).fill(null));

  const horizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(horizontalOverflow).toBeLessThanOrEqual(1);
});

test("ignores a stray filtered Atom link when applied metadata selects all-off", async ({ page }) => {
  const calls = await captureApiCalls(page, call => successfulResponse(call, {
    links: { atomWithFilters: FILTERED_ATOM_HREF }
  }));

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  await expectUnfilteredAtom(page);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveCount(0);
});

test("loads a saved weather choice and keeps soonest in the POST", async ({ page }) => {
  await preloadWeather(page, "prefer_clear");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague&order=soonest");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  expect(calls[0].method).toBe("POST");
  expect([...new URL(calls[0].url).searchParams.entries()]).toEqual([["order", "soonest"]]);
  expect(calls[0].body).toEqual({ q: "Prague", weatherRanking: "prefer_clear" });
  await expect(page.getByRole("radio", { name: "Prefer clear skies" })).toBeChecked();
  expect(await storedWeather(page)).toBe("prefer_clear");
  await expect(page).toHaveURL("/search?q=Prague&order=soonest");
  await expectNoOldFilteredAtomLabel(page);
});

test("removes a stored balanced choice and uses the default", async ({ page }) => {
  await preloadWeather(page, "balanced");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  expect(calls[0].method).toBe("GET");
  await expect(page.getByRole("radio", { name: "Moon Service recommendation" })).toBeChecked();
  expect(await storedWeather(page)).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toBeHidden();
});

test("discards an unknown stored weather choice", async ({ page }) => {
  await preloadWeather(page, "storm_focus");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  expect(calls[0].method).toBe("GET");
  await expect(page.getByRole("radio", { name: "Moon Service recommendation" })).toBeChecked();
  expect(await storedWeather(page)).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toHaveText(UNSUPPORTED_NOTICE);
});

test("uses balanced after a storage read failure and keeps later changes in memory", async ({ page }) => {
  await blockStorageMethod(page, "getItem");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("GET");
  await expect(page.locator("#preference-storage-notice")).toHaveText(MEMORY_ONLY_NOTICE);

  await openPreferences(page);
  await page.getByRole("radio", { name: "Prefer clear skies" }).check();
  await applyPreferences(page);
  await waitForCallCount(calls, 2);
  expect(calls[1].body).toEqual({ q: "Prague", weatherRanking: "prefer_clear" });

  await searchFor(page, "Kyoto");
  await waitForCallCount(calls, 3);
  expect(calls[2].body).toEqual({ q: "Kyoto", weatherRanking: "prefer_clear" });
});

test("keeps an applied weather choice in memory when storage writes fail", async ({ page }) => {
  await blockStorageMethod(page, "setItem");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByRole("radio", { name: "Don't use weather in ranking" }).check();
  await applyPreferences(page);
  await waitForCallCount(calls, 2);

  expect(calls[1].body).toEqual({ q: "Prague", weatherRanking: "ignore_weather" });
  expect(await storedWeather(page)).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toHaveText(MEMORY_ONLY_NOTICE);

  await searchFor(page, "Kyoto");
  await waitForCallCount(calls, 3);
  expect(calls[2].body).toEqual({ q: "Kyoto", weatherRanking: "ignore_weather" });
});

test("keeps balanced in memory when reset cannot remove the saved choice", async ({ page }) => {
  await preloadWeather(page, "prefer_clear");
  await blockStorageMethod(page, "removeItem");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByRole("button", { name: "Reset all preferences" }).click();
  await waitForCallCount(calls, 2);

  expect(calls[1].method).toBe("GET");
  expect(calls[1].body).toBeNull();
  expect(await storedWeather(page)).toBe("prefer_clear");
  await expect(page.locator("#preference-storage-notice")).toHaveText(MEMORY_ONLY_NOTICE);

  await searchFor(page, "Kyoto");
  await waitForCallCount(calls, 3);
  expect(calls[2].method).toBe("GET");
  expect(calls[2].body).toBeNull();
});

test("moves through native weather radios without submitting", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);

  const balanced = page.getByRole("radio", { name: "Moon Service recommendation" });
  const preferClear = page.getByRole("radio", { name: "Prefer clear skies" });
  const ignoreWeather = page.getByRole("radio", { name: "Don't use weather in ranking" });
  await balanced.focus();
  await page.keyboard.press("ArrowDown");
  await expect(preferClear).toBeChecked();
  await expect(preferClear).toBeFocused();
  await page.keyboard.press("ArrowDown");
  await expect(ignoreWeather).toBeChecked();
  await expect(ignoreWeather).toBeFocused();
  expect(calls).toHaveLength(1);
  expect(await storedWeather(page)).toBeNull();
});

test("applies and stores a weather-only choice without changing the browser URL", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByRole("radio", { name: "Prefer clear skies" }).check();
  await applyPreferences(page);
  await waitForCallCount(calls, 2);

  expect(calls[1].method).toBe("POST");
  expect(calls[1].body).toEqual({ q: "Prague", weatherRanking: "prefer_clear" });
  expect(await storedWeather(page)).toBe("prefer_clear");
  await expect(page).toHaveURL("/search?q=Prague");
  await expectNoOldFilteredAtomLabel(page);
  await expectPreferenceBearingActions(page, FILTERED_ATOM_HREF);
});

test("keeps hard limits separate and omits balanced from their POST", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await openPreferences(page);
  await page.getByLabel("Limit Moon altitude").check();
  await page.getByRole("slider", { name: "Minimum Moon altitude" }).focus();
  await page.keyboard.press("ArrowUp");
  await page.getByRole("radio", { name: "Prefer clear skies" }).check();
  await applyPreferences(page);
  await waitForCallCount(calls, 2);

  expect(calls[1].body).toEqual({
    preferences: ALTITUDE_PREFERENCES,
    weatherRanking: "prefer_clear",
    q: "Prague"
  });
  await expect(page.locator("#hard-preference-count")).toHaveText("1 active");
  await expect(page.locator("#weather-ranking-summary")).toHaveText(" · Prefer clear skies");
  await expectPreferenceBearingActions(page, FILTERED_ATOM_HREF);

  await openPreferences(page);
  await page.getByRole("radio", { name: "Moon Service recommendation" }).check();
  await applyPreferences(page);
  await waitForCallCount(calls, 3);

  expect(calls[2].body).toEqual({ preferences: ALTITUDE_PREFERENCES, q: "Prague" });
  expect(calls[2].body.weatherRanking).toBeUndefined();
  expect(await storedWeather(page)).toBeNull();
  await expect(page.locator("#hard-preference-count")).toHaveText("1 active");
  await expect(page.locator("#weather-ranking-summary")).toBeHidden();
  await expectPreferenceBearingActions(page, FILTERED_ATOM_HREF);
});

const invalidFilteredAtomCases = [
  { name: "absent", value: undefined },
  { name: "non-string", value: 42 },
  { name: "blank", value: " \t " }
];

for (const scenario of invalidFilteredAtomCases) {
  test("keeps the calendar warning when filtered Atom data is " + scenario.name, async ({ page }) => {
    await preloadWeather(page, "prefer_clear");
    const calls = await captureApiCalls(page, call => {
      const payload = successfulResponse(call);
      if (scenario.value === undefined) {
        delete payload.links;
      } else {
        payload.links = { atomWithFilters: scenario.value };
      }
      return payload;
    });

    await page.goto("/search?q=Prague");
    await waitForCallCount(calls, 1);

    await expectPreferenceBearingActions(page, null);
  });
}

test("keeps the filtered Atom warning without calendar actions", async ({ page }) => {
  await preloadWeather(page, "prefer_clear");
  const calls = await captureApiCalls(page, call => {
    const payload = successfulResponse(call);
    payload.opportunities.forEach(opportunity => delete opportunity.links.ics);
    return payload;
  });

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const atom = page.getByRole("button", { name: "Copy Atom feed link", exact: true });
  await expect(atom).toHaveAttribute("data-share-url", await absoluteUrl(page, FILTERED_ATOM_HREF));
  await expect(atom).toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveText(PREFERENCE_WARNING_TEXT);
  await expect(page.getByRole("link", { name: "Download calendar event" })).toHaveCount(0);
  await expectNoOldFilteredAtomLabel(page);
});

test("omits the warning when applied metadata has no usable action", async ({ page }) => {
  await preloadWeather(page, "prefer_clear");
  const calls = await captureApiCalls(page, call => {
    const payload = successfulResponse(call);
    payload.links = { atomWithFilters: " \t " };
    delete payload.opportunities[0].links.ics;
    payload.opportunities[1].links.ics = 42;
    payload.opportunities[2].links.ics = " \t ";
    return payload;
  });

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  await expect(page.getByRole(
    "button", { name: "Copy Atom feed link", exact: true }
  )).toHaveCount(0);
  await expectNoOldFilteredAtomLabel(page);
  await expect(page.getByRole(
    "link", { name: "Download calendar event", exact: true }
  )).toHaveCount(0);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveCount(0);
});

test("leaves weather ranking out of planning requests", async ({ page }) => {
  await preloadWeather(page, "prefer_clear");
  const calls = await captureApiCalls(page, call => {
    if (call.path === "/api/opportunities/planning") {
      return { status: "temporarily_unavailable", message: "Try again shortly." };
    }
    return successfulResponse(call, {
      opportunities: [],
      emptyReason: { code: "no_opportunities", text: "No opportunity was found." }
    });
  });

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await page.getByRole("button", { name: "Find the next matching Moon date" }).click();
  await waitForCallCount(calls, 2);

  const planning = calls[1];
  expect(planning.path).toBe("/api/opportunities/planning");
  expect(planning.method).toBe("POST");
  expect(planning.body).toEqual({
    locationId: fixture.location.id,
    preferences: { version: 1 }
  });
  expect(planning.body.weatherRanking).toBeUndefined();
});

const scoreModes = [
  { mode: "balanced", label: "Moon Service recommendation" },
  { mode: "prefer_clear", label: "Prefer clear skies" },
  { mode: "ignore_weather", label: "Don't use weather in ranking" }
];

for (const scenario of scoreModes) {
  test("labels " + scenario.mode + " scores exactly", async ({ page }) => {
    if (scenario.mode !== "balanced") await preloadWeather(page, scenario.mode);
    const calls = await captureApiCalls(page);

    await page.goto("/search?q=Prague");
    await waitForCallCount(calls, 1);

    await expect(page.locator(".moon-pass-card").first().locator(".score-block .score-label"))
      .toHaveText(scenario.label);
    const choiceLabels = await page.locator(".choice-score-basis").allTextContents();
    expect(new Set(choiceLabels)).toEqual(new Set([scenario.label]));
    const detailLabels = await page.locator(".score-details summary").allTextContents();
    expect(new Set(detailLabels)).toEqual(new Set(["Score details · " + scenario.label]));
  });
}

test("hides ignored weather score rows but keeps raw sky facts", async ({ page }) => {
  await preloadWeather(page, "ignore_weather");
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const scoreTerms = await page.locator(".score-details dt").allTextContents();
  expect(scoreTerms).not.toContain("Weather");
  expect(scoreTerms).not.toContain("Confidence");
  const skyValues = await page.locator(".pass-metric-grid").evaluateAll(grids =>
    grids.map(grid => Array.from(grid.querySelectorAll("dt")).find(term =>
      term.textContent === "Sky"
    )?.nextElementSibling?.textContent?.trim() || "")
  );
  expect(skyValues.length).toBeGreaterThan(0);
  expect(skyValues.every(Boolean)).toBe(true);
});

/**
 * @typedef {{ method: string, path: string, url: string, body: any }} ApiCall
 */

async function captureApiCalls(page, responseForCall = successfulResponse) {
  /** @type {ApiCall[]} */
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const rawBody = request.postData();
    const call = {
      method: request.method(),
      path: new URL(request.url()).pathname,
      url: request.url(),
      body: rawBody === null ? null : JSON.parse(rawBody)
    };
    calls.push(call);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(responseForCall(call))
    });
  });
  return calls;
}

function successfulResponse(call, overrides = {}) {
  const payload = clone(fixture);
  const mode = call.body?.weatherRanking;
  if (mode === "prefer_clear" || mode === "ignore_weather") {
    payload.appliedWeatherRanking = mode;
  }
  if (mode === "ignore_weather") {
    payload.opportunities.forEach(opportunity => {
      delete opportunity.components.weatherFit;
      delete opportunity.components.forecastConfidence;
    });
  }
  if (call.body?.preferences) {
    payload.appliedPreferenceVersion = 1;
    payload.normalizedActiveFilters = clone(call.body.preferences);
    delete payload.normalizedActiveFilters.version;
    payload.excludedSampleCount = 0;
    payload.ignoredPreferenceFields = [];
    payload.ignoredPreferenceFieldCount = 0;
    payload.additionalIgnoredPreferenceFieldCount = 0;
  }
  if (mode === "prefer_clear" || mode === "ignore_weather" || call.body?.preferences) {
    payload.links = { atomWithFilters: FILTERED_ATOM_HREF };
  }
  return Object.assign(payload, overrides);
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}

async function waitForCallCount(calls, count) {
  await expect.poll(() => calls.length).toBe(count);
}

async function expectUnfilteredAtom(page) {
  const unfiltered = page.getByRole("button", { name: "Copy Atom feed link", exact: true });
  await expect(unfiltered).toHaveCount(1);
  await expect(unfiltered).toHaveAttribute("data-share-url", await absoluteUrl(page, UNFILTERED_ATOM_HREF));
  await expect(unfiltered).not.toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
  await expectNoOldFilteredAtomLabel(page);
}

async function expectPreferenceBearingActions(page, filteredHref) {
  const warning = page.locator("#" + PREFERENCE_WARNING_ID);
  await expect(warning).toHaveCount(1);
  await expect(warning).toBeVisible();
  await expect(warning).toHaveText(PREFERENCE_WARNING_TEXT);

  const calendarLinks = page.getByRole(
    "link", { name: "Download calendar event", exact: true }
  );
  await expect(calendarLinks).toHaveCount(fixture.opportunities.length);
  expect(await calendarLinks.evaluateAll(links => links.map(
    link => link.getAttribute("aria-describedby")
  ))).toEqual(Array(fixture.opportunities.length).fill(PREFERENCE_WARNING_ID));

  const atom = page.getByRole("button", { name: "Copy Atom feed link", exact: true });
  if (filteredHref === null) {
    await expect(atom).toHaveCount(0);
  } else {
    await expect(atom).toHaveCount(1);
    await expect(atom).toHaveAttribute("data-share-url", await absoluteUrl(page, filteredHref));
    await expect(atom).toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
    await atom.focus();
    await expect(atom).toBeFocused();
  }
  await expectNoOldFilteredAtomLabel(page);
  await calendarLinks.first().focus();
  await expect(calendarLinks.first()).toBeFocused();

  expect(await page.evaluate(warningId => {
    const notice = document.getElementById(warningId);
    const list = document.querySelector(".opportunity-list");
    return Boolean(notice && list
      && (notice.compareDocumentPosition(list) & Node.DOCUMENT_POSITION_FOLLOWING));
  }, PREFERENCE_WARNING_ID)).toBe(true);
}

async function expectNoOldFilteredAtomLabel(page) {
  await expect(page.getByRole(
    "link", { name: "Atom feed with these filters", exact: true }
  )).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Atom feed", exact: true })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Copy link", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Open share link", exact: true })).toHaveCount(0);
}

async function absoluteUrl(page, path) {
  return page.evaluate(value => window.location.origin + value, path);
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => /** @type {HTMLDetailsElement} */ (node).open)) {
    await details.locator("summary").click();
  }
}

async function applyPreferences(page) {
  await page.getByRole("button", { name: "Use these limits and weather choice" }).click();
}

async function searchFor(page, query) {
  await page.getByLabel("City or town").fill(query);
  await page.getByRole("button", { name: "Find", exact: true }).click();
}

async function preloadWeather(page, value) {
  await page.addInitScript(({ key, stored }) => {
    window.localStorage.setItem(key, stored);
  }, { key: WEATHER_KEY, stored: value });
}

async function storedWeather(page) {
  return page.evaluate(key => window.localStorage.getItem(key), WEATHER_KEY);
}

async function blockStorageMethod(page, method) {
  await page.addInitScript(({ key, methodName }) => {
    const original = Storage.prototype[methodName];
    Storage.prototype[methodName] = function (candidateKey, ...args) {
      if (candidateKey === key) throw new Error("Weather preference storage is blocked");
      return original.call(this, candidateKey, ...args);
    };
  }, { key: WEATHER_KEY, methodName: method });
}
