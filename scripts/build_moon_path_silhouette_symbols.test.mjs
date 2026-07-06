import assert from "node:assert/strict";
import {
  buildCatalogFromManifest,
  buildSymbolRecord,
  sanitizeSvgSymbol
} from "./build_moon_path_silhouette_symbols.mjs";

const safeSvg = `<svg viewBox="0 0 10 20" xmlns="http://www.w3.org/2000/svg">
  <path class="moon-path-foreground-shape" d="M0 20 L5 0 L10 20 Z" />
  <rect class="moon-path-foreground-window" x="3" y="10" width="2" height="3" rx="0.7" />
</svg>`;
const offsetViewBoxSvg = `<svg viewBox="0 5 10 20" xmlns="http://www.w3.org/2000/svg">
  <path class="moon-path-foreground-shape" d="M0 25 L5 5 L10 25 Z" />
</svg>`;

const validEntry = {
  id: "generic-test-symbol",
  file: "generic/test.svg",
  baselineY: 20,
  intrinsicHeight: 20,
  tags: ["generic", "test"],
  license: "project-owned",
  attribution: "Moon Service test asset"
};

assert.equal(sanitizeSvgSymbol(safeSvg, "safe.svg").elements.length, 2);
assert.equal(buildSymbolRecord(validEntry, safeSvg).id, "generic-test-symbol");

assert.throws(function rejectScript() {
  sanitizeSvgSymbol(`<svg viewBox="0 0 10 10"><script /></svg>`, "unsafe.svg");
}, /unsafe SVG content/);

assert.throws(function rejectEventHandler() {
  sanitizeSvgSymbol(`<svg viewBox="0 0 10 10"><path onclick="alert(1)" d="M0 0" /></svg>`, "unsafe.svg");
}, /unsafe SVG content/);

assert.throws(function rejectExternalHref() {
  sanitizeSvgSymbol(`<svg viewBox="0 0 10 10"><image href="https://example.com/a.png" /></svg>`, "unsafe.svg");
}, /unsafe SVG content/);

assert.throws(function rejectUnsupportedAttribute() {
  sanitizeSvgSymbol(`<svg viewBox="0 0 10 10"><rect x="0" y="0" width="1" height="1" fill="red" /></svg>`, "unsafe.svg");
}, /unsupported attribute/);

assert.throws(function rejectMissingLicense() {
  buildSymbolRecord({ ...validEntry, id: "generic-no-license", license: "" }, safeSvg);
}, /license must be present/);

assert.throws(function rejectInvalidBaseline() {
  buildSymbolRecord({ ...validEntry, id: "generic-bad-baseline", baselineY: 25 }, safeSvg);
}, /baselineY must fit/);

assert.throws(function rejectBelowViewBoxBaseline() {
  buildSymbolRecord({ ...validEntry, id: "generic-low-baseline", baselineY: 4 }, offsetViewBoxSvg);
}, /baselineY must fit/);

assert.throws(function rejectIntrinsicHeightBelowBaseline() {
  buildSymbolRecord({ ...validEntry, id: "generic-bad-height", baselineY: 12, intrinsicHeight: 20 }, safeSvg);
}, /intrinsicHeight must not exceed/);

assert.throws(function rejectDuplicateIds() {
  buildCatalogFromManifest({
    schemaVersion: 1,
    symbols: [
      validEntry,
      { ...validEntry, file: "generic/test-2.svg" }
    ]
  }, new URL("file:///tmp/"), function readText() {
    return safeSvg;
  });
}, /Duplicate silhouette symbol id/);

assert.throws(function rejectFileUrlSource() {
  buildCatalogFromManifest({
    schemaVersion: 1,
    symbols: [
      { ...validEntry, id: "generic-file-url", file: "file:///tmp/asset.svg" }
    ]
  }, new URL("file:///tmp/moon-path-silhouettes/"), function readText() {
    return safeSvg;
  });
}, /file must stay inside/);

assert.throws(function rejectEncodedTraversal() {
  buildCatalogFromManifest({
    schemaVersion: 1,
    symbols: [
      { ...validEntry, id: "generic-encoded-traversal", file: "generic/%2e%2e/asset.svg" }
    ]
  }, new URL("file:///tmp/moon-path-silhouettes/"), function readText() {
    return safeSvg;
  });
}, /file must stay inside/);

console.log("Silhouette symbol sanitizer tests passed.");
