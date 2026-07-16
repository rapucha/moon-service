---
name: sensitive-information-review
description: Run a fresh read-only pre-publication review for secrets, credentials, unexpected PII, and sensitive content in source, Git history, unpublished PR text, PDFs, office documents, images, archives, and other attachments. Use before an agent-authored push, nontrivial PR creation, or relevant agent-authored mutation to a nontrivial PR, when explicitly asked for a pre-commit sensitive-data check, or when outgoing documents need privacy scrutiny.
---

# Sensitive Information Review

## Purpose

Reconstruct the exact material an agent is about to publish and inspect it for
credible secrets and unexpected personal information. Include intermediate Git
objects and document-internal content that a final tree diff would miss.

Operate read-only. Report findings to the primary agent; do not edit files,
rewrite history, rotate credentials, delete remote data, or change the PR.

## Preconditions

- Delegate every mandatory review to a fresh read-only subagent. The author of
  the outgoing work does not satisfy this review.
- Read the repository instructions and obtain the exact review mode and inputs
  from the primary agent. Do not infer a broader scan.
- Use only installed local tools and read-only remote queries. Do not install a
  scanner, fetch secrets from user storage, or follow unrelated links.
- Create temporary inspection material only under a private mode-`0700`
  directory with mode-`0600` files. Remove only that directory when done.
- Never execute a document, macro, script, binary, archive member, or embedded
  object. Do not follow symlinks or extract device files.

If a required input, object, attachment, or inspection capability is missing,
return `review_required`; do not reduce the promised coverage silently.

## Select One Review Mode

### Optional pre-commit

Use only when the user explicitly requests it. Inspect exactly the staged index
and a proposed commit message supplied by the primary agent. Do not include
unstaged or unrelated untracked files. This mode is advisory and does not
replace either mandatory pre-publication gate.

### Mandatory pre-push

Require:

- the complete, explicit refspec set and push options, expanding implicit,
  wildcard, tag, deletion, mirror, and multi-ref behavior;
- each exact local source ref and resolved object ID;
- the actual remote name, each destination ref, and each freshly resolved
  destination object ID; and
- the intended PR base when the destination does not yet exist.

Do not substitute `@{upstream}` for a different push destination. When the
destination exists, inspect objects reachable from each source and not from its
destination. For a new destination, use the intended PR-base merge base as the
bounded exclusion and disclose the assumption. Inspect annotated tag messages
and objects. Exclude unrelated staged, unstaged, and untracked work.

### Mandatory outgoing PR publication

Select the exact operation: PR creation, title/body update, relevant comment or
review reply, attachment upload, or a settled batch of those mutations. Require:

- the exact unpublished text or attachment bytes and the intended repository,
  PR, and operation for every outgoing item;
- for PR creation, the proposed base and head refs/object IDs and resolved merge
  base; and
- recorded full object IDs and reachability evidence for any prior `clear`
  pre-push reviews claimed to cover that base-to-head graph.

For PR creation, reconstruct and inspect every commit and blob in the proposed
merge-base-to-head range not covered by matching pre-push evidence. A verdict
label without the exact recorded range is not coverage. Inspect the proposed
title, body, relevant outgoing comments or replies, and attachment content
before publishing them.

For a mutation to an existing PR, use its live surface only as read-only context
for the destination and publication purpose. Inspect only the exact new or
changed outgoing material and attachments; do not rescan already-public PR text,
discussion, or Git history. Review attachment bytes before upload. If later
outgoing text contains a service-generated attachment URL, review that text as
its own mutation.

Do not scan unrelated repository history, unrelated comments or links, user
home files, environment variables, shell history, credential stores, or
unrelated local work.

## Reconstruct Git Publication Content

For pre-push and uncovered PR-creation Git ranges, record the complete
publication plan, refs, and full object IDs before inspection. Use Git object
reachability, not only `git diff`, so content added and removed in a later commit
is retained in the review. Run every traversal and object read with
`git --no-replace-objects`. Replacement refs, grafts, shallow boundaries, or
other history-rewriting metadata that cannot be shown to match the transferred
graph are a coverage gap and yield `review_required`.

1. Enumerate every commit in the selected range in chronological order. Inspect
   each full commit message through a redacting inspection path; do not print
   raw messages to a tool log.
2. Inventory every changed path at every commit as raw, NUL-delimited events
   with full old/new object IDs, including additions, modifications, copies,
   renames, type changes, merge-parent differences, and deletions. Inspect every
   nonzero postimage blob even when it is already reachable from the boundary.
   Inspect path names and symlink-target blobs as static text; never follow them.
3. Detect Git LFS pointer blobs and inspect both the pointer and its referenced
   payload. Resolve the payload without execution from the local LFS store or a
   read-only remote retrieval, verify its declared size and object ID, detect its
   real format, and apply the document/binary bounds below. A missing payload or
   tool, unsafe retrieval, or size/object-ID mismatch yields `review_required`.
4. Separately enumerate all objects reachable from the source/head but not the
   selected exclusion boundary as a completeness audit. Classify them with
   `git cat-file` and inspect every unique blob version not already covered.
5. Deduplicate identical content work without dropping any path, commit, or
   reused-blob context. Record counts for commits, paths, blobs, and special
   entries.
6. Treat inaccessible, missing, corrupt, or oversized required objects as a
   coverage gap and return `review_required`.

Useful inventory primitives include:

```text
git --no-replace-objects rev-list --reverse <boundary>..<source>
git --no-replace-objects rev-list --objects <source> --not <boundary>
git --no-replace-objects diff-tree --root -m --raw --no-abbrev -z -r <commit>
git --no-replace-objects cat-file --batch-check
```

Do not paste candidate values into command arguments. Do not dump raw commit or
blob content, NUL-delimited path inventory, or raw images to the terminal or an
image-view/model tool. Feed content to a local inspection process that emits
only sanitized context, redacted derivatives, locations, categories, and
opaque finding IDs.

## Inspect Repository and PR Material

Detect format from content rather than trusting an extension. Plain text and
source review must consider:

- private keys, passwords, authentication headers, signed or credential-bearing
  URLs, connection strings, webhook credentials, provider/cloud keys, and API,
  access, refresh, or session tokens;
- realistic high-entropy values or valid credential/signature structures; and
- unexpected personal email addresses, phone numbers, full names, street
  addresses, precise private locations, government/account identifiers, and
  comparable personal data.

Inspect PR text and remote comments without printing raw API payloads. When a
path, URL, or attachment name is itself sensitive, redact the sensitive segment
in the reported location.

For PDFs, office documents, OpenDocument files, images, archives, generic
binaries, and embedded content, read
[document-inspection.md](references/document-inspection.md) completely and
apply its coverage and bounds. Recursively inspect supported embedded files;
missing required tooling or incomplete coverage yields `review_required`.

## Context-Aware Triage

A test path or variable name is one signal, not an exemption. Treat a value as
likely synthetic only when multiple independent signals agree, such as:

- explicit `dummy`, `fixture`, `example`, or `not-a-real` wording;
- low-entropy repetition or an unmistakable placeholder structure;
- a reserved example domain, address range, or fictional number;
- generation at test runtime rather than a stored literal; or
- a documented provider sample that is publicly intended for examples.

A provider-shaped prefix, realistic entropy, valid signature structure, live
domain, credential-bearing URL, or value copied from local configuration stays
suspicious even under a test path.

Do not flag public commit/contributor identity, project handles, these review
callsigns, copyright attribution, public citations, or a deliberately
documented public contact by default. Flag unexpected personal data or unclear
publication purpose for review. Judge names and contact-like strings in context
rather than treating every match as private.

## Keep Inspection Output Safe

- Never reproduce a complete candidate secret or unnecessary personal value in
  a command, tool output, temporary filename, finding, comment, or response.
- Replace the candidate span before any context leaves the local inspection
  process. Reveal only the minimum surrounding context needed for triage.
- Assign sequential, per-review IDs such as `SIR-001`. Do not derive the ID from
  the candidate and do not report hashes, prefixes, suffixes, or fingerprints.
- Report a repository path and line/page/member only when safe. Otherwise use a
  sanitized container location plus an opaque member label.
- Do not persist candidates in tracked fixtures, notes, patches, or review
  artifacts. Temporary extracted/OCR text remains private and is deleted after
  the review.

## Verdicts

Return exactly one verdict value:

- `block` — a credible credential or private key, or clearly unauthorized
  sensitive personal data. The primary agent must not perform the reviewed
  publication step.
- `review_required` — intent is ambiguous, or a required surface is
  inaccessible, unreadable, encrypted, unsupported, malformed, too large for
  bounded inspection, or uninspected because tooling is missing. Do not present
  the gate as passed; obtain owner resolution or restore coverage.
- `clear` — every required in-scope surface was inspected and no credible
  finding remains. State exclusions and limitations; never certify that no
  sensitive information exists.

If a credential may already be remote, return `block`, stop further
publication, and advise the owner that rotation/revocation and history
remediation may be required. Do not perform those actions without explicit
authority.

## Output Contract

Lead with:

```text
Verdict: clear | review_required | block

Scope:
- Mode, operation, source/head, destination/base, boundary, and reviewed
  unpublished material

Coverage:
- Commit messages, path events, blob versions, PR surfaces, documents, tools,
  counts, bounds, and gaps

Findings:
- SIR-001 | sanitized location | category | confidence | concise reason

Exclusions and limitations:
- Explicit out-of-scope surfaces and policy exclusions

Primary-agent action:
- Proceed, resolve ambiguity/coverage, or stop publication and escalate
```

Use `None` when there are no findings or gaps. A `clear` result must still list
what was inspected and what policy exclusions were applied.

## Primary-Agent Follow-Up

- Triage every finding without asking the reviewer to mutate work.
- Re-run a fresh review after remediation if unpublished contents changed.
- Immediately before publication, re-resolve the applicable refspecs, refs,
  object IDs, destination, operation, and exact unpublished bytes through the
  same redacting path. Any pre-publication mismatch invalidates the verdict and
  requires a fresh review.
- Treat `review_required` as unresolved until the owner decides or full coverage
  becomes available.
- Publishing the exact reviewed inputs completes that gate and does not
  invalidate its recorded verdict. Review a later PR mutation separately using
  only its new outgoing content. Do not run a mandatory review after publication
  or merely for final handoff; report recorded pre-publication outcomes and
  verify final refs, checks, and PR state read-only.
