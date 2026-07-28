import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const STORAGE_KEY = "moonService.opportunityPreferences.v1";
const EMPTY_TEXT = "No opportunities match these hard limits. Remove a limit or reset the preferences.";
const DISCLAIMER = "This long-range check uses Moon geometry only. It does not include weather or your other hard preferences.";
const PREFERENCES = {
  version: 1,
  namedPhases: ["waxing_crescent", "full_moon"],
  brightLimbOrientationDegrees: [{ start: 350, end: 10 }]
};
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

test("shows the next match with the resolved year and IANA timezone", async ({ page }) => {
  const nextMatchAt = "2026-10-08T18:22:00Z";
  const notice = await showResponse(page, {
    location: {
      ...sourceFixture.location,
      displayName: "Stockholm, Sweden",
      timezone: "Europe/Stockholm",
      countryCode: "SE"
    },
    phaseOrientationAvailability: {
      status: "next_match",
      lookAheadDays: 200,
      nextMatchAt
    }
  });
  const formatted = await page.evaluate(({ value, timezone }) =>
    new Intl.DateTimeFormat(navigator.language, {
      dateStyle: "medium",
      timeStyle: "short",
      timeZone: timezone
    }).format(new Date(value)), {
    value: nextMatchAt,
    timezone: "Europe/Stockholm"
  });

  await expect(notice).toBeVisible();
  await expect(notice).toHaveText(
    EMPTY_TEXT
      + " Next astronomical match for the selected phase and orientation: "
      + formatted + " Europe/Stockholm. " + DISCLAIMER
  );
  await expect(notice).toContainText("2026");
  await expect(notice).toContainText("Europe/Stockholm");
  await expect(notice.locator("..")).toHaveAttribute("aria-live", "polite");
});

test("shows the bounded not-found result with hostile location text safely", async ({ page }) => {
  const hostileName = "Stockholm<img src=x onerror=\"window.availabilityInjectionRan=1\">";
  const notice = await showResponse(page, {
    location: {
      ...sourceFixture.location,
      displayName: hostileName,
      timezone: "Europe/Stockholm",
      countryCode: "SE"
    },
    phaseOrientationAvailability: {
      status: "not_found",
      lookAheadDays: 200
    }
  });

  await expect(notice).toBeVisible();
  await expect(notice).toHaveText(
    EMPTY_TEXT
      + " The astronomical calculation found no match for the selected phase and orientation near "
      + hostileName + " during the next 200 days. " + DISCLAIMER
  );
  await expect(page.locator("img[src='x']")).toHaveCount(0);
  expect(await page.evaluate(() => Reflect.get(window, "availabilityInjectionRan")))
    .toBeUndefined();
  await expect(notice.locator("..")).toHaveAttribute("aria-live", "polite");
});

for (const scenario of [
  {
    name: "an omitted object",
    overrides: {}
  },
  {
    name: "an unknown status",
    overrides: {
      phaseOrientationAvailability: {
        status: "try_later",
        lookAheadDays: 200,
        nextMatchAt: "2026-10-08T18:22:00Z"
      }
    }
  },
  {
    name: "a malformed object",
    overrides: {
      phaseOrientationAvailability: []
    }
  },
  {
    name: "a date-only next-match timestamp",
    overrides: {
      phaseOrientationAvailability: {
        status: "next_match",
        lookAheadDays: 200,
        nextMatchAt: "2026-10-08"
      }
    }
  },
  {
    name: "an impossible next-match date",
    overrides: {
      phaseOrientationAvailability: {
        status: "next_match",
        lookAheadDays: 200,
        nextMatchAt: "2026-02-30T00:00:00Z"
      }
    }
  }
]) {
  test(`preserves the filtered-empty message for ${scenario.name}`, async ({ page }) => {
    const notice = await showResponse(page, scenario.overrides);

    await expect(notice).toBeVisible();
    await expect(notice).toHaveText(EMPTY_TEXT);
    await expect(notice).not.toContainText("200");
  });
}

async function showResponse(page, overrides) {
  await page.emulateMedia({ reducedMotion: "reduce" });
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
  }, {
    key: STORAGE_KEY,
    value: PREFERENCES
  });
  var requestBody;
  await page.route("**/api/opportunities**", async route => {
    requestBody = route.request().postDataJSON();
    const response = /** @type {any} */ (JSON.parse(JSON.stringify(sourceFixture)));
    Object.assign(response, {
      opportunities: [],
      appliedPreferenceVersion: 1,
      normalizedActiveFilters: PREFERENCES,
      excludedSampleCount: 1,
      ignoredPreferenceFieldCount: 0,
      ignoredPreferenceFields: [],
      additionalIgnoredPreferenceFieldCount: 0,
      emptyReason: {
        code: "no_opportunities_match_preferences",
        text: "No opportunity matched the active preferences."
      }
    }, overrides);
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(response)
    });
  });

  await page.goto("/search?q=Stockholm");
  await expect.poll(() => requestBody).toEqual({
    q: "Stockholm",
    preferences: PREFERENCES
  });
  return page.locator("#preference-empty-notice");
}
