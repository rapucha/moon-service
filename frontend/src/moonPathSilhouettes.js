import { svgElement } from "./dom.js";
import { round1 } from "./format.js";
import { MOON_PATH_SILHOUETTE_SYMBOLS } from "./moonPathSilhouetteSymbols.js";

var SILHOUETTE_SEQUENCE_WIDTH = 320;

// Moon path foreground tuning:
// - Heights are apparent altitude degrees so silhouettes scale with the chart ceiling.
// - Smaller layer duration means faster parallax drift; opacity keeps the Moon path dominant.
// - Figures place sanitized SVG symbols by id; source assets live under assets/moon-path-silhouettes/.
// - See "Moon Path Foreground Animation" in docs/ui-spec.md for the symbol contract.
var SILHOUETTE_HEIGHT_DEGREES = {
  hill: 2.2,
  house: 3,
  tree: 4.5,
  midRise: 5.5,
  church: 6.8,
  tallTower: 11.7
};

var SILHOUETTE_LAYERS = [
  {
    id: "far",
    className: "is-far",
    opacity: 0.1,
    durationSeconds: 54,
    delaySeconds: -21,
    offsetX: 126,
    figures: [
      { symbol: "generic-hill-166", x: 0, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.hill },
      { symbol: "generic-block-2x2", x: 88, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.midRise },
      { symbol: "generic-tree-wavy", x: 184, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.tree },
      { symbol: "generic-house-gabled", x: 228, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.house },
      { symbol: "generic-tower-1x6", x: 286, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.tallTower }
    ]
  },
  {
    id: "mid",
    className: "is-mid",
    opacity: 0.15,
    durationSeconds: 34,
    delaySeconds: -11,
    offsetX: 48,
    figures: [
      { symbol: "generic-hill-146", x: 0, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.hill },
      { symbol: "generic-block-2x3", x: 82, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.midRise },
      { symbol: "generic-church-small", x: 166, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.church },
      { symbol: "generic-tree-wavy", x: 254, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.tree },
      { symbol: "generic-house-gabled", x: 300, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.house }
    ]
  },
  {
    id: "near",
    className: "is-near",
    opacity: 0.22,
    durationSeconds: 24,
    delaySeconds: 0,
    offsetX: 0,
    figures: [
      { symbol: "generic-hill-130", x: 0, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.hill },
      { symbol: "generic-house-gabled", x: 82, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.house },
      { symbol: "generic-tree-wavy", x: 150, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.tree },
      { symbol: "generic-block-2x3", x: 224, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.midRise },
      { symbol: "generic-tower-1x6", x: 276, heightDegrees: SILHOUETTE_HEIGHT_DEGREES.tallTower }
    ]
  }
];

var SILHOUETTE_SYMBOLS_BY_ID = MOON_PATH_SILHOUETTE_SYMBOLS.reduce(function indexSymbols(index, symbol) {
  index[symbol.id] = symbol;
  return index;
}, {});
validateSilhouetteLayerConfig();
var ACTIVE_SILHOUETTE_SYMBOLS = activeSilhouetteSymbols();

export function altitudeForegroundArtwork(left, top, bottom, chartWidth, mode, firstTime, ceiling, chartHeight) {
  var idSuffix = mode + "-" + Math.abs(Math.round(firstTime)).toString(36);
  var clipId = "moon-path-foreground-clip-" + idSuffix;
  var symbolIds = foregroundSymbolIds(idSuffix);
  var layerSequences = SILHOUETTE_LAYERS.map(function (layer) {
    return {
      layer: layer,
      sequenceId: "moon-path-foreground-" + layer.id + "-" + idSuffix
    };
  });
  var sequenceWidth = SILHOUETTE_SEQUENCE_WIDTH;
  var repetitions = Math.ceil(chartWidth / sequenceWidth) + 2;
  var scale = foregroundAngleScale(ceiling, chartHeight);

  return [
    svgElement("defs", {},
      svgElement("clipPath", { id: clipId },
        svgElement("rect", {
          x: left,
          y: top,
          width: round1(chartWidth),
          height: bottom - top
        })),
      foregroundSymbolDefinitions(symbolIds),
      layerSequences.map(function (entry) {
        return svgElement("g", { id: entry.sequenceId }, foregroundLayerSymbols(entry.layer, symbolIds, bottom, scale));
      })),
    svgElement("g", {
      className: "moon-path-foreground-layer",
      "aria-hidden": "true",
      "clip-path": "url(#" + clipId + ")"
    },
      layerSequences.map(function (entry) {
        return svgElement("g", {
          className: "moon-path-foreground " + entry.layer.className,
          style: foregroundLayerStyle(entry.layer)
        },
          foregroundSequenceUses(entry.sequenceId, left, repetitions, sequenceWidth));
      }))
  ];
}

function foregroundSymbolIds(idSuffix) {
  return ACTIVE_SILHOUETTE_SYMBOLS.reduce(function indexIds(ids, symbol) {
    ids[symbol.id] = "moon-path-symbol-" + symbol.id + "-" + idSuffix;
    return ids;
  }, {});
}

function foregroundSymbolDefinitions(symbolIds) {
  return ACTIVE_SILHOUETTE_SYMBOLS.map(function renderDefinition(symbol) {
    return svgElement("symbol", {
      id: symbolIds[symbol.id],
      viewBox: symbol.viewBox.x + " " + symbol.viewBox.y + " " + symbol.viewBox.width + " " + symbol.viewBox.height
    },
      symbol.elements.map(symbolElement));
  });
}

function symbolElement(elementDefinition) {
  return svgElement(elementDefinition.tag, elementDefinition.attrs);
}

function foregroundAngleScale(ceiling, chartHeight) {
  return function scaleDegrees(degreesValue) {
    return round1((degreesValue / Math.max(1, ceiling)) * chartHeight);
  };
}

function foregroundLayerStyle(layer) {
  return "--moon-path-foreground-opacity: " + layer.opacity
    + "; --moon-path-foreground-duration: " + layer.durationSeconds + "s"
    + "; --moon-path-foreground-delay: " + layer.delaySeconds + "s"
    + "; --moon-path-foreground-shift: -" + SILHOUETTE_SEQUENCE_WIDTH + "px";
}

function foregroundSequenceUses(sequenceId, left, repetitions, sequenceWidth) {
  var uses = [];
  for (var index = 0; index < repetitions; index += 1) {
    uses.push(svgElement("use", {
      className: "moon-path-foreground-use",
      "data-moon-path-artwork": "true",
      href: "#" + sequenceId,
      transform: "translate(" + round1(left + (index * sequenceWidth)) + " 0)"
    }));
  }
  return uses;
}

function foregroundLayerSymbols(layer, symbolIds, baseline, scale) {
  return layer.figures.map(function renderFigure(figure) {
    return foregroundFigureSymbol(figure, layer.offsetX || 0, symbolIds, baseline, scale);
  }).flat();
}

function foregroundFigureSymbol(figure, layerOffsetX, symbolIds, baseline, scale) {
  var symbol = SILHOUETTE_SYMBOLS_BY_ID[figure.symbol];
  if (!symbol) {
    throw new Error("Unknown Moon path silhouette symbol: " + figure.symbol);
  }
  var targetHeight = scale(figure.heightDegrees);
  var symbolScale = targetHeight / Math.max(1, symbol.intrinsicHeight);
  var x = layerOffsetX + (figure.x || 0);
  var y = baseline - (symbol.baselineY * symbolScale);

  return svgElement("use", {
    className: "moon-path-foreground-symbol",
    "data-moon-path-artwork": "true",
    "data-moon-path-symbol": figure.symbol,
    href: "#" + symbolIds[figure.symbol],
    x: round1(x),
    y: round1(y),
    width: round1(symbol.viewBox.width * symbolScale),
    height: round1(symbol.viewBox.height * symbolScale)
  });
}

function validateSilhouetteLayerConfig() {
  SILHOUETTE_LAYERS.forEach(function validateLayer(layer) {
    if (!Array.isArray(layer.figures)) {
      throw new Error("Moon path silhouette layer must define figures: " + layer.id);
    }
    layer.figures.forEach(function validateFigure(figure) {
      if (!SILHOUETTE_SYMBOLS_BY_ID[figure.symbol]) {
        throw new Error("Unknown Moon path silhouette symbol: " + figure.symbol);
      }
      if (!Number.isFinite(figure.heightDegrees) || figure.heightDegrees <= 0) {
        throw new Error("Moon path silhouette figure heightDegrees must be positive: " + figure.symbol);
      }
      if (!Number.isFinite(figure.x)) {
        throw new Error("Moon path silhouette figure x must be finite: " + figure.symbol);
      }
    });
  });
}

function activeSilhouetteSymbols() {
  var activeIds = new Set();
  SILHOUETTE_LAYERS.forEach(function collectLayer(layer) {
    layer.figures.forEach(function collectFigure(figure) {
      activeIds.add(figure.symbol);
    });
  });
  return MOON_PATH_SILHOUETTE_SYMBOLS.filter(function isActive(symbol) {
    return activeIds.has(symbol.id);
  });
}
