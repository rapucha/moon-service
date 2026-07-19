# Diagram Sources

The `.puml` files in this directory are the editable source of truth. The SVG
files are generated reading copies and must not be edited directly.

| PlantUML source | Generated SVG |
| --- | --- |
| `calibration-feedback-sequence.puml` | `calibration-feedback-sequence.svg` |
| `opportunity-search-get.puml` | `opportunity-search-get.svg` |
| `opportunity-search-post.puml` | `opportunity-search-post.svg` |
| `scoring-flow.puml` | `scoring-flow.svg` |

## Pinned Renderer

- PlantUML: `1.2026.6`, commit `6287b33`
- JAR: `plantuml-1.2026.6.jar`, 29,499,608 bytes
- Download: `https://github.com/plantuml/plantuml/releases/download/v1.2026.6/plantuml-1.2026.6.jar`
- SHA-256: `89948f14c93756c7a3fb7b69078ff37e8489fd79dd430c582b931e2f65358690`
- Java: OpenJDK `25.0.3`
- Font: DejaVu Sans from `fonts-dejavu-core` `2.37-8`
- External Graphviz: not used; `scoring-flow.puml` selects PlantUML's internal
  Smetana layout explicitly

Download the JAR to `/tmp/plantuml-1.2026.6.jar`, confirm its size and SHA-256,
then run this command from the repository root:

```bash
java -Djava.awt.headless=true -jar /tmp/plantuml-1.2026.6.jar --format svg --charset UTF-8 docs/diagrams/calibration-feedback-sequence.puml docs/diagrams/opportunity-search-get.puml docs/diagrams/opportunity-search-post.puml docs/diagrams/scoring-flow.puml
```

The command must replace all four SVGs byte-for-byte from the tracked inputs.
Run it twice and compare the SVG checksums to verify deterministic regeneration.
Also inspect each full-size SVG and each image at ordinary Markdown reading
width; labels must be unclipped and branches distinguishable.
