# Diagram Sources

Edit only the sources named below. Generated SVG and PDF files are reading
copies and must not be edited directly.

## PlantUML Sources

| PlantUML source | Generated SVG |
| --- | --- |
| `calibration-feedback-sequence.puml` | `calibration-feedback-sequence.svg` |
| `hosted-alpha-resource-limits.puml` | `hosted-alpha-resource-limits.svg` |
| `opportunity-search-get.puml` | `opportunity-search-get.svg` |
| `opportunity-search-post.puml` | `opportunity-search-post.svg` |
| `scoring-flow.puml` | `scoring-flow.svg` |

### Pinned Renderer

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
java -Djava.awt.headless=true -jar /tmp/plantuml-1.2026.6.jar --format svg --charset UTF-8 docs/diagrams/calibration-feedback-sequence.puml docs/diagrams/hosted-alpha-resource-limits.puml docs/diagrams/opportunity-search-get.puml docs/diagrams/opportunity-search-post.puml docs/diagrams/scoring-flow.puml
```

The command must replace all five SVGs byte-for-byte from the tracked inputs.
Run it twice and compare the SVG checksums to verify deterministic regeneration.
Also inspect each full-size SVG and each image at ordinary Markdown reading
width; labels must be unclipped and branches distinguishable.

## Hosted-Alpha PDF Sources

The custom current-style variant uses two hand-authored SVG sources:

| Page source | Generated reading copy |
| --- | --- |
| `hosted-alpha-resource-admission.svg` | page 1 of `hosted-alpha-resource-limits.pdf` |
| `token-bucket-mechanics.svg` | page 2 of `hosted-alpha-resource-limits.pdf` |

The PlantUML SVG above is the alternate rendering of the same admission model.
The second PDF page retains the TokenBucket algorithm detail.

### Pinned PDF Renderers

- Google Chrome: `150.0.7871.114`, package
  `google-chrome-stable` `150.0.7871.114-1`
- Ghostscript: `10.02.1`, package `ghostscript`
  `10.02.1~dfsg1-0ubuntu7.8`
- Font: DejaVu Sans from `fonts-dejavu-core` `2.37-8`

Run these commands from the repository root. `--no-sandbox` is used only for
the two tracked local SVG inputs in the constrained headless renderer.

```bash
diagram_tmp="$(mktemp -d)"
google-chrome --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --no-pdf-header-footer --user-data-dir="$diagram_tmp/chrome-page-1" --print-to-pdf="$diagram_tmp/page-1.pdf" "file://$PWD/docs/diagrams/hosted-alpha-resource-admission.svg"
google-chrome --headless=new --no-sandbox --disable-gpu --disable-dev-shm-usage --no-pdf-header-footer --user-data-dir="$diagram_tmp/chrome-page-2" --print-to-pdf="$diagram_tmp/page-2.pdf" "file://$PWD/docs/diagrams/token-bucket-mechanics.svg"
gs -q -dBATCH -dNOPAUSE -sDEVICE=pdfwrite -dCompatibilityLevel=1.4 -dAutoRotatePages=/None -dEmbedAllFonts=true -dSubsetFonts=true -dCompressFonts=true -dDetectDuplicateImages=true -dOmitInfoDate=true -dOmitID=true -dOmitXMP=true -sOutputFile=docs/diagrams/hosted-alpha-resource-limits.pdf -f "$diagram_tmp/page-1.pdf" "$diagram_tmp/page-2.pdf" -c '[ /Title (Moon Service hosted-alpha resource limits) /DOCINFO pdfmark'
```

Run the complete pipeline twice from separate temporary directories and compare
the PDF checksums. The output must be byte-identical, contain two A4 landscape
pages, and remain within the repository's generated-output budget. Inspect both
pages at full size and ordinary documentation width. Labels must be readable,
unclipped, and consistent with the current admission behavior.
