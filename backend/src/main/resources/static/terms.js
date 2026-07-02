import { element } from "./dom.js";

var TERM_DESCRIPTIONS = {
  "Location": "The kind of place this lookup accepts.",
  "Storage": "Where recent searches are kept.",
  "Output": "What this page produces after a lookup.",
  "Forecast": "How many days of forecast data were considered.",
  "Evaluated": "How many candidate Moon windows were checked before ranking.",
  "Timezone": "The local timezone used for displayed times.",
  "Lookup": "Whether this result came from a typed query or a selected location.",
  "Suggested": "The best instant inside the displayed Moon window.",
  "Duration": "How long the candidate window lasts.",
  "Moon altitude": "Moon height above the horizon. Lower values are usually more useful for landscape composition.",
  "Moon azimuth": "Compass direction of the Moon, shown as ° clockwise from north.",
  "Bucket": "The ambient-light category around the window, such as golden hour or twilight.",
  "Sun altitude": "Sun height relative to the horizon. Negative values mean the Sun is below the horizon.",
  "Moon phase": "How much of the visible Moon disk is lit at the suggested time, including waxing or waning shape.",
  "Summary": "A compact weather description for the candidate window.",
  "Cloud": "Mean and maximum cloud cover across the candidate window.",
  "Precip": "Precipitation risk and expected amount across the candidate window.",
  "Visibility": "Forecast surface visibility near the location.",
  "Phase score": "How well the Moon illumination fits the scoring model.",
  "Sun light": "How well the ambient light fits the scoring model.",
  "Weather": "How well forecast conditions fit the scoring model.",
  "Confidence": "How much confidence the scoring model has in the forecast inputs."
};

export function fact(label, value) {
  return element("div", {},
    element("dt", {}, term(label)),
    element("dd", {}, value || "Unavailable"));
}

function term(label) {
  var description = TERM_DESCRIPTIONS[label];
  if (!description) {
    return label;
  }
  return element("span", {
    className: "tooltip",
    title: description,
    "data-tooltip": description
  }, label);
}
