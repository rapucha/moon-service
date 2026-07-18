import assert from "node:assert/strict";
import { lightBandSegments } from "../frontend/src/moonPathLightBands.js";

const canberraSunrisePoints = chartPoints([
  point("2026-07-10T17:39:00Z", -18.0, "night"),
  point("2026-07-10T20:09:01Z", -12.0, "night"),
  point("2026-07-10T20:40:10Z", -6.0, "nautical_twilight"),
  point("2026-07-10T21:07:46Z", -0.833, "civil_twilight"),
  point("2026-07-10T21:49:00Z", 6.0, "golden_hour"),
  point("2026-07-11T03:23:00Z", 32.0, "daylight")
]);

const bands = lightBandSegments(canberraSunrisePoints);
assert.deepEqual(
  bands.map(function (band) {
    return band.lightBucket;
  }),
  ["night", "nautical_twilight", "civil_twilight", "golden_hour", "daylight"]
);

const civilTwilight = bands.find(function (band) {
  return band.lightBucket === "civil_twilight";
});
assert.ok(civilTwilight, "Expected a civil twilight band.");
assert.equal(civilTwilight.startsAt, "2026-07-10T20:40:10Z");
assert.equal(civilTwilight.endsAt, "2026-07-10T21:07:46Z");
assert.ok(
  durationMinutes(civilTwilight) > 27 && durationMinutes(civilTwilight) < 28,
  "Expected Canberra civil twilight to span the sunrise threshold interval, not a narrow sliver."
);

const fallbackBands = lightBandSegments([
  { x: 0, at: "2026-07-10T20:00:00Z", lightBucket: "night" },
  { x: 10, at: "2026-07-10T20:30:00Z", lightBucket: "civil_twilight" }
]);
assert.equal(fallbackBands[0].lightBucket, "night");

console.log("Moon path light-band tests passed.");

function point(at, sunAltitudeDegrees, lightBucket) {
  return {
    at: at,
    time: Date.parse(at),
    altitudeDegrees: 4,
    azimuthDegrees: 90,
    sunAltitudeDegrees: sunAltitudeDegrees,
    lightBucket: lightBucket
  };
}

function chartPoints(points) {
  const firstTime = points[0].time;
  const span = points[points.length - 1].time - firstTime;
  return points.map(function (point) {
    return {
      ...point,
      x: ((point.time - firstTime) / span) * 672
    };
  });
}

function durationMinutes(band) {
  return (Date.parse(band.endsAt) - Date.parse(band.startsAt)) / 60000;
}
