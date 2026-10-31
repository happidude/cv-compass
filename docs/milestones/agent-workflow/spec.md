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
