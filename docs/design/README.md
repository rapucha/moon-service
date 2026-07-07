# Design Iterations

Purpose
- Preserve useful UI design checkpoints that informed product decisions.
- Keep mockups separate from the runtime frontend so implementation can follow
  once the direction is agreed.
- For reversible UI experiment history, use the workflow in
  `docs/ui-spec.md`.

Moon Pass Card
- `docs/design/moon-pass-card-mockups.html`: desktop mockups comparing compact
  Moon pass card options, with revised Option B as the preferred direction.
- `docs/design/moon-pass-card-mobile-mockups.html`: mobile mockups for the same
  compact Moon pass card direction.
- `docs/design/azimuth-diagram-shape-mockups.html`: azimuth diagram shape
  variants, including a compact compass, an old compass rose, a horizon fan, an
  azimuth strip, and a compass ring.
- `docs/design/combined-altitude-azimuth-mockups.html`: combined chart variants
  that put azimuth on the altitude chart's time axis.
- `docs/design/final-moon-pass-card-mockup.html`: previous final mockup
  combining compact Moon pass cards with the selected top azimuth rail chart.
- `docs/design/pass-interval-hierarchy-mockups.html`: current hierarchy mockup
  for the Moon pass card, with the preferred pass interval placement.
- `docs/design/generic-silhouette-mockups.html`: generic building, tree, and
  hill silhouette variants for low-altitude Moon path intuition.
- `docs/design/silhouette-followup-mockups.html`: simplified right-side
  silhouette scale and animated foreground follow-up variants.
- `docs/design/silhouette-three-layer-mockup.html`: single-card mockup for
  three-layer near, mid, and far parallax silhouettes.
- `docs/design/sun-marker-mockups.html`: larger, richer secondary Sun marker
  variants sized closer to the Alternative Moon marker.

Current Direction
- Use compact recommendation rows instead of repeated prose summaries.
- Mark the strongest recommendation with a `Best` badge and label the other
  same-pass window as `Alternative`.
- Keep window length, Moon altitude/direction, light bucket, Sun altitude,
  a coarse sky/weather label, and a short photo hint visible.
- Use one shared pass-level Moon path chart where altitude, light buckets, and a
  top azimuth rail share the same time axis.
