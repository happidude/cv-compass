# Documentation index

Shared Markdown is versioned. Secrets, CVs, runtime captures and generated reports
stay ignored. Classification is by content; names alone never justify moving files.

| Class | Sources | Use |
| --- | --- | --- |
| Rules | [Root](../AGENTS.md), component AGENTS, [policy](AGENT_POLICY.md), [workflow](AGENT_WORKFLOW.md) | Current scope/permission/role requirements |
| Conventions | [Convention index](conventions/README.md), backend/worker/frontend conventions | Detailed implementation rules |
| Contracts | [Contract map](contracts/README.md); worker/design canonical JSON; backend MAPPING/experience/M11 | Consult before API/data integration; do not move runtime artifacts |
| Operations | Component READMEs; backend [local OCR](../backend/LOCAL_OCR.md), [raw-text local](../backend/RAW_TEXT_V2_LOCAL.md), [logging](../backend/LOGGING.md), [mapping operations](../backend/MAPPING_OPERATIONS.md); worker [handoff](../worker/design/M6_BASELINE_HANDOFF.md) | Run/recovery procedures; historical caveats stay explicit |
| Milestone/task specs | [Registry](milestones/README.md), [roadmap](roadmap.md), [POC foundation draft](POC_CV_INGESTION_DATABASE_FOUNDATION.md), [sanitized mapping reference](specs/mapping-design-reference.md); backend MAPPING/M11; worker M6_1_OCR_WORKER_DESIGN/M7_INTEGRATION/OCR_V2_RAW_TEXT | Existing status labels retained; no inferred Active/completion |
| Verification | [Procedure](VERIFICATION.md), backend *_REPORT / *_VERIFICATION and FOUNDATION_CONFIGURATION_REPORT; frontend VERIFICATION; worker M6_2_VERIFICATION/M6_2_REVIEW/M6_2_HARDENING_REPORT/M6_2_HARDENING_REVIEW and PR9 records; [format report](conventions/REFACTOR_REPORT.md) | Commit-scoped evidence, not current acceptance |
| History | [History index](history/README.md); worker M6_1_REVIEW/M6_1_STACK_SPIKE/M6_2_GCC_INVESTIGATION/M6_2_HARDENING_DECISIONS and superseded banners; backend extracted historical evidence | Retained provenance, never current task permission |
| Future/backlog | worker/design/OCR_QUALITY_BACKLOG.md, NATIVE_TEXT_QUALITY.md | Proposals requiring explicit scope |
| Templates | [Spec](templates/spec.md), [verification](templates/verification.md), [PR](templates/PR.md) | Reusable starting points |

Mixed documents are split by inspected sections when current guidance is changed:
Git procedure loses the obsolete milestone/worktree plan; convention index loses
survey/application history; backend guide loses standalone historical evidence and
the obsolete roadmap. Their live contract/operation sections stay reachable.
MAPPING/M11 and worker designs remain at existing paths because their contract
and runtime references are still used; classify their sections by the table above,
and do not archive the whole file.

## Reference audit and retention

On 2026-10-31, source/tool paths and Markdown references were inspected before
splitting. No code depends on the superseded single-agent prose or Git plan.
Their content is preserved under history and the old workflow path redirects.
Convention detail files and runtime design/schema assets remain in place.
Old reports with unresolved GCC/accuracy/cleanup evidence remain reachable rather
than being moved simply because an M6 name looks old.

Absent legacy POC milestone/contract and local-only M7/M8 handoff files are listed
in the [roadmap](roadmap.md), [contract map](contracts/README.md) and extracted
history as gaps. Do not invent or silently certify them. Existing XLSX/PNG/SVG/DOT
design artifacts and docs/api-tests captures are retained locally, not newly
published: contents/privacy/authority were not verified in this task.

The local client-derived M10.1 draft stays ignored; only the sanitized reference
above is shared. No CV-derived excerpts or identifying source metadata are
published by this documentation migration.
