import { element } from "./dom.js";
import { fact } from "./terms.js";

export function scoreBlock(score) {
  var value = Number.isFinite(score) ? Math.max(0, Math.min(100, score)) : 0;
  return element("div", { className: "score-block", ariaLabel: "Opportunity score" },
    element("span", { className: "score-value" }, Number.isFinite(score) ? String(score) : "--"),
    element("span", {
      className: "score-label tooltip",
      title: "Overall fit score from 0 to 100, combining Moon position, light, phase, weather, and confidence.",
      "data-tooltip": "Overall fit score from 0 to 100, combining Moon position, light, phase, weather, and confidence."
    }, "score"),
    element("span", { className: "score-meter" },
      element("span", { style: "width: " + value + "%" })));
}

export function scoreDetails(components) {
  var entries = [
    ["Moon altitude", components.moonAltitudeFit],
    ["Sun light", components.sunLightFit],
    ["Phase score", components.moonIlluminationFit],
    ["Weather", components.weatherFit],
    ["Confidence", components.forecastConfidence]
  ].filter(function (entry) {
    return Number.isFinite(entry[1]);
  });

  if (entries.length === 0) {
    return null;
  }

  return element("details", { className: "score-details" },
    element("summary", {}, "Score details"),
    element("dl", { className: "detail-grid" },
      entries.map(function (entry) {
        return fact(entry[0], String(entry[1]));
      })
    )
  );
}
