import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const PREFERENCE_KEY = "moonService.opportunityPreferences.v1";
const AS_OF = "2026-07-04T03:40:00Z";
const TIMEZONE = "Europe/Prague";
const ACTIVE_PREFERENCES = {
  version: 1,
  altitudeDegrees: { minimum: 3.14, maximum: 18 }
};
const sourceFixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));

const ACTIVE_SAMPLES = [
  point("2026-07-04T00:00:00Z", 0, 112, 45, 20, 10, -14, 24, "night", "start"),
  point(AS_OF, 27, 180, 120, 135, -25, 6, 65, "golden_hour", "now"),
  point("2026-07-04T15:40:00Z", 42, 225, 170, 220, 40, 35, 244, "daylight", "path"),
  point("2026-07-05T05:40:00Z", 0, 248, 230, 305, 70, -3, 330, "civil_twilight", "end")
];

const NEXT_PASS_SAMPLES = [
  point("2026-07-04T10:00:31Z", 0, 88, 48, 24, 12, -1, 65, "civil_twilight", "start"),
  point("2026-07-04T12:00:00Z", 21, 130, 54, 30, 14, 18, 110, "daylight", "path"),
  point("2026-07-04T14:00:00Z", 25, 190, 62, 38, 16, 11, 186, "daylight", "path"),
  point("2026-07-04T16:00:00Z", 0, 250, 70, 44, 18, -3, 258, "civil_twilight", "end")
];

const ACTIVE_CURRENT_MOON = {
  horizonState: "above_or_on_horizon",
  moon: {
    altitudeDegrees: 27,
    azimuthDegrees: 180,
    illuminationPercent: 81.4,
    phaseAngleDegrees: 120,
    brightLimbTiltDegrees: 135,
    northPoleTiltDegrees: -25,
    phaseName: "waxing_gibbous"
  },
  sun: {
    altitudeDegrees: 6,
    azimuthDegrees: 65,
    lightBucket: "golden_hour"
  },
  activePass: {
    startBoundary: { status: "found", at: "2026-07-04T00:00:00Z" },
    endBoundary: { status: "not_found_within_range" },
    representedStartsAt: "2026-07-04T00:00:00Z",
    representedEndsAt: "2026-07-05T05:40:00Z",
    path: {
      start: clone(ACTIVE_SAMPLES[0]),
      now: clone(ACTIVE_SAMPLES[1]),
      end: clone(ACTIVE_SAMPLES.at(-1)),
      samples: clone(ACTIVE_SAMPLES)
    }
  }
};

const BELOW_HORIZON_CURRENT_MOON = {
  horizonState: "below_horizon",
  moon: {
    altitudeDegrees: -18.2,
    azimuthDegrees: 301.4,
    illuminationPercent: 3.2,
    phaseAngleDegrees: 344,
    brightLimbTiltDegrees: null,
    northPoleTiltDegrees: null,
    phaseName: "new_moon"
  },
  sun: {
    altitudeDegrees: -12.4,
    azimuthDegrees: 42,
    lightBucket: "night"
  },
  nextRiseBoundary: { status: "found", at: "2026-07-04T10:00:31Z" },
  nextPass: {
    startBoundary: { status: "found", at: "2026-07-04T10:00:31Z" },
    endBoundary: { status: "found", at: "2026-07-04T16:00:00Z" },
    representedStartsAt: "2026-07-04T10:00:31Z",
    representedEndsAt: "2026-07-04T16:00:00Z",
    path: {
      start: clone(NEXT_PASS_SAMPLES[0]),
      end: clone(NEXT_PASS_SAMPLES.at(-1)),
      samples: clone(NEXT_PASS_SAMPLES)
    }
  },
  activePass: null
};

test("renders an unranked active snapshot as a collapsed path-first disclosure", async ({ page }) => {
  const calls = await captureProductCalls(page, () => activeResponse());

  await page.goto("/search?q=Prague");
  await waitForCalls(calls, 1);

  expect(calls[0]).toMatchObject({ method: "GET", body: null });
  expect([...new URL(calls[0].url).searchParams.entries()]).toEqual([["q", "Prague"]]);

  const card = page.locator("details#current-moon-card");
  const summary = card.locator(":scope > summary");
  await expect(card).not.toHaveAttribute("open", "");
  await expect(summary).toBeVisible();
  await expect(summary).toHaveText("Moon now — 27° high");
  await expect(page.getByRole("checkbox", { name: /Moon now/i })).toHaveCount(0);
  await expect(card.locator(".moon-path-panel")).toBeHidden();
  expect(await precedes(card, page.locator(".opportunity-list"))).toBe(true);

  await expect(page.locator(".summary-count").first())
    .toHaveText("2 ranked Moon passes · 3 candidate windows");
  await expect(page.locator(".moon-pass-card")).toHaveCount(2);
  await expect(page.locator(".moon-pass-card .rank-label"))
    .toHaveText(["Best match", "Option 2"]);
  await expect(page.locator(".choice-rank"))
    .toHaveText(["Rank 2 · score 74", "Rank 1 · score 81", "Rank 3 · score 78"]);

  await summary.focus();
  await page.keyboard.press("Enter");
  await expect(card).toHaveAttribute("open", "");
  await expect(summary).toBeFocused();
  expect(calls).toHaveLength(1);

  const pathPanel = card.locator(".moon-path-panel");
  const conciseSummary = card.locator(".current-moon-summary");
  await expect(pathPanel).toBeVisible();
  await expect(conciseSummary).toBeVisible();
  expect(await precedes(pathPanel, conciseSummary)).toBe(true);
  const expectedSnapshot = await formattedLocalTimeWithOffset(page, AS_OF, TIMEZONE);
  await expect(conciseSummary).toHaveText(new RegExp(
    "^Status for " + escapeRegExp(expectedSnapshot) + ":"
  ));
  await expect(conciseSummary).toContainText("Waxing gibbous");
  await expect(conciseSummary).toContainText("27.0°");
  await expect(conciseSummary).toContainText("Moonrise");
  await expect(conciseSummary)
    .toContainText(await formattedLocalTimeWithOffset(
      page, "2026-07-04T00:00:00Z", TIMEZONE
    ));
  await expect(conciseSummary).toContainText("Moonset");
  await expect(conciseSummary).toContainText("Not found within 26 hours");
  await expect(conciseSummary).not.toContainText("A new search gets a new snapshot.");
  expect(await conciseSummary.innerText()).not.toMatch(
    /illumination|bright.limb|north.pole|modelled horizon|sun altitude|sun azimuth|ambient light/i
  );
  await expect(card).not.toContainText("81%");
  await expect(card).not.toContainText("Golden hour");

  await expect(pathPanel).toContainText("Moon path at Now");
  await expect(pathPanel.locator(".moon-path-summary")).toHaveCount(0);
  await expect(pathPanel.getByText("Sun pass", { exact: true })).toHaveCount(0);
  await expect(pathPanel.locator(".sun-altitude-chart")).toHaveCount(0);
  expect(await pathPanel.innerText()).not.toMatch(/Suggested|Best|Alternative|Rank\b/i);
  for (const mode of ["desktop", "mobile"]) {
    const moonNow = pathPanel.locator(
      `.moon-altitude-chart.altitude-chart-${mode} .moon-sample-marker[data-at='${AS_OF}']`
    );
    await expect(moonNow.locator("title")).toHaveText("Now Moon position, 27.0° altitude");
    await expect(moonNow.locator(".moon-sample-marker-label")).toHaveText("Now");
  }
  await expectCurrentMarkerOrientations(card, ACTIVE_SAMPLES);

  const domeDetails = card.locator(".sky-picture-details")
    .filter({ hasText: "Sky dome at snapshot time" });
  await domeDetails.locator("summary").click();
  const dome = domeDetails.locator(".sky-dome-chart");
  await expect(dome).toBeVisible();
  await expect(dome).toHaveAccessibleName(new RegExp(
    "at snapshot time " + escapeRegExp(
      await formattedLocalClockWithOffset(page, AS_OF, TIMEZONE)
    ) + ".*Sun 6\\.0° altitude.*Moon 27\\.0° altitude"
  ));
  await expect(dome.locator(".sky-body.is-sun")).toHaveAttribute("data-altitude-degrees", "6");
  await expect(dome.locator(".sky-body.is-moon")).toHaveAttribute("data-altitude-degrees", "27");
  await expect(card.getByRole("button", { name: /calibration|feedback/i })).toHaveCount(0);
  await expect(card.getByRole("link", { name: /calibration|feedback/i })).toHaveCount(0);
  await expectNoTechnicalAsOf(card);
  await expectUsableWidth(page, card);

  await summary.focus();
  await page.keyboard.press("Enter");
  await expect(card).not.toHaveAttribute("open", "");
  await expect(summary).toBeFocused();
  await expect(pathPanel).toBeHidden();
  expect(calls).toHaveLength(1);
  await expect(page.locator(".moon-pass-card")).toHaveCount(2);

  await page.keyboard.press("Enter");
  await expect(card).toHaveAttribute("open", "");
  await expect(pathPanel).toBeVisible();
  expect(calls).toHaveLength(1);
});

test("shows the snapshot for POST and resets the disclosure on each ordered response", async ({ page }) => {
  await page.addInitScript(({ key, value }) => {
    window.localStorage.setItem(key, JSON.stringify(value));
  }, { key: PREFERENCE_KEY, value: ACTIVE_PREFERENCES });
  const calls = await captureProductCalls(page, () => preferenceResponse());

  await page.goto("/search?q=Prague");
  await waitForCalls(calls, 1);

  expect(calls[0].method).toBe("POST");
  expect(new URL(calls[0].url).pathname).toBe("/api/opportunities");
  expect(new URL(calls[0].url).search).toBe("");
  expect(calls[0].body).toEqual({ q: "Prague", preferences: ACTIVE_PREFERENCES });
  const card = page.locator("details#current-moon-card");
  const summary = card.locator(":scope > summary");
  await expect(card).not.toHaveAttribute("open", "");
  await expect(summary).toHaveText("Moon now — 27° high");
  await summary.click();
  await expect(card).toHaveAttribute("open", "");
  expect(calls).toHaveLength(1);
  expect(await page.evaluate(key => JSON.parse(window.localStorage.getItem(key)), PREFERENCE_KEY))
    .toEqual(ACTIVE_PREFERENCES);
  expect(await page.evaluate(() => Object.keys(window.localStorage)
    .filter(key => /current.*moon|moon.*current/i.test(key)))).toEqual([]);

  const soonest = page.getByRole("radio", { name: "Soonest" });
  await soonest.focus();
  await page.keyboard.press("Space");
  await waitForCalls(calls, 2);
  expect(calls[1].method).toBe("POST");
  expect([...new URL(calls[1].url).searchParams.entries()]).toEqual([["order", "soonest"]]);
  expect(calls[1].body).toEqual({ q: "Prague", preferences: ACTIVE_PREFERENCES });
  await expect(soonest).toBeChecked();
  await expect(page).toHaveURL("/search?q=Prague&order=soonest");
  await expect(card).not.toHaveAttribute("open", "");
  await expect(card.locator(".moon-path-panel")).toBeHidden();
  await expect(page.locator(".summary-count").first())
    .toHaveText("2 Moon passes · 3 candidate windows");
  await expect(page.locator(".moon-pass-card .rank-label"))
    .toHaveText(["Soonest", "Later pass 2"]);
  await expect(page.locator(".choice-rank"))
    .toHaveText(["Score 74", "Score 81", "Score 78"]);
});

test("keeps empty recovery beside a collapsed below-horizon sky snapshot", async ({ page }) => {
  const response = belowHorizonEmptyResponse();
  const calls = await captureProductCalls(page, () => response);

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForCalls(calls, 1);

  const card = page.locator("details#current-moon-card");
  await expect(card).not.toHaveAttribute("open", "");
  await expect(card).toBeVisible();
  await expect(card.locator(":scope > summary"))
    .toHaveText("Moon now — Below horizon · Rises in 6 hr 21 min");
  await expect(page.locator(".summary-count").first())
    .toHaveText("0 ranked Moon passes · 0 candidate windows");
  await expect(page.locator(".moon-pass-card, .opportunity-list")).toHaveCount(0);
  const recovery = page.locator("details.status-panel.warning");
  await expect(recovery.locator("summary"))
    .toContainText("No match — No opportunities found in the next 7 days");
  expect(await precedes(card, recovery)).toBe(true);

  await card.locator(":scope > summary").click();
  await expect(card).toHaveAttribute("open", "");
  const conciseSummary = card.locator(".current-moon-summary");
  const expectedSnapshot = await formattedLocalTimeWithOffset(page, AS_OF, TIMEZONE);
  await expect(conciseSummary).toHaveText(new RegExp(
    "^Status for " + escapeRegExp(expectedSnapshot) + ":"
  ));
  await expect(conciseSummary).toContainText("18.2° below the horizon");
  await expect(conciseSummary).not.toContainText("No Moon pass is active at this snapshot.");
  await expect(conciseSummary).not.toContainText("A new search gets a new snapshot.");
  expect(await card.innerText()).not.toMatch(
    /illumination|bright.limb|north.pole|modelled horizon|sun altitude|sun azimuth|ambient light/i
  );
  await expect(card).not.toContainText("3%");
  const upcomingPath = card.locator(".moon-path-panel");
  await expect(upcomingPath).toBeVisible();
  await expect(upcomingPath).toContainText("Upcoming Moon path");
  await expect(upcomingPath).not.toContainText("Moon path at Now");
  await expect(upcomingPath.getByText("Sun pass", { exact: true })).toHaveCount(0);
  await expect(upcomingPath.locator(".sun-altitude-chart")).toHaveCount(0);
  expect(await upcomingPath.locator(".light-band").count()).toBeGreaterThan(0);
  await expect(upcomingPath.locator(".moon-sample-marker.is-now")).toHaveCount(0);
  await expect(card.getByText("Active Moon pass", { exact: true })).toHaveCount(0);
  await expect(card.getByText("Moonrise", { exact: true })).toHaveCount(0);
  await expect(card.getByText("Moonset", { exact: true })).toHaveCount(0);
  await expect(card).not.toContainText(/continuous visibility/i);
  expect(await card.innerText()).not.toMatch(/Suggested|Best|Alternative/i);

  const domeDetails = card.locator(".sky-picture-details")
    .filter({ hasText: "Sky dome at snapshot time" });
  await expect(domeDetails).toHaveCount(1);
  await domeDetails.locator("summary").click();
  const dome = domeDetails.locator(".sky-dome-chart");
  await expect(dome).toBeVisible();
  await expect(dome).toHaveAccessibleName(new RegExp(
    "Sun and Moon sky position at snapshot time " + escapeRegExp(
      await formattedLocalClockWithOffset(page, AS_OF, TIMEZONE)
    ) + ".*Sun and Moon below horizon; Sun -12\\.4°.*Moon -18\\.2°"
  ));
  await expect(dome.locator(".sky-body.is-sun")).toHaveAttribute("data-altitude-degrees", "-12.4");
  await expect(dome.locator(".sky-body.is-moon")).toHaveAttribute("data-altitude-degrees", "-18.2");
  const positions = await dome.locator(".sky-body").evaluateAll(bodyNodes => bodyNodes.map(body => {
    const transform = body.getAttribute("transform") || "";
    const match = transform.match(/translate\(([-0-9.]+) ([-0-9.]+)\)/);
    const azimuth = Number(body.getAttribute("data-azimuth-degrees"));
    return {
      role: body.classList.contains("is-sun") ? "sun" : "moon",
      y: match ? Number(match[2]) : Number.NaN,
      localHorizonY: 226 - Math.cos(azimuth * Math.PI / 180) * 25
    };
  }));
  expect(positions.map(position => position.role).sort()).toEqual(["moon", "sun"]);
  for (const position of positions) {
    expect(position.y, `${position.role} should be drawn below its local horizon`)
      .toBeGreaterThan(position.localHorizonY);
  }
  await expect(dome.locator(".sky-body.is-moon .sky-body-image"))
    .toHaveAttribute("href", /^data:image\/png;base64,/);
  await expectNoTechnicalAsOf(card);
  await expectUsableWidth(page, card);
});

test("uses accepted below-horizon rise copy without another request", async ({ page }) => {
  const response = belowHorizonEmptyResponse();
  response.currentMoon.nextRiseBoundary = {
    status: "found", at: "2026-07-04T03:40:59Z"
  };
  response.currentMoon.nextPass = null;
  const calls = await captureProductCalls(page, () => response);

  await page.goto("/search?locationId=moon-service-3067696");
  await waitForCalls(calls, 1);
  await expect(page.locator("#current-moon-card > summary"))
    .toHaveText("Moon now — Below horizon · Rises in less than 1 min");
  expect(calls).toHaveLength(1);

  response.currentMoon.nextRiseBoundary = { status: "not_found_within_range" };
  await page.goto("/search?locationId=moon-service-3067696&order=soonest");
  await waitForCalls(calls, 2);
  await expect(page.locator("#current-moon-card > summary"))
    .toHaveText("Moon now — Below horizon · Rise not found within 26 hours");
  expect(calls).toHaveLength(2);

  response.currentMoon.nextRiseBoundary = {
    status: "found", at: "2026-07-05T05:40:00Z"
  };
  await page.goto("/search?locationId=moon-service-3067696&order=score");
  await waitForCalls(calls, 3);
  await expect(page.locator("#current-moon-card > summary"))
    .toHaveText("Moon now — Below horizon · Rises in 26 hr");
  await page.locator("#current-moon-card > summary").click();
  await expect(page.locator("#current-moon-card .moon-path-panel")).toHaveCount(0);
  expect(calls).toHaveLength(3);
});

test("uses place-local time with a numeric UTC offset across location timezones", async ({ page }) => {
  let response = activeResponse();
  const calls = await captureProductCalls(page, () => response);
  const locations = [
    { query: "Prague", timezone: "Europe/Prague", countryCode: "CZ" },
    { query: "New York", timezone: "America/New_York", countryCode: "US" },
    { query: "Canberra", timezone: "Australia/Canberra", countryCode: "AU" }
  ];

  for (const location of locations) {
    response = activeResponse();
    response.location.timezone = location.timezone;
    response.location.countryCode = location.countryCode;
    const expectedCallCount = calls.length + 1;
    await page.goto("/search?q=" + encodeURIComponent(location.query));
    await waitForCalls(calls, expectedCallCount);

    const card = page.locator("details#current-moon-card");
    await card.locator(":scope > summary").click();
    const expectedSnapshot = await formattedLocalTimeWithOffset(
      page, AS_OF, location.timezone
    );
    const expectedMoonrise = await formattedLocalTimeWithOffset(
      page, "2026-07-04T00:00:00Z", location.timezone
    );
    const conciseSummary = card.locator(".current-moon-summary");
    await expect(conciseSummary).toHaveText(new RegExp(
      "^Status for " + escapeRegExp(expectedSnapshot) + ":"
    ));
    await expect(conciseSummary).toContainText(expectedMoonrise);
    await expect(conciseSummary).not.toContainText("A new search gets a new snapshot.");
    await expectNoTechnicalAsOf(card);
  }
});

function point(at, altitudeDegrees, azimuthDegrees, moonPhaseAngleDegrees,
  brightLimbTiltDegrees, northPoleTiltDegrees, sunAltitudeDegrees,
  sunAzimuthDegrees, lightBucket, role) {
  return {
    at, altitudeDegrees, azimuthDegrees, moonPhaseAngleDegrees,
    brightLimbTiltDegrees, northPoleTiltDegrees, sunAltitudeDegrees,
    sunAzimuthDegrees, lightBucket, role
  };
}

function activeResponse(overrides = {}) {
  return Object.assign(productResponse(ACTIVE_CURRENT_MOON), overrides);
}

function preferenceResponse() {
  const response = activeResponse();
  response.appliedPreferenceVersion = 1;
  response.normalizedActiveFilters = { altitudeDegrees: ACTIVE_PREFERENCES.altitudeDegrees };
  response.excludedSampleCount = 0;
  response.ignoredPreferenceFields = [];
  response.ignoredPreferenceFieldCount = 0;
  response.additionalIgnoredPreferenceFieldCount = 0;
  return response;
}

function belowHorizonEmptyResponse() {
  return productResponse(BELOW_HORIZON_CURRENT_MOON, {
    opportunities: [],
    emptyReason: {
      code: "no_useful_opportunities",
      text: "No useful opportunity met the current search threshold."
    }
  });
}

function productResponse(currentMoon, overrides = {}) {
  const response = clone(sourceFixture);
  response.generatedAt = AS_OF;
  response.asOf = AS_OF;
  response.currentMoon = clone(currentMoon);
  return Object.assign(response, overrides);
}

async function captureProductCalls(page, responseForCall) {
  const calls = [];
  await page.route("**/api/opportunities**", async route => {
    const request = route.request();
    const call = {
      method: request.method(),
      url: request.url(),
      body: request.method() === "POST" ? request.postDataJSON() : null
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

async function waitForCalls(calls, count) {
  await expect.poll(() => calls.length).toBe(count);
}

async function formattedLocalTimeWithOffset(page, value, timezone) {
  return formattedPlaceTimeWithOffset(page, value, timezone, true);
}

async function formattedLocalClockWithOffset(page, value, timezone) {
  return formattedPlaceTimeWithOffset(page, value, timezone, false);
}

async function formattedPlaceTimeWithOffset(page, value, timezone, includeDate) {
  return page.evaluate(({ instant, zone, withDate }) => {
    const date = new Date(instant);
    const locale = navigator.languages?.[0] || navigator.language;
    /** @type {Intl.DateTimeFormatOptions} */
    const options = withDate
      ? { dateStyle: "medium", timeStyle: "short", timeZone: zone }
      : { timeStyle: "short", timeZone: zone };
    const formatted = new Intl.DateTimeFormat(locale, options).format(date);
    const zonePart = new Intl.DateTimeFormat("en-US", {
      hour: "numeric",
      timeZone: zone,
      timeZoneName: "longOffset"
    }).formatToParts(date).find(part => part.type === "timeZoneName")?.value;
    const match = /^GMT(?:([+-])(\d{2}):(\d{2}))?$/.exec(zonePart || "");
    if (!match) {
      throw new Error(`Could not determine the UTC offset for ${zone}`);
    }
    const offset = match[1]
      ? `${match[1] === "-" ? "−" : "+"}${match[2]}:${match[3]}`
      : "+00:00";
    return `${formatted} local time (UTC${offset})`;
  }, { instant: value, zone: timezone, withDate: includeDate });
}

async function expectNoTechnicalAsOf(card) {
  await expect(card).not.toContainText(/\basOf\b/i);
  await expect(card.locator('[aria-label*="asOf" i], [title*="asOf" i]')).toHaveCount(0);
}

function escapeRegExp(value) {
  return value.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

async function precedes(first, second) {
  return first.evaluate((node, other) => Boolean(
    node.compareDocumentPosition(other) & Node.DOCUMENT_POSITION_FOLLOWING
  ), await second.elementHandle());
}

async function expectCurrentMarkerOrientations(card, samples) {
  const rendered = await card.evaluate(async (node, expectedSamples) => {
    const modulePath = "/moonPhaseView.js";
    const { moonPhaseImageDataUrl } = await import(modulePath);
    const expectedByTime = new Map(expectedSamples.map(sample => [sample.at, sample]));
    return Object.fromEntries(["desktop", "mobile"].map(mode => [mode,
      Array.from(node.querySelectorAll(
        `.moon-altitude-chart.altitude-chart-${mode} .moon-sample-marker`
      )).map(marker => {
        const at = marker.getAttribute("data-at");
        const sample = expectedByTime.get(at);
        return {
          at,
          actual: marker.querySelector(".moon-sample-marker-image")?.getAttribute("href"),
          expected: sample ? moonPhaseImageDataUrl(
            sample.moonPhaseAngleDegrees,
            64,
            sample.brightLimbTiltDegrees,
            sample.northPoleTiltDegrees
          ) : null
        };
      })
    ]));
  }, samples);
  for (const mode of ["desktop", "mobile"]) {
    expect(rendered[mode]).toHaveLength(samples.length);
    for (const marker of rendered[mode]) {
      expect(marker.expected, `${mode} marker ${marker.at} should belong to the current path`)
        .toMatch(/^data:image\/png;base64,/);
      expect(marker.actual, `${mode} marker ${marker.at} should use its sample orientation`)
        .toBe(marker.expected);
    }
  }
}

async function expectUsableWidth(page, card) {
  const widths = await card.evaluate(node => {
    const results = node.closest("#results");
    return {
      card: node.getBoundingClientRect().width,
      results: results?.getBoundingClientRect().width || 0,
      overflow: document.documentElement.scrollWidth - document.documentElement.clientWidth
    };
  });
  expect(widths.card).toBeGreaterThan(0);
  expect(widths.card).toBeLessThanOrEqual(widths.results + 1);
  expect(widths.overflow).toBeLessThanOrEqual(1);
  await expect(card.locator(":scope > summary")).toBeVisible();
}

function clone(value) {
  return JSON.parse(JSON.stringify(value));
}
