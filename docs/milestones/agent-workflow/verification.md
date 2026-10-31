# Agent workflow maintenance verification

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
