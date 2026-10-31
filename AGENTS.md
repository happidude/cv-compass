# CV Compass — Agent rules

Applies repository-wide; component AGENTS.md files add local requirements.
Read [workflow](docs/AGENT_WORKFLOW.md), [policy](docs/AGENT_POLICY.md),
[Git procedure](docs/CV_COMPASS_GIT_WORKFLOW.md), the component convention and
the approved task spec before editing. [Docs index](docs/README.md) classifies
sources; [roadmap](docs/roadmap.md) records only confirmed scope/status.

## Scope and architecture

- Work only on the approved task. Do not start another milestone automatically.
  Approved scope is authorization to execute; do not ask for the same permission again.
- Spring Boot owns DB writes, business validation and normalization. The Python PDF
  child stays offline; only its host supervisor uses the agreed internal HTTP API.
- Preserve original PDFs and raw extraction. Never fabricate candidate data,
  merge identities by name, or send CV content to external services.
- Java business logic is developer-owned unless explicitly authorized. Python work
  does not authorize Java edits; frontend edits require explicit task scope.
- No LLM/parser/prompt, search/ranking/embedding, new infrastructure or future
  feature implementation without separate approval. Existing code is not approval.

## Permissions and safety

- Keep secrets and real CV data out of commits, logs and public artifacts. Do not
  access private environment/production config or real CVs in ordinary development.
- CI workflows/security hooks may be edited only within an explicitly approved
  task scope. Never weaken assertions, skip checks or lower coverage to hide defects.
- GitHub settings/rulesets/secrets, production, releases and merge are outside
  ordinary development; they require a separately assigned task and explicit
  authorization. This workflow task authorizes none of those operations.
- Never edit applied Flyway migrations, force push, rewrite history, push main,
  bypass hooks, delete remote branches or run destructive cleanup. Do not install
  global tools or change machine configuration.
- New schema/dependencies, build/formatter/security config, API contracts, personal
  data logic, transaction/async boundaries, file deletion/mass moves, shared-module
  or architectural changes need explicit scope approval. See detailed policy.
- Record new out-of-spec decisions under `## Open Questions` in the PR; explain
  context, options and recommendation. Continue independent approved work.
  If rules conflict, follow the stricter rule and record the conflict.
- Preserve developer changes. Stage explicit paths. Track shared rules/docs and
  project agent config; keep secrets, CVs and runtime/build artifacts ignored.

## Execution and delivery

- Primary agent coordinates approved spec → Implementer → Tester → Reviewer → PR.
  Only one writer (including the primary and write-producing checks) per working
  tree at a time. Reviewer is read-only. Follow the workflow handoff protocol.
- Implementer may write accompanying tests, helpers and synthetic fixtures; prefer
  test-first for behavior changes. Tester independently checks acceptance criteria
  and adds missing tests, never production fixes. Primary assigns each repair to
  one writer. No role may weaken assertions/checks or reduce coverage to hide defects.
- At most three complete fix–test–review cycles; unresolved blocking issues after
  cycle 3 mean BLOCKED. No self-acceptance or automatic fourth cycle.
- Use configured formatters; no unrelated refactoring. Code, comments, commits and
  PR text are English. Comments explain non-obvious reasons.
- Run applicable local checks on final source, inspect fresh reports and warnings,
  then report local/CI results separately. Missing checks are NOT VERIFIED.
- Deliver a focused Conventional Commit, push only the approved feature branch and
  open a PR when authorized (already authorized for this workflow task).
  Merge/deploy/settings operations need a separate explicit task and authorization;
  never perform them or clean up branches/worktrees automatically.
