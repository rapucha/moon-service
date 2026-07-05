import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { spawnSync } from "node:child_process";
import { tmpdir } from "node:os";

const repoRoot = resolve(new URL("..", import.meta.url).pathname);
const outputPath = resolve(repoRoot, "tests/ui/fixtures/moon-pass-curve-corpus.json");
const problemTargetCount = 10;

const scanRequests = [
  request("2026-07-04", 30),
  request("2026-08-03", 30),
  request("2026-09-02", 30)
];

const knownProblemCases = [
  {
    id: "problem-prague-2026-07-08-night-shoulder",
    classification: "problem",
    passStartsAt: "2026-07-07T22:09:09Z",
    reviewNote: "Current cubic rendering develops a visible shoulder on the rising night segment around local 03:10."
  },
  {
    id: "problem-prague-2026-07-10-night-inflection",
    classification: "problem",
    passStartsAt: "2026-07-09T22:49:09Z",
    reviewNote: "Current cubic rendering changes curvature on the rising night segment before the main daylight arc."
  }
];

const knownGoodCases = [
  {
    id: "good-prague-2026-07-04-smooth-set",
    classification: "known_good",
    passStartsAt: "2026-07-03T22:00:00Z",
    reviewNote: "Reference curve with a smooth set-side recommendation; candidate renderers should not flatten or kink it."
  },
  {
    id: "good-prague-2026-07-05-balanced-pass",
    classification: "known_good",
    passStartsAt: "2026-07-04T21:28:50Z",
    reviewNote: "Reference high pass with both rise and set recommendations; candidate renderers should preserve the clean arch."
  }
];
const excludedProblemPassStarts = new Set([
  // Human review: too close to acceptable, likely to overfit curve experiments.
  "2026-08-15T07:14:13Z"
]);

const responses = scanRequests.map(runPrototype);
const groupsByPassStart = passGroups(responses);
const manualStarts = new Set([...knownProblemCases, ...knownGoodCases].map(curveCase => curveCase.passStartsAt));
const autoProblemCases = rankedProblemCandidates(groupsByPassStart)
  .filter(candidate => !manualStarts.has(candidate.pass.startsAt))
  .filter(candidate => !excludedProblemPassStarts.has(candidate.pass.startsAt))
  .slice(0, problemTargetCount - knownProblemCases.length)
  .map((candidate, index) => ({
    id: "problem-prague-" + localDateId(candidate.pass.startsAt, responses[0].location.timezone) + "-current-cubic-" + String(index + 1).padStart(2, "0"),
    classification: "problem",
    passStartsAt: candidate.pass.startsAt,
    reviewNote: "Auto-selected from broad Prague scan: current cubic renderer shows high curvature-change diagnostics.",
    selectionDiagnostics: candidate.diagnostics
  }));

const cases = [...knownProblemCases, ...autoProblemCases, ...knownGoodCases];
const selectedOpportunities = [];
const caseSummaries = cases.map(curveCase => {
  const group = groupsByPassStart.get(curveCase.passStartsAt);
  if (!group) {
    throw new Error("Could not find pass starting at " + curveCase.passStartsAt);
  }
  selectedOpportunities.push(...group.opportunities);
  return caseSummary(curveCase, group);
});

const firstResponse = responses[0];
const fixture = {
  ...firstResponse,
  generatedAt: "2026-07-04T00:00:00Z",
  forecastHorizonDays: scanRequests.reduce((days, item) => days + item.forecastHorizonDays, 0),
  startsAt: firstResponse.startsAt,
  endsAt: responses[responses.length - 1].endsAt,
  candidateWindowsEvaluated: responses.reduce((total, item) => total + item.candidateWindowsEvaluated, 0),
  opportunities: selectedOpportunities,
  messages: [
    {
      level: "info",
      code: "curve_corpus_fixture",
      text: "Provider-free curve corpus generated from fixed-weather Prague prototype pass samples."
    },
    ...(Array.isArray(firstResponse.messages) ? firstResponse.messages : [])
  ],
  diagnostics: {
    ...(firstResponse.diagnostics || {}),
    curveCorpus: {
      generatedBy: "scripts/build_moon_pass_curve_corpus.mjs",
      scanRequests,
      problemTargetCount,
      cases: caseSummaries
    }
  }
};

mkdirSync(dirname(outputPath), { recursive: true });
writeFileSync(outputPath, JSON.stringify(fixture, null, 2) + "\n");

for (const curveCase of caseSummaries) {
  console.log([
    curveCase.classification,
    curveCase.id,
    curveCase.startsAt + " -> " + curveCase.endsAt,
    "samples=" + curveCase.sampleCount,
    "recommendations=" + curveCase.recommendationCount,
    "curvatureChanges=" + curveCase.currentCubicApproxDiagnostics.curvatureSignChanges,
    "selectionScore=" + curveCase.currentCubicApproxDiagnostics.selectionScore
  ].join(" | "));
}
console.log("Wrote " + outputPath);

function request(start, forecastHorizonDays) {
  return {
    locationId: "prague-cz",
    start,
    forecastHorizonDays,
    maxMoonAltitudeDegrees: 12,
    limit: 100
  };
}

function runPrototype(scanRequest, index) {
  const requestPath = resolve(tmpdir(), "moon-pass-curve-corpus-request-" + index + ".json");
  writeFileSync(requestPath, JSON.stringify(scanRequest));

  const prototype = spawnSync("mvn", [
    "-q",
    "test-compile",
    "org.codehaus.mojo:exec-maven-plugin:3.3.0:java",
    "-Dexec.classpathScope=test",
    "-Dexec.mainClass=dev.moonservice.scoringprototype.cli.MoonScoringPrototype",
    `-Dexec.args=--request ${requestPath}`
  ], {
    cwd: resolve(repoRoot, "prototypes/jvm-scoring"),
    encoding: "utf8",
    maxBuffer: 40 * 1024 * 1024
  });

  if (prototype.status !== 0) {
    process.stderr.write(prototype.stderr || prototype.stdout);
    process.exit(prototype.status ?? 1);
  }
  return JSON.parse(prototype.stdout);
}

function passGroups(responsesToGroup) {
  const groups = new Map();
  for (const response of responsesToGroup) {
    for (const opportunity of response.opportunities || []) {
      const pass = opportunity.moonPass || {};
      const existing = groups.get(pass.startsAt) || {
        pass,
        opportunities: []
      };
      existing.opportunities.push(opportunity);
      groups.set(pass.startsAt, existing);
    }
  }
  return groups;
}

function rankedProblemCandidates(groups) {
  return Array.from(groups.values())
    .map(group => ({
      pass: group.pass,
      diagnostics: currentCubicDiagnostics(group)
    }))
    .filter(candidate => candidate.diagnostics.curvatureSignChanges >= 4)
    .sort((left, right) => right.diagnostics.selectionScore - left.diagnostics.selectionScore);
}

function caseSummary(curveCase, group) {
  const diagnostics = curveCase.selectionDiagnostics || currentCubicDiagnostics(group);
  return {
    ...curveCase,
    selectionDiagnostics: undefined,
    passId: group.pass.id,
    startsAt: group.pass.startsAt,
    endsAt: group.pass.endsAt,
    sampleCount: group.pass.path.samples.length,
    recommendationCount: group.opportunities.length,
    currentCubicApproxDiagnostics: diagnostics
  };
}

function currentCubicDiagnostics(group) {
  const chartPoints = toChartPoints(combinedPassSamples(group));
  const sampled = resampleByLength(sampleCurrentCubic(chartPoints), 240);
  const maxAltitude = Math.max(...combinedPassSamples(group).map(sample => sample.altitudeDegrees));
  const durationHours = (new Date(group.pass.endsAt).getTime() - new Date(group.pass.startsAt).getTime()) / (60 * 60 * 1000);
  const curvatureSignChanges = curvatureSignChangeCount(sampled);
  const closeSamplePairs = closeAdjacentSampleCount(combinedPassSamples(group));
  const localExtrema = localExtremaCount(sampled);

  return {
    curvatureSignChanges,
    localExtrema,
    closeSamplePairs,
    sampleCount: chartPoints.length,
    maxAltitudeDegrees: round1(maxAltitude),
    durationHours: round1(durationHours),
    selectionScore: round1((curvatureSignChanges * 10) + (closeSamplePairs * 1.5) + (maxAltitude / 10) + Math.min(durationHours, 20))
  };
}

function combinedPassSamples(group) {
  const samplesByTime = new Map();
  for (const sample of passPathSamples(group.pass.path || {})) {
    addPathSample(samplesByTime, sample);
  }
  for (const opportunity of group.opportunities) {
    addPathSample(samplesByTime, (opportunity.moonPath || {}).suggested);
  }
  return Array.from(samplesByTime.keys())
    .sort((left, right) => left - right)
    .map(time => samplesByTime.get(time));
}

function passPathSamples(path) {
  const samples = Array.isArray(path.samples) ? path.samples : [];
  return samples.concat([path.start, path.end]);
}

function addPathSample(samplesByTime, sample) {
  if (!sample || !sample.at || !Number.isFinite(sample.altitudeDegrees) || !Number.isFinite(sample.azimuthDegrees)) {
    return;
  }
  const time = new Date(sample.at).getTime();
  if (Number.isFinite(time)) {
    samplesByTime.set(time, sample);
  }
}

function toChartPoints(samples) {
  const left = 34;
  const top = 70;
  const bottom = 326;
  const chartWidth = 672;
  const chartHeight = bottom - top;
  const firstTime = new Date(samples[0].at).getTime();
  const lastTime = new Date(samples[samples.length - 1].at).getTime();
  const timeSpan = Math.max(1, lastTime - firstTime);
  const maxAltitude = samples.reduce((max, sample) => Math.max(max, sample.altitudeDegrees), 0);
  const ceiling = Math.min(90, Math.max(12, Math.ceil((maxAltitude + 1) / 5) * 5));
  return samples.map(sample => ({
    x: left + ((new Date(sample.at).getTime() - firstTime) / timeSpan) * chartWidth,
    y: bottom - (clamp(sample.altitudeDegrees, 0, ceiling) / ceiling) * chartHeight
  }));
}

function sampleCurrentCubic(points) {
  const tangents = monotoneTangents(points);
  const sampled = [];
  for (let index = 0; index < points.length - 1; index += 1) {
    const point = points[index];
    const next = points[index + 1];
    const dx = next.x - point.x;
    if (dx <= 0) {
      sampled.push(next);
      continue;
    }
    const control1 = {
      x: point.x + dx / 3,
      y: point.y + tangents[index] * dx / 3
    };
    const control2 = {
      x: next.x - dx / 3,
      y: next.y - tangents[index + 1] * dx / 3
    };
    for (let step = index === 0 ? 0 : 1; step <= 10; step += 1) {
      sampled.push(cubicPoint(point, control1, control2, next, step / 10));
    }
  }
  return sampled;
}

function resampleByLength(points, count) {
  const cumulative = [0];
  for (let index = 1; index < points.length; index += 1) {
    const previous = points[index - 1];
    const current = points[index];
    cumulative[index] = cumulative[index - 1] + Math.hypot(current.x - previous.x, current.y - previous.y);
  }
  const total = cumulative[cumulative.length - 1];
  if (!Number.isFinite(total) || total <= 0) {
    return points;
  }

  const resampled = [];
  let segment = 1;
  for (let step = 0; step <= count; step += 1) {
    const target = (total * step) / count;
    while (segment < cumulative.length - 1 && cumulative[segment] < target) {
      segment += 1;
    }
    const previous = points[segment - 1];
    const current = points[segment];
    const span = Math.max(0.0001, cumulative[segment] - cumulative[segment - 1]);
    const ratio = clamp((target - cumulative[segment - 1]) / span, 0, 1);
    resampled.push({
      x: previous.x + ((current.x - previous.x) * ratio),
      y: previous.y + ((current.y - previous.y) * ratio)
    });
  }
  return resampled;
}

function monotoneTangents(points) {
  const slopes = [];
  const tangents = [];
  points.slice(0, -1).forEach((point, index) => {
    const next = points[index + 1];
    const dx = Math.max(1, next.x - point.x);
    slopes.push((next.y - point.y) / dx);
  });
  tangents[0] = slopes[0];
  tangents[points.length - 1] = slopes[slopes.length - 1];

  for (let index = 1; index < points.length - 1; index += 1) {
    const before = slopes[index - 1];
    const after = slopes[index];
    tangents[index] = before * after <= 0 ? 0 : (before + after) / 2;
  }

  slopes.forEach((slope, index) => {
    if (slope === 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      return;
    }
    const first = tangents[index] / slope;
    const second = tangents[index + 1] / slope;
    if (first < 0 || second < 0) {
      tangents[index] = 0;
      tangents[index + 1] = 0;
      return;
    }
    const sum = first * first + second * second;
    if (sum > 9) {
      const scale = 3 / Math.sqrt(sum);
      tangents[index] = scale * first * slope;
      tangents[index + 1] = scale * second * slope;
    }
  });
  return tangents;
}

function cubicPoint(start, control1, control2, end, ratio) {
  const inverse = 1 - ratio;
  return {
    x: (inverse ** 3 * start.x) + (3 * inverse ** 2 * ratio * control1.x) + (3 * inverse * ratio ** 2 * control2.x) + (ratio ** 3 * end.x),
    y: (inverse ** 3 * start.y) + (3 * inverse ** 2 * ratio * control1.y) + (3 * inverse * ratio ** 2 * control2.y) + (ratio ** 3 * end.y)
  };
}

function closeAdjacentSampleCount(samples) {
  return samples.slice(1).reduce((count, sample, index) => {
    const previous = samples[index];
    const minutes = (new Date(sample.at).getTime() - new Date(previous.at).getTime()) / (60 * 1000);
    return minutes > 0 && minutes < 6 ? count + 1 : count;
  }, 0);
}

function localExtremaCount(points) {
  const signs = [];
  for (let index = 1; index < points.length; index += 1) {
    const dy = points[index].y - points[index - 1].y;
    if (Math.abs(dy) >= 0.35) {
      signs.push(Math.sign(dy));
    }
  }
  return signChangeCount(signs);
}

function curvatureSignChangeCount(points) {
  const signs = [];
  for (let index = 1; index < points.length - 1; index += 1) {
    const previous = points[index - 1];
    const current = points[index];
    const next = points[index + 1];
    const dx1 = Math.max(0.1, current.x - previous.x);
    const dx2 = Math.max(0.1, next.x - current.x);
    const slope1 = (current.y - previous.y) / dx1;
    const slope2 = (next.y - current.y) / dx2;
    const second = slope2 - slope1;
    if (Math.abs(second) >= 0.015) {
      signs.push(Math.sign(second));
    }
  }
  return signChangeCount(signs);
}

function signChangeCount(signs) {
  return signs.slice(1).reduce((count, sign, index) => {
    const previous = signs[index];
    return sign !== previous ? count + 1 : count;
  }, 0);
}

function localDateId(value, timezone) {
  return new Intl.DateTimeFormat("en-CA", {
    timeZone: timezone,
    year: "numeric",
    month: "2-digit",
    day: "2-digit"
  }).format(new Date(value));
}

function clamp(value, min, max) {
  return Math.min(max, Math.max(min, value));
}

function round1(value) {
  return Math.round(value * 10) / 10;
}
