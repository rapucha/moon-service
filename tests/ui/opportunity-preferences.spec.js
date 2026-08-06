import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const MEMORY_ONLY_NOTICE = "Preference storage is unavailable. Changes last only on this page; previously saved preferences may return after reload.";
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

const ALTITUDE_PREFERENCES = {
  version: 1,
  altitudeDegrees: {
    minimum: 3.14,
    maximum: 18
  }
};

const SLIDER_ALTITUDE_PREFERENCES = {
  version: 1,
  altitudeDegrees: {
    minimum: 11,
    maximum: 30
  }
};

const CLOCK_PREFERENCES = {
  ...ALTITUDE_PREFERENCES,
  time: {
    mode: "local_clock",
    window: {
      start: "22:30",
      end: "02:15"
    }
  }
};

const AMBIENT_PREFERENCES = {
  ...ALTITUDE_PREFERENCES,
  time: {
    mode: "light_bucket",
    buckets: [
      "golden_hour",
      "civil_twilight",
      "night"
    ]
  }
};

const ALL_PREFERENCES = {
  ...AMBIENT_PREFERENCES,
  azimuthDegrees: { included: { start: 330, end: 30 } },
  namedPhases: ["waxing_crescent", "waning_crescent"],
  brightLimbOrientationDegrees: [{ start: 337.5, end: 22.5 }]
};
const EMPTY_PREFERENCE_RESULT = {
  opportunities: [],
  emptyReason: {
    code: "no_opportunities_match_preferences",
    text: "No opportunities match the active preferences."
  }
};

/**
 * @typedef {{
 *   method: string,
 *   url: string,
 *   body: any
 * }} ApiCall
 */

test("uses GET by default and keeps the limits disclosure responsive", async ({ page }, testInfo) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  await expect(page.getByRole("heading", { name: "Prague, Czechia" })).toBeVisible();

  expect(calls[0].method).toBe("GET");
  expect(calls[0].body).toBeNull();
  const requestUrl = new URL(calls[0].url);
  expect(requestUrl.pathname).toBe("/api/opportunities");
  expect([...requestUrl.searchParams.entries()]).toEqual([["q", "Prague"]]);

  const details = page.locator("#opportunity-preferences");
  const summary = details.locator("summary");
  const workspace = page.locator(".workspace");
  const isMobile = testInfo.project.name === "mobile";

  await expect(details).toHaveJSProperty("open", !isMobile);
  await expect(summary).toContainText(/Limits\s*None active/);

  if (isMobile) {
    await expect(summary).toBeVisible();
    await summary.focus();
    await page.keyboard.press("Enter");
    await expect(details).toHaveJSProperty("open", true);
    await page.keyboard.press("Enter");
    await expect(details).toHaveJSProperty("open", false);
    await page.keyboard.press("Enter");
    await expect(details).toHaveJSProperty("open", true);

    const detailsBox = await details.boundingBox();
    const workspaceBox = await workspace.boundingBox();
    expect(detailsBox).not.toBeNull();
    expect(workspaceBox).not.toBeNull();
    expect(detailsBox.y).toBeLessThan(workspaceBox.y);
  } else {
    await expect(summary).toBeHidden();
    const detailsBox = await details.boundingBox();
    const workspaceBox = await workspace.boundingBox();
    expect(detailsBox).not.toBeNull();
    expect(workspaceBox).not.toBeNull();
    expect(detailsBox.x).toBeLessThan(workspaceBox.x);
  }

  const horizontalOverflow = await page.evaluate(() =>
    document.documentElement.scrollWidth - document.documentElement.clientWidth
  );
  expect(horizontalOverflow).toBeLessThanOrEqual(1);
});

test("keeps focus in an invalid clock field without requesting", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  const status = page.locator("#preference-form-status");
  const apply = page.getByRole("button", { name: "Use these limits" });
  await page.getByRole("radio", { name: "Local clock window" }).check();
  const start = page.getByLabel("Local clock window start");
  await start.fill("22:30");
  await page.getByLabel("Local clock window end").fill("22:30");
  await apply.click();

  await expect(status).toHaveText(
    "The clock window needs different start and end times in HH:mm format."
  );
  await expect(start).toBeFocused();
  expect(calls).toHaveLength(0);
});

test("uses one fixed pair of 24-hour fields for the local clock window", async ({ page }) => {
  await page.goto("/search?q=Prague");
  await openPreferences(page);
  await page.getByRole("radio", { name: "Local clock window" }).check();

  const start = page.getByLabel("Local clock window start");
  await expect(start).toHaveAttribute("type", "text");
  await expect(start).toHaveAttribute("placeholder", "HH:mm");
  await expect(page.locator(".preference-clock-row")).toHaveCount(1);
  await expect(page.getByRole("button", { name: /window/i })).toHaveCount(0);
  await expect(page.locator("#preference-clock-help")).toContainText("24-hour HH:mm");
});

test("posts, stores, and restores altitude and one cross-midnight clock window", async ({ page }) => {
  await recordFetchOptions(page);
  await page.goto("/about");
  await page.evaluate(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
  }, { key: STORAGE_KEY, value: ALTITUDE_PREFERENCES });
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  await expect(page.getByRole("slider", { name: "Minimum Moon altitude" }))
    .toHaveAttribute("aria-valuenow", "3.14");
  await expect(page.getByRole("slider", { name: "Maximum Moon altitude" }))
    .toHaveAttribute("aria-valuenow", "18");
  await page.getByRole("radio", { name: "Local clock window" }).check();
  await page.getByLabel("Local clock window start").fill("22:30");
  await page.getByLabel("Local clock window end").fill("02:15");
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: CLOCK_PREFERENCES
  });
  const postUrl = new URL(calls[0].url);
  expect(postUrl.pathname).toBe("/api/opportunities");
  expect(postUrl.search).toBe("");
  expect(await lastFetchOptions(page)).toEqual({
    method: "POST",
    cache: "no-store"
  });

  await expect(page).toHaveURL("/search?q=Prague");
  await expect(page.locator("#preference-count")).toHaveText("2 active");
  await expect(page.locator("#preference-timezone-note"))
    .toHaveText("Clock window uses Europe/Prague.");
  expect(await storedPreferences(page)).toEqual(CLOCK_PREFERENCES);

  await page.reload();
  await waitForCallCount(calls, 2);
  await openPreferences(page);
  expect(calls[1].method).toBe("POST");
  expect(calls[1].body).toEqual({
    q: "Prague",
    preferences: CLOCK_PREFERENCES
  });
  await expect(page.getByLabel("Limit Moon altitude")).toBeChecked();
  await expect(page.getByRole("slider", { name: "Minimum Moon altitude" }))
    .toHaveAttribute("aria-valuenow", "3.14");
  await expect(page.getByRole("slider", { name: "Maximum Moon altitude" }))
    .toHaveAttribute("aria-valuenow", "18");
  await expect(page.getByRole("radio", { name: "Local clock window" })).toBeChecked();
  await expect(page.getByLabel("Local clock window start")).toHaveValue("22:30");
  await expect(page.getByLabel("Local clock window end")).toHaveValue("02:15");
});

test("switches, removes the time-and-light group in its control, and resets", async ({ page }, testInfo) => {
  await preloadState(page, CLOCK_PREFERENCES);
  const calls = await captureApiCalls(page);

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  await page.getByLabel("Ambient light").check();
  await expect(page.getByLabel("Golden hour")).toBeChecked();
  await page.getByLabel("Civil twilight").check();
  await page.getByLabel("Night").check();
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");
  expect(new URL(calls[0].url).search).toBe("");
  expect(calls[0].body).toEqual({
    locationId: "moon-service-3067696",
    preferences: AMBIENT_PREFERENCES
  });
  await expect(page.locator("#preference-count")).toHaveText("2 active");

  await openPreferences(page);
  await page.getByLabel("No time limit").check();
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("POST");
  expect(calls[1].body).toEqual({
    locationId: "moon-service-3067696",
    preferences: ALTITUDE_PREFERENCES
  });
  await expect(page.locator("#preference-count")).toHaveText("1 active");
  expect(await storedPreferences(page)).toEqual(ALTITUDE_PREFERENCES);

  await openPreferences(page);
  await page.getByLabel("Ambient light").check();
  expect(await page.locator("#preference-light-editor input:checked")
    .evaluateAll(inputs => inputs.map(input => /** @type {HTMLInputElement} */ (input).value)))
    .toEqual(["golden_hour", "civil_twilight", "night"]);
  await page.getByRole("button", { name: "Reset all preferences" }).click();

  await waitForCallCount(calls, 3);
  expect(calls[2].method).toBe("GET");
  expect(calls[2].body).toBeNull();
  expect([...new URL(calls[2].url).searchParams.entries()])
    .toEqual([["locationId", "moon-service-3067696"]]);
  await expect(page.locator("#active-preference-summary")).toHaveCount(0);
  await expect(page.locator("#preference-count")).toHaveText("None active");
  const resetFocus = testInfo.project.name === "mobile"
    ? page.locator("#opportunity-preferences > summary")
    : page.getByLabel("Limit Moon altitude");
  await expect(resetFocus).toBeFocused();
  expect(await storedPreferences(page)).toBeNull();
});

test("normalizes supported version-one storage before sending or saving it", async ({ page }) => {
  await preloadState(page, {
    ...CLOCK_PREFERENCES,
    altitudeDegrees: {
      ...CLOCK_PREFERENCES.altitudeDegrees,
      futureAltitudeRule: {
        inclusive: true
      }
    },
    time: {
      ...CLOCK_PREFERENCES.time,
      futureTimeRule: "location_local",
      window: {
        ...CLOCK_PREFERENCES.time.window,
        futureWindowRule: true
      }
    },
    future: {
      nested: true
    }
  });
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  expect(calls[0].method).toBe("POST");
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: CLOCK_PREFERENCES
  });
  expect(await storedPreferences(page)).toEqual(CLOCK_PREFERENCES);
  await expect(page.locator("#preference-count")).toHaveText("2 active");
});

const discardedStorageCases = [
  {
    name: "malformed JSON",
    raw: "{"
  },
  {
    name: "an unsupported version",
    raw: JSON.stringify({
      version: 2,
      altitudeDegrees: {
        minimum: 3,
        maximum: 18
      }
    })
  },
  {
    name: "invalid supported fields",
    raw: JSON.stringify({
      version: 1,
      time: {
        mode: "light_bucket",
        buckets: []
      }
    })
  },
  {
    name: "the former plural clock-window shape",
    raw: JSON.stringify({
      version: 1,
      time: {
        mode: "local_clock",
        windows: [{
          start: "22:30",
          end: "02:15"
        }]
      }
    })
  }
];

for (const storageCase of discardedStorageCases) {
  test("discards stored preferences with " + storageCase.name, async ({ page }) => {
    await preloadRawState(page, storageCase.raw);
    const calls = await captureApiCalls(page);

    await page.goto("/search?q=Prague");
    await waitForCallCount(calls, 1);

    expect(calls[0].method).toBe("GET");
    expect(calls[0].body).toBeNull();
    expect(await storedPreferences(page)).toBeNull();
    await expect(page.locator("#preference-storage-notice")).toBeVisible();
    await expect(page.locator("#preference-storage-notice"))
      .toContainText("Saved preferences were discarded");
    await expect(page.locator("#preference-count")).toHaveText("None active");
  });
}

test("continues with default GET when preference storage reads fail at load", async ({ page }) => {
  await page.addInitScript(key => {
    const originalGetItem = Storage.prototype.getItem;
    Storage.prototype.getItem = function (candidateKey) {
      if (candidateKey === key) {
        throw new Error("Storage read is blocked");
      }
      return originalGetItem.call(this, candidateKey);
    };
  }, STORAGE_KEY);
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  expect(calls[0].method).toBe("GET");
  expect(calls[0].body).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toBeVisible();
  await expect(page.locator("#preference-storage-notice"))
    .toHaveText(MEMORY_ONLY_NOTICE);

  await page.getByLabel("City or town").fill("Kyoto");
  await page.getByRole("button", { name: "Find" }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("GET");
  expect(calls[1].body).toBeNull();
  expect([...new URL(calls[1].url).searchParams.entries()]).toEqual([["q", "Kyoto"]]);
});

test("continues with GET in page memory when reset cannot remove storage", async ({ page }) => {
  await preloadState(page, ALTITUDE_PREFERENCES);
  await page.addInitScript(key => {
    const originalRemoveItem = Storage.prototype.removeItem;
    Storage.prototype.removeItem = function (candidateKey) {
      if (candidateKey === key) {
        throw new Error("Storage removal is blocked");
      }
      return originalRemoveItem.call(this, candidateKey);
    };
  }, STORAGE_KEY);
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");

  await openPreferences(page);
  await page.getByRole("button", { name: "Reset all preferences" }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("GET");
  expect(calls[1].body).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toBeVisible();
  await expect(page.locator("#preference-storage-notice"))
    .toHaveText(MEMORY_ONLY_NOTICE);
  await expect(page.locator("#active-preference-summary")).toHaveCount(0);
  expect(await storedPreferences(page)).toEqual(ALTITUDE_PREFERENCES);

  await page.getByLabel("City or town").fill("Kyoto");
  await page.getByRole("button", { name: "Find" }).click();

  await waitForCallCount(calls, 3);
  expect(calls[2].method).toBe("GET");
  expect(calls[2].body).toBeNull();
  expect([...new URL(calls[2].url).searchParams.entries()]).toEqual([["q", "Kyoto"]]);
});

test("keeps preferences in page memory when browser storage blocks writes", async ({ page }) => {
  await page.addInitScript(key => {
    const originalSetItem = Storage.prototype.setItem;
    Storage.prototype.setItem = function (candidateKey, value) {
      if (candidateKey === key) {
        throw new Error("Storage is blocked");
      }
      return originalSetItem.call(this, candidateKey, value);
    };
  }, STORAGE_KEY);
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  await page.getByLabel("Limit Moon altitude").check();
  await page.getByRole("slider", { name: "Minimum Moon altitude" }).focus();
  await page.keyboard.press("ArrowUp");
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 1);
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: SLIDER_ALTITUDE_PREFERENCES
  });
  await expect(page.locator("#preference-storage-notice")).toBeVisible();
  await expect(page.locator("#preference-storage-notice"))
    .toHaveText(MEMORY_ONLY_NOTICE);
  expect(await storedPreferences(page)).toBeNull();

  await page.getByLabel("City or town").fill("Kyoto");
  await page.getByRole("button", { name: "Find" }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].body).toEqual({
    q: "Kyoto",
    preferences: SLIDER_ALTITUDE_PREFERENCES
  });
});

test("renders server preference metadata as safe text without changing the share URL", async ({ page }) => {
  const unsafePath = "/future<img src=x onerror=\"window.preferenceInjectionRan=1\">";
  const normalizedActiveFilters = { ...ALL_PREFERENCES };
  delete normalizedActiveFilters.version;
  await preloadState(page, ALL_PREFERENCES);
  const calls = await captureApiCalls(page, call => successfulResponse(call, {
    ...EMPTY_PREFERENCE_RESULT,
    forecastHorizonDays: 13,
    normalizedActiveFilters,
    ignoredPreferenceFields: [unsafePath],
    ignoredPreferenceFieldCount: 3,
    additionalIgnoredPreferenceFieldCount: 2,
    preferenceImpact: {
      unfilteredOpportunityCount: 1,
      filters: [
        impact("altitudeDegrees", 1, "2026-08-01T03:15:00Z"),
        impact("azimuthDegrees", 0),
        impact("time", 0),
        impact("namedPhases", 1),
        impact("brightLimbOrientationDegrees", 1)
      ]
    }
  }));

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  const noMatch = page.locator("details.status-panel.warning");
  const impactDetails = noMatch.locator(".preference-impact");
  await expect(noMatch).toHaveJSProperty("open", false);
  await expect(noMatch.locator("summary")).toContainText("No match — No opportunities found in the next 13 days");
  await noMatch.locator("summary").click();
  await expect(impactDetails).toContainText("Without preferences: 1 opportunity.");
  const rows = impactDetails.locator(".detail-grid > div");
  await expect(rows).toHaveCount(5);
  await expect(rows).toContainText([
    /Moon altitude\s*1 opportunity · 0 fewer\s*Next theoretical match/,
    /Moon direction\s*0 opportunities · 1 fewer · Largest reduction/,
    /Time & light\s*0 opportunities · 1 fewer · Largest reduction/,
    /Moon shape\s*1 opportunity · 0 fewer\s*No theoretical match/,
    /Bright-limb orientation\s*1 opportunity · 0 fewer\s*No theoretical match/
  ]);
  await expect(impactDetails).toContainText("Each preference is evaluated by itself with the others off.");
  await expect(page.locator("#preference-ignored-notice")).toContainText(
    "The server ignored 3 unsupported preference fields."
  );
  await expect(page.locator("#preference-ignored-notice")).toContainText(unsafePath);
  await expect(page.locator("#preference-ignored-notice"))
    .toContainText("2 more were not listed.");
  await expect(page.locator("img[src='x']")).toHaveCount(0);

  await expect(page.locator("#active-preference-summary")).toHaveCount(0);
  await expect(page.locator("#preference-count")).toHaveText("5 active");
  await expect(page.locator("#preference-timezone-note")).toBeHidden();
  await expect(noMatch.locator(":scope > summary .tooltip")).toHaveAttribute("title", "No candidate window matched this search.");
  await expect(page.locator(".summary-grid, .preference-impact .tooltip")).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Open share link" }))
    .toHaveAttribute("href", "/search?q=Prague");
  const currentUrl = new URL(page.url());
  expect([...currentUrl.searchParams.entries()]).toEqual([["q", "Prague"]]);
});

for (const scenario of [
  { name: "hides valid preference impact when an opportunity is found",
    state: ALTITUDE_PREFERENCES, empty: false, filters: [impact("altitudeDegrees", 1)] },
  { name: "hides a malformed empty-result preference impact",
    state: ALTITUDE_PREFERENCES, empty: true,
    filters: [impact("altitudeDegrees", 1, "2026-02-30T00:00:00Z")] },
  { name: "hides duplicate empty-result preference-impact filters",
    state: { ...ALTITUDE_PREFERENCES, namedPhases: ["full_moon"] }, empty: true,
    filters: [impact("altitudeDegrees", 1), impact("altitudeDegrees", 1)] }
]) {
  test(scenario.name, async ({ page }) => {
    await preloadState(page, scenario.state);
    const calls = await captureApiCalls(page, call => successfulResponse(call, {
      ...(scenario.empty ? EMPTY_PREFERENCE_RESULT : {}),
      preferenceImpact: { unfilteredOpportunityCount: 1, filters: scenario.filters }
    }));
    await page.goto("/search?q=Prague");
    await waitForCallCount(calls, 1);
    await expect(page.locator(".preference-impact")).toHaveCount(0);
  });
}

test("documents local preference storage, request use, and preference-free sharing", async ({ page }) => {
  await page.goto("/about");

  const privacy = page.locator("#privacy-and-providers");
  await expect(privacy).toContainText(
    "Moon altitude, availability, included and blocked compass sectors, selected Moon shapes, and bright-limb orientation preferences are stored in this browser."
  );
  await expect(privacy).toContainText(
    "Each search with active preferences sends them to the Moon Service server for that search only."
  );
  await expect(privacy).toContainText(
    "The server does not permanently store them"
  );
  await expect(privacy).toContainText(
    "Share links include the location and may include the selected order, but never include preferences."
  );
  await expect(privacy).toContainText(
    "A browser opening the link applies its own saved preferences, if any."
  );
});

async function captureApiCalls(page, responseForCall = successfulResponse) {
  /** @type {ApiCall[]} */
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const rawBody = request.postData();
    const call = {
      method: request.method(),
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
  const payload = /** @type {any} */ (JSON.parse(JSON.stringify(sourceFixture)));
  if (call.body && call.body.preferences) {
    payload.appliedPreferenceVersion = 1;
    payload.normalizedActiveFilters = { ...call.body.preferences };
    delete payload.normalizedActiveFilters.version;
    payload.excludedSampleCount = 0;
    payload.ignoredPreferenceFields = [];
    payload.ignoredPreferenceFieldCount = 0;
    payload.additionalIgnoredPreferenceFieldCount = 0;
  }
  return Object.assign(payload, overrides);
}

function impact(filter, count, nextMatchAt) {
  return nextMatchAt
    ? { filter, matchingOpportunityCount: count, status: "next_match", lookAheadDays: 200, nextMatchAt }
    : { filter, matchingOpportunityCount: count, status: "not_found", lookAheadDays: 200 };
}

async function waitForCallCount(calls, count) {
  await expect.poll(() => calls.length).toBe(count);
}

async function openPreferences(page) {
  const details = page.locator("#opportunity-preferences");
  const open = await details.evaluate(node =>
    /** @type {HTMLDetailsElement} */ (node).open
  );
  if (!open) {
    await details.locator("summary").click();
  }
}

async function preloadState(page, state) {
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
  }, {
    key: STORAGE_KEY,
    value: state
  });
}

async function preloadRawState(page, raw) {
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, value);
  }, {
    key: STORAGE_KEY,
    value: raw
  });
}

async function storedPreferences(page) {
  const raw = await page.evaluate(key => window.localStorage.getItem(key), STORAGE_KEY);
  return raw === null ? null : JSON.parse(raw);
}

async function recordFetchOptions(page) {
  await page.addInitScript(() => {
    const originalFetch = window.fetch;
    Reflect.set(window, "moonServiceFetchCalls", []);
    window.fetch = function (input, init) {
      const records = Reflect.get(window, "moonServiceFetchCalls");
      records.push({
        method: init && init.method ? init.method : "GET",
        cache: init && init.cache ? init.cache : null
      });
      return originalFetch.call(window, input, init);
    };
  });
}

async function lastFetchOptions(page) {
  return page.evaluate(() => {
    const records = Reflect.get(window, "moonServiceFetchCalls");
    return records[records.length - 1];
  });
}
