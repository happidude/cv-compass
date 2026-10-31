# Agent workflow

Use [root rules](../AGENTS.md), [policy](AGENT_POLICY.md),
[Git procedure](CV_COMPASS_GIT_WORKFLOW.md), component rules/conventions and an
approved [spec](templates/spec.md). This procedure supersedes the single-agent
workflow; historical results never authorize implementation or establish acceptance.

## Approval and orchestration

The primary agent records the approved spec, approval source/date, base commit,
branch/worktree, acceptance criteria, allowed paths, excluded work and permissions.
An explicit task message can be the approval source; no second spec approval is
needed for scope already approved. Missing approval blocks dependent implementation.
Do not infer Active/completed milestone status; see [roadmap](roadmap.md).

Primary coordinates spec → Implementer → Tester → Reviewer → PR, sequentially.
Test ownership follows [root Execution and delivery](../AGENTS.md#execution-and-delivery).
Docs/config-only work uses applicable structural checks; do not add artificial
application tests or dependencies.

| Role | Owns | Must not do |
| --- | --- | --- |
| Primary | Spec, scope, handoffs, evidence, bounded cycles, docs/config coordination, Git/PR delivery | Concurrent writes or self-accept unresolved blockers |
| Implementer | Approved production code, accompanying tests/helpers/synthetic fixtures and scoped docs | Weaken assertions/checks/coverage or expand scope |
| Tester | Independent acceptance verification, missing tests and test helpers/synthetic fixtures | Fix production code, weaken assertions/checks/coverage or replace full checks with subsets |
| Reviewer | Read-only complete diff/context review and findings | Edit any file, run write-producing tools, accept on behalf of developer |

Only one writer per working tree, including primary, agents, formatters/builds and
tests that create files. Primary grants an explicit write lease: owner, paths,
base/source, task, allowed commands and expected report. Wait for the agent to
finish and release it before the next writer; interruption alone is not release:
confirm subprocesses have stopped and inspect status/diff. Reviewer runs only
read-only inspection while all writers/checks are stopped. No background writes.
Different worktrees require independent scope; do not assume shared contracts can
be edited concurrently.

Each handoff includes spec/approval link, base/current commit or fingerprint,
changed/untracked paths, commands/results, findings and next owner. Agents read
the applicable rules; they cannot delegate onward or publish unless primary
explicitly assigns it. Primary remains responsible for scope and complete evidence.

## Bounded implementation, verification and review

1. Implement only approved criteria, preserving existing code ownership/contracts.
2. Tester runs affected checks then applicable full local gates on final source;
   follow [verification](VERIFICATION.md), inspect fresh reports and warnings.
3. Reviewer reads the full diff against the agreed base, staged/unstaged changes
   and intended new files, plus relevant callers/contracts/tests. Assess acceptance,
   correctness, module/transaction boundaries, security/privacy, concurrency,
   logging and test gaps; mark irrelevant categories N/A with reason.
4. Findings need stable ID, severity, exact file/line, impact, evidence or
   reproduction, recommended fix and disposition (open / fixed and verified /
   rejected with evidence). Missing checks are evidence gaps, not invented defects.
5. Primary routes production defects to Implementer and defects in Tester-added
   tests to Tester. Each repair receives an explicit owner and exclusive write
   lease, then affected verification, required full gates on final source and
   another Reviewer pass.

At most **three complete fix–test–review cycles**, counting initial review as
cycle 1. Keep one counter for the task; fixes/agent replacement do not reset it.
Critical (exposure/data loss), High (major correctness/security/acceptance) and
Medium (material defect/test gap) block acceptance; Low can be documented.
No automatic fourth cycle. Remaining blockers after cycle 3: report **BLOCKED**,
retain evidence and request a new bounded task/decision. Never waive findings to
pass. Outside-spec issues are reported under PR Open Questions; continue only
independent approved work. An environment obstacle is BLOCKED with checks
NOT VERIFIED; a demonstrated check failure is FAIL. Neither is PASS.

## Delivery gate

All criteria met, no unresolved Critical/High/Medium, scoped local checks passed,
complete final diff reviewed, no secrets/CVs/runtime artifacts, and diff-check clean.
Changes after review require a new pass within the budget. Local and CI evidence
identify the same final source; report each separately, including path-filtered CI.
Do not claim merge readiness until required CI is green.

Use [verification template](templates/verification.md) and [PR template](../.github/pull_request_template.md).
Stage explicit paths, inspect staged diff, commit (Conventional Commits), push the
approved branch and open PR. Existing commit/push authorization persists; do not
ask again. If required verification is blocked, an explicitly requested source
snapshot may be published as a **draft**, with blockers and NOT VERIFIED checks;
it is not accepted/completed. CI workflow/security-hook edits require an approved
spec listing exact paths and intent; never weaken or skip checks to get green.
Outside-scope proposals go under Open Questions. Merge, GitHub settings/branch
protection/rulesets, repository secret creation/editing, releases/tags and
production database/server/deployment operations are human-only: agents never
perform them, regardless of task authorization. This workflow follow-up performs
none of those operations. Never automatically delete branches/worktrees.
Stop at the assigned task.

## Codex custom agents

Checked against official OpenAI documentation on 2026-10-31:
[custom agents and controls](https://learn.chatgpt.com/docs/agent-configuration/subagents),
[configuration reference](https://learn.chatgpt.com/docs/config-file/config-reference),
and [AGENTS.md discovery](https://developers.openai.com/codex/guides/agents-md).

Project definitions are standalone `.codex/agents/{implementer,tester,reviewer}.toml`;
required fields are name, description and developer_instructions. Project
`.codex/config.toml` enables agents and caps open subagents at one; close a finished
agent before spawning another. The primary still enforces the write lease.
Models/effort inherit the session; no account-specific model is pinned.
Reviewer read-only behavior is instruction-based: custom files inherit parent
permissions and cannot enforce an independent read-only sandbox. Use a parent
read-only session for stronger review isolation when needed; do not claim TOML
instructions enforce OS-level isolation.

Project configuration may require a trusted checkout and a new session. Validate
syntax/schema and actual role discovery separately. A current chat does not reload
new roles automatically. If custom-role dispatch is unavailable, report it and use
sequential explicitly instructed roles without claiming native dispatch succeeded.
No global settings, credentials, MCP services or new dependencies are required.
