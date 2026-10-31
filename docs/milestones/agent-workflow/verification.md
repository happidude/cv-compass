# Agent workflow maintenance verification

## Initial delivery evidence — historical

The following initial-delivery evidence applies to f0c4049a28da23f79ffcd6c965b048dcba3cc8e3
and its earlier review budget only. It is not verification of the follow-up below.

## Source and acceptance

Approved [spec](spec.md), base 81a8a6220e34871ea9fc68aa8e65d19785d0eff9,
branch chore/agent-workflow, checkout E:/cv-compass. Final commit is identified in
the PR; precommit payload SHA256 per file is retained in the local ignored
backend/build/agent-workflow-test/results.json. The evidence file itself is
excluded from any self-referential fingerprint claim.

Rules, classified docs, workflow/templates/registry and three agent definitions
implement acceptance 1–6. Ignore probes implement criterion 7. Diff/config/link
checks and Git/PR delivery implement criterion 8. Production code, application
dependencies, canonical JSON contracts and CI have no changes.

## Local verification

Windows 11 / PowerShell, Python 3.14.6 stdlib, codex-cli 0.164.0.
Tester wrote only temporary scripts/output under backend/build/agent-workflow-test,
released the write lease before primary corrections; no concurrent writers.

- Command: py -3.14 E:/cv-compass/backend/build/agent-workflow-test/check.py.
  PASS: 35 changed/new files, 30 Markdown files, 147 relative links; zero missing targets/anchors/ignored targets. Four TOML definitions and 30/30 ignore probes pass.
- Four TOML files parse with required/exact project fields and role names.
  Official source/schema review: [workflow sources](../../AGENT_WORKFLOW.md).
- Initial Tester found three ignore gaps: nested docs secrets/credentials and
  arbitrary agent runtime TOML. Primary restored final sensitive exclusions and
  restricted the agent allowlist to the three approved definitions.
- Command: git diff --check. PASS; LF-to-CRLF checkout-policy notices only.
  No EOL/config normalization of canonical artifacts.
- Frozen CI/source/build/dependency path diff: empty. Existing three CI workflows
  remain unchanged. Application tests/formatters: N/A, docs/config-only changes.
- Command: codex app-server generate-json-schema --out <local ignored output>.
  Exit 0. Generated protocol contained no custom-agent listing method. Native role
  discovery/dispatch: NOT VERIFIED; no model task launched using the new roles.
- Codex strict startup configuration parse: exit 0, no unknown-field diagnostics; resolved output withheld. An earlier strict features-list attempt was unsupported (exit 1), so it was replaced by the supported startup parser. Native dispatch remains unverified.
- No gitleaks executable is available: full secret scanner NOT VERIFIED.
  Manual intended-file review and ignore probes do not claim equivalent coverage.

## Review and delivery

Cycle 1 read-only review found R1 (High): a newly shareable local design draft
contained client-derived material, and R2 (Medium): the database draft retained an
obsolete live roadmap. R1 is fixed by keeping the original ignored, publishing a
content-free reference and changing inbound links. R2 is fixed by splitting the
obsolete section into explicitly historical planning and updating live references.
Cycle 2 Tester re-verification PASS: R1 original draft remains ignored and absent
from intended/staged files; the safe reference is shareable. R2 historical planning
and corrected live-reference links pass the complete link/config/ignore/scope checks.
The original private draft was not read, scanned, copied or hashed in this recheck.
Cycle 2 read-only review confirmed R1/R2 fixed with no blocking findings. Staging then exposed four legacy Markdown hard-break lines as trailing whitespace and an extra archive EOF newline; these were corrected. Final staged verification and cycle 3 review are required before publishing.
This is the pre-publish evidence record; exact delivery commit/status is in the PR.
CI is NOT VERIFIED until the pushed source has actual successful runs. The PR
will remain draft while native runtime validation or required CI is unverified.
No application Active/completed milestone, production readiness or OCR accuracy
acceptance is inferred. No merge/deploy/GitHub settings changes.

## Open Questions

No new application/design decision is requested by this task. Existing missing
legacy POC contract/milestone/local handoff documents remain documented gaps;
integration changes must reconcile approval/contracts before depending on them.
Native custom-agent discovery requires a new trusted Codex session that loads
the project definitions; this chat cannot claim it hot-reloaded them.


## Follow-up evidence — 2026-10-31

Historical evidence for the prior follow-up at
fcd986783e2176a98af4b51a995212c5e402a181. The sensitive-operation wording tested
in that iteration is superseded by the second bounded revision below; it is not
current authority or verification of these new edits.

Fresh authorization and acceptance criteria: [spec follow-up](spec.md#follow-up-authorization--2026-10-31).
Starting inspected local/PR head: f0c4049a28da23f79ffcd6c965b048dcba3cc8e3.
PR base: 81a8a6220e34871ea9fc68aa8e65d19785d0eff9. This is a new maximum-three-cycle
iteration; the initial delivery's three passes do not validate these edits.

Implementation scope: synchronized test ownership and scoped CI/sensitive-operation
authorization, canonical tracked .github PR template and active links. Implementer
edits rules/docs/TOML only; primary assigns exclusive handoffs. No application,
dependency, build/formatter, CI workflow, GitHub settings, production, release,
merge, Ready transition, new archive or milestone-status changes are authorized.

Fresh cycle 1 independent Tester, Windows/PowerShell, Python 3.14.6 stdlib and
codex-cli 0.164.0; exclusive lease for temporary verification outputs and this
follow-up section only. No application checks are applicable to docs/config edits.

- Command: py -3.14 E:/cv-compass/backend/build/agent-workflow-followup/check.py.
  PASS on the complete safe PR payload: 36 files, 31 Markdown files, 150 relative
  links, zero missing targets/anchors/ignored targets, four TOML definitions and
  31/31 ignore probes. The follow-up changes 17 paths. Initial temporary phrase
  checking was line-wrap-sensitive; normalizing whitespace corrected the helper,
  and the same acceptance assertions passed without production changes.
- Independent ownership/authorization checks PASS: Implementer accompanying tests,
  helpers and synthetic fixtures; independent Tester acceptance/missing tests with
  no production fixes; read-only Reviewer; primary assigns one repair owner/write
  lease; no weakened assertions/checks/coverage; explicitly scoped CI/hooks.
  That check also covered the now-superseded sensitive-operation policy; it does
  not establish current permission for human-only operations.
- Canonical .github/pull_request_template.md is trackable, with the former docs
  form a single compatibility pointer. Private original M10.1 draft remains
  ignored and absent from intended/index files; it was not read/scanned/copied/
  hashed. Application/dependency/build/formatter/CI diff against PR base is empty.
- Commands git diff --check and git diff --cached --check: PASS. At this check the
  index is empty; untracked-template whitespace was separately checked and is
  clean. Template staging/full staged-payload parity still require primary staging
  and final read-only review; empty-index PASS is not staged-template evidence.
- Native attempt: codex -a never exec --ephemeral --sandbox read-only --cd
  E:/cv-compass --json <bounded custom-role smoke prompt>; exit 0 in 40.25 seconds.
  Actual JSONL has three wait events with empty receiver identities and no spawn
  event proving the project definitions. The final response is
  NATIVE_DISPATCH_NOT_VERIFIED. Textual claims of role tokens are not dispatch
  proof. No shell/file/application/publishing work was requested in that smoke.
  Next action: a trusted supported session must expose actual named-role spawn
  events for all three project definitions; no trust/model/settings bypass used.
- Secret scanner: Get-Command gitleaks,trufflehog found neither; command-v probes
  in existing WSL/default and CVCompass-Ubuntu-24.04 returned exit 1 with no scanner.
  Full secret scan NOT VERIFIED; no tools installed. Next action: use an approved
  existing/provisioned scanner on the safe Git payload, with redacted output.
- Safe file SHA256 fingerprints and fresh results are in ignored
  backend/build/agent-workflow-followup/results.json; bounded native JSONL/result
  is retained alongside it. This record identifies the starting commit plus exact
  current payload; it does not claim a future commit hash or old-delivery PASS.

Cycle 1 independent read-only review and final staged verification are pending.
CI for the new follow-up head is NOT VERIFIED until it is pushed and actual runs
are inspected. Native dispatch/full secret scan remain NOT VERIFIED as above.
Keep Draft; no completion/merge-readiness or application milestone claim.


## Second bounded revision — phase 1 evidence

Approval: [second revision spec](spec.md#second-bounded-revision-authorization--2026-10-31).
Inspected starting local/PR head: fcd986783e2176a98af4b51a995212c5e402a181.
PR base: 81a8a6220e34871ea9fc68aa8e65d19785d0eff9. The fresh budget is at most
three total fix–test–review cycles including the later translation phase.

Phase 1 implementation restores human-only operations regardless of task,
keeps CI/security-hook changes conditional on a spec with exact paths/intent,
and centralizes full test-ownership prose in root Execution and delivery.
Component duplicates are removed; workflow role table remains, and routing is
production defects to Implementer, defects in Tester-added tests to Tester.
Policy/verification/templates point to root; each agent TOML has one ownership
sentence and its role-specific constraints. Earlier permissive statements above
are superseded historical records, not current authority.

Fresh phase 1 cycle 1 independent Tester checks: PASS, Windows/PowerShell with
Python 3.14.6 stdlib. Command:
py -3.14 E:/cv-compass/backend/build/agent-workflow-revision2/check_phase1.py.

- Whole tracked Markdown corpus: 69 files and 228 relative links, zero missing
  targets, heading anchors or ignored/untracked link targets. Full PR payload:
  36 paths; this phase changes 14. Four TOML files parse; 31/31 ignore probes pass.
- Human-only operations remain never-for-agents regardless of task; scoped CI/
  security-hook changes require exact spec paths/intent and cannot weaken checks.
  Active wording scan outside docs/history found no affirmative conditional
  permission. Earlier spec/evidence wording is explicitly superseded history.
- Full ownership prose is in root Execution and delivery; component duplicates
  are absent with clean EOF, policy/verification/PR form reference root, workflow
  retains its role table and correct defect routing, and each changed agent TOML
  has one ownership sentence plus role-specific constraints.
- Application/dependency/build/formatter/canonical JSON/CI workflow diff against
  PR base is empty. Canonical PR template is tracked; private original M10.1 is
  ignored and absent from intended/index files, and was not read/scanned/hashed.
- git diff --check and git diff --cached --check: PASS. Index is empty at this
  check, so final staged-source parity remains a primary pre-review check.
  Explicit new-file whitespace scan found no issues. A temporary classifier was
  corrected to recognize the approved PR template as outside frozen CI paths;
  no source or requirement was changed for that helper correction.
- Translation inventory is prepared, not executed: 23 English Markdown paths
  plus description/developer_instructions in three agent TOMLs. Eight safe explicit
  Markdown exclusions have working-byte SHA256 snapshots: five docs/history files,
  backend/HISTORICAL_VERIFICATION.md, POC database draft and mostly Vietnamese/mixed
  backend README. Preserve excluded bytes, paths/URLs, commands/flags/code fences,
  TOML keys/name, role names, status labels and technical terms in phase 2.
  Root-only language policy is the sole approved semantic change during translation;
  all other binding rules require a source-to-target equivalence table.
- Fresh results/file fingerprints and the safe excluded-byte inventory are in
  ignored backend/build/agent-workflow-revision2/results.json and
  translation-inventory.json. They exclude the private original and do not claim
  a future English-checkpoint commit hash.

Read-only Reviewer cycle 1 is pending before the English-only checkpoint commit.
No translation, application/dependency/build/formatter/CI workflow or human-only
operation is performed by this phase. Earlier native/full scanner attempts remain
NOT VERIFIED; neither probe was repeated or relabeled PASS. CI for the future
new head remains NOT VERIFIED. Keep Draft and use at most three total cycles for
this revision including translation; the English checkpoint must precede phase 2.
