# Camera-preview scene authoring

Use `scripts/build_camera_preview_scene.py` to author one fixed, registered
six-level foreground pyramid for issue #249. The command is offline tooling.
It does not define a browser contract, select runtime levels, call Image Gen,
or make a candidate acceptable without human review.

## Tool environment

Run the command with the repository's pinned Pillow version in a temporary
environment:

```bash
python3 -m venv /tmp/moon-scene-authoring-venv
/tmp/moon-scene-authoring-venv/bin/python -m pip install \
  -r scripts/requirements-moon-texture.txt
```

Pillow must report version 12.3.0. The command also records the libwebp codec
version because WebP bytes can change with either component. It never changes
`PIL.Image.MAX_IMAGE_PIXELS`. It turns `DecompressionBombWarning` into an error
and allows `DecompressionBombError` to stop the command.

Keep source masters, exact guides, candidate images, comparisons, overviews,
and rejected generations in a temporary directory. Issue #248 tracks none of
those files.

## Manifest contract

The tooling-only JSON manifest has `schemaVersion: 1`, exactly six ordered
levels, and `cumulativeWorldWidthRatio`. The declared cumulative ratio and the
ratio computed from the first and last `worldWidthMetres` must each be at least
625 and must agree.

Each level contains these fields:

- `id`: a unique identifier that starts with a letter or digit and then uses
  only letters, digits, `.`, `_`, and `-`;
- `parent`: the preceding level's `id`, or `null` for the first level;
- `width` and `height`: output pixels; width is at least 960 and height is at
  least 75 percent of width;
- `worldWidthMetres`: the declared horizontal world extent;
- `anchor`: the shared normalized `{ "x": ..., "y": ... }` contact point;
- `parentCrop`: `[left, top, right, bottom]` pixels in the accepted parent, or
  `null` for the first level; Pillow treats `right` and `bottom` as exclusive;
  and
- `accepted`: metadata copied from an accepted `build` report.

`accepted` may be absent while `prepare` and `build` create candidates. It is
required on all six levels before `check` or `diagnose` can run.

An accepted record has this shape:

```json
{
  "path": "temporary-or-final-relative-path/level-0.webp",
  "width": 960,
  "height": 720,
  "bytes": 123456,
  "sha256": "64-lowercase-hex-characters",
  "pillowVersion": "12.3.0",
  "webpCodecVersion": "1.6.0"
}
```

The first level has no parent or crop. Every later level names the immediately
preceding level as its parent. Its declared scale step is
`parent world width / child world width`; the value must be greater than one
and no greater than 3.7. The crop width must represent that same world extent.
The crop aspect ratio must match the child output without distortion. The
declared and computed cumulative ratios use relative and absolute tolerances of
`1e-6` when the command compares them.

Every level declares the same normalized anchor. The integer crop width may
differ from the world-width calculation by at most one parent pixel, and its
height may differ from the aspect-ratio calculation by at most one parent
pixel. Independently, the crop may place the mapped anchor by at most one
parent pixel on either axis from its exact floating-point position. These
tolerances handle rounding only; they do not permit a changed viewpoint.

## Authoring sequence

Start with an accepted parent image that has an alpha channel. An RGB input is
allowed only when it has one flat removable color and the command receives an
explicit `--chroma-key '#RRGGBB'` value.

Create a child guide from the manifest crop:

```bash
/tmp/moon-scene-authoring-venv/bin/python \
  scripts/build_camera_preview_scene.py prepare \
  --manifest /tmp/scene/manifest.json \
  --level level-1 \
  --parent /tmp/scene/accepted/level-0.webp \
  --output /tmp/scene/guides/level-1.png
```

The command crops the accepted parent and resamples that crop to the declared
child dimensions with LANCZOS. It then resets the child alpha at a fixed
midpoint: values below 128 become transparent, and values at or above 128
become opaque. This prevents repeated crops from magnifying an antialias band
until the guide loses fully transparent and fully opaque regions. The fixed
binary edge may look harder than the source edge. For a long recursive chain,
place the shared anchor on a locally horizontal or vertical foreground edge.
Thresholding cannot keep an offset or diagonal raster edge centred when pixel
rounding accumulates. Repeat this step from each accepted parent. Do not prepare
all children from the widest source.

The exact guide may itself become the candidate:

```bash
/tmp/moon-scene-authoring-venv/bin/python \
  scripts/build_camera_preview_scene.py build \
  --manifest /tmp/scene/manifest.json \
  --level level-1 \
  --guide /tmp/scene/guides/level-1.png \
  --output /tmp/scene/candidates/level-1.webp
```

An optional Image Gen refinement must use the guide as its coordinate frame.
Supply the already selected local result; the repository command never calls a
provider:

```bash
/tmp/moon-scene-authoring-venv/bin/python \
  scripts/build_camera_preview_scene.py build \
  --manifest /tmp/scene/manifest.json \
  --level level-1 \
  --guide /tmp/scene/guides/level-1.png \
  --refinement /tmp/scene/refinements/level-1.png \
  --comparison /tmp/scene/review/level-1-refinement.png \
  --output /tmp/scene/candidates/level-1.webp
```

The refined input must have the guide's dimensions. The command restores the
thresholded guide alpha silhouette and writes a comparison, but it cannot prove
that Image Gen kept masonry, vegetation, perspective, or other visible features
fixed. Reject a refinement when the comparison shows moved or contradictory
detail.

`build` uses lossless WebP with `quality=100`, `method=6`, and `exact=true`.
With identical local pixels, arguments, Pillow version, and libwebp version,
an unrefined build produces identical bytes. Copy the JSON report into the
level's `accepted` record and make its path relative to the manifest when the
final package needs to be portable.

## Check and visual review

Verify all accepted paths, dimensions, byte lengths, hashes, and toolchain
versions without writing files:

```bash
/tmp/moon-scene-authoring-venv/bin/python \
  scripts/build_camera_preview_scene.py check \
  --manifest /tmp/scene/manifest.json
```

`check` proves file and metadata consistency. It does not inspect content
geometry or accept an Image Gen result.

Create the temporary human-review diagnostics only after all six accepted
records are present:

```bash
/tmp/moon-scene-authoring-venv/bin/python \
  scripts/build_camera_preview_scene.py diagnose \
  --manifest /tmp/scene/manifest.json \
  --output-dir /tmp/scene/review/transitions
```

The five adjacent sheets render the accepted parent crop and accepted child
in the child's declared common world-space viewport, plus a 50 percent overlay.
Shared features must have the same position and size. The separate overview
labels all six progressively smaller declared world widths. It is not a map
of issue #245 runtime selection boundaries.

Record the final accepted files' build reports and source or Image Gen
provenance. Keep lossless masters, guides, comparisons, discarded candidates,
and other diagnostics outside the repository. The accepted asset package and
its provenance belong to issue #249, not this tooling issue.
