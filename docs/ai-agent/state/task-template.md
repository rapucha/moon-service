# AI Task Template

Purpose
- Structured worksheet for substantial AI-assisted tasks that may span multiple
  sessions or require explicit handoff.

Metadata
- Title: `{title}`
- Owner/Role: `{agent role or human owner}`
- Created: `{YYYY-MM-DD}`
- Status: `TODO | In Progress | Done | Blocked`
- Scope: `{modules/docs involved}`

Context
- Problem statement:
  - `{what problem or gap is being addressed}`
- Desired outcome:
  - `{concrete, verifiable end state}`
- Non-goals:
  - `{what should not be included}`

Plan
- Steps:
  1. `{step}`
  2. `{step}`
  3. `{step}`
- Risks and mitigations:
  - `{risk}` -> `{mitigation}`
- Rollback:
  - `{how to safely revert or disable the change}`

Files
- Planned edits:
  - `{path}` - `{purpose}`
- Generated or local-only files:
  - `{path}` - `{keep, ignore, or remove before commit}`
- Deferred items:
  - `{follow-up}` - `{reason}`

Validation
- Commands:
  - `{command}` -> `{expected outcome}`
- Results:
  - `{command}` -> `{actual outcome}`
- Skipped validation:
  - `{command}` -> `{reason}`

Decisions
- `{decision}` - `{rationale}`

Handoff
- Current state:
  - `{what is complete}`
- Next step:
  - `{specific next action}`
- Watch-outs:
  - `{known risks, failing commands, untracked files}`

Checklists
- Change review: `docs/ai-agent/checklists/change-review.md`
- API contract change: `docs/ai-agent/checklists/api-contract-change.md`
- Module addition: `docs/ai-agent/checklists/module-addition.md`
