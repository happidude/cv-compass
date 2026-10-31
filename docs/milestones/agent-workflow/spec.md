# Agent workflow maintenance specification

## Approval

Developer request in this chat on 2026-10-31 approves this workflow task, rule/agent
configuration edits, branch `chore/agent-workflow`, commit/push and PR. This file
records that existing authorization; no new application milestone is authorized.
Base: `81a8a62`; existing clean checkout `E:/cv-compass` reused on the requested
dedicated maintenance branch. No additional milestone worktree is needed for
this docs/config task. Preserve other worktrees.

## Goal and acceptance

1. Inspect source, docs and existing CI; preserve application code/dependencies/CI.
2. Classify rules/conventions/contracts/operations/specs/verification/history.
   Archive only superseded material after reference checks; split mixed guides.
3. Short root rules plus backend/worker/frontend rules; retain detailed conventions.
4. Add workflow, roadmap, milestone registry and spec/verification/PR templates;
   do not infer application Active or completion status.
5. Configure Implementer, Tester and read-only Reviewer using verified official
   Codex docs. Primary coordinates; one writer at a time.
6. Execute already-approved scope without asking again; report scope changes,
   three-cycle maximum and BLOCKED if issues remain.
7. Track shared docs/rules/config while secrets/CVs/runtime data stay ignored.
8. Verify links/config/ignore/frozen paths, commit, push and open PR; no merge,
   deploy or GitHub setting changes.

## Impact and verification plan

Allowed: root/component rules, shared documentation/links/classification, project
agent TOML and root ignore patterns. No API/DB/security/application dependency or
production changes. Existing runtime contract artifacts retain paths/bytes.
Primary writes docs/config; sequential Tester validates then Reviewer inspects
read-only. Docs/config structural checks replace irrelevant application tests;
no new tool dependency. Source-level acceptance does not certify OCR production,
accuracy or an application milestone.

Check relative targets/anchors, TOML shape, role boundaries, reference migration,
ignore probes, unchanged CI/source/build/dependency artifacts and diff whitespace.
Record unavailable native role discovery or CI as NOT VERIFIED. Use maximum three
review cycles. Publish a draft if required verification remains blocked.

## Follow-up authorization — 2026-10-31

A new explicit developer request authorizes a fresh maximum of three fix–test–review
cycles for Draft PR #20 on chore/agent-workflow. Inspected starting local/PR head:
f0c4049a28da23f79ffcd6c965b048dcba3cc8e3; PR base:
81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Earlier checks/reviews remain historical
evidence for the initial delivery, not checks of this follow-up.

Acceptance criteria:
1. Implementer may write accompanying tests/helpers/synthetic fixtures and prefers
   test-first for behavior changes. Tester independently verifies acceptance and
   adds missing tests, never production fixes. All roles preserve assertions/checks/
   coverage. Primary assigns each repair with one writer at a time.
2. CI/security-hook edits require an explicitly approved task scope; checks cannot
   be weakened for green results. GitHub settings/rulesets/secrets, production,
   release and merge require a separately assigned task and explicit authorization;
   they are outside ordinary development. This follow-up performs none of them.
3. Canonical PR template is .github/pull_request_template.md, tracked, with the old
   docs template reduced to a pointer and relevant links updated.
4. Synchronize active rules/policy/workflow/verification/templates/custom-agent
   definitions. Do not rewrite historical evidence to imply fresh verification.
5. Independently check links/anchors, TOML, ignores, staged/unstaged whitespace,
   template tracking and private draft exclusion; review the entire PR diff.
   Prove application/dependency/build/formatter/CI files unchanged against PR base.
6. Attempt native role dispatch and an available secret scanner without global
   installation or machine changes; missing support is NOT VERIFIED with next action.
7. Conventional Commit, normal push to the same branch, update PR description with
   final head and actual evidence. Keep Draft; no merge/force push/Ready/settings.

Allowed paths: root/component AGENTS, .codex agent TOML, policy/workflow/verification,
related templates/docs/links and .github/pull_request_template.md only. Application,
dependency, build/formatter config and .github/workflows are frozen. No new archives,
documentation deduplication sweep or Active milestone assignment. Primary coordinates
→ Implementer edits → Tester verifies → Reviewer reads only, with exclusive handoffs.
