# Moon texture source and provenance

The compact Moon renderer uses a 128×64 grayscale luminance map embedded in
`frontend/src/moonTexture.js`. The exact 2048×1024 source
JPEG is tracked here so the runtime derivative can be rebuilt and audited
without depending on a mutable remote URL.

## Source

- Item: [NASA SVS CGI Moon Kit, 2025 LRO color map](https://svs.gsfc.nasa.gov/4720/)
- File: [`lroc_color_2k.jpg`](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_2k.jpg)
- Retrieved: 2026-07-09
- Dimensions and size: 2048×1024, 457,942 bytes
- SHA-256: `f7130a1822681fa7512d7dcfd40db8c10b9ba4f06777910348698260ed7a2170`
- Underlying data: Lunar Reconnaissance Orbiter Camera color mosaic

This 2K JPEG is the retained source for Moon Service. NASA also publishes EXR
and TIFF products ranging from hundreds of megabytes to multiple gigabytes;
those are not needed for this renderer and are not stored in the repository.
Because this directory is outside `backend/src/main/resources`, the source JPEG
is not served to browsers or included in the backend runtime artifact.

## Generated runtime texture

`scripts/build_moon_texture.py` performs the documented transformation:

1. Verify the tracked JPEG SHA-256 and its 2048×1024 dimensions.
2. Convert RGB to Pillow `L` grayscale.
3. Resize to 128×64 with Pillow Lanczos resampling. This provides approximately
   64 horizontal texture samples across the visible lunar hemisphere when the
   Moon is rendered on a 64-pixel canvas.
4. Verify the 8,192 luminance bytes have SHA-256
   `ec492a4a37698ee395dfd0598a03c69497986504a0c55fc4319b48b77a8372cd`.
5. Base64-encode those bytes into the generated block in `moonTexture.js`.

The recorded output was verified with Pillow 12.3.0. Install the asset-only
tooling in a disposable virtual environment when regeneration is needed:

```bash
python3 -m venv /tmp/moon-texture-venv
/tmp/moon-texture-venv/bin/pip install -r scripts/requirements-moon-texture.txt
/tmp/moon-texture-venv/bin/python -B scripts/build_moon_texture.py --check
/tmp/moon-texture-venv/bin/python -B scripts/build_moon_texture.py
```

The repository's configured Python environment can also run:

```bash
npm run moon-texture:check
npm run moon-texture:build
```

Normal builds do not download the source or install Pillow. The source image
and generated module stay committed so runtime builds remain offline.

## Usage and credit

The [NASA SVS usage FAQ](https://svs.gsfc.nasa.gov/help/) identifies SVS
content as public domain unless otherwise noted; the CGI Moon Kit page states
no exception. The source page asks users to credit:

> NASA's Scientific Visualization Studio

Moon Service provides that credit and source link on its public About page.
NASA's general [Images and Media Usage Guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/)
still apply: do not imply NASA approval, sponsorship, partnership, or
endorsement, and do not use NASA insignia or logotypes as product branding.

The texture is sampled as a longitude/latitude map on the visible lunar sphere.
Phase illumination remains a separate calculation so lighting and surface
orientation can be handled independently.
