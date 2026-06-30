# Agent GitHub Identity Policy

Purpose
- Define how agent-authored work should appear in GitHub.
- Let the human owner review and approve agent-created pull requests without
  GitHub treating the owner as the PR author.
- Keep repository permissions small and auditable.

Decision
- Use a dedicated GitHub user account, called the agent account in this policy,
  for Moon Service agent work now.
- Defer a GitHub App until this repo needs installation tokens, multi-repo
  automation, or finer lifecycle controls than a dedicated user account can
  provide.
- Do not store agent account credentials, tokens, or setup secrets in this
  repository.

Rationale
- An agent account solves the immediate review problem: agent-created branches,
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
  `<github-user-id>+<agent-login>@users.noreply.github.com`.
- Keep the agent account's email private in GitHub account settings.
- Configure Git identity locally per repository, not globally, unless the same
  agent identity is intentionally reused across repositories.

GitHub Permissions
- Add the agent account as a collaborator with Write access to this repository.
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
- Protect every active integration or release branch.
- Today that means `spring-enterprise-ish-refactor`, because it is the current
  integration branch.
- If `main` becomes the direct integration or release branch, protect `main`.
- If future long-lived integration branches are introduced, protect them before
  they start receiving routine PR merges.
- Require pull requests before merging to protected branches.
- Once the agent account is active, require at least one human approving review
  for agent-authored pull requests.
- Require conversation resolution before merge.
- Dismiss stale approvals when new commits are pushed after review.
- Require status checks once CI exists. Until CI exists, do not configure
  placeholder required checks that would block every PR; keep local validation
  evidence in the PR body.
- Block force pushes and branch deletion on protected branches.
- Do not allow the agent account to bypass branch protection.

Workflow
- Agent work should use issue-backed branches named
  `issue-<number>-short-topic`.
- Agent-created PRs should mention the issue they address and use `Closes #N`
  when the PR fully resolves the issue.
- Agent-created PRs should be assigned to `rapucha` and request `rapucha` as a
  reviewer when created:
  - `gh pr create --assignee rapucha --reviewer rapucha ...`
- The repository also has a `pull_request_target` workflow that assigns
  `rapucha` and requests review from `rapucha` for non-draft PRs opened by
  `moon-service-agent`. The workflow must not checkout or execute PR code.
- The human owner should review agent-authored PRs through GitHub review when
  branch protection requires it.
- If a PR is still authored by the human account during the transition, formal
  self-approval is not meaningful; use PR discussion and local validation until
  the agent account is active.
- If the human owner directly edits an agent branch, keep that visible in the
  commit history instead of rewriting authorship.

Account Switching Guardrail
- Agents must not switch GitHub CLI, Git, browser, token, SSH, or other repo
  workflow identity from the agent account to the human owner account unless
  the human explicitly approves that exact switch.
- If an agent believes solving a problem requires switching to the owner
  account, the agent must stop, notify the human, explain what is happening,
  state why the owner account appears necessary, and wait for approval or
  denial before switching.
- If approval is denied, the agent may provide additional technical reasoning
  or safer alternatives, but must not switch accounts.
- Owner-account use should be treated as exceptional and temporary. After an
  explicitly approved switch, the agent should return GitHub CLI and repo
  workflow defaults to the agent account as soon as the approved action is
  complete.

GitHub CLI Auth Troubleshooting
- If `gh auth status` reports an invalid token from Codex, do not immediately
  assume the GitHub token is bad. In this environment, sandboxed network access
  can make `gh` report a valid token as invalid.
- Retry the auth check with network access before asking the user to rotate a
  token:
  - `gh auth status`
  - `gh api user --jq .login`
- If the user's normal terminal reports a valid account but Codex still cannot
  authenticate, check whether the terminal is using keyring-backed auth while
  Codex can only see `~/.config/gh/hosts.yml`.
- Do not print token values while inspecting GitHub CLI config. Redact
  `oauth_token` values if showing `hosts.yml` contents.
- If Codex must use `gh` and cannot access the keyring, the user may choose to
  run `gh auth login -h github.com --with-token --insecure-storage` so GitHub
  CLI writes a plaintext token entry that the Codex process can read. Call out
  the plaintext-storage risk and recommend a short-lived token or later
  rotation.
- Prefer confirming the active account with `gh api user --jq .login` before
  creating PRs or issue comments. Do not switch to the owner account unless the
  user explicitly approves that exact identity switch.

Setup Checklist
1. Create or choose the dedicated GitHub user account that will act as the
   agent account.
2. Enable two-factor authentication and private email on that account.
3. Add the agent account as a repository collaborator with Write access.
4. Create a fine-grained token or equivalent local GitHub authentication for
   this repository only.
5. Store the credential outside the repository.
6. Configure repository-local Git author identity:
   - `git config user.name "Moon Service Agent"`
   - `git config user.email "<github-user-id>+<agent-login>@users.noreply.github.com"`
7. Confirm `git config --local --get user.email` uses the agent account's
   noreply email.
8. Confirm GitHub CLI or remote authentication writes as the agent account
   before opening agent-authored PRs.
9. Enable branch protection review requirements after the agent account can
   open PRs.

Smoke Test
- Before enabling required human reviews, open one small issue-backed pull
  request from the agent account.
- Verify the issue, branch, pull request, comments, and commits show the agent
  account identity.
- Verify commits use the agent account's GitHub noreply email.
- Verify the human owner can review the pull request as a separate GitHub
  identity.
- Merge the smoke-test pull request before treating the agent-account workflow
  as ready for protected branch review requirements.

When To Revisit
- More than one repository needs agent automation.
- Token rotation or permission auditing becomes burdensome.
- Agent work needs short-lived installation tokens.
- CI, release, or deployment workflows need automation beyond branch and PR
  operations.
