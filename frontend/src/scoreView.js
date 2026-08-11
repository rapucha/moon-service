import { element } from "./dom.js";
import { fact } from "./terms.js";
import { weatherRankingLabel } from "./weatherRankingPreference.js";

export function scoreBlock(score, weatherRanking) {
  var value = Number.isFinite(score) ? Math.max(0, Math.min(100, score)) : 0;
  var label = weatherRankingLabel(weatherRanking);
  var description = scoreDescription(weatherRanking);
  return element("div", {
    className: "score-block",
    ariaLabel: "Opportunity score · " + label
  },
    element("span", { className: "score-value" }, Number.isFinite(score) ? String(score) : "--"),
    element("span", {
      className: "score-label tooltip",
      title: description,
      "data-tooltip": description
    }, label),
    element("span", { className: "score-meter" },
      element("span", { style: "width: " + value + "%" })));
}

export function scoreDetails(components, weatherRanking) {
  var entries = [
    ["Moon altitude", components.moonAltitudeFit],
    ["Sun light", components.sunLightFit],
    ["Phase score", components.moonIlluminationFit],
    ["Weather", components.weatherFit],
    ["Confidence", components.forecastConfidence]
  ];
  if (weatherRanking === "ignore_weather") {
    entries = entries.slice(0, 3);
  }
  entries = entries.filter(function (entry) {
    return Number.isFinite(entry[1]);
  });

  if (entries.length === 0) {
    return null;
  }

  return element("details", { className: "score-details" },
    element("summary", {}, "Score details · " + weatherRankingLabel(weatherRanking)),
    element("p", {}, scoreDescription(weatherRanking)),
    element("dl", { className: "detail-grid" },
      entries.map(function (entry) {
        return fact(entry[0], String(entry[1]));
      })
    )
  );
}

function scoreDescription(weatherRanking) {
  if (weatherRanking === "prefer_clear") {
    return "Clearer skies have more influence on this score, but weather does not filter opportunities.";
  }
  if (weatherRanking === "ignore_weather") {
    return "Weather and forecast confidence are excluded from this score. Forecast facts remain visible.";
  }
  return "Moon position, ambient light, phase, weather, and forecast confidence all contribute to this score.";
}
