# CV Compass — Git Branch & Worktree Rules

> **Status:** Project convention for the CV Compass POC.  
> **Repository model:** Monorepo — one GitHub repository for `backend/`, `worker/`, `frontend/`, `infra/`, `docs/`, and `scripts/`.  
> **Primary environment:** Windows 11 / PowerShell.  
> **Main repository location (example):** `E:\cv-compass`.

## 1. Purpose

This document defines how developers and Coding Agents create branches, use Git worktrees, review changes, merge milestones, and clean up. Agents **must read this document before starting a milestone**.

- **One repository, multiple worktrees:** Each worktree checks out one branch of the same repository. A branch is **not** tied to a folder.
- **`main` is the stable integration branch:** Do not develop directly on `main`.
- **Feature branches are short-lived:** Create them for milestones or focused features, not permanently for `backend/` or `worker/`.
- **Worktrees are created on demand:** Do not create all planned worktrees in advance.
- **Scope follows the assigned task:** A worktree may contain the entire monorepo, but an Agent may only edit the approved paths.

## 2. Repository layout

```text
E:\
├── cv-compass\                 # main; stable integration checkout
├── cv-compass-db\              # Milestone 1 worktree (when active)
├── cv-compass-backend-base\    # Milestone 2 worktree (when active)
├── cv-compass-upload\          # Milestone 3 worktree (when active)
├── cv-compass-ocr\             # Milestone 4 worktree (when active)
├── cv-compass-integration\     # Milestone 5 worktree (when active)
└── cv-compass-evaluation\      # Milestone 6 worktree (when active)
```

All worktrees belong to the **same Git repository and GitHub remote**. Do not run `git init` inside `backend/`, `worker/`, or another subdirectory. Do not add nested repositories or submodules without explicit approval.

## 3. Milestone branch/worktree plan

| Milestone | Branch | Worktree path | Allowed scope by default | Primary owner |
|---|---|---|---|---|
| M1 — Database Foundation | `feature/database-foundation` | `E:\cv-compass-db` | `backend/`, `infra/`, `docs/` | Agent implements; developer reviews |
| M2 — Backend Foundation | `feature/backend-foundation` | `E:\cv-compass-backend-base` | `backend/`, relevant `docs/` | Agent implements; developer reviews |
| M3 — CV Upload & Batch | `feature/cv-upload` | `E:\cv-compass-upload` | `backend/`, relevant `docs/` | Developer codes Java; Agent guides/reviews |
| M4 — Python OCR Worker | `feature/ocr-worker` | `E:\cv-compass-ocr` | `worker/`, relevant `docs/` | Agent implements Python |
| M5 — Java–Python Integration | `feature/worker-integration` | `E:\cv-compass-integration` | `backend/`, `worker/`, `docs/` | Shared; Java business logic remains developer-owned |
| M6 — POC Evaluation | `feature/poc-evaluation` | `E:\cv-compass-evaluation` | `scripts/`, `docs/`, explicitly approved fixes | Developer + Agent |

This table is a **plan, not permission to implement future milestones**. The developer must authorize each milestone and any cross-scope changes. If the agreed milestone scope changes, update the plan after approval.

## 4. Branch naming conventions

- Feature: `feature/<short-kebab-case-description>`
- Bug fix: `fix/<short-kebab-case-description>`
- Maintenance: `chore/<short-kebab-case-description>`
- Documentation: `docs/<short-kebab-case-description>`

Use descriptive names and avoid permanent branches such as `backend`, `python`, or `frontend`. Do not create a second branch with the same name if it already exists; inspect first.

## 5. Standard milestone workflow (PowerShell)

### 5.1 Preflight — before creating a worktree

From the main checkout:

```powershell
cd E:\cv-compass
git status --short
git branch --show-current
git worktree list
git fetch origin
git switch main
git pull --ff-only origin main
```

**Preconditions:** The `main` checkout must have no uncommitted changes before switching or pulling. If it is dirty, **stop and ask the developer** how to proceed. Do not automatically stash, reset, discard, or commit their work. If `main` is already checked out in this worktree, `git switch main` is unnecessary.

### 5.2 Create Milestone 1 worktree

```powershell
cd E:\cv-compass
git worktree add -b feature/database-foundation E:\cv-compass-db main
cd E:\cv-compass-db
git branch --show-current
git status --short
code .
```

Expected active branch: `feature/database-foundation`.

If the branch or destination already exists, **do not force creation**. Inspect with `git branch --list`, `git worktree list`, and `Test-Path E:\cv-compass-db`, then ask before reusing or changing it.

### 5.3 Work inside the assigned worktree

- Confirm the current directory and branch before editing.
- Read `AGENTS.md`, this document, and the relevant milestone specification in `docs/`.
- For M1, implement **database foundation only**. No JPA Entities, Repositories, Controllers, Services, or Python OCR.
- Do not edit the stable `E:\cv-compass` checkout while working in `E:\cv-compass-db`.
- Never modify unrelated files to make tests pass without approval.
- Keep generated artifacts, secrets, real CV files, build outputs, and local configuration out of Git.

Useful verification:

```powershell
pwd
git branch --show-current
git status --short
git diff --stat
git diff --check
```

### 5.4 Review, commit, and push

After tests and developer review:

```powershell
git status --short
git diff --check
git add backend/ infra/ docs/
git diff --cached --stat
git diff --cached
git commit -m "feat(db): initialize POC database schema"
git push -u origin feature/database-foundation
```

**Important:** Stage only paths actually changed and approved. The `git add backend/ infra/ docs/` command is an M1 example, not permission to stage unrelated changes. Prefer explicit paths when practical. Do not commit or push without the developer's approval if the Agent is executing commands on their behalf.

Create a Pull Request from `feature/database-foundation` into `main`. Review the diff, checks, migrations, and any schema decisions before merging. Prefer merging via GitHub after review. Do not force-push to `main`.

### 5.5 Merge and cleanup

**Only after the Pull Request is confirmed merged**, and the feature worktree has no uncommitted changes:

```powershell
cd E:\cv-compass
git switch main
git pull --ff-only origin main
git worktree remove E:\cv-compass-db
git branch -d feature/database-foundation
git worktree list
```

`git branch -d` may refuse deletion depending on merge strategy (for example, squash merge). If that happens, **do not automatically use `-D`**; confirm the PR is merged and ask for approval before force-deleting the local branch. Remote branch deletion is optional and follows repository policy.

Do not remove a worktree containing uncommitted changes; do not use `git worktree remove --force` without explicit approval.

## 6. Starting subsequent milestones

Create the next worktree only after its prerequisites have been merged into `main` (unless parallel work is explicitly planned).

```powershell
# Milestone 2 — after M1 is merged
cd E:\cv-compass
git pull --ff-only origin main
git worktree add -b feature/backend-foundation E:\cv-compass-backend-base main

# Milestone 3 — after M2 is merged
# Run from an updated main checkout:
git worktree add -b feature/cv-upload E:\cv-compass-upload main

# Milestone 4 — can run in parallel with M3 after shared prerequisites/contracts are ready
git worktree add -b feature/ocr-worker E:\cv-compass-ocr main

# Milestone 5 — after M3 and M4 are integrated into main
git worktree add -b feature/worker-integration E:\cv-compass-integration main

# Milestone 6 — after M5 is integrated into main
git worktree add -b feature/poc-evaluation E:\cv-compass-evaluation main
```

Each command assumes the branch/path does not already exist and the main checkout has been updated. Repeat the **preflight** steps before each new milestone; do not run the entire block blindly.

## 7. Parallel development rules

M3 Java Upload and M4 Python OCR may run simultaneously **only after**:

1. Shared database/backend prerequisites are merged.
2. The Java–Python API contract is agreed and documented in `docs/`.
3. Each task has a clear file ownership boundary.

Example:

- `E:\cv-compass-upload` → `feature/cv-upload` → developer edits Java.
- `E:\cv-compass-ocr` → `feature/ocr-worker` → Agent edits Python.

Each worktree has its own checked-out branch and working files. Both branches can be pushed independently and merged through separate PRs. Before integrating, refresh from `main` using a developer-approved merge or rebase strategy; **do not automatically rebase shared/published branches or force-push**.

If both tasks require edits to the same API contract, coordinate those edits first rather than allowing conflicting independent changes.

## 8. Agent-specific Git safety rules

The Coding Agent **must**:

- Report its current worktree path and branch before implementation.
- Inspect `git status` before and after edits.
- Respect the task's approved paths and code ownership rules in `AGENTS.md`.
- Show a concise implementation plan before major changes and wait for approval when required.
- Keep commits focused and describe what changed.
- Report executed tests accurately; never claim a build or migration passed without running it.
- Stop when a branch/worktree is missing, dirty in an unexpected way, or diverged in a way requiring a decision.

The Coding Agent **must not**, without explicit developer approval:

- Commit, push, merge, delete branches, or remove worktrees.
- Run `git reset --hard`, `git clean -fd`, destructive checkout/restore, or force push.
- Use `git branch -D` or `git worktree remove --force`.
- Rewrite published Git history.
- Switch another person's active branch or alter another worktree.
- Edit Java business logic as part of a Python task.
- Automatically start the next milestone.

## 9. Pull Request checklist

Before requesting a merge:

- [ ] Correct feature branch and worktree.
- [ ] Only approved files changed.
- [ ] No credentials, personal CV data, or generated build artifacts committed.
- [ ] Relevant build/tests/migrations executed, with results reported.
- [ ] Database migrations reviewed (if applicable).
- [ ] Shared API contract updated and reviewed (if applicable).
- [ ] PR description explains scope, design decisions, and limitations.
- [ ] Developer approved the merge.

## 10. Relationship to `AGENTS.md`

Add this section to the root `AGENTS.md` (or an equivalent reference):

```markdown
## Git Workflow

All Coding Agents must read and follow `docs/CV_COMPASS_GIT_WORKFLOW.md` before starting a milestone.

- Use one GitHub monorepo and a dedicated short-lived feature branch/worktree per milestone.
- Never implement directly on `main`.
- Respect milestone scope and developer ownership.
- Do not commit, push, merge, or delete branches/worktrees without explicit approval.
- Do not automatically proceed to the next milestone.
```

**Rule precedence:** Explicit developer instructions for the current task take priority over the planned milestone table, but destructive Git operations still require clear approval.
