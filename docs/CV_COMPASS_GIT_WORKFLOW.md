# Git branch and worktree procedure

Follow [root rules](../AGENTS.md) and [agent workflow](AGENT_WORKFLOW.md).
Milestone scope/status belongs in [roadmap](roadmap.md) and approved specs, never
in this procedure. [Legacy plan](history/legacy-milestone-plan.md) is historical.

## Preflight

Inspect current directory, branch, status, worktrees and agreed base. Preserve
developer changes; never stash/reset/discard automatically. Do not implement on main.
Use a dedicated short-lived branch/worktree per milestone; create only on demand,
after scope approval. A maintenance task can reuse a clean checkout on its
explicitly requested dedicated branch, as this workflow task does.

PowerShell (same Git commands in a Linux shell; use its repository paths):

```powershell
git status --short
git branch --show-current
git worktree list
git branch --list chore/example
# Create only if the requested branch does not exist and checkout is clean:
git switch -c chore/example
```

For a new milestone worktree use `git worktree add -b feature/example <approved-path> <agreed-base>`.
If branch/path exists, inspect ownership and base before reusing it. Never alter
another person's worktree, create nested repositories or run a whole future plan
as a command batch. Fetch when needed; do not switch/pull another active checkout
or rewrite published history. Report path/branch before implementation.

## Names, commits and publishing

Branches: `feature/`, `fix/`, `chore/`, `refactor/`, `docs/` plus lowercase
hyphenated purpose. An explicit requested name wins.
Conventional Commits, English imperative, one logical change per commit.
Keep formatting-only commits separate from behavior changes.

Run applicable checks/formatter and bounded review, inspect final status/diff,
then stage explicit approved paths. Never stage an entire dirty directory.
Do not force-add ignored secrets/runtime artifacts; shared docs/rules must be
tracked through reviewed ignore rules.

```powershell
git diff --check
git add -- <explicit-approved-paths>
git diff --cached --stat
git diff --cached --check
git diff --cached
git commit -m "chore: apply approved agent workflow"
git push -u origin <approved-branch>
```

Commit/push/open PR are allowed when authorized by the task; do not re-ask.
Otherwise prepare the concrete reviewed result first. Never push failing checks
as ready: a specifically authorized source snapshot may use a draft PR with
explicit blockers/NOT VERIFIED evidence, per the agent workflow.

PR targets main and uses [PR template](../.github/pull_request_template.md), spec, verification,
exact source, review findings/cycles and Open Questions. Human review remains
required. Merge, production/deploy, release and GitHub settings/rulesets/secrets
are outside ordinary development and require a separately assigned task plus
explicit authorization. Commit/push/PR permission does not include them.
Never force push, push main, rewrite history, bypass hooks or delete remote
branches. Do not automatically remove local branches/worktrees.

## Coordination

One writer per branch/worktree at a time, including verification tools.
Use explicit ownership handoffs in the spec/verification/PR, not chat memory alone.
Separate worktrees do not authorize concurrent edits to shared contracts.
Do not start another milestone until explicitly assigned and prerequisites confirmed.
