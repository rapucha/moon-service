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

const CLOCK_PREFERENCES = {
  ...ALTITUDE_PREFERENCES,
  time: {
    mode: "local_clock",
    windows: [
      {
        start: "22:30",
        end: "02:15"
      },
      {
        start: "04:30",
        end: "07:15"
      }
    ]
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

/**
 * @typedef {{
 *   method: string,
 *   url: string,
 *   body: any
 * }} ApiCall
 */

test("uses GET by default and keeps the hard-limit disclosure responsive", async ({ page }, testInfo) => {
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
  await expect(page.locator("#preference-count")).toHaveText("None active");

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

test("keeps focus in invalid altitude and clock fields without requesting", async ({ page }) => {
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  const minimum = page.getByLabel("Minimum");
  const maximum = page.getByLabel("Maximum");
  const status = page.locator("#preference-form-status");
  const apply = page.getByRole("button", { name: "Use these limits" });
  await page.getByLabel("Limit Moon altitude").check();
  await minimum.fill("");
  await maximum.fill("18");
  await apply.click();

  await expect(status).toHaveText(
    "Use an altitude range from 0° to 90°, with minimum not above maximum."
  );
  await expect(minimum).toBeFocused();
  expect(calls).toHaveLength(0);

  await minimum.fill("19");
  await maximum.fill("18");
  await apply.click();

  await expect(status).toContainText("minimum not above maximum");
  await expect(minimum).toBeFocused();
  expect(calls).toHaveLength(0);

  await minimum.fill("3.14");
  await page.getByLabel("Local clock windows").check();
  const start = page.getByLabel("Local clock window 1 start");
  await start.fill("22:30");
  await page.getByLabel("Local clock window 1 end").fill("22:30");
  await apply.click();

  await expect(status).toHaveText(
    "Each clock window needs different start and end times in HH:mm format."
  );
  await expect(start).toBeFocused();
  expect(calls).toHaveLength(0);
});

test("posts altitude and two cross-midnight clock windows and restores them", async ({ page }) => {
  await recordFetchOptions(page);
  const calls = await captureApiCalls(page);

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  await page.getByLabel("Limit Moon altitude").check();
  await page.getByLabel("Minimum").fill("3.14");
  await page.getByLabel("Maximum").fill("18");
  expect(await page.getByLabel("Minimum").evaluate(input =>
    /** @type {HTMLInputElement} */ (input).validity.stepMismatch
  )).toBe(false);
  await page.getByLabel("Local clock windows").check();
  await page.getByLabel("Local clock window 1 start").fill("22:30");
  await page.getByLabel("Local clock window 1 end").fill("02:15");
  await page.getByRole("button", { name: "Add another window" }).click();
  await page.getByLabel("Local clock window 2 start").fill("04:30");
  await page.getByLabel("Local clock window 2 end").fill("07:15");
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
  await expect(page.locator("#active-filter-list > li")).toHaveCount(2);
  await expect(page.locator("#active-filter-list > li").first())
    .toContainText("Moon altitude 3.14°–18°");
  await expect(page.locator("#active-filter-list > li").nth(1))
    .toContainText("Local time 22:30–02:15, 04:30–07:15");
  await expect(page.locator("#preference-count")).toHaveText("2 active");
  await expect(page.locator("#preference-timezone-note")).toBeVisible();
  await expect(page.locator("#preference-timezone-note")).toContainText("Europe/Prague");
  expect(await storedPreferences(page)).toEqual(CLOCK_PREFERENCES);

  await page.reload();
  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("POST");
  expect(calls[1].body).toEqual({
    q: "Prague",
    preferences: CLOCK_PREFERENCES
  });
  await expect(page.getByLabel("Limit Moon altitude")).toBeChecked();
  await expect(page.getByLabel("Minimum")).toHaveValue("3.14");
  await expect(page.getByLabel("Maximum")).toHaveValue("18");
  await expect(page.getByLabel("Local clock windows")).toBeChecked();
  await expect(page.getByLabel("Local clock window 1 start")).toHaveValue("22:30");
  await expect(page.getByLabel("Local clock window 1 end")).toHaveValue("02:15");
  await expect(page.getByLabel("Local clock window 2 start")).toHaveValue("04:30");
  await expect(page.getByLabel("Local clock window 2 end")).toHaveValue("07:15");
  await expect(page.locator("#active-filter-list > li")).toHaveCount(2);
});

test("switches to one ambient-light constraint, removes it as a group, and resets", async ({ page }, testInfo) => {
  await preloadState(page, CLOCK_PREFERENCES);
  const calls = await captureApiCalls(page);

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForCallCount(calls, 1);
  calls.length = 0;
  await openPreferences(page);

  await page.getByLabel("Ambient light").check();
  await page.getByLabel("Daylight").uncheck();
  await page.getByLabel("Golden hour").check();
  await page.getByLabel("Civil twilight").check();
  await page.getByLabel("Nautical twilight").uncheck();
  await page.getByLabel("Night").check();
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 1);
  expect(calls[0].method).toBe("POST");
  expect(new URL(calls[0].url).search).toBe("");
  expect(calls[0].body).toEqual({
    locationId: "moon-service-3067696",
    preferences: AMBIENT_PREFERENCES
  });
  await expect(page.locator("#active-filter-list > li")).toHaveCount(2);
  await expect(page.locator("#active-filter-list > li").nth(1))
    .toContainText("Light: Golden hour, Civil twilight, Night");
  await expect(page.locator("#active-filter-list")).not.toContainText("Local time");

  await page.getByRole("button", {
    name: "Remove Light: Golden hour, Civil twilight, Night"
  }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("POST");
  expect(calls[1].body).toEqual({
    locationId: "moon-service-3067696",
    preferences: ALTITUDE_PREFERENCES
  });
  await expect(page.locator("#active-filter-list > li")).toHaveCount(1);
  await expect(page.locator("#active-filter-list")).toContainText("Moon altitude 3.14°–18°");
  await expect(page.locator("#active-filter-list")).not.toContainText("Light:");
  await expect(page.getByRole("button", { name: "Remove Moon altitude 3.14°–18°" }))
    .toBeFocused();
  expect(await storedPreferences(page)).toEqual(ALTITUDE_PREFERENCES);

  await page.getByRole("button", { name: "Reset all preferences" }).click();

  await waitForCallCount(calls, 3);
  expect(calls[2].method).toBe("GET");
  expect(calls[2].body).toBeNull();
  expect([...new URL(calls[2].url).searchParams.entries()])
    .toEqual([["locationId", "moon-service-3067696"]]);
  await expect(page.locator("#active-preference-summary")).toBeHidden();
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
      windows: CLOCK_PREFERENCES.time.windows.map(function (window, index) {
        return {
          ...window,
          futureWindowRule: index + 1
        };
      })
    },
    azimuthDegrees: {
      minimum: 90,
      maximum: 270
    },
    namedPhases: ["waning_crescent"],
    brightLimbOrientationDegrees: 42,
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
  await expect(page.locator("#active-filter-list > li")).toHaveCount(2);
  await expect(page.locator("#active-filter-list")).not.toContainText("azimuth");
  await expect(page.locator("#active-filter-list")).not.toContainText("phase");
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

  await page.getByRole("button", { name: "Reset all preferences" }).click();

  await waitForCallCount(calls, 2);
  expect(calls[1].method).toBe("GET");
  expect(calls[1].body).toBeNull();
  await expect(page.locator("#preference-storage-notice")).toBeVisible();
  await expect(page.locator("#preference-storage-notice"))
    .toHaveText(MEMORY_ONLY_NOTICE);
  await expect(page.locator("#active-preference-summary")).toBeHidden();
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
  await page.getByLabel("Minimum").fill("3.14");
  await page.getByLabel("Maximum").fill("18");
  await page.getByRole("button", { name: "Use these limits" }).click();

  await waitForCallCount(calls, 1);
  expect(calls[0].body).toEqual({
    q: "Prague",
    preferences: ALTITUDE_PREFERENCES
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
    preferences: ALTITUDE_PREFERENCES
  });
});

test("renders server preference metadata as safe text without changing the share URL", async ({ page }) => {
  const unsafePath = "/future<img src=x onerror=\"window.preferenceInjectionRan=1\">";
  await preloadState(page, ALTITUDE_PREFERENCES);
  const calls = await captureApiCalls(page, call => successfulResponse(call, {
    appliedPreferenceVersion: 1,
    normalizedActiveFilters: ALTITUDE_PREFERENCES,
    excludedSampleCount: 1,
    ignoredPreferenceFields: [unsafePath],
    ignoredPreferenceFieldCount: 3,
    additionalIgnoredPreferenceFieldCount: 2,
    opportunities: [],
    emptyReason: {
      code: "no_opportunities_match_preferences",
      text: "No opportunities match the active preferences."
    }
  }));

  await page.goto("/search?q=Prague");
  await waitForCallCount(calls, 1);

  await expect(page.locator("#preference-excluded-notice"))
    .toHaveText("Candidate samples excluded before ranking: 1.");
  await expect(page.locator("#preference-ignored-notice")).toContainText(
    "The server ignored 3 unsupported preference fields."
  );
  await expect(page.locator("#preference-ignored-notice")).toContainText(unsafePath);
  await expect(page.locator("#preference-ignored-notice"))
    .toContainText("2 more were not listed.");
  await expect(page.locator("#preference-empty-notice")).toBeVisible();
  await expect(page.locator("#preference-empty-notice"))
    .toContainText("No opportunities match these hard limits.");
  await expect(page.locator("img[src='x']")).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, "preferenceInjectionRan")))
    .toBeUndefined();

  await expect(page.locator("#active-filter-list > li")).toHaveCount(1);
  await expect(page.locator("#active-filter-list")).toContainText("Moon altitude 3.14°–18°");
  await expect(page.locator("#preference-timezone-note")).toBeHidden();
  await expect(page.locator(".status-panel.warning .tooltip"))
    .toHaveAttribute("title", "No candidate window matched this search.");
  await expect(page.getByRole("link", { name: "Open share link" }))
    .toHaveAttribute("href", "/search?q=Prague");
  const currentUrl = new URL(page.url());
  expect([...currentUrl.searchParams.entries()]).toEqual([["q", "Prague"]]);
});

test("documents local preference storage, request use, and location-only sharing", async ({ page }) => {
  await page.goto("/about");

  const privacy = page.locator("#privacy-and-providers");
  await expect(privacy).toContainText(
    "Moon altitude and time preferences are stored in this browser."
  );
  await expect(privacy).toContainText(
    "Each search with active preferences sends them to the Moon Service server"
  );
  await expect(privacy).toContainText(
    "uses them for that search and does not permanently store them"
  );
  await expect(privacy).toContainText(
    "Share links include only the location, not the preferences."
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
    payload.normalizedActiveFilters = call.body.preferences;
    payload.excludedSampleCount = 0;
    payload.ignoredPreferenceFields = [];
    payload.ignoredPreferenceFieldCount = 0;
    payload.additionalIgnoredPreferenceFieldCount = 0;
  }
  return Object.assign(payload, overrides);
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
