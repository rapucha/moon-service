# Moon texture provenance

The compact Moon renderer uses a 64×32 grayscale luminance map embedded in
`backend/src/main/resources/static/moonTexture.js`.

- Source: [NASA SVS CGI Moon Kit, 2025 LRO color map](https://svs.gsfc.nasa.gov/4720/)
- Source file: [`lroc_color_2k.jpg`](https://svs.gsfc.nasa.gov/vis/a000000/a004700/a004720/lroc_color_2k.jpg)
- Credit requested by the source page: NASA's Scientific Visualization Studio
- Underlying data: Lunar Reconnaissance Orbiter Camera color mosaic
- Transformation: converted to grayscale and resized from 2048×1024 to 64×32
  with Lanczos resampling; the raw luminance bytes are Base64-encoded in the
  JavaScript module.
- Usage terms: [NASA Images and Media Usage Guidelines](https://www.nasa.gov/nasa-brand-center/images-and-media/)

The texture is sampled as a longitude/latitude map on the visible lunar sphere.
Phase illumination remains a separate calculation so a future observer-oriented
bright-limb angle can rotate the lighting without requiring another texture.
