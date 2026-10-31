# <Task or milestone> verification

## Source and scope
- Spec/approval:
- Base and exact tested/reviewed commit or payload fingerprints:
- Changed files and acceptance criterion → evidence mapping:
- Environment/tool versions, isolated resources (no credentials/paths containing CV data):

## Executed checks
| Command and working directory | Source | Result | Tests / failures / errors / skipped | Evidence |
| --- | --- | --- | --- | --- |
| <actual command> | <commit/fingerprint> | PASS / FAIL / NOT VERIFIED / N/A | <fresh counts or N/A> | <log/report, no private data> |

## Review
- Cycle count (1–3), Reviewer, base/source, actual diff including new files:
- Findings: ID/severity/file:line/impact/evidence/fix/disposition:
- Warnings fixed/remaining, cause/impact/deferral:
- Acceptance criteria met or unresolved:

## Delivery and limitations
- Local gate:
- CI result and exact new head (separate from local and prior deliveries):
- Native custom-agent dispatch command/events/result (parser PASS is separate):
- Secret scanner command/coverage/result or NOT VERIFIED with next action:
- Canonical PR template tracked; private M10.1 draft ignored and absent from commit:
- Application/dependency/build/formatter/CI unchanged against PR base where required:
- Missing verification, reason and next action:
- Production/accuracy/real-data acceptance (never implied by technical PASS):
- PR/commit link:
- BLOCKED if unresolved after cycle 3; no self-acceptance:

## Open Questions
- Outside-spec issue: context, options, recommendation, dependent work left undone.
