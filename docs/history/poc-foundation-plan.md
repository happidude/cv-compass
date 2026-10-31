# Historical POC foundation plan

> Extracted from the database-foundation draft after reference review.
> All “Current”, “next”, approval and checkbox statements below describe the
> original plan only. They do not establish current status or permission.
> See [roadmap](../roadmap.md) and [foundation draft](../POC_CV_INGESTION_DATABASE_FOUNDATION.md).

## 7. Implementation roadmap

### Current developer-approved roadmap (2026-10-17)

| Milestone | Approved boundary |
|---|---|
| M4 — Single CV Upload | PR #5; standalone PDF upload and metadata lookup |
| M5 — Batch Management | PR #6; independent batch ingestion, per-file results and batch queries |
| M6 — OCR Worker Integration | Next milestone; detailed scope and contracts require approval before implementation |

M4 originally included both upload features. The developer deliberately split
Single CV Upload and Batch Management to implement, review and test them
independently. This approved scope change is not scope deviation or a workflow
violation. No authorization to start M6 or merge PR #6 is implied.

For M5, batch counters describe successful/failed file ingestion, and no candidate,
processing job or extraction is created. The full POC OCR flow elsewhere in this
document is future architecture, not the approved M5 implementation scope.

### Historical proposed stages (reference only)

The A–D checklist below records the original database-foundation proposal. It does
not override the approved numbered milestones or authorize future work.

### Milestone A — Database foundation (original assignment)

- [ ] Inspect existing Gradle, Spring Boot and Flyway setup.
- [ ] Create Flyway migration for the five tables.
- [ ] Add PK, FK, CHECK constraints and required indexes.
- [ ] Validate migration on a fresh PostgreSQL database.
- [ ] Confirm uppercase quoted identifiers and future JPA naming implications.
- [ ] Stop for review before implementing APIs or OCR.

### Milestone B — Upload pipeline

- [ ] Batch upload API, maximum 10 PDFs per request.
- [ ] File validation, SHA-256 hashing and server-controlled storage.
- [ ] Batch/document/job creation and error handling.

### Milestone C — Python worker and extraction

- [ ] Native PDF text extraction, then OCR fallback.
- [ ] Persist raw text and structured JSONB.
- [ ] Job status, retry and basic observability.

### Milestone D — Test with 100 sample CVs

- [ ] Include native-text PDFs, scanned PDFs, mixed Vietnamese/English, multiple pages, duplicate files and malformed PDFs.
- [ ] Record success/failure rates, processing times, OCR quality and resource usage.
- [ ] Review candidate identity ambiguities and extraction errors before production schema expansion.
