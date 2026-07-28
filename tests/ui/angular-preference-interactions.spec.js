import { expect, test } from "@playwright/test";
import { readFileSync } from "node:fs";

const sourceFixture = JSON.parse(readFileSync(
  new URL("./fixtures/moon-pass-response.json", import.meta.url),
  "utf8"
));

const SNAP_CASES = [
  {
    name: "green start",
    label: "Included compass sector start",
    deltas: [11, 12, 8, 5, 4],
    expected: ["340", "350", "350", "350", "334"]
  },
  {
    name: "red start",
    label: "Blocked view start",
    deltas: [-11, -12, -8, -5, -4],
    expected: ["340", "330", "330", "330", "346"]
  },
  {
    name: "green end",
    label: "Included compass sector end",
    deltas: [-11, -12, -8, -5, -4],
    expected: ["20", "10", "10", "10", "26"]
  },
  {
    name: "red end",
    label: "Blocked view end",
    deltas: [11, 12, 8, 5, 4],
    expected: ["20", "30", "30", "30", "14"]
  }
];

test("shows zone feedback only after the mouse rests", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "desktop", "This test exercises mouse hover.");
  await openAngularControls(page, true);
  const zone = page.locator("[data-preference-zone]");
  await expect(zone).toHaveCSS("cursor", "auto");
  const box = await zone.boundingBox();
  expect(box).not.toBeNull();

  await zone.hover({ position: { x: box.width * 0.94, y: box.height * 0.7 } });
  await page.waitForTimeout(400);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);

  await page.mouse.move(box.x + box.width * 0.9, box.y + box.height * 0.7);
  await page.waitForTimeout(400);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);
  await expect(zone).toHaveClass(/is-tooltip-visible/, { timeout: 1200 });

  await page.mouse.move(box.x + box.width * 0.85, box.y + box.height * 0.7);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);
  await expect(zone).toHaveClass(/is-tooltip-visible/, { timeout: 800 });
  await expect(zone).not.toHaveClass(/is-tooltip-visible/, { timeout: 2000 });
  await page.waitForTimeout(700);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);

  await page.mouse.move(box.x + box.width * 0.8, box.y + box.height * 0.7);
  await page.mouse.move(box.x - 10, box.y - 10);
  await page.waitForTimeout(700);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);
});

test("does not show zone feedback for touch", async ({ page }, testInfo) => {
  test.skip(testInfo.project.name !== "mobile", "This test exercises touch.");
  await openAngularControls(page, true);
  const zone = page.locator("[data-preference-zone]");
  const box = await zone.boundingBox();
  expect(box).not.toBeNull();

  await page.touchscreen.tap(box.x + box.width / 2, box.y + box.height / 2);
  await page.waitForTimeout(700);
  await expect(zone).not.toHaveClass(/is-tooltip-visible/);
});

test("draws the schematic Moon pass as one parabola", async ({ page }) => {
  await openAngularControls(page, true);
  const path = page.locator(".preference-preview-segment");
  const moons = page.locator(".preference-preview-moon");
  await expect(path).toHaveCount(1);
  await expect(path).toHaveAttribute("d", /^M.+ Q.+$/);
  await expect(moons).toHaveCount(13);

  const centers = await moons.evaluateAll(elements => elements.map(element =>
    Number(element.getAttribute("y")) + Number(element.getAttribute("height")) / 2
  ));
  const pathCoordinates = (
    String(await path.getAttribute("d")).match(/-?\d+(?:\.\d+)?/g) || []
  ).map(Number);
  expect(pathCoordinates).toHaveLength(6);
  expect(pathCoordinates[1]).toBeCloseTo(centers[0], 6);
  expect(pathCoordinates[3]).toBeCloseTo(2 * centers[6] - centers[0], 6);
  expect(pathCoordinates[5]).toBeCloseTo(centers[12], 6);
  const secondDifferences = centers.slice(0, -2).map((value, index) =>
    value - 2 * centers[index + 1] + centers[index + 2]
  );
  expect(Math.max(...secondDifferences) - Math.min(...secondDifferences))
    .toBeLessThan(0.001);
  expect(secondDifferences[0]).toBeGreaterThan(0);
});

for (const snapCase of SNAP_CASES) {
  test("keeps the " + snapCase.name + " handle closed through pointer tremor", async ({
    page
  }, testInfo) => {
    await openAngularControls(page, false);
    const handle = page.getByRole("slider", { name: snapCase.label });
    const drag = await beginDegreeDrag(
      page,
      handle,
      page.locator("#preference-compass-track"),
      testInfo.project.name === "mobile"
    );
    try {
      for (let index = 0; index < snapCase.deltas.length; index += 1) {
        await drag.move(snapCase.deltas[index]);
        await expect(handle).toHaveAttribute("aria-valuenow", snapCase.expected[index]);
      }
    } finally {
      await drag.end();
    }
  });
}

async function openAngularControls(page, includeAltitude) {
  await page.route("**/api/opportunities**", route => route.fulfill({
    status: 200,
    contentType: "application/json",
    body: JSON.stringify(sourceFixture)
  }));
  await page.goto("/search?q=Prague");
  const details = page.locator("#opportunity-preferences");
  if (!await details.evaluate(node => node.open)) {
    await details.locator(":scope > summary").click();
  }
  if (includeAltitude) {
    await page.getByLabel("Limit Moon altitude").check();
  }
  await page.getByLabel("Limit Moon direction").check();
}

async function beginDegreeDrag(page, handle, track, touch) {
  await handle.scrollIntoViewIfNeeded();
  const handleBox = await handle.boundingBox();
  const trackBox = await track.boundingBox();
  expect(handleBox).not.toBeNull();
  expect(trackBox).not.toBeNull();
  const start = {
    x: handleBox.x + handleBox.width / 2,
    y: handleBox.y + handleBox.height / 2
  };
  const point = degrees => ({
    x: start.x + trackBox.width * degrees / 360,
    y: start.y
  });
  if (touch) return beginTouchDrag(page, start, point);

  await page.mouse.move(start.x, start.y);
  await page.mouse.down();
  return {
    move: degrees => page.mouse.move(point(degrees).x, point(degrees).y),
    end: () => page.mouse.up()
  };
}

async function beginTouchDrag(page, start, point) {
  const session = await page.context().newCDPSession(page);
  const touchPoint = value => [{
    x: value.x,
    y: value.y,
    id: 1,
    radiusX: 1,
    radiusY: 1,
    force: 1
  }];
  await session.send("Input.dispatchTouchEvent", {
    type: "touchStart",
    touchPoints: touchPoint(start)
  });
  return {
    move: degrees => session.send("Input.dispatchTouchEvent", {
      type: "touchMove",
      touchPoints: touchPoint(point(degrees))
    }),
    end: async () => {
      await session.send("Input.dispatchTouchEvent", { type: "touchEnd", touchPoints: [] });
      await session.detach();
    }
  };
}
