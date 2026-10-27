# M10.2 — Mapping foundation

Operational corrections and current property limits: [MAPPING_OPERATIONS.md](MAPPING_OPERATIONS.md).
The fixed values below describe original M10.2 defaults; the operations document
defines configurable values and restart behavior after the foundation correction.

Base: `origin/main d0b7f81d02e2f5910aeea356c2322f91cca7e5bb`.
Design: `E:\cv-compass\docs\CV_Compass_M10_1_Design.md` (local ignored document,
26 October 2026), read alongside the explicit M10.2 request. Initial discovery
missed this ignored document; a direct filesystem search found it before publishing.

## Boundaries and operation

`mapping` depends only on `ingestion::ocr-input` and shared safe operational logging
in `web`. The read-only named interface returns a fixed extraction ID, its original
page numbers and unchanged page raw text. It never selects the newest extraction.
No OCR endpoint/protocol, PDF, candidate, document/job status or extraction row is
modified by mapping. V7 is additive; V1–V6 and canonical OCR artifacts are unchanged.

The worker is an in-process Java CPU worker, separate from the Python OCR worker.
No new network worker API, service, authentication surface, script/SQL execution,
LLM or external processing is introduced. Register/activate/read-result services
are internal Java seams. No review UI or broad auto-accept is implemented.

Mapping is disabled unless `cv.mapping.enabled=true`. A trusted operator can supply
an external JSON definition using `cv.mapping.definition-file` and a new
`cv.mapping.revision`, then restart the application without rebuilding/deploying code.
Example JSON is in `src/test/resources/mapping/synthetic-definition.json`.
The file is bounded to 64 KiB and validated before activation. Startup failures
emit only a fixed message, without parser/SQL errors or file contents.
The definition includes `schemaVersion=1` and `engineCompatibility=literal-mapping/1.0`.
Only STRING/PAGE with FIRST_LINE (no argument) and case-sensitive LITERAL
(nonblank argument of at most 256 code points) are supported. There are at most
32 unique fields, named by `[a-z][a-z0-9_]{0,63}`. No arbitrary regex is accepted.
New data types, scopes or extractors require a code change and engine-version decision.

Configurations retain canonical SHA-256 and DRAFT/TESTED/ACTIVE validation state.
TESTED means validated against the supported schema/capabilities, not CV accuracy.
Activated revisions cannot be updated/deleted, enforced by a PostgreSQL trigger.
Registering the same revision with equal JSON is idempotent; different JSON
conflicts. Activation validates the definition and atomically updates settings.
Jobs pin the immutable config row/revision and `literal-mapping/1.0`.
Existing pending jobs retain their selected revision when the active config changes.

## Durable queue and recovery

A scanner page (1–100 extraction IDs), inserts and cursor advance commit atomically.
Each sweep captures and persists an upper bound, so new arrivals cannot extend a
sweep forever and starve reconciliation.
The settings row serializes scanners/activation. A full sweep always returns to
zero, so reconciliation includes IDs reserved earlier but committed later.
There is no permanent high-water exclusion. Restarts use the stored cursor.
The unique key is (extraction ID, config ID, engine version). Repeated sweeps do not
create duplicate jobs. Reconciliation has eventual sweep latency and O(N) reads;
large-scale throughput has not been benchmarked.

Claim uses `FOR UPDATE SKIP LOCKED` in a short transaction. It commits a random UUID
fencing token, attempt count and a 120-second DB-time lease before processing.
Processing and authoritative result validation occur outside the write transaction.
The first read pins a canonical input SHA-256 in a separate short, fenced
transaction. Retries must match that fingerprint; source changes fail explicitly.
The hash includes document/extraction IDs, schema/status, OCR profile revision,
page numbers/status/raw text and source warnings. The lease is also the fixed
hard deadline; this worker does not renew either.
Completion locks the job and checks current token/status/lease using DB wall time
after acquiring the lock. It inserts the result and completes the job atomically,
then checks the lease again before returning. A stale result rolls back both writes.
The reaper takes the same job row lock and cannot race past a completion.
Leases are not extended; bounded CPU work must fit 120 seconds.

Only known transient/recoverable database failures and expired leases retry after
30 seconds, at most three claims. Schema/config/input/rule and other unexpected
runtime failures are terminal; there is no blanket RuntimeException retry.
After exhaustion the job is FAILED with a fixed safe code. No OCR retry/status
is affected. Job attempt count and last error are retained; this foundation does not
provide a separate per-attempt audit history or manual retry/reset API.

Accepted replay with the same token and canonical result SHA-256 is idempotent,
including after lease expiry. A different result conflicts. Old/reclaimed tokens
cannot replace a later attempt's result. Completion reconstructs job/config identity
from the database rather than trusting a caller-modified claim.
Results are returned only through the internal service.

## Input and evidence semantics

OCR schema 1.0 and 2.0 are readable. Only pages with status `ok` participate.
Success and partial map to NEEDS_REVIEW; partial/incomplete pages retain
`incompleteSource=true`. Failed/unrecognized OCR is UNSUPPORTED_INPUT/OCR_UNUSABLE.
A source with no ok pages is NO_OK_PAGES; legacy whole-text data is LEGACY_UNPAGED.
Unsupported inputs have no fabricated page numbers or field evidence.
Missing field matches are omitted. Fields take the first matching ok page.

Result schema 1 stores extraction/document IDs, OCR schema/profile, input SHA-256,
config ID/revision, engine version,
quality, incomplete-source flag, rejection reason and field/evidence values.
Evidence is (extractionId, original page number, start, end, original text), with
[start,end) offsets counted in Unicode code points in that page's raw_text.
Substring validation uses code-point-aware conversion and exact string equality;
no Unicode normalization or grapheme-count conversion is performed.
There is no PDF-coordinate highlighting promise.
Result rows are immutable. Evidence values have a separate 4,096-code-point cap
and canonical result JSON has a 1 MiB cap; limits fail explicitly without truncation.
Source admission retains the OCR limits of 50 pages/50,000 code points per page
and 500,000 per document.

Safe lifecycle events contain only IDs and fixed codes. Scheduler failures never
log throwable messages/stacks, since SQL/parser exceptions can include source text.
Synthetic tests use invented headings/skills only and never invoke real PDFs,
external AI or candidate creation.

## Verification

Use Java 25, PostgreSQL 17 and the existing per-class isolated schema/storage helper.
The test database must be separate from the development database.
From backend:

```sh
./gradlew test --tests '*Mapping*Tests' --tests '*ModulithArchitectureTests' --tests '*BackendPackageStructureTests' --rerun-tasks
./gradlew spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
```

The final evidence/report is in M10_2_REPORT.md. Existing OCR production and
accuracy gates remain unchanged; synthetic technical success establishes no
real-CV extraction accuracy.

## Deliberate foundation limits relative to the broader M10.1 design

The explicit M10.2 request permits a minimal synthetic slice. STRING/PAGE and two
literal/source-line rules are implemented; semantic candidate/experience fields,
section splitting, taxonomy, normalized text and multi-span experience assembly
remain M10.3 work. No normalized offsets are generated, so all emitted spans are
verified directly against raw_text. The supported engine is fixed and incompatible
config revisions are rejected before activation.
FIFO ready ordering is used, with bounded sweeps and finite retries; no separate
priority/backfill operator API is included. State names PENDING/PROCESSING/COMPLETED
describe execution only. Retry wait is represented by PENDING + NEXT_ATTEMPT_AT.
The in-process worker has one scheduled task per application instance; database
fencing also supports multiple instances safely. No global single-worker cap or
representative throughput/lease benchmark is claimed.
