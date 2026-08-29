import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const fixture = /** @type {any} */ (JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
)));
const CALENDAR_PATH = "/calendars/opportunities.ics?locationId=opaque-location"
  + "&preferences=%7B%22time%22%3A%7B%22startLocal%22%3A%2204%3A00%22%7D%7D";
const ATOM_PATH = "/feeds/atom?locationId=moon-service-3067696";
const PREFERENCE_WARNING_ID = "preference-link-warning";
const PREFERENCE_WARNING_TEXT = "The Atom feed and calendar links on this page contain your selected location "
  + "and photography filters. Anyone with one of these links can see that information, including your "
  + "preferred observation times and viewing direction (altitude and azimuth). Do not share these links "
  + "if those details are private.";

test("copies the two exact feed URLs without fetching either feed", async ({ page }) => {
  await recordClipboardWrites(page);
  const payload = responseWithCalendar(CALENDAR_PATH);
  await serveResponse(page, () => payload);
  const feedRequests = [];
  page.on("request", request => {
    const path = new URL(request.url()).pathname;
    if (path.startsWith("/feeds/") || path.startsWith("/calendars/")) {
      feedRequests.push(request.url());
    }
  });

  await page.goto("/search?q=Prague");

  const buttons = page.locator(".share-tools button");
  const expectedAtom = await absoluteUrl(page, ATOM_PATH);
  const expectedCalendar = await absoluteUrl(page, CALENDAR_PATH);
  await expect(buttons).toHaveText(["Copy Atom feed link", "Copy calendar feed link"]);
  await expect(buttons.nth(0)).toHaveAttribute("data-share-url", expectedAtom);
  await expect(buttons.nth(1)).toHaveAttribute("data-share-url", expectedCalendar);
  await expect(buttons.nth(0)).not.toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
  await expect(buttons.nth(1)).not.toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
  await expect(page.locator(".share-tools a")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Copy link", exact: true })).toHaveCount(0);
  await expect(page.getByRole("link", { name: "Open share link", exact: true })).toHaveCount(0);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveCount(0);
  expect(feedRequests).toEqual([]);

  await buttons.nth(0).click();
  await expect(buttons.nth(0)).toHaveText("Copied");
  await buttons.nth(1).click();

  await expect(buttons.nth(1)).toHaveText("Copied");
  expect(await page.evaluate(() => Reflect.get(window, "__moonCalendarCopies")))
    .toEqual([expectedAtom, expectedCalendar]);
  expect(feedRequests).toEqual([]);
});

test("shows the filtered warning and calendar action for an empty result", async ({ page }) => {
  const payload = responseWithCalendar(CALENDAR_PATH);
  payload.opportunities = [];
  payload.normalizedActiveFilters = {
    altitudeDegrees: { minimum: 5, maximum: 20 }
  };
  await serveResponse(page, () => payload);

  await page.goto("/search?q=Prague");

  const button = page.getByRole("button", { name: "Copy calendar feed link", exact: true });
  await expect(button).toBeVisible();
  await expect(button).toHaveAttribute("aria-describedby", PREFERENCE_WARNING_ID);
  await expect(page.locator("#" + PREFERENCE_WARNING_ID)).toHaveText(PREFERENCE_WARNING_TEXT);
  await expect(page.getByRole("button", { name: "Copy Atom feed link", exact: true })).toHaveCount(0);
  await expect(page.getByRole(
    "link", { name: "Download calendar event", exact: true }
  )).toHaveCount(0);
  await expect(page.getByText("No opportunities found", { exact: false })).toBeVisible();
});

test("hides absent, malformed, and non-product calendar feed values", async ({ page }) => {
  const scenarios = [
    { name: "absent" },
    { name: "non-string", value: 42 },
    { name: "empty", value: "" },
    { name: "blank", value: " \t " },
    { name: "leading whitespace", value: " " + CALENDAR_PATH },
    { name: "trailing whitespace", value: CALENDAR_PATH + " " },
    { name: "absolute", value: "https://calendar.example/opportunities.ics" },
    { name: "scheme-relative", value: "//calendar.example/opportunities.ics" },
    { name: "fixture location", value: CALENDAR_PATH, locationKind: "fixture" }
  ];
  let scenario = scenarios[0];
  await serveResponse(page, () => {
    const payload = responseWithCalendar(scenario.value);
    if (!("value" in scenario)) delete payload.links.calendarFeed;
    if (scenario.locationKind) payload.location.kind = scenario.locationKind;
    return payload;
  });

  for (const current of scenarios) {
    scenario = current;
    await page.goto("/search?q=" + encodeURIComponent(current.name));
    await expect(page.getByRole(
      "button", { name: "Copy calendar feed link", exact: true }
    )).toHaveCount(0);
  }
});

test("uses the existing prompt fallback for both feed URLs", async ({ page }) => {
  await recordPromptCalls(page);
  await serveResponse(page, () => responseWithCalendar(CALENDAR_PATH));

  await page.goto("/search?q=Prague");

  const buttons = page.locator(".share-tools button");
  const expectedAtom = await absoluteUrl(page, ATOM_PATH);
  const expectedCalendar = await absoluteUrl(page, CALENDAR_PATH);
  await buttons.nth(0).click();
  await buttons.nth(1).click();

  await expect(buttons).toHaveText(["Copied", "Copied"]);
  expect(await page.evaluate(() => Reflect.get(window, "__moonCalendarPrompts")))
    .toEqual([
      { message: "Copy share link", value: expectedAtom },
      { message: "Copy share link", value: expectedCalendar }
    ]);
});

async function absoluteUrl(page, path) {
  return page.evaluate(value => window.location.origin + value, path);
}

function responseWithCalendar(calendarFeed) {
  const payload = structuredClone(fixture);
  payload.links = { calendarFeed: calendarFeed };
  return payload;
}

async function serveResponse(page, response) {
  await page.route("**/api/opportunities**", async route => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify(response())
    });
  });
}

async function recordClipboardWrites(page) {
  await page.addInitScript(() => {
    Reflect.set(window, "__moonCalendarCopies", []);
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText(value) {
          Reflect.get(window, "__moonCalendarCopies").push(value);
          return Promise.resolve();
        }
      }
    });
  });
}

async function recordPromptCalls(page) {
  await page.addInitScript(() => {
    Reflect.set(window, "__moonCalendarPrompts", []);
    Object.defineProperty(navigator, "clipboard", { configurable: true, value: undefined });
    window.prompt = function (message, value) {
      Reflect.get(window, "__moonCalendarPrompts").push({ message: message, value: value });
      return null;
    };
  });
}
