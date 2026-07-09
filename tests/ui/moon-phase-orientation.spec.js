import { expect, test } from "@playwright/test";

test("rotates Moon illumination and surface texture independently", async ({ page }) => {
  await page.goto("/search");

  const renderings = await page.evaluate(async () => {
    const modulePath = "/moonPhaseView.js";
    const { drawMoonPhase } = await import(modulePath);

    function render(phaseAngleDegrees, brightLimbTiltDegrees, northPoleTiltDegrees, includeTilt = true) {
      const canvas = document.createElement("canvas");
      canvas.width = 64;
      canvas.height = 64;
      if (includeTilt) {
        drawMoonPhase(canvas, phaseAngleDegrees, brightLimbTiltDegrees, northPoleTiltDegrees);
      } else {
        drawMoonPhase(canvas, phaseAngleDegrees);
      }
      const context = canvas.getContext("2d");
      const image = context?.getImageData(0, 0, canvas.width, canvas.height);
      const averageBrightness = predicate => {
        let total = 0;
        let count = 0;
        for (let y = 0; y < canvas.height; y += 1) {
          for (let x = 0; x < canvas.width; x += 1) {
            if (!predicate(x, y)) {
              continue;
            }
            const offset = (y * canvas.width + x) * 4;
            if ((image?.data[offset + 3] || 0) === 0) {
              continue;
            }
            total += (image?.data[offset] || 0)
              + (image?.data[offset + 1] || 0)
              + (image?.data[offset + 2] || 0);
            count += 1;
          }
        }
        return total / Math.max(1, count);
      };
      return {
        dataUrl: canvas.toDataURL("image/png"),
        image,
        top: averageBrightness((x, y) => y < 26 && x >= 18 && x <= 46),
        right: averageBrightness((x, y) => x > 38 && y >= 18 && y <= 46),
        bottom: averageBrightness((x, y) => y > 38 && x >= 18 && x <= 46),
        left: averageBrightness((x, y) => x < 26 && y >= 18 && y <= 46)
      };
    }

    function rotationError(base, rotated) {
      let difference = 0;
      let channels = 0;
      for (let y = 0; y < 64; y += 1) {
        for (let x = 0; x < 64; x += 1) {
          const targetOffset = (y * 64 + x) * 4;
          const sourceOffset = ((63 - x) * 64 + y) * 4;
          for (let channel = 0; channel < 4; channel += 1) {
            difference += Math.abs(
              (rotated.image?.data[targetOffset + channel] || 0)
              - (base.image?.data[sourceOffset + channel] || 0));
            channels += 1;
          }
        }
      }
      return difference / channels;
    }

    function summary(rendering) {
      return {
        dataUrl: rendering.dataUrl,
        top: rendering.top,
        right: rendering.right,
        bottom: rendering.bottom,
        left: rendering.left
      };
    }

    const surfaceNorth = render(180, 0, 0);
    const surfaceEast = render(180, 0, 90);

    return {
      fallback: summary(render(90, undefined, undefined, false)),
      invalidFallback: summary(render(90, "invalid", "invalid")),
      invalidSurfaceFallback: summary(render(90, 90, "invalid")),
      missingSurfaceFallback: summary(render(90, 90)),
      tilt0: summary(render(90, 0)),
      tilt90: summary(render(90, 90)),
      tilt180: summary(render(90, 180)),
      tilt270: summary(render(90, 270)),
      waningTilt0: summary(render(270, 0)),
      full0: summary(render(180, 0)),
      full90: summary(render(180, 90)),
      surfaceNorth: summary(surfaceNorth),
      surfaceEast: summary(surfaceEast),
      surfaceRotationError: rotationError(surfaceNorth, surfaceEast)
    };
  });

  expect(renderings.invalidFallback.dataUrl).toBe(renderings.fallback.dataUrl);
  expect(renderings.invalidSurfaceFallback.dataUrl).toBe(renderings.missingSurfaceFallback.dataUrl);
  expect(renderings.full0.dataUrl).toBe(renderings.full90.dataUrl);
  expect(renderings.surfaceEast.dataUrl).not.toBe(renderings.surfaceNorth.dataUrl);
  expect(renderings.surfaceRotationError).toBeLessThan(2);
  expect(renderings.fallback.right).toBeGreaterThan(renderings.fallback.left);
  expect(renderings.tilt0.top).toBeGreaterThan(renderings.tilt0.bottom);
  expect(renderings.tilt90.right).toBeGreaterThan(renderings.tilt90.left);
  expect(renderings.tilt180.bottom).toBeGreaterThan(renderings.tilt180.top);
  expect(renderings.tilt270.left).toBeGreaterThan(renderings.tilt270.right);
  expect(renderings.waningTilt0.top).toBeGreaterThan(renderings.waningTilt0.bottom);
});
