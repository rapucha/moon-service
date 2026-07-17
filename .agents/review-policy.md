# Review Policy

This file is the single source for review measures, numeric gates, and review
triggers. `AGENTS.md` owns the workflow and mutation rules. Review skills own
their review methods. Skills that assess scope size, documentation size, or
output budgets must read this file before returning a verdict.

Do not copy the numbers into another policy or skill. Link here instead. If a
number elsewhere conflicts with this file, use this file and fix the duplicate.

## Effects

- A **hard gate** limits one pull request. Stop and rerun the implementation
  scope review when the plan or actual diff crosses it. Split the work unless
  the repository owner approves the recorded exception.
- A **review trigger** requires focused staged-diff review and evidence. It does
  not by itself force a split or another scope review.
- An **informational measure** must be recorded when the workflow asks for it.
  It does not block a pull request or grant scope.
- An **authority rule** can require review regardless of size.

All maxima and trigger thresholds are inclusive.

## Planning Estimates

Before editing, record:

- paths expected to change;
- other paths that may be needed and why;
- an expected range for the ordinary-file count; and
- an estimated ordinary-churn range when useful.

These are estimates, not a fixed path list, unless accepted issue text makes a
path part of the outcome. A path or count difference alone
does not require new scope authority or another scope review when the actual
work still has the accepted outcome, concerns, dependencies, output classes,
and hard gates.

A range describes uncertainty. The gap between the actual count and the top of
the range is not a budget for more behavior, cleanup, or unrelated files.
Record the actual paths and count in the pull request. Explain any meaningful
difference. Stop and replan for a new behavior, concern, dependency, output
class, or hard-gate crossing.

## Ordinary Work

These concern and file maxima are hard gates:

| Change category | Maximum concerns or subsystems | Maximum ordinary files |
| --- | ---: | ---: |
| Bug fix | 1 | 6 |
| New feature or default | 2 supporting one accepted outcome | 10 |
| Documentation-only | 1 | 6 |

Documentation-only work changes no runtime, policy, workflow, configuration,
CI, or tooling behavior. A policy or workflow change uses the new-feature or
default category. An authorized refactor uses that category's file maximum but
may contain only one concern. Operations and dependency work use the
new-feature or default maxima plus explicit authority for their consequences.

Examples of separate concerns include backend behavior, frontend UX,
deployment or operations, CI or automation, and provider or privacy policy.
Code, tests, and documentation that directly support one accepted behavior are
one concern. Room under a maximum never authorizes another concern.

An ordinary file is every changed file that is not generated, vendored, or a
lock file. Agent- or LLM-authored files are ordinary. Mixed authored and
generated files are ordinary. A manifest that causes a lock-file change is
ordinary.

Record total added plus deleted ordinary lines as an informational measure.
Ordinary churn has no numeric hard gate. It does not block a pull request,
force a split, or trigger another scope review.

## Code-File Size

The resulting code-line maximum is a hard gate for each changed code file:

| File type | Maximum code lines |
| --- | ---: |
| Production or runtime code, including scripts, configuration, and workflows | 400 |
| Test and prototype code | 600 |

A code line is a physical nonblank line that contains code or configuration
after comment-only content is ignored.

- Blank and comment-only lines do not count.
- A line that mixes code and a comment counts as code.
- Block comments and documentation-only docstrings do not count. A docstring or
  string used as runtime data counts.
- Each nonblank source line in a multiline runtime string counts.
- Shebangs, YAML configuration, and commands in workflow `run:` blocks count.
- Executed embedded code counts. An ordinary string literal counts once as its
  host-language line.
- When classification is unclear, count the line as code.
- A mixed file uses the stricter maximum. A test or prototype file that also
  contains production or runtime behavior uses the production maximum.
- Do not pack statements or expressions onto fewer lines to pass the gate.

New files and files below their maximum must finish within it. An existing
oversized file may change when its code-line count does not grow. Do not remove
unrelated code to offset growth.

If accepted work must grow an oversized file, stop before editing. Record its
exact base count, expected maximum, reason, and explicit owner approval. Record
the actual result after staging. Stop again if it exceeds the approved maximum.

## Authored Documentation

Classify authored documents by what they can change:

- **Control documents:** `AGENTS.md`, `SKILL.md`, pull-request templates,
  workflow rules, and API or operations rules.
- **Decision documents:** architecture, product, privacy, and similar records.
- **Explanatory documents:** guides, indexes, and navigation that carry no rule
  or decision.

For every changed authored document, record its resulting nonblank lines and
its added plus deleted nonblank lines. These size conditions are review
triggers:

| Condition | Trigger |
| --- | ---: |
| Added plus deleted nonblank lines in one update | 150 |
| A new or changed document crosses from below this size to at least this size | 600 resulting nonblank lines |
| A document already at or above this size grows | 600 resulting nonblank lines |

A small correction to a document already at or above the size trigger does not
trigger size review when the file does not grow and the update stays below the
changed-line trigger.

Authority rules can require review sooner:

- Every substantive control-document change requires an independent staged
  review.
- Every change that adds, removes, or changes a decision in a decision document
  requires that review.
- Pure spelling, formatting, or wording changes may skip it when meaning does
  not change.

One independent review can satisfy both authority and size triggers. A focused
documentation review checks for removed constraints, contradictions, new
authority, repeated rules, mismatch with code, and unclear structure.

Code fences count toward document size. Check executable embedded blocks under
the code-size gate too. When comment-only lines or documentation-only
docstrings change, measure those prose lines separately and apply the
documentation triggers. Check a mixed prose and code file under both rules.

Truly generated reference documentation uses the generated-output budget and
regeneration evidence. Its tracked inputs remain ordinary.

## Generated, Vendored, and Lock Files

These independent budgets are hard gates:

| Output class | Maximum files | Maximum textual churn | Maximum aggregate resulting size |
| --- | ---: | ---: | ---: |
| Generated | 8 | 20,000 lines | 512 KiB |
| Vendored, when explicitly authorized | 2 | Not capped separately | 1 MiB |
| Lock files | 1 | 2,000 lines | 256 KiB |

Vendored output is not allowed without explicit issue authority. The authorized
budget above applies only after that decision.

Generated output must be emitted byte-for-byte by a tracked deterministic
command from tracked inputs. Plans and pull requests record its paths,
generator and version, inputs, exact command, file count, textual churn,
resulting bytes, clean regeneration, and semantic validation. Visual snapshots
also need a pinned reproducible environment.

Authorized vendoring records provenance, an immutable version or hash, the
license, and why repository storage is required. Lock-file evidence records the
package-manager version and reproduction command.
