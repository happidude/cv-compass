# <Task or milestone> specification

## Approval
- Approval source, developer/date, exact approved scope:
- Base commit, branch/worktree:
- Status evidence (unconfirmed unless explicitly established):

## Goal and acceptance criteria
- Goal:
- Observable criteria, including edge/failure cases:
- Out of scope:

## Impact and ownership
- Allowed files/modules; Implementer production and accompanying test/helper/synthetic-fixture ownership:
- Tester independent acceptance verification and missing-test ownership (no production edits):
- Primary-assigned repair owner, exclusive write lease and handoff:
- Assertions/checks/coverage preserved by every role:
- API/DB/security/dependency/privacy/architecture impact (none or approved decision):
- Open decisions and independent work possible:

## Verification plan
- Failing acceptance/regression test before behavior change:
- Affected checks and required full gates:
- Prerequisites, synthetic fixtures, isolated schema/storage:
- Docs/config-only checks and application-suite N/A rationale if applicable:
- Expected reports/warnings; maximum three fix–test–review cycles:
- Publishing authorization and PR Draft/Ready constraints:
- CI/security-hook edits: explicit approved files/intent or none; no weakening checks:
- Separately assigned/authorized sensitive operations (settings/rulesets/secrets, production, release, merge) or none:
