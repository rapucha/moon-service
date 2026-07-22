# Container Image Publication

## Purpose

Moon Service publishes a tested multi-architecture backend image to the GitHub
Container Registry (GHCR). Image publication packages a successful `main`
revision. Digest-pinned deployment and rollback on the Raspberry Pi are
implemented under [#95](https://github.com/rapucha/moon-service/issues/95).
Reliable timer rearming and exact physical-deployment acknowledgement are
follow-up [#107](https://github.com/rapucha/moon-service/issues/107). The host
contract is documented in the
[Raspberry Pi runbook](../deployment/raspberry-pi/README.md).

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
- `Deployment tests`: pinned Ansible role syntax, shell/Compose validation,
  plus fake-registry/Docker/readiness transitions for digest advancement,
  serialization, retention, and rollback.

Hosted CI runs every Playwright test with `--ignore-snapshots`. The current
pixel baselines depend on the workstation browser, fonts, and renderer, so
enforcing them on GitHub's Ubuntu image would produce environment-only failures.
`npm run frontend:check` remains the full local command for pixel comparisons;
`npm run frontend:ci` keeps all non-pixel assertions in the publication gate.

These jobs do not call live Open-Meteo endpoints. On `main`, image publication
depends on all three jobs. Before pushing, the workflow builds and starts both
the AMD64 and ARM64 images, waits for `/readyz`, and verifies the embedded
source revision, OCI labels, and non-root runtime identity. ARM64 runs through
QEMU on the GitHub-hosted AMD64 runner. After publication and serialized `main`
promotion, a separate main-only job waits for exact confirmation from the
physical Pi.

The workflow uses only GitHub-hosted runners. Test jobs receive read-only
repository access. Only image publication and tag promotion receive
`packages: write`; promotion also receives `deployments: write` so it can record
the exact result, while the separate confirmation waiter receives Deployments
read access only. Each uses the repository-scoped `GITHUB_TOKEN`. All external
Actions are pinned to full commit SHAs.

## Pull-Request Preview Publication

`.github/workflows/publish-pr-preview.yml` publishes one approved pull-request
preview without changing the production publication flow. The repository owner
starts it from `main` and supplies only a pull-request number. The workflow
continues only when both the original actor and the triggering actor are
`rapucha` and the workflow itself comes from `main`. It does not run
automatically for pull requests.

Trusted default-branch code first requires an open, same-repository pull
request whose base is `main`. It resolves the exact full head revision and
requires the newest matching attempt of the existing pull-request test workflow
to be complete. Exactly one `Backend tests`, `Frontend tests`, and `Deployment
tests` job must have succeeded. A newer pending or failed attempt cannot be
hidden by an older success.

The workflow keeps pull-request execution separate from registry authority:

- The build job has read-only repository access. It checks out only the
  validated head, keeps no checkout credential, receives no production or host
  secret, and uses no shared writable Actions build cache.
- That job builds only `linux/arm64` and runs the trusted default-branch copy of
  `scripts/smoke_container_image.sh` against the result.
- When a build is needed, only the OCI image archive and a machine-readable
  expected identity cross the one-day Actions artifact boundary. The artifact
  name identifies its workflow run and attempt.
- A separate read-only job revalidates the open pull request and exact head
  after the build. The package-write job cannot start unless this check passes.
- After the package-write job acquires the shared queue, trusted code validates
  the pull request, head, and required CI again before it accesses GHCR.
- The package-write job trusts the validation outputs, not identity claims in
  the archive. It strictly inspects the archive and does not run the image,
  pull-request source, or another pull-request-controlled command.

Preview publication uses only the existing public package and these reserved
tags:

```text
ghcr.io/rapucha/moon-service:preview-pr-<number>-<full-sha>
ghcr.io/rapucha/moon-service:preview
```

The immutable tag points to a one-platform OCI index for `linux/arm64`. Its
source and revision identify the validated pull-request head. Trusted index
annotations also record the workflow run and attempt that first published it.
The moving `preview` tag points to the same verified index digest.

If the immutable tag already exists, the workflow validates its platform,
source, revision, child digest, and first-producer metadata. An exact match is
reused without rebuilding or overwriting it. A mismatch fails closed. Reuse
keeps the original producer run and attempt; a later rerun does not make an old
immutable image a newer channel candidate.

Channel updates use GitHub's maximum concurrency queue. One package-write job
runs at a time, and up to 100 jobs can wait without replacing another pending
job. GitHub may cancel arrivals beyond that service limit. Because build
completion order can differ from dispatch order, the workflow compares the
producer workflow run number and attempt before it moves `preview`. It refuses
to replace a newer channel, including when a later rerun tries to reuse an
older immutable image.

Only the publication job receives the repository-scoped, short-lived
`GITHUB_TOKEN` with `packages: write`. GitHub cannot limit this token to a tag
prefix, so trusted workflow code constructs and enforces both preview tags. The
workflow must not change `main`, a production full-revision tag, or another
package tag.

The actor, triggering-actor, dispatch-ref, and workflow-ref checks protect
normal use of the canonical workflow. They do not enforce an owner-only
boundary against repository writers. A writer can dispatch a modified branch
copy that removes those checks and requests `packages: write`, and GHCR cannot
restrict that token to preview tags. For the current project, every human or
automation principal with repository write access is trusted as an Actions and
GHCR administrator. Revisit this design before granting write access to a
less-trusted principal or giving preview images access to sensitive data or
credentials. [Issue #203](https://github.com/rapucha/moon-service/issues/203)
owns the decision about stronger enforcement.

The successful job summary records the pull-request number, full revision,
immutable reference, and digest for manual deployment. This workflow does not
create a GitHub Deployment or contact the Raspberry Pi.

The one-day Actions artifact expires automatically. This workflow does not
delete GHCR package versions. Dispatch should remain deliberate and low-volume
until [#201](https://github.com/rapucha/moon-service/issues/201) adds routine
preview replacement, expiry, and package retention.

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

## Physical Deployment Confirmation

A green image build or `main` promotion proves that a deployable artifact
exists; it does not by itself prove that the Pi is running it. On a successful
push to `main`, the workflow therefore:

1. uses the promotion result's exact revision and immutable multi-platform
   index digest, including the already-newer result selected when an older
   workflow rerun must not move `main` backward;
2. creates a GitHub Deployment with environment `raspberry-pi`, task
   `deploy:raspberry-pi`, and the exact revision as its `ref`;
3. records payload schema version `1`, image repository, digest, revision,
   expected deployment-reporter bot login, workflow run ID, workflow run
   attempt, and workflow run URL, then posts an initial `queued` status;
4. waits up to 15 minutes for a status posted by the Pi's repository-only
   deployment-reporter GitHub App;
5. succeeds only when the matching Deployment reports that the same revision
   and digest are healthy on the physical host.

The Pi may report success only after all of these identities agree:

- the expected GitHub Deployment revision and digest;
- `current.env` on the host;
- the running container's immutable image digest and revision labels;
- Docker health and the revision returned by `/readyz`.

The reporter uses outbound HTTPS and short-lived GitHub App installation
tokens. Its root-only App private key stays on the host, outside both Git and
the application container. The confirmation path does not require an inbound
LAN connection, router port forwarding, Tailscale enrollment, or a public
Funnel URL.

The waiter accepts `queued`, `pending`, and `in_progress` as nonterminal and
succeeds only on `success`. It fails on `failure`, `error`, or `inactive`;
`inactive` means a newer, differing image identity superseded this Deployment
before it received exact confirmation. Requests for the same revision and
digest remain eligible for the same Pi success rather than superseding one
another. A missing callback or a success status whose Deployment payload is
invalid or mismatched also fails the bounded confirmation job. An older
workflow must never downgrade a healthy newer deployment merely to satisfy its
own waiter.

Only `success` created by repository Actions variable
`MOON_PI_DEPLOYMENT_REPORTER_LOGIN` is authoritative. The value is the
repo-installed App's `<app-slug>[bot]` login and is copied into the Deployment
payload for confirm-only reruns. A success from any other actor fails. Because
statuses are append-only, an authoritative success that races just before a
newer promotion appends `inactive` still proves the exact revision ran and
takes precedence over that later supersession marker.

The 15-minute timeout fails the confirmation job without posting a terminal
`error` status. This avoids racing a Pi success that arrives at the deadline and
allows a late exact success to remain visible; a confirm-only rerun can validate
the stored creation attempt and accept that status without creating a new image
deployment.

## Independent Host-Configuration Status

Application success remains scoped to the exact image. A separate non-production
`raspberry-pi-host-config` / `provision:raspberry-pi` Deployment shows whether the
tracked Ansible role was applied.

The first producer-enabled workflow queues the current fingerprint. Later, a
parallel job validates the push and pre-promotion GHCR revisions as ancestors of
the exact promotion, selects the older baseline, and queues only for role or
fingerprint-helper changes. Jobs may overlap so GitHub cannot replace a pending
signal with a later application-only one.

The producer fingerprints the tracked role and posts a queued request. It does
not wait for provisioning or block image promotion or confirmation.

After final assertions, the playbook records the applied fingerprint and uses
the outbound reporter App to complete an exact request. No private inventory,
address, key, token, or rendered value enters the fingerprint or payload. This
is point-in-time evidence, not continuous monitoring.

Historical requests are not reconciled: overlaps may create duplicates, and the
playbook completes the newest exact match. Application-only ranges after activation create none.

## One-Time Repository Setup

Before enabling the main-only confirmation gate, install the deployment-reporter
GitHub App on this repository and configure all three App values on the Pi as
documented in the runbook. If reporting is deliberately left disabled, the Pi
continues to deploy GHCR `main` locally but every new confirmation waiter times
out; that is an expected failed external gate, not a successful deployment
signal.

After this workflow has completed successfully at least once:

1. In the repository rules for `main`, require the stable `Backend tests`,
   `Frontend tests`, and `Deployment tests` status checks before merge. Image
   publication still refuses to run after a failed test even before this rule is
   enabled, but the rule also prevents knowingly broken pull requests from
   merging.
2. Open the `moon-service` package settings and confirm that the package is
   connected to `rapucha/moon-service` with Actions access inherited from the
   repository. The OCI source label should establish this connection on the
   first publication.
3. Choose the pull boundary for the alpha host:
   - Recommended for the public-source tester alpha: change the package from
     private to public, then verify an anonymous digest-pinned pull. GitHub does
     not allow a public package to be changed back to private.
   - If the package remains private, the Pi needs a separate read-only GHCR
     credential outside Git with documented rotation/recovery.

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
After readiness succeeds, it must confirm the exact recorded identity to the
matching GitHub Deployment; merely reporting that some application process is
up is insufficient. GHCR `main` remains the desired-state source, and local
deployment continues when the GitHub Deployments API is unavailable; the
external confirmation then remains queued and eventually times out.

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
- A timed-out or failed physical-deployment confirmation does not erase the
  tested image or silently move `main` backward. Inspect the Pi timer, deployer
  result, GitHub Deployment statuses, and exact running revision/digest; rerun
  confirmation only after repairing the host or callback path.
- If a newer successful publication supersedes a pending confirmation, keep the
  newer healthy revision. The older Deployment becomes `inactive` and its exact
  confirmation job fails; never roll the host back solely to make an old
  workflow green.
- For explicit operator rollback or recovery, retag a previously verified
  digest; do not rebuild an old source revision under a new identity:

```bash
docker buildx imagetools create \
  --tag ghcr.io/rapucha/moon-service:main \
  ghcr.io/rapucha/moon-service@sha256:<known-good-digest>
```

Manual retagging is an exceptional operator action. Record the selected Git
revision and digest so the Pi can retain the correct rollback image and report
the selected identity accurately.
