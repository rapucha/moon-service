# Container Image Publication

## Purpose

Moon Service publishes a tested multi-architecture backend image to the GitHub
Container Registry (GHCR). Image publication packages a successful `main`
revision; deployment and rollback on the Raspberry Pi remain owned by
[#95](https://github.com/rapucha/moon-service/issues/95).

The image repository is:

```text
ghcr.io/rapucha/moon-service
```

## Workflow Contract

`.github/workflows/test-and-publish-container.yml` runs for pull requests to
`main` and pushes to `main`.

Pull requests and `main` pushes run these deterministic checks in parallel:

- `Backend tests`: Java 25 and `mvn test -pl backend -am`.
- `Frontend tests`: Node.js, static checks, and fixture-backed Playwright tests.

Hosted CI runs every Playwright test with `--ignore-snapshots`. The current
pixel baselines depend on the workstation browser, fonts, and renderer, so
enforcing them on GitHub's Ubuntu image would produce environment-only failures.
`npm run frontend:check` remains the full local command for pixel comparisons;
`npm run frontend:ci` keeps all non-pixel assertions in the publication gate.

These jobs do not call live Open-Meteo endpoints. On `main`, image publication
depends on both jobs. Before pushing, the workflow builds and starts both the
AMD64 and ARM64 images, waits for `/readyz`, and verifies the embedded source
revision, OCI labels, and non-root runtime identity. ARM64 runs through QEMU on
the GitHub-hosted AMD64 runner; the physical Pi validation remains part of #95.

The workflow uses only GitHub-hosted runners. Test jobs receive read-only
repository access. Only image publication and tag promotion receive
`packages: write`, using the repository-scoped `GITHUB_TOKEN`. All external
Actions are pinned to full commit SHAs.

## Image Identity

Every successful `main` revision publishes one manifest with these platforms:

- `linux/amd64`
- `linux/arm64`

The full Git commit tag identifies the build that produced the image:

```text
ghcr.io/rapucha/moon-service:<40-character-git-sha>
```

GHCR tags are registry pointers and are not inherently immutable. The workflow
therefore fails closed when the full-SHA tag already exists: it validates and
reuses the existing manifest instead of overwriting it. The manifest digest is
the immutable deployment identity:

```text
ghcr.io/rapucha/moon-service@sha256:<digest>
```

The moving `main` tag is updated only after both test jobs, both platform smoke
checks, and manifest validation pass. Promotions are serialized. A candidate
can replace `main` only when the currently tagged revision is its Git ancestor;
an older rerun cannot move `main` backward after a newer successful revision.

Both platform configs include:

- `org.opencontainers.image.source=https://github.com/rapucha/moon-service`
- `org.opencontainers.image.revision=<git-sha>`
- `MOON_BUILD_REVISION=<git-sha>`

The multi-platform index repeats source and revision as OCI annotations so tag
promotion can compare revisions without starting the application.

## One-Time Repository Setup

After this workflow has completed successfully at least once:

1. In the repository rules for `main`, require the stable `Backend tests` and
   `Frontend tests` status checks before merge. Image publication still refuses
   to run after a failed test even before this rule is enabled, but the rule also
   prevents knowingly broken pull requests from merging.
2. Open the `moon-service` package settings and confirm that the package is
   connected to `rapucha/moon-service` with Actions access inherited from the
   repository. The OCI source label should establish this connection on the
   first publication.
3. Choose the pull boundary for the alpha host:
   - Recommended for the public-source tester alpha: change the package from
     private to public, then verify an anonymous digest-pinned pull. GitHub does
     not allow a public package to be changed back to private.
   - If the package remains private, #95 must provision a separate read-only
     GHCR credential outside Git and document rotation/recovery.

Do not add a long-lived package token to GitHub Actions. Publication uses only
the short-lived `GITHUB_TOKEN` supplied to the publishing jobs.

## Operator Verification

Set the revision and inspect its manifest:

```bash
IMAGE=ghcr.io/rapucha/moon-service
REVISION=<40-character-git-sha>

docker buildx imagetools inspect "$IMAGE:$REVISION"
docker buildx imagetools inspect "$IMAGE:$REVISION" \
  --format '{{json .Manifest}}' | jq
```

Record the digest printed by the manifest inspection. On each matching host,
pull the same deployment digest; Docker selects the host's platform from the
index without rebuilding:

```bash
DIGEST=sha256:<digest>

docker pull "$IMAGE@$DIGEST"
```

Run that command on both the AMD64 host and the ARM64 Pi. Do not force both
platforms sequentially under the same index-digest reference in Docker's
classic image store: it can hold only one selected variant for that reference.
The hosted workflow instead derives each platform's child-manifest digest from
the verified index and pulls the two distinct child references. Those child
digests are verification details; deployment and rollback continue to use the
multi-architecture index digest recorded above.

On a matching host, verify the runtime contract without making a geocoding or
weather request:

```bash
MOON_SERVICE_CONTAINER_PLATFORM=linux/amd64 \
  scripts/smoke_container_image.sh "$IMAGE@$DIGEST" "$REVISION"
```

The Pi deployment must resolve `main` to a digest and record that digest before
restarting the service. It must not treat the moving tag as deployment identity.

## Failure And Recovery

- A failed test, platform smoke, build, or manifest check leaves `main`
  unchanged. Rerun the failed workflow for the same `main` revision after the
  cause is fixed or a transient registry/runner failure has cleared.
- If a full-SHA tag exists, reruns validate it rather than overwrite it. If the
  existing tag is demonstrably incomplete or corrupt, remove that package
  version deliberately in GHCR, then rerun the workflow. Check other tag and
  digest references before deleting a package version.
- A failed `main` promotion can be retried safely. The workflow reuses the
  validated full-SHA image and repeats the ancestry check.
- For explicit operator rollback or recovery, retag a previously verified
  digest; do not rebuild an old source revision under a new identity:

```bash
docker buildx imagetools create \
  --tag ghcr.io/rapucha/moon-service:main \
  ghcr.io/rapucha/moon-service@sha256:<known-good-digest>
```

Manual retagging is an exceptional operator action. Record the selected Git
revision and digest so #95 can retain the correct rollback image.
