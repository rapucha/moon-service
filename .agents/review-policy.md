# Review Policy

This file defines the review measures, numeric limits, and review triggers for
Moon Service. `AGENTS.md` defines the workflow and mutation rules. Each review
skill defines how to run its review. A review skill that measures scope,
documentation, or output must read this file before it returns a verdict.

Do not copy these numbers into another policy or skill. Link to this file. If
another file gives a different number, use this file and correct the duplicate.

## What Each Rule Does

- A **hard gate** limits one pull request. If a plan or diff crosses a hard
  gate, stop and repeat the implementation scope review. Split the work unless
  the repository owner approves a recorded exception.
- A **review trigger** requires a focused review of the staged diff and review
  evidence. A trigger alone does not require a split or another scope review.
- An **informational measure** must be recorded when the workflow asks for it.
  It does not block a pull request or authorize more work.
- An **authority rule** can require review regardless of size.

Every maximum and trigger threshold is inclusive.

## Plan Before Editing

Record these estimates before editing:

- paths that will probably change;
- other paths that may be needed, and why;
- the expected range of ordinary files; and
- the estimated ordinary churn when that estimate is useful.

These values are forecasts. They are not a fixed path list unless the accepted
issue makes a path part of the result. A different path or file count alone
does not require new authority or another scope review when the work keeps the
accepted outcome, concerns, dependencies, output classes, and hard gates.

A range describes uncertainty. Unused room below its upper bound does not
authorize more behavior, cleanup, or files. Record the actual paths and file
count in the pull request, and explain a meaningful difference from the plan.
Stop and make a new plan if the work adds behavior, a concern, a dependency, an
output class, or a hard-gate crossing.

## Ordinary Work

The concern and ordinary-file limits below are hard gates:

| Change category | Maximum concerns or subsystems | Maximum ordinary files |
| --- | ---: | ---: |
| Bug fix | 1 | 6 |
| New feature or default | 2 supporting one accepted outcome | 10 |
| Documentation-only | 1 | 6 |

Documentation-only work does not change runtime behavior, policy, workflow,
configuration, CI, or tooling. A policy or workflow change uses the new-feature
or-default category. An authorized refactor uses that category's file limit but
may contain only one concern. Operations and dependency work use the
new-feature-or-default limits and require explicit authority for their effects.

Backend behavior, frontend UX, deployment or operations, CI or automation, and
provider or privacy policy are examples of separate concerns. Code, tests, and
documentation that directly support one accepted behavior form one concern.
Space below a maximum never authorizes another concern.

An ordinary file is any changed file that is not generated, vendored, or a lock
file. Agent- or LLM-authored files are ordinary. A file that mixes authored and
generated content is ordinary. A manifest that causes a lock-file change is
ordinary.

Record the total added plus deleted lines in ordinary files. This churn is
informational. It has no numeric limit and does not block a pull request, force
a split, or trigger another scope review.

## Code-File Size

Each changed code file has this resulting code-line hard limit:

| File type | Maximum code lines |
| --- | ---: |
| Production or runtime code, including scripts, configuration, and workflows | 400 |
| Test and prototype code | 600 |

A code line is a physical, nonblank line that contains code or configuration
after comment-only content is removed.

- Blank and comment-only lines do not count.
- A line that contains both code and a comment counts.
- Block comments and documentation-only docstrings do not count. A docstring or
  string used as runtime data counts.
- Every nonblank source line in a multiline runtime string counts.
- Shebangs, YAML configuration, and commands in workflow `run:` blocks count.
- Executed embedded code counts. An ordinary string literal counts once as its
  host-language line.
- If classification is unclear, count the line as code.
- A mixed file uses the stricter limit. A test or prototype file that also
  contains production or runtime behavior uses the production limit.
- Do not pack statements or expressions onto fewer lines to pass the gate.

A new file, or an existing file below its limit, must finish within the limit.
An existing oversized file may change only when its code-line count does not
grow. Do not remove unrelated code to offset growth.

If accepted work must grow an oversized file, stop before editing. Record its
exact starting count, expected maximum, reason, and the owner's explicit
approval. Record the actual result after staging. Stop again if it exceeds the
approved maximum.

## Authored Documentation

Classify an authored document by what it can change:

- **Control documents:** `AGENTS.md`, `SKILL.md`, pull-request templates,
  workflow rules, and API or operations rules.
- **Decision documents:** architecture, product, privacy, and similar records.
- **Explanatory documents:** guides, indexes, and navigation that carry no rule
  or decision.

For every changed authored document, record its resulting nonblank lines and
its added plus deleted nonblank lines. These size conditions trigger review:

| Condition | Trigger |
| --- | ---: |
| Added plus deleted nonblank lines in one update | 150 |
| A new or changed document crosses from below this size to at least this size | 600 resulting nonblank lines |
| A document already at or above this size grows | 600 resulting nonblank lines |

A small correction to a document already at or above the size threshold does
not trigger size review when the file does not grow and the update remains
below the changed-line threshold.

Authority rules can require earlier review:

- Every substantive control-document change requires an independent staged
  review.
- Every change that adds, removes, or changes a decision in a decision document
  requires that review.
- A spelling, formatting, or wording-only change may skip it when meaning does
  not change.

One independent review can satisfy both authority and size triggers. A focused
documentation review checks for removed constraints, contradictions, new
authority, repeated rules, mismatch with code, and unclear structure.

Code fences count toward document size. Apply the code-size gate to executable
embedded blocks as well. Measure changed comment-only lines and
documentation-only docstrings as prose and apply the documentation triggers.
Check a file that mixes prose and code under both sets of rules.

Truly generated reference documentation uses the generated-output budget and
includes regeneration evidence. Its tracked inputs remain ordinary files.

## Generated, Vendored, and Lock Files

Each output class has its own hard limits:

| Output class | Maximum files | Maximum textual churn | Maximum aggregate resulting size |
| --- | ---: | ---: | ---: |
| Generated | 8 | 20,000 lines | 512 KiB |
| Vendored, when explicitly authorized | 2 | Not capped separately | 1 MiB |
| Lock files | 1 | 2,000 lines | 256 KiB |

Vendored output is forbidden without explicit issue authority. The limits above
apply only after that decision.

A tracked deterministic command must reproduce generated output byte for byte
from tracked inputs. Plans and pull requests record the output paths,
generator and version, inputs, exact command, file count, textual churn,
resulting bytes, clean regeneration, and semantic validation. Visual snapshots
also require a pinned, reproducible environment.

Authorized vendoring records provenance, an immutable version or hash, the
license, and why repository storage is required. Lock-file evidence records the
package-manager version and reproduction command.
