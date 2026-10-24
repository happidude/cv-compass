# Local OCR verification — 2026-10-24

Branch `feature/local-ocr-flow`, base `62d1d8307fc5b88f7556b083df111bc85b334f5d`,
worktree `E:/cv-compass-m9`. One agent used Developer/Reviewer roles with three bounded
passes (the final pass checks historical-documentation labeling). Source, new files
and the complete actual diff were reviewed; no unresolved
Critical/High/Medium/Low findings. Git diff --check passes. No merge or UI redesign.

Functional source SHA256:
`98ef4af7c33f7c98394a4f7ab73d4cd8643379b62e1428a247228adb9a9ec588`.
Recipe: unique sorted paths from `git ls-files --cached --others --exclude-standard`
for backend/src, worker/src, worker/tests, frontend/src, frontend/tests, the four
frontend Vite/Playwright config files, test-origins.ts and frontend/.env.example;
hash each file's bytes, join `path + space + lowercase SHA256` with LF and no final
LF, SHA256 the UTF-8 result (240 files). Documentation is outside that fingerprint;
functional source was unchanged after final checks. The commit containing this
record identifies the published revision; local evidence is not a CI result.

## Executed checks on final functional source

| Check | Tests / failures / errors / skipped | Result |
|---|---|---|
| Backend `./gradlew.bat assemble check --rerun-tasks --console=plain --warning-mode=all` | 320 / 0 / 0 / 0, 41 fresh suites | PASS; 7 tasks executed, including compilation, JAR and Modulith/architecture |
| Python `-W error` compileall, pip check, full `tests/run_checks.py --xml ...` with Docker | 150 / 0 / 0 / 0 | PASS; owned Docker cleanup leftovers=[] |
| Frontend `npm run check` | 22 / 0 / 0 / 0 unit/HTTP tests | PASS typecheck, lint, format, production build |
| Fresh unit JUnit run with default+junit reporters | 22 / 0 / 0 / 0 | PASS; XML inspected |
| `npm run test:browser` | 30 / 0 / 0 / 0 | PASS; Chromium, axe/layout, HTTP/polling/upload regressions, dynamic ports |
| `npm exec playwright -- test --config playwright.local.config.ts` | 2 / 0 / 0 / 0 | PASS on final JAR, actual UI + worker + PostgreSQL/storage, mock off |

Commands ran from the corresponding backend/, worker/ or frontend/ directory.
Backend: Windows Microsoft JDK25.0.4.1, Gradle9.7.1, PostgreSQL17.11, UTC; private
TEST_DB_* values. Automated integration tests used and cleaned only their own
temporary schemas/storage, explicitly authorized by the user. They never reset
runtime/public data. Final terminal logs and fresh JUnit XML/HTML were read.
The retained Gradle problems report contains five older test-compilation diagnostics
(incorrect new-test ImportFile/tick calls), fixed before these final runs; its
timestamp predates the fresh successful test report. Compiler/deprecation warnings0.
81 WARN/ERROR lines in test XML are expected rejection/constraint/injected-failure
tests, including the added startup-validation failures; none were suppressed.

Worker: Ubuntu24.04 WSL, existing hash-locked CPython3.14.8 venv, Docker29.6.1 and
provisioned Tesseract/models/font. Full suite used a fresh unique owner UUID and the
existing cleanup tooling. It reused the measured child image
`sha256:4d15e9b9e17c836ea68d22f7eb2318b529f1edf8da39bf8962045c20648a5b4b`;
its saved source manifest still matches the original worker deployment source.
No rebuilt-image or production-readiness claim is made for the new supervisor.

Frontend: Windows Node24.22.0/npm11.19.1 and existing lockfile installation.
Two known Zod/Rollup explanatory-comment annotation warnings and the inherited
FORCE_COLOR/NO_COLOR notice remain visible, as documented by the historical M9
verification. No dependency edits, onwarn filter or warning suppression was used.
The browser test coordinator pins its dynamically allocated ports for its workers;
an initial connection-refused run exposed config being re-evaluated per worker,
which was corrected without changing any UI assertion.

## Actual runtime/E2E

The runtime used the existing database and storage, one backend, existing private
worker config/spool and the current UI. No second backend/database/schema/storage
was created for E2E. Database host was changed to 127.0.0.1 only after the user
confirmed it. The backend bind configuration, pull.toml origin and API_PROXY_TARGET
were aligned; plain HTTP stayed on numeric loopback and WSL/backend health checked.
Frontend VITE_MOCK=false. Importer stayed disabled. No existing CV was read/OCRed.

Both final synthetic PDFs were generated inside the browser test and uploaded via
the real UI, without a manifest: documents18/19, jobs6/7. The worker authenticated,
claimed/downloaded/processed them and the UI polled and rendered exact expected page
text. The scanned-image test additionally asserts page method=ocr. Read-only JDBC
audit confirmed **one job, one attempt and one extraction per document**, actual
PostgreSQL JSONB storage, one page, admission_mode=local and no fabricated fixture_id.
Documents present before this work had zero jobs backfilled. Worker halt=false;
runtime-owner Docker container/volume inventories were both empty after delivery.

Earlier exploratory synthetic uploads (documents13–17) are retained with their
original outcomes for inspection. Document15 is correctly FAILED/NO_USABLE_TEXT:
the first scanned fixture was below the existing >=40-alphanumeric/>=5-word usability
policy. The fixture was expanded, not the policy/assertion weakened; its failed
JSONB result was not overwritten. All retained runtime records/files from this work
are generated synthetic inputs, not real CVs. Original runtime documents are intact.

Synthetic-only restriction was verified independently by the existing real-PG
unknown-hash/size tests and default/config regression tests. Local mode's unit and
real-PG tests verify no-manifest admission, single/batch/disk-ingestion enqueue once,
no extraction fabricated by ingestion, selected-profile authorization and rejection
of incomplete/contradictory/prod configuration. Existing transaction compensation,
auth, lease, fencing, retry, replay and isolation tests remain enabled and pass.

Worker recovery inspected only safe metadata: the old halt retained a generic
code, an unclaimed journal had zero DB attempts and no attempt/outcome directory,
and no supervisor was running. Journal/halt were archived together under exclusive
spool lock; no unacknowledged outcome was erased. Historical exception details are
not available from the generic marker. Runtime DB host/origin inconsistencies and
Windows JDK socket/timezone prerequisites were resolved explicitly, not bypassed.

Ignored local evidence: backend/build/local-flow-check/, worker/build/local-flow-final/
(tests.xml and verification/cleanup.json), frontend/.ci-reports/final-*.log,
final-unit-junit.xml and local-e2e.xml, browser HTML report. Runtime configuration,
tokens, spool, build/log output and user-local config are not committed. The main
checkout's pre-existing application-dev.yaml change remains intact.

CI for this branch/commit is **NOT VERIFIED** separately. Production remains
**BLOCKED_GCC_WARNING_UNRESOLVED**, accuracy **NOT_EVALUATED**. This is a tested
local diagnostic workflow, not production approval or OCR-accuracy evaluation.
