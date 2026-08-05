# Camera-preview proof-of-concept scene

This directory contains the six-level foreground scene accepted for issue
#249. The owner accepted the complete sequence on 2026-08-04 as a proof of
concept. It demonstrates a registered photographic zoom from a wide landscape
to a close limestone subject. It is not presented as polished production
photography.

The assets are fictional. They contain no Moon. Issue #245 remains responsible
for choosing a level and drawing the Moon as a separate canvas layer.

## Package

The six lossless alpha WebPs are the canonical authored media. Each image is
960 × 720 pixels. Together they occupy 3,174,948 bytes.

| Asset | Parent | World width (m) | Bytes | SHA-256 |
| --- | --- | ---: | ---: | --- |
| `level-0.webp` | — | 1,350 | 596,502 | `0f08e48ac32a05128c7ead88a8303168cd8c046612d690d4d6fc481f9446e55a` |
| `level-1.webp` | `level-0` | 371.25 | 523,112 | `289d1c681be4c9a8a34dfe36a33b1709989f3a5525f6199d6c51addb23708b0b` |
| `level-2.webp` | `level-1` | 102.09375 | 531,956 | `62c9e08d65b731cf3d73845f119144402139002b1552f09febe4482f6fd31966` |
| `level-3.webp` | `level-2` | 28.07578125 | 594,140 | `3a3adffbc3097d59894e7569396b64365727493d5cc215937d73b2193b0847ac` |
| `level-4.webp` | `level-3` | 7.72083984375 | 511,290 | `d74bb2f12970f3df918dfb91ce97b9681380c87ceaba6bd741c84cfb95196b6f` |
| `level-5.webp` | `level-4` | 2.12323095703125 | 417,948 | `11e901adc1f355f8480d3a66f869e98d871547774174c7e7a93d142e41eff253` |

`scene-pyramid.json` is the tooling manifest. Its accepted records bind these
paths to their byte lengths, hashes, dimensions, and encoding provenance. For
Level 0, the Pillow and libwebp fields retain the original #249 build
environment, and `postAcceptanceEdit` records the final #245 GIMP export.

## Geometry

All levels declare normalized anchor
`{ "x": 0.46875, "y": 0.5138888888888888 }`, corresponding to web pixel
`(450, 370)`. Every child uses parent crop `[326, 268, 590, 466]`. The crop is
264 × 198 pixels and keeps the anchor within one parent pixel after mapping.

Each declared world-width step is `40 / 11`, about 3.63636. The cumulative
world-width ratio is 635.8234348125749. This covers the supported 4 mm through
2,500 mm proof-of-concept range with a small margin.

The closest level keeps `[672, 0, 960, 144]` transparent. Native authoring from
Level 1 onward used point `(680, 556)`, crop `[494, 404, 890, 701]`, and reserve
`[1014, 0, 1448, 217]` at 1448 × 1086. The native crop and web delivery crop
differ slightly because of integer rounding. Both keep the registered point
within one pixel per step.

## Accepted visual limitations

The owner described the complete sequence as not great but acceptable as a
proof of concept. In particular:

- Level 2 retains marginally acceptable generated-looking stone texture.
- Level 4 and Level 5 retain a ragged zig-zag rock edge that the owner marked
  as acceptable.
- The corrected Level 4 and Level 5 remove the separate tangled formation that
  the owner did not accept.

Do not reuse these images as an unstated production-quality bar. Improving the
photographic finish requires a separately approved replacement task.

## Accepted source provenance

Generation used ChatGPT's built-in Image Gen through the owner's Enterprise
SSO access. It did not use the OpenAI API or an API key. Native source images,
guides, annotations, repair scripts, comparisons, rejected attempts, and prompt
text for rejected calls whose outputs contribute no pixels remain temporary
working evidence outside the repository. The exact prompts whose outputs
contribute pixels to the accepted chain are recorded below.

The table records the accepted Image Gen source chain. For Levels 1 through 5,
`Accepted PNG` is the exact 960 × 720 alpha image encoded into the package
WebP. The Level 0 PNG supplied the pre-trim WebP that the owner edited as
described below.

| Level | Accepted operation | Accepted PNG SHA-256 |
| --- | --- | --- |
| 0 | New wide fictional Mediterranean master; chroma removal and alpha normalization | `cbe13c646e356a25766be11fc83126a2315d4ae9ce827a69517f3cff4727067c` |
| 1 | Focused ruin and ridge repair, followed by deterministic pink-only hue correction | `8d350ec6bcf899b830e9a1f6cb0af3497eab88dcdc1cb1156dced9a9e912eea4` |
| 2 | Native-resolution detail restoration followed by the accepted texture correction and web downscale | `4af46cb131fb199058e567e4cf67748cb7a83244fc07a1bc56b2f711ca1be001` |
| 3 | Native-resolution registered detail restoration and web downscale | `08d665e65890a9fddb0f47a4da7fc9c75f1261e6fb7f7b71c371a799c226580e` |
| 4 | Native restoration, exact Moon-clearance alpha trim, and exact red-object alpha trim | `ae21afdc5d1a77e25dee180b218929a01bd0f82b4c553c41b6eb3bcb2f4de858` |
| 5 | Native registered restoration, rebuilt from corrected Level 4, followed by the corresponding exact red-object alpha trim | `9cef0203e27a91138c646212c11f7d457508d1f74fcf34534acd7c6139def8e8` |

### Level 0

The accepted wide master keeps a small ruin integrated into a rocky slope,
large open sky, sparse vegetation, and restrained warm side light. Its built-in
Image Gen source SHA-256 is
`b90b43cc902c96769274ba4327071fb47644ae8d5ead5c3312a363390d6937c0`.

During the final #245 runtime review, the owner used GIMP 2.10.36 with system
libwebp 1.3.2 to trim one unwanted small structure from the accepted Level 0
WebP and exported it as lossless WebP. This operation introduced no external
or newly generated image. A raw decoded comparison against the #249 file found
50 alpha changes within `[426, 353, 434, 362]`: alpha only decreased, 36 pixels
became fully transparent, and no pixel gained foreground coverage. It also
found 1,304 RGB-only changes under pixels that were fully transparent in both
files. Those values span `[5, 221, 959, 491]` but cannot affect composited
output. The owner accepted the revised file in the hosted #245 preview.

### Level 1

The accepted repair rebuilt only the clipped-looking ruin and the adjacent
shadow band while preserving the wide terrain. Its Image Gen source SHA-256 is
`e852b2bac38a840b0d556e0de5bc2e9160d69e2623eb8f85e9710d6bfef38416`.
Its normalized natural-alpha candidate SHA-256 is
`78227e00efd033f9173e18ac9d03e31709ec9ecf2bf1365ac9f53e59c58470b8`.
The owner accepted the red-marked generated-looking stone region on condition
that the two pink regions were corrected. A deterministic hue correction then
changed 788 marked pixels, no pixels outside the approved bounds, no pixels in
the accepted red-marked region, and no alpha bytes.

### Level 2

The accepted native texture-correction Image Gen source SHA-256 is
`e799470fb6ff914b3f14a4b6bf1be236fefe7832e8c205721198f0bdbb9357fa`.
The accepted 1448 × 1086 natural-alpha candidate SHA-256 is
`d009aaefaf2657f05d262b81375a51b69128aa8f720374c358441674cf2e0c17`.
The owner accepted its remaining texture concern only marginally.

### Level 3

The accepted Image Gen source SHA-256 is
`efc4591d8f11bb5a5f8c098d1283232c0aa9e4fecdb16998570eacbd1e9b337e`.
The accepted 1448 × 1086 natural-alpha candidate SHA-256 is
`7a27df4fbd7e3f94c85d6f2b502d4cf69235ce79d70324a3795dbf4d4fcfd0d1`.

### Level 4

The accepted restoration's Image Gen source SHA-256 is
`3affebf2fb3745ebaf210d0d8512712a36307457295ed6f74c82bf2ae76b9008`.
The initial 1448 × 1086 natural-alpha candidate SHA-256 is
`1f9bc2fbc677394c1a2ff922613bfaf34db005c070ccdaec18a43cf0573400ce`.

The owner approved an exact non-generative trim of 505 native foreground
pixels so the complete Level 5 Moon could fit. A later exact alpha-only trim
removed the owner-marked tangled formation. The final native candidate SHA-256
is `280ec45783260afca4db0f88ce1c3f0b7a34274d4a2d9222fd8d497300d02ca6`.
Both trims changed zero retained foreground RGB pixels.

### Level 5

The accepted restoration's Image Gen source SHA-256 is
`f7d32b097ec5d80d807901e7a94f0c5008cc2f36c13d6fed8538c710e2ffd60e`.
Its first 1448 × 1086 natural-alpha candidate SHA-256 is
`880f3bdab7082d3d1250954326098cd174bc02dac88b4c6ee9bdcf3e32ba61c0`.
Rebuilding from the final Level 4 alpha applied the corresponding exact trim
without changing retained RGB pixels. The final native candidate SHA-256 is
`e33ea024c19c065de7aad76e4f2047367d4eb708728d825d894bf24c952a8617`.

## Accepted-chain Image Gen prompts

These prompts are copied verbatim from the authoring record. They include every
Image Gen call whose output contributes pixels to the accepted chain. The
Level 1 initial restoration and Level 2 first native restoration were rejected
as standalone candidates, but they became edit targets for later accepted
repairs. Rejected calls that contribute no pixels are excluded. The
deterministic hue and alpha corrections described above did not use Image Gen.

### Level 0 wide master

This prompt produced Image Gen source
`b90b43cc902c96769274ba4327071fb47644ae8d5ead5c3312a363390d6937c0`,
which supplied the accepted Level 0 pixels after chroma removal and alpha
normalization.

```text
Use case: photorealistic-natural
Asset type: new finished Level 0 widest master for a six-level registered camera-preview zoom sequence.
Input image: Image 1 is a visual reference only for the dry Mediterranean terrain, irregular limestone ruin, vegetation, restrained warm light, and photographic character. Do not copy its framing or use it as an inset.
Primary request: Create a new coherent very-wide landscape photograph of the same fictional Mediterranean mountainside. Show a broad natural rocky slope with a tiny ruined limestone lookout embedded in the terrain near x=414, y=330 of a 960 by 720 composition. The ruin should be only a few pixels tall at final size but remain a distinct natural landmark that later zoom levels can reveal.
Scene/backdrop: Broad dry mountain terrain with sparse mature shrubs, scattered irregular limestone, restrained distant landforms, and large unobstructed open sky. The slope must be physically continuous and grounded across the lower and left portions of the frame.
Subject: A very small broken limestone ruin naturally integrated into the hillside at the future zoom point. Keep its silhouette simple, irregular, and non-generic. It must not become a castle, house, tower complex, or dominant object.
Style/medium: Photorealistic natural wide landscape photography with coherent optics, atmospheric depth, restrained warm late-afternoon side light, and non-repeating real-world detail.
Composition/framing: Strict 4:3 landscape. Keep the future zoom area around x=414, y=330 visually useful. Place the tiny ruin at that point with connected rocky terrain extending well beyond it on all sides. Preserve generous clear central and upper-right sky for a separately drawn Moon.
Sky constraint: every sky pixel must be one perfectly flat uniform solid #ff00ff chroma key, with no gradient, cloud, haze, texture, reflection, or foreground magenta.
Constraints: no Moon; no marker; no crop line; no road; no trail; no terraces; no village; no other building; no water; no text; no person; no vehicle; no logo; no watermark.
Avoid: regular masonry, repeated rocks or shrubs, geometric rows, terraces, tracks, floating structure, abrupt terrain ending, oversized ruin, dramatic fantasy mountains, collage, CGI, illustration, magenta spill, halo, or sky variation.
```

### Level 1 initial restoration input

This prompt produced Image Gen source
`053ec18c2ba5ade1fae8a45b1ae1937a3b6ad10b66547f6a086c4f5a2e480d74`
and intermediate candidate
`1c2084dd97fb401d33308774262dbf4be50636462557e4f5a535c5daf388daa2`.
The standalone candidate was rejected, but it became the sole edit target for
the accepted focused repair.

```text
Use case: precise-object-edit
Asset type: finished Level 1 photograph for a registered six-level camera-preview zoom sequence.
Input image: Image 1 is the sole edit target and immutable guide for the complete 4:3 framing, viewpoint, perspective, hillside silhouette, foreground coverage, tiny ruin position, major rocks, shrubs, lighting, and flat chroma-key sky.
Primary request: Turn the enlarged soft crop into a sharp, convincing closer photograph of the exact same Mediterranean mountainside. Restore natural photographic detail to the existing rocks, dry grasses, shrubs, distant ridge, and tiny broken limestone ruin without changing any broad or medium feature.
Scene/backdrop: Preserve every sky pixel as one perfectly flat uniform solid #ff00ff chroma key. Add nothing to the sky and do not use #ff00ff in the foreground.
Subject: Keep the small ruin at the same position, scale, simple broken silhouette, and connection to the hillside. Keep every major rock and shrub aligned. Clarify only plausible fine terrain and masonry detail.
Style/medium: Photorealistic natural telephoto landscape photography in restrained warm late-afternoon side light; the same landscape coming into focus, not a redesigned scene.
Composition/framing: Strict 4:3 landscape. Preserve the exact crop, scale, viewpoint, perspective, silhouette coordinates, foreground coverage, and clear upper-right sky. Do not pan, crop, shift, rotate, stretch, zoom, or change perspective.
Change only: plausible high-frequency photographic detail and local clarity inside the existing foreground.
Keep unchanged: all broad and medium terrain, the ruin, all physical connections, every sky pixel, light direction, exposure, and color balance.
Constraints: no Moon; no marker; no crop line; no new structure; no opening or arch; no new large rock; no road; no trail; no terrace; no text; no person; no vehicle; no logo; no watermark.
Avoid: enlarged or redesigned ruin, castle, regular masonry, repeated rocks or shrubs, geometric rows, terraces, tracks, grids, moved silhouette, pasted rectangle, blurred remnant, magenta spill, halo, sky variation, over-sharpening, collage, CGI, or illustration.
```

### Level 1 focused repair

This prompt produced Image Gen source
`e852b2bac38a840b0d556e0de5bc2e9160d69e2623eb8f85e9710d6bfef38416`,
which supplied the accepted Level 1 pixels before the deterministic pink-only
hue correction.

```text
Use case: precise-object-edit
Asset type: focused repair of a registered photographic Level 1 landscape asset.

Input roles:
- Image 1 is the sole edit target and the authority for exact 4:3 framing, viewpoint, scale, hillside silhouette, foreground extent, terrain, sky, and all areas outside the defect.
- Image 2 is annotation guidance only. Its red mark identifies the construction that looks clipped or partly erased; its green mark follows an unwanted dark horizontal ridge band. Do not copy Image 2's blue sky or reproduce either colored mark.

Primary request: Rebuild only the small ruined limestone construction and the narrow ridge area identified by the red and green annotations. Make the complete small ruin read as one coherent, physically connected limestone construction emerging naturally from the hillside. Remove the black smudge above and left of it and remove the dark horizontal band extending left along the ridge. Replace those defects with naturally sunlit limestone, dry grass, shrubs, and ridge detail matching the immediately surrounding scene. The ruin must end with a plausible natural broken outline, with no part appearing hidden behind a mask.

Preserve exactly: Image 1's complete outer foreground silhouette, hillside profile, ruin position and modest scale, all major rocks and shrubs, crop, viewpoint, perspective, restrained warm late-afternoon side light, exposure, color, and every area outside the focused repair. Keep the lighting physically coherent; do not create an unexplained cast-shadow band or suggest an off-scene object blocking the sun.

Backdrop: Preserve the sky as perfectly flat, uniform solid #ff00ff. Add no foreground beyond the existing silhouette. Do not use #ff00ff inside foreground.

Constraints: no Moon; no red or green marks; no annotation; no mask edge; no halo; no dark smoke or smudge; no new structure; no enlarged castle; no opening or arch; no path, road, terrace, person, vehicle, text, logo, or watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: clipped ruin; erased construction; straight or mechanical extraction edge; black fringe; horizontal shadow stripe; pasted patch; repeated masonry; procedural rock texture; changed terrain; changed sky.
```

### Level 2 first native restoration input

This prompt produced Image Gen source
`8cd4857867a63da0fe6c4c7134bdfdf3a290f51c913989d7ccff08ab43cd8b2a`
and native candidate
`407c4e948fa0c946016e9eb0491c9f89343a834df79ba4638c6dd0a65662c21d`.
The standalone candidate was rejected, but it became the sole edit target for
the accepted texture correction.

```text
Use case: precise-object-edit
Asset type: strict native-resolution photographic restoration for registered Level 2.

Input image: Image 1 is the sole edit target and immutable pixel-position authority. Treat it as an existing photograph that is slightly out of focus, not as a scene to reinterpret, rebuild, improve compositionally, or make more plausible.

Primary request: Restore focus and fine photographic clarity only. Recover restrained micro-detail in the exact existing limestone surfaces, mortar shadows, dry grass, and shrubs. Keep every stone, shrub, dark patch, unusual shape, and physical connection in exactly the same position and size, including arrangements that look odd or imperfect. Do not infer, redesign, replace, add, remove, enlarge, or simplify any object.

Immutable geometry: Preserve every foreground-to-sky silhouette coordinate, the exact compact ruin outline, every broad and medium contour, every rock boundary, every plant position, foreground coverage, crop, scale, viewpoint, perspective, and all clear-sky coordinates. The output must align with Image 1 in a 50-percent cross-fade. The right edge at native row y=556 must remain near x=683, with the registered point (680,556) still on the edge.

Color repair only: Remove residual false pink, purple, or magenta fringe from the foreground and boundary without moving the boundary. Replace only that color contamination with nearby warm limestone, muted vegetation color, or natural shadow.

Backdrop: Preserve the complete sky as one perfectly flat, uniform solid RGB(121,159,181), hex #799fb5, for later background removal. Add no gradient, cloud, texture, shadow, object, or color variation to the sky. Do not use this blue inside the foreground.

Style/medium: Natural high-resolution telephoto landscape photography in restrained warm late-afternoon side light. Preserve the existing exposure, contrast, light direction, and color balance. Fine texture must be irregular and subtle.

Change only: focus, restrained high-frequency photographic surface detail, and false pink/magenta color contamination.

Constraints: no semantic reconstruction; no redesigned ruin; no new or removed stone; no new plant; no opening or arch; no castle; no road; no trail; no terrace; no repeated texture; no procedural masonry; no pink foreground; no Moon; no marker; no text; no person; no vehicle; no logo; no watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: making the ruin taller, wider, cleaner, more regular, or more realistic; changing the hillside outline; dense holes; honeycomb; lace texture; grooves; ribs; repeated pits; straight mask edge; halo; pasted patch; double image; over-sharpening.
```

### Level 2 texture correction

This prompt produced Image Gen source
`e799470fb6ff914b3f14a4b6bf1be236fefe7832e8c205721198f0bdbb9357fa`,
which supplied the accepted Level 2 pixels after the native correction and web
downscale.

```text
Use case: precise-object-edit
Asset type: native-resolution Level 2 surface-texture correction.

Input roles:
- Image 1 is the sole edit target and immutable authority for exact 4:3 framing, every stone and plant position, ruin geometry, hillside geometry, foreground coverage, silhouette, lighting, color, and flat blue sky.
- Image 2 is annotation guidance only. Its red lasso identifies the most obvious example of the defective texture. The same defect appears more subtly across the complete foreground, so correct it everywhere. Do not reproduce the red mark.

Primary request: Remove the unnatural regular dotted and pitted pattern from every limestone surface across the complete foreground. Replace only that defective surface texture with convincing quiet Mediterranean limestone: mostly restrained natural grain, sparse randomly placed tiny pores, occasional small chips, a few irregular ordinary cracks, and subtle non-repeating weathering. No evenly spaced dots, repeated circular holes, rows, clusters, or procedural marks may remain.

Important distinction: Preserve every existing stone's complete shape, boundary, position, scale, broad shading, contact with neighboring stones, and physical role. Preserve all shrubs, dry grass, and their positions. Change only the artificial surface pattern; do not rebuild or rearrange the ruin or hillside.

Immutable geometry: Preserve every foreground-to-sky coordinate, the compact ruin outline, all broad and medium contours, rock boundaries, plant positions, crop, viewpoint, perspective, and every clear-sky coordinate. The output must align with Image 1 in a 50-percent cross-fade. At native row y=556, the rightmost edge remains near x=683 and point (680,556) remains on foreground.

Backdrop: Keep the complete sky perfectly flat and uniform RGB(121,159,181), hex #799fb5. Add no gradient, cloud, texture, object, or variation. Do not use this blue inside foreground.

Style/medium: High-resolution natural telephoto landscape photography in restrained warm late-afternoon side light. Keep natural imperfect surfaces; do not polish, melt, blur, or smooth the rocks into plastic.

Change only: the repeated pitted/dotted surface texture and the minimum immediately surrounding pixels needed for natural continuity.

Constraints: no regular dots; no repeated pits; no honeycomb; no lace; no pebble grid; no rows; no procedural texture; no new stone; no removed stone; no changed ruin; no new plant; no changed silhouette; no pink or magenta fringe; no Moon; no marker; no text; no person; no vehicle; no logo; no watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: broad redesign; different masonry; changed stone courses; changed vegetation; smooth featureless rock; dense pores; repeated cracks; veins; grooves; ribs; artificial sharpening; pasted patch; double image; halo; sky variation.
```

### Level 3 native restoration

This prompt produced Image Gen source
`efc4591d8f11bb5a5f8c098d1283232c0aa9e4fecdb16998570eacbd1e9b337e`,
which supplied the accepted Level 3 pixels after web downscale.

```text
Use case: precise-object-edit
Asset type: strict native-resolution photographic restoration for registered Level 3.

Input image: Image 1 is the sole edit target and immutable authority for the exact 4:3 framing, crop, viewpoint, perspective, foreground coverage, rock and plant placement, silhouette, broad lighting, color, and flat blue sky. Treat it as an enlarged crop of an existing photograph that needs recovered focus, not a scene to redesign.

Primary request: Restore convincing native-resolution photographic clarity to the exact existing limestone rocks, crevices, dry grass, and small shrubs. Recover believable fine detail while removing the enlarged artificial pitted and dotted pattern inherited from the source. The result must look like a real closer telephoto photograph of the same hillside.

Materials/textures: Use quiet, varied Mediterranean limestone with restrained natural grain, sparse randomly placed tiny pores, occasional small chips, a few irregular ordinary cracks, and subtle non-repeating weathering. Most stone faces must be relatively calm. Preserve each broad and medium stone shape, boundary, position, scale, shading role, and contact with neighboring stones. Preserve plant positions and rooted connections.

Immutable geometry: Preserve every foreground-to-sky coordinate, the complete sloping outer edge, all broad and medium contours, every clear-sky coordinate, and the amount of foreground. The output must align with Image 1 in a 50-percent cross-fade. At native row y=556, the rightmost foreground edge remains near x=693 and point (680,556) remains on foreground. Keep the upper-right sky completely clear.

Backdrop: Preserve the entire sky as one perfectly flat uniform solid RGB(121,159,181), hex #799fb5, for later background removal. Add no gradient, cloud, texture, object, or color variation. Do not use this blue inside the foreground.

Style/medium: High-resolution photorealistic natural close-telephoto landscape photography in restrained warm late-afternoon side light. Preserve the existing light direction, exposure, broad contrast, and color balance. Keep natural imperfections without artificial sharpening.

Change only: restore plausible high-frequency photographic detail and replace the repeated pitted/dotted surface artifacts.

Constraints: no semantic redesign; no added or removed stone; no changed rock boundary; no new plant; no changed silhouette; no regular dots; no repeated pits; no honeycomb; no lace; no pebble grid; no rows; no procedural pattern; no grooves; no ribs; no pink or magenta fringe; no Moon; no marker; no text; no person; no vehicle; no logo; no watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: broad reconstruction; different masonry; invented structure; dense holes; repeated cracks; vein networks; melted or plastic stone; smooth featureless rock; artificial sharpening; pasted patch; double image; halo; sky variation; CGI; illustration.
```

### Level 4 native restoration

This prompt produced Image Gen source
`3affebf2fb3745ebaf210d0d8512712a36307457295ed6f74c82bf2ae76b9008`,
which supplied the retained Level 4 RGB pixels before the deterministic
Moon-clearance and red-object alpha trims.

```text
Use case: precise-object-edit
Asset type: strict native-resolution photographic restoration for registered Level 4.

Input image: Image 1 is the sole edit target and immutable authority for the exact 4:3 framing, crop, viewpoint, perspective, foreground coverage, every large stone boundary, the existing dry plant, silhouette, broad lighting, color, and flat blue sky. Treat it only as an enlarged crop of an existing photograph that needs recovered optical focus.

Primary request: Restore this exact close view as a convincing high-resolution telephoto photograph of the same limestone boulders and existing dry plant. Recover crisp natural edges and subtle real photographic detail without redesigning any stone, crack, shadow, plant stem, or physical connection.

Stone surfaces: Replace the enlarged mottled, dotted, pitted appearance with broad quiet limestone planes and restrained continuous mineral micro-roughness. The rock faces should be mostly calm and solid, with slight irregular tonal variation and only incidental natural wear. Do not add discrete holes, pores, dimples, repeated flecks, spot clusters, grooves, or decorative marks. Preserve every broad shadow and the existing large angular facets.

Plant: Preserve the existing dry plant exactly where it is, rooted behind the rock and projecting naturally toward the right. Clarify only its existing stems and tips. Do not add, remove, multiply, or move stems.

Immutable geometry: Preserve every foreground-to-sky coordinate, the complete stone-and-plant outer edge, every large rock boundary, every clear-sky coordinate, and the amount of foreground. The output must align with Image 1 in a 50-percent cross-fade. At native row y=556, the rightmost foreground edge remains near x=711 and point (680,556) remains on foreground. Keep the upper-right sky completely clear.

Backdrop: Preserve the entire sky as one perfectly flat uniform solid RGB(121,159,181), hex #799fb5, for later background removal. Add no gradient, cloud, texture, object, or variation. Do not use this blue inside foreground.

Style/medium: High-resolution photorealistic close telephoto landscape photography in restrained warm late-afternoon side light. Preserve the existing light direction, exposure, broad contrast, and color balance. Natural optical clarity, not artificial sharpening.

Change only: recover plausible fine photographic clarity and replace the enlarged artificial surface markings with quiet continuous limestone texture.

Constraints: no semantic reconstruction; no new or removed stone; no changed stone boundary; no new or removed plant stem; no changed silhouette; no dots; no pits; no holes; no honeycomb; no lace; no pebble grid; no rows; no procedural texture; no grooves; no ribs; no vein network; no repeated cracks; no pink or magenta fringe; no Moon; no marker; no text; no person; no vehicle; no logo; no watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: broad redesign; different masonry; invented structure; dense surface detail; patterned microtexture; melted or plastic stone; featureless digital smoothing; artificial sharpening; pasted patch; double image; halo; sky variation; CGI; illustration.
```

### Level 5 native restoration

This prompt produced Image Gen source
`f7d32b097ec5d80d807901e7a94f0c5008cc2f36c13d6fed8538c710e2ffd60e`,
which supplied the retained Level 5 RGB pixels before rebuilding from corrected
Level 4 and applying the deterministic red-object alpha trim.

```text
Use case: precise-object-edit
Asset type: strict native-resolution photographic restoration for registered Level 5, the closest view in a six-level zoom sequence.

Input image: Image 1 is the sole edit target and immutable authority for the exact 4:3 framing, crop, viewpoint, perspective, foreground coverage, large stone contour, existing plant fragments, silhouette, lighting, color, and flat blue sky. Treat it as an enlarged crop of an existing photograph that needs recovered optical focus, not a scene to redesign.

Primary request: Restore this exact closest view as a convincing high-resolution telephoto photograph of the same large limestone edge and the same existing dry plant rooted behind it. Recover crisp natural photographic detail while replacing the enlarged looping, dotted, and mottled source artifacts with quiet real limestone.

Stone surface: Preserve the complete large stone shape, broad light and shadow, and all major contours. Give it broad calm limestone planes, restrained continuous mineral micro-roughness, slight irregular tonal variation, and only incidental natural wear. Do not add discrete holes, repeated pores, circular dimples, loops, curls, lace, grooves, veins, ribs, grids, repeated flecks, or procedural marks.

Plant: Preserve the existing plant pieces in their exact positions and physical connections behind the stone. Clarify only their existing natural stems and tips. Do not add, remove, multiply, or move stems, and do not extend any plant into the protected Moon-clearance sky.

Immutable geometry: Preserve every foreground-to-sky coordinate, the complete rock-and-plant outer edge, every clear-sky coordinate, and the amount of foreground. The output must align with Image 1 in a 50-percent cross-fade. At native row y=556, the rightmost foreground edge remains near x=719 and point (680,556) remains on solid foreground.

Moon clearance: Keep the complete blue-sky disk bounded approximately by x=692 to x=1435 and y=12 to y=755 entirely free of rock, plant, halo, or any other foreground. This is clearance for a separately drawn Moon; do not draw the Moon or a circle. Keep the upper-right reserve fully clear.

Backdrop: Preserve the entire sky as one perfectly flat uniform solid RGB(121,159,181), hex #799fb5, for later background removal. Add no gradient, cloud, texture, object, halo, or color variation. Do not use this blue inside foreground.

Style/medium: High-resolution photorealistic natural close-telephoto landscape photography in restrained warm late-afternoon side light. Preserve the existing light direction, exposure, broad contrast, and color balance. Natural optical clarity, not artificial sharpening.

Change only: restore plausible fine photographic clarity and replace enlarged artificial surface markings with quiet continuous limestone texture.

Constraints: no semantic reconstruction; no new or removed stone; no changed broad stone boundary; no new or removed plant stem; no changed silhouette; no foreground inside the Moon-clearance disk; no dots; no pits; no holes; no honeycomb; no lace; no loops; no curls; no grooves; no ribs; no vein network; no repeated cracks; no pink or magenta fringe; no Moon; no marker; no circle; no text; no person; no vehicle; no logo; no watermark. Do not crop, pan, shift, rotate, stretch, or zoom.

Avoid: broad redesign; invented structure; dense surface detail; patterned microtexture; melted or plastic stone; featureless digital smoothing; artificial sharpening; pasted patch; double image; halo; sky variation; CGI; illustration.
```

## Encoding

The six #249 PNGs were encoded separately with this command shape:

```bash
/tmp/moon-scene-authoring-venv/bin/python -B \
  scripts/build_camera_preview_scene.py build \
  --manifest assets/camera-preview/scene-pyramid.json \
  --level level-N \
  --guide /tmp/accepted-level-N.png \
  --output assets/camera-preview/level-N.webp
```

The encoder used Pillow 12.3.0, libwebp 1.6.0, lossless WebP, quality 100,
method 6, and exact transparent RGB preservation. The package images are
authored media, not generated build output.

Level 0 received the later owner-authored GIMP trim described above. Its final
lossless export replaces only that package file; Levels 1 through 5 retain the
original encoding path.

## Validation

Run the focused tooling tests:

```bash
/tmp/moon-scene-authoring-venv/bin/python -B -m unittest \
  scripts/test_build_camera_preview_scene.py
```

Verify the accepted paths and metadata without writing files:

```bash
/tmp/moon-scene-authoring-venv/bin/python -B \
  scripts/build_camera_preview_scene.py check \
  --manifest assets/camera-preview/scene-pyramid.json
```

Create temporary transition diagnostics:

```bash
/tmp/moon-scene-authoring-venv/bin/python -B \
  scripts/build_camera_preview_scene.py diagnose \
  --manifest assets/camera-preview/scene-pyramid.json \
  --output-dir /tmp/moon-service-issue-249-final-diagnostics
```

The accepted PNG sequence also passed these temporary content checks before
packaging:

- all six dimensions are 960 × 720;
- all six retain the registered point and clear upper-right reserve;
- no accepted foreground contains opaque chroma-like pixels;
- adjacent alpha intersection-over-union values are 0.991894, 0.996748,
  0.989299, 0.979084, and 0.997087;
- the maximum measured adjacent zoom step is 3.656566;
- the complete native Level 5 Moon diameter is 742.739 pixels, is inside the
  frame, and has zero foreground overlap; and
- the owner reviewed the complete interactive sequence and accepted it as a
  proof of concept.
