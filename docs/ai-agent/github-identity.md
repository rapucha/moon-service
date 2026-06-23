# Agent GitHub Identity Policy

Purpose
- Define how AI-agent work should appear in GitHub.
- Let the human owner review and approve agent-created pull requests without
  GitHub treating the owner as the PR author.
- Keep repository permissions small and auditable.

Decision
- Use a dedicated machine-user GitHub account for Moon Service agent work now.
- Defer a GitHub App until this repo needs installation tokens, multi-repo
  automation, or finer lifecycle controls than a machine user can provide.
- Do not store bot credentials, tokens, or setup secrets in this repository.

Rationale
- A machine user solves the immediate review problem: agent-created branches,
  pull requests, issue updates, and review comments are authored by a separate
  GitHub identity.
- The repo is currently small enough that a GitHub App would add setup and
  maintenance overhead without clear near-term benefit.
- A dedicated identity keeps public history honest: commits and comments should
  show whether a human or agent performed the action.

Public Identity
- Use a clearly non-human account name, for example `moon-service-agent` or
  another name approved by the owner.
- Use a commit author name that matches that account, for example
  `Moon Service Agent`.
- Use the account's GitHub noreply email for commits:
  `<github-user-id>+<bot-login>@users.noreply.github.com`.
- Keep the machine user's email private in GitHub account settings.
- Configure Git identity locally per repository, not globally, unless the same
  bot identity is intentionally reused across repositories.

GitHub Permissions
- Add the machine user as a collaborator with Write access to this repository.
- Grant only the permissions needed to do agent workflow work:
  - push issue branches;
  - create and update pull requests;
  - read and comment on issues and pull requests;
  - update issue metadata only when explicitly part of the task workflow.
- Do not grant admin access, secret management, environment management,
  package publishing, or branch-protection bypass privileges.
- If using a fine-grained personal access token, scope it to this repository
  only and give it the minimum repository permissions needed for Contents,
  Pull requests, Issues, and Metadata. Use an expiration date and rotate it.
- Do not grant workflow-file write permission unless a specific future issue
  requires changing GitHub Actions workflows.

Branch Protection Policy
- Protect `spring-enterprise-ish-refactor` while it is the integration branch.
- Protect `main` if it becomes the direct integration or release branch.
- Require pull requests before merging to protected branches.
- Once the machine user is active, require at least one human approving review
  for agent-authored pull requests.
- Require conversation resolution before merge.
- Dismiss stale approvals when new commits are pushed after review.
- Require status checks once CI exists. Until CI exists, do not configure
  placeholder required checks that would block every PR; keep local validation
  evidence in the PR body.
- Block force pushes and branch deletion on protected branches.
- Do not allow the machine user to bypass branch protection.

Workflow
- Agent work should use issue-backed branches named
  `issue-<number>-short-topic`.
- Agent-created PRs should mention the issue they address and use `Closes #N`
  when the PR fully resolves the issue.
- The human owner should review agent-authored PRs through GitHub review when
  branch protection requires it.
- If a PR is still authored by the human account during the transition, formal
  self-approval is not meaningful; use PR discussion and local validation until
  the machine user is active.
- If the human owner directly edits an agent branch, keep that visible in the
  commit history instead of rewriting authorship.

Setup Checklist
1. Create or choose the dedicated GitHub machine-user account.
2. Enable two-factor authentication and private email on that account.
3. Add the machine user as a repository collaborator with Write access.
4. Create a fine-grained token or equivalent local GitHub authentication for
   this repository only.
5. Store the credential outside the repository.
6. Configure repository-local Git author identity:
   - `git config user.name "Moon Service Agent"`
   - `git config user.email "<github-user-id>+<bot-login>@users.noreply.github.com"`
7. Confirm `git config --local --get user.email` uses the bot noreply email.
8. Confirm GitHub CLI or remote authentication writes as the machine user
   before opening agent-authored PRs.
9. Enable branch protection review requirements after the bot account can open
   PRs.

When To Revisit
- More than one repository needs agent automation.
- Token rotation or permission auditing becomes burdensome.
- Agent work needs short-lived installation tokens.
- CI, release, or deployment workflows need automation beyond branch and PR
  operations.
