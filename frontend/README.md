# Frontend source boundary

The current web app remains static HTML, CSS, and plain JavaScript served by
Spring Boot. This directory owns browser-delivered source; it is not a separate
installed package or framework application.

## Directories

- `src/` contains authored runtime files and mixed files such as
  `moonTexture.js`, whose texture payload is generated in place.
- `assets/` contains browser assets that are served directly.
- `generated/` contains only deterministic generated browser modules. Run the
  owning generator instead of editing these files by hand.

The backend Maven build copies the contents of all three directories into
classpath `/static`. Their filenames therefore remain root-relative public URLs
such as `/app.js`, `/favicon.svg`, and `/moonPathSilhouetteSymbols.js`.

## Tooling

Node configuration remains at the repository root, and browser tests remain in
`tests/ui/`. TypeScript `rootDirs` models `src/` and `generated/` as one runtime
module namespace because Maven flattens them when packaging.

Use these checks from the repository root:

```bash
npm run frontend:check
npm run moon-texture:check
npm run js:docs
mvn test -pl backend -am
```

`npm run silhouettes:build` reproduces
`generated/moonPathSilhouetteSymbols.js`. `npm run moon-texture:build` updates
the generated payload inside `src/moonTexture.js`.
