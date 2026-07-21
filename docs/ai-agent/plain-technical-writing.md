# Plain Technical Writing

Use this guide for technical prose written by agents for Moon Service. It
applies to responses, project documentation, issues, pull requests, GitHub
comments and replies, reviews, commit messages, and code comments.

The goal is prose that a colleague can understand once without losing
technical precision.

## Preserve Meaning First

Technical accuracy and contract meaning take priority over every style
heuristic. Clarity must not change behavior, responsibility, or obligation.
Keep the original meaning when simplifying text.

- Preserve API names, identifiers, protocol terms, status codes, and
  established contract language exactly when referring to them.
- Preserve quotations, legal text, and text that another system must consume
  exactly.
- Keep required template fields. Write their contents plainly.
- Use technical terms when they are more exact than common substitutes. Explain
  an unfamiliar term briefly when the reader needs that explanation.
- Follow a user's requested style unless it conflicts with project rules or
  would make the technical meaning less exact.

Do not simplify a sentence by changing who acts, when it applies, how strong
the requirement is, or what result an observer can verify.

## Use Modal Verbs Exactly

Modal verbs carry different meanings:

- `must` states an obligation or required behavior;
- `should` states a recommendation that may have a justified exception;
- `may` grants permission or describes an allowed option; and
- `can` describes capability or possibility.

Do not replace one modal with another merely to make a sentence shorter. For
example, “The server must reject an invalid token” is a requirement. “The
server can reject an invalid token” only describes a capability.

## Write Direct Prose

- Put the result, decision, or request first.
- Name the actor when ownership matters: the user, client, server, service,
  database, thread, process, or application.
- Prefer active voice when it makes the actor and action clearer. Passive voice
  is useful when the actor is unknown or unimportant.
- Prefer concrete verbs to abstract noun phrases. Write “validate the request”
  instead of “perform validation of the request.”
- Keep one main idea in a sentence when practical. Separate distinct
  conditions, exceptions, and outcomes.
- Use common words and short paragraphs when they stay exact.
- Use lists when they reveal structure, not merely to break prose into pieces.
- Remove formal filler, delayed conclusions, decorative metaphors, and repeated
  qualifications that add no meaning.

These are judgment rules, not quotas. A longer sentence is acceptable when it
expresses one precise relationship more clearly than several fragments.

## Write Testable Requirements

A requirement should identify:

1. the actor responsible for the behavior;
2. the required action;
3. the object of that action;
4. the condition, if one applies; and
5. the observable result that proves the requirement was met.

Keep unrelated requirements separate. Each requirement should be testable on
its own.

Prefer:

> When credentials are invalid, the server must return `401 Unauthorized`.

Avoid:

> When credentials are invalid, a `401 Unauthorized` response must be returned
> by the server.

The preferred version keeps the same condition, actor, obligation, and
observable result. It does not weaken `must` into `can`.

## Explain Behavior in Execution Order

State the outcome first. Then describe the steps in the order they happen.
Name the responsible component at each important step. Separate normal
behavior from failure behavior.

Prefer:

> The server accepts the request only when the token is valid. It validates the
> token first. If validation fails, it returns `401 Unauthorized`.

Use a concrete example when an abstraction is otherwise hard to follow.

## Write Useful Code Comments

Explain why code exists, why a choice was made, or which non-obvious constraint
the code preserves. Do not restate an operation that the code already makes
clear. One or two direct sentences are usually enough.

Keep comments accurate when behavior changes. An obsolete explanation is worse
than no explanation.

## Review Without Mechanical Enforcement

Before publishing technical prose, check:

- Is the main point easy to find?
- Are the actor, action, condition, and result clear where they matter?
- Did an edit change a contract, modal verb, exception, or responsibility?
- Can an abstract noun phrase become a concrete verb without losing meaning?
- Would splitting a sentence or paragraph make the sequence easier to follow?
- Does any phrase add formality but no information?
- Does each requirement describe one independently testable behavior?
- Does each code comment explain something the code does not already say?

Sentence length, clause count, passive voice, noun stacks, and readability
scores may prompt a closer look. They must never create an automatic finding,
gate failure, or required rewrite. Do not add a style linter or numeric
threshold to enforce this guide.

When reviewing prose, flag wording only when a simpler version keeps the same
technical meaning. Suggest the replacement and explain any non-obvious meaning
that it preserves. Do not turn review into personal style preference.
