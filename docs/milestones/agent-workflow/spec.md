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

Historical approval record for fcd986783e2176a98af4b51a995212c5e402a181.
Its sensitive-operation policy is superseded by the second bounded revision below;
it grants no current permission for human-only operations.

A new explicit developer request authorizes a fresh maximum of three fix–test–review
cycles for Draft PR #20 on chore/agent-workflow. Inspected starting local/PR head:
f0c4049a28da23f79ffcd6c965b048dcba3cc8e3; PR base:
81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Earlier checks/reviews remain historical
evidence for the initial delivery, not checks of this follow-up.

Acceptance criteria:
1. Test ownership follows [root Execution and delivery](../../../AGENTS.md#execution-and-delivery); the prior iteration checked that source and its scoped handoffs.
2. This earlier iteration revised CI/security-hook scope and sensitive-operation
   wording. The latter policy was superseded by the second bounded revision's
   human-only rule; this historical criterion grants no current permission.
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

## Second bounded revision authorization — 2026-10-31

Developer authorizes a new iteration starting at inspected local/PR head
fcd986783e2176a98af4b51a995212c5e402a181; PR base remains
81a8a6220e34871ea9fc68aa8e65d19785d0eff9. Use the existing branch and Draft PR #20.
The fresh counter starts at cycle 1, maximum three fix–test–review cycles total,
including the separately reviewed translation phase. No new branch or PR.

Phase 1 acceptance (English commit first):
- Restore human-only, never-for-agents merge, GitHub settings/branch protection/
  rulesets, repository secret creation/edit, releases/tags and production DB/server/
  deployment operations, regardless of task. Keep scoped CI/security-hook edits
  conditional on an approved spec listing exact paths and intent; never weaken
  checks. Keep secret/CV confidentiality and no external CV content transmission.
- Full test-ownership prose belongs only in root AGENTS.md; component additions
  are removed, policy/verification/PR template point to root, workflow keeps its
  role table and routes production defects to Implementer and Tester-added test
  defects to Tester. Agent TOMLs use one ownership sentence plus role-specific rules.
- Check whole-repo active wording outside docs/history without reading the private
  M10.1 draft. Earlier spec/evidence statements are superseded historical approval
  records, never current permission. Record any retained historical wording clearly.
- Tester verifies and Reviewer reads the entire PR diff before commit 1, message
  docs: ..., English. Do not translate before this checkpoint.

Phase 2 acceptance (separate translation commit):
- Translate PR-added/modified English Markdown and agent description/instructions
  into Vietnamese without changing requirements. Exclude docs/history/*,
  backend/HISTORICAL_VERIFICATION.md, POC database draft, already-Vietnamese files
  and all frozen application/canonical JSON/CI artifacts; preserve excluded bytes.
- Preserve paths, URLs, commands/flags, code blocks, TOML keys/name, role names,
  conventional status labels and technical terms. Update all inbound heading anchors.
- Root AGENTS.md alone defines language: docs/rules/templates/PR Vietnamese;
  code/code comments/identifiers/commit messages English. Other files refer to it.
- Tester produces a per-binding-rule equivalence table against commit 1, checks
  English residue with explicit allowed exceptions, and reruns links/TOML/ignore/
  whitespace/frozen-diff checks. Reviewer compares both versions read-only.
- Commit 2 message: docs: translate shared agent documentation to Vietnamese.
  Vietnamese PR body lists both SHAs, translated/excluded files with reasons.

For both phases: one writer; Primary → Implementer → Tester → read-only Reviewer;
relative Markdown links/anchors have no broken targets, TOML parses, ignore probes
protect private M10.1 and retain canonical PR template tracking. Application/source,
dependencies/build/formatter and CI workflows are frozen against base81a8a62.
Native dispatch/full secret scanning stay NOT VERIFIED unless actually established.
Normal push only to chore/agent-workflow; keep Draft, no Ready/merge/force push or
sensitive operations. CI results must identify the new head; no premature CI PASS.

## Open Questions — deferred work

Not authorized now: remove task-specific statements from durable rules/templates;
merge the three workflow documents; archive M6/M10/M11 reports; assign Active in
roadmap. Record these as next work in the PR; do not perform them in this iteration.
