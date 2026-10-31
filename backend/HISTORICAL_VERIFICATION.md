# Backend historical verification and legacy roadmap

> Extracted from inspected README sections on 2026-10-31. Original commit-scoped
> results/status/authorization below are historical, never current acceptance.
> Missing local-only docs named here were not recreated. Current procedure:
> [verification](../docs/VERIFICATION.md); [roadmap](../docs/roadmap.md).

CI expiry/submission race fix (2026-10-23), review base `2445e3c`: the reaper uses
SKIP LOCKED, so a submission holding the job lock can defer expiry to the next
scheduled sweep. A controlled PostgreSQL lock reproduced the previous test's
expected PENDING / actual PROCESSING failure. The concurrency test now models that
next sweep, retaining the final PENDING/no-extraction assertions. A deterministic
regression verifies the locked job is skipped, other jobs expire, stale submission
cannot write, and the next sweep marks exactly one attempt EXPIRED. Runtime SQL,
services, worker and constraints are unchanged.

From backend/ on the same Windows JDK25.0.4.1/Gradle9.7.1/PostgreSQL17.11 setup:
`./gradlew.bat test --tests '*OcrJobIntegrationTests' --rerun-tasks --console=plain --warning-mode=all`
passed **15/0/0/0**; `./gradlew.bat check --rerun-tasks --console=plain --warning-mode=all`
passed **314/0/0/0**, including Modulith, with4 tasks freshly executed. Fresh log,
HTML and39 XML suites inspected; compiler warnings0, unchanged73 expected negative
test WARN/ERROR lines. Owned schemas/temporary storage only. Evidence, including
the initial failed reproduction: build/m8-expiry-race-fix/. Final backend src SHA256:
`0e6ee88785f134d9e98d0bbd92d9064bfb7702b60bcd38148fb6211b161f842c`.
Review:2 passes against2445e3c, no unresolved findings; git diff --check PASS.
Python not rerun (worker unchanged). CI for this fix is separate from local results;
the prior Persistence CI at2445e3c failed. Production/accuracy gates remain unchanged.

Upload response fix at `2445e3c` (2026-10-23), review base `2ed7246`: upload returns the
`DocumentMetadata` already committed by the service without a subsequent query;
201, Location and no-store remain. GET detail retains the M8 processing/OCR fields.
On Windows Microsoft JDK25.0.4.1, Gradle9.7.1 and PostgreSQL17.11, from backend/:

- `./gradlew.bat test --tests '*CvUploadIntegrationTests' --tests '*CvDocumentQueryHttpTests' --rerun-tasks --console=plain --warning-mode=all`: **25/0/0/0** tests/failures/errors/skipped.
- `./gradlew.bat check --rerun-tasks --console=plain --warning-mode=all`: **313/0/0/0**,
  4 tasks freshly executed; Modulith PASS. NO-SOURCE test resources are inapplicable.
  Fresh terminal log, HTML and39 JUnit XML suites inspected. Compiler warnings0;
  73 WARN/ERROR lines are expected negative validation/constraint/failure-path tests.

Tests use TEST_DB_* with a private local credential, per-suite owned schemas and
temporary storage; no dev data/storage. Logs/XML: build/m8-upload-response-fix/.
Final backend src SHA256: `fc81dfb6e988f8f5906f4972a2cbf3c2b2e972444994aecd192146eb3d587ac8`
(calculation below). Review:2 passes against2ed7246, no unresolved findings;
git diff --check PASS. Worker unchanged; Python/Docker/E2E not rerun for this fix.
CI for this revision NOT VERIFIED. Production/accuracy gates below remain unchanged.

Historical M7–M8 local verification (2026-10-23): **technical PASS at `2ed7246`**,
base `363b4c5` plus the previous upload/detail response alignment. That alignment
is superseded by the scoped fix below: upload returns service metadata directly;
expanded processing/OCR fields belong to GET detail. Regression compares shared
metadata exactly and checks independently read processing state separately.

- Windows Microsoft JDK25.0.4.1, Gradle9.7.1, real PostgreSQL17.11: from backend/,
  `./gradlew.bat assemble check --rerun-tasks --console=plain --warning-mode=all`:
  **312 tests / 0 failures / 0 errors / 0 skipped**, 7 tasks freshly executed, JAR
  and architecture/Modulith PASS. Targeted upload/MVC regression:24/0/0/0.
- Ubuntu24.04 WSL, CPython3.14.8, Docker29.6.1 Linux/cgroupv2: existing
  `build_baseline.py --output worker/build/m7-m8-local/baseline` then
  `verify_baseline.py --build worker/build/m7-m8-local/baseline` completed; fresh
  hash-locked venv/pip check/syntax and **147/0/0/0 full tests**, including Docker.
- Real Ubuntu JDK25 backend + PostgreSQL + pull supervisor and run_pull_worker.py
  CLI + source-built Docker OCR: native/scanned/blank synthetic pages persisted
  as JSONB and read through list/detail. Two uploads →2 documents/jobs/attempts/
  extractions. Exact replay leaves counts unchanged; changed bytes→409. No dev
  instance/storage or real CV used; test schemas/private directories removed,
  backend/worker stopped and both run-owned Docker cleanup reports have no leftovers.

Initial check on363b4c5 failed upload/detail equality and one attempt time constraint
after DB clock moved backwards ~0.7s. Response code was fixed; time constraints and
assertions were preserved. The final full check passed; retain clock-regression
evidence and stop for operator investigation if it recurs. Initial E2E harness also
expected immediate claim; bounded normal polling fixed that harness expectation,
without relaxing result/replay/cleanup assertions. All initial logs are retained.

Java compiler/deprecation warnings:0. Runtime build retains43 warning/notice lines,
including upstream Python/pip notices and unresolved Tesseract GCC stringop-overflow;
no suppression or compiler/Docker change. **Production remains BLOCKED_GCC_WARNING_UNRESOLVED;
accuracy NOT_EVALUATED.** CI for the final source is NOT VERIFIED separately.

Historical backend src SHA256 at `2ed7246`:
`8446aa587477dbc9841bec763f73c5a5f9fea64c2c79ab266a9ac753c2b0206b`
(repo-relative backend/src paths + space + lowercase file SHA256, sorted, joined LF,
no terminal LF). Worker image:
`sha256:597c6b0f4caa3cd0a9873476e3d504a8eee28b2b2056d2b30b63437848f88136`;
source-manifest SHA256 `ff7d9d4e93278b2f146872a93de1d45636a4604e89af8d0bbdf4e78187c9a650`.
Builder's WSL Git commit field was null due shared-checkout ownership; Windows Git
HEAD363b4c5 and the unchanged worker source manifest establish the image source.
Local logs/XML/config-free reports: backend/build/m7-m8-local/ and
worker/build/m7-m8-local/baseline/verification/. The local ignored run guide is
docs/M7_M8_LOCAL_RUN_GUIDE.md; it is not force-added. Review:2 bounded passes,
response finding fixed and verified, no unresolved Critical/High/Medium finding;
git diff --check PASS. No merge, UI or next milestone.



M8 disk-import finding fix (base `8403a1b`, 2026-10-23): constructor-injected
BatchProperties caps candidate selection at min(import batch size, batch maximum)
before queueing. Local configuration accepts import=10 / maximum=5; both individual
property constraints remain validated. Synthetic orchestration regression drains
12 files as 5/5/2, with no queued remainder or duplicate on the next tick; a smaller
import size is also respected. A real file/PostgreSQL regression is included but
NOT VERIFIED without TEST_DB_PASSWORD. No retry/schema/OCR/Docker change.

Final local checks, Windows/JDK25.0.4.1/Gradle9.7.1, from backend/: regression
10/0/0/0 tests/failures/errors/skipped; final independent suite including these
regressions 204/0/0/0, JAR assemble PASS (7 tasks freshly executed).
Commands: `test --tests '*DiskImportBatchLimitTests' --tests '*DiskImportServiceTests'
--tests '*DiskImportConfigurationTests' --rerun-tasks --console=plain --warning-mode=all`;
`assemble test` with the independent selection below and the same flags; final
`check --rerun-tasks --console=plain --warning-mode=all`: 314/110/0/0, FAILED
(4 tasks freshly executed). Missing isolated PostgreSQL17 credentials block all
12 failing suites, including the new file/history regression; PostgreSQL/Docker/CI
remain NOT VERIFIED. No compiler/deprecation warnings. Existing expected negative
WARN/ERROR and fixture-cleanup failures remain visible. No generated problems report.
Logs/XML: build/m8-import-{regression,independent-final,full-check}.log and
build/m8-import-evidence/. Source src/ fingerprint using the calculation below:
`2b532c3dbd870bdccaee90d141ff23c137744a224ab9c8c526c1da921b4d39b6`.
Review: 2 bounded passes, no unresolved Critical/High/Medium findings; diff-check
PASS. Gate remains BLOCKED / NOT VERIFIED. Commit/push is the requested source
snapshot; rerun full local check with private TEST_DB_* on stable PostgreSQL17.

Initial M8 verification (`8403a1b`, 2026-10-23, Windows, Microsoft JDK 25.0.4.1, Gradle 9.7.1):
`assemble test --rerun-tasks --console=plain --warning-mode=all` with the explicit
independent suite selection: **201 tests / 0 failures / 0 errors / 0 skipped**, JAR
build PASS, including architecture/Modulith. Selection: all `*Tests.java` classes
except `*IntegrationTests` and `BackendApplicationTests`; no assertions were removed
or weakened. `check --rerun-tasks --console=plain --warning-mode=all` freshly ran
compileJava/processResources/compileTestJava/test: **310 / 109 / 0 / 0**, FAILED.
All 11 failing suites lack TEST_DB_PASSWORD; PostgreSQL 17 is not connected or
verified. Four new M8 SQL tests are BLOCKED / NOT VERIFIED. Existing secondary
fixture-cleanup failures remain visible, not converted to skips. No compiler or
deprecation warnings; expected negative-test WARN/ERROR logs remain. Git line-ending
notices reflect existing Windows checkout policy; canonical OCR bytes are unchanged.

Verified backend `src/` SHA256 before/after checks:
`70bba08759b8a0bc7b158c34884eab9b8683365ff6ca980cd896ac98191e914f`.
Fingerprint: sorted relative path + space + lowercase file SHA256, joined by LF,
no terminal LF. Base M7: `5b647bc`. Local log/XML: `build/m8-independent-final.log`,
`build/m8-full-check.log`, `build/m8-evidence/{independent,full}/`. One agent reviewed
the complete M8 staged diff against M7: no Critical/High/Medium findings. Final
evidence/documentation review is cycle 2/3; `git diff --check` passes.
CI for M8 is NOT VERIFIED. Gate is **BLOCKED / NOT VERIFIED**, not complete or ready
for acceptance. Supply private TEST_DB_* for stable PostgreSQL 17 and rerun the full
check using existing isolated schema/storage fixtures. M7 live OCR/Docker and the
GCC/Tesseract production blocker remain unverified/unresolved; worker/Docker are
unchanged. Commit/push is a user-authorized source snapshot, not a quality-gate PASS.



## Phạm vi và roadmap đã điều chỉnh

Quyết định phạm vi được developer phê duyệt (2026-10-17): M4 ban đầu bao gồm cả
Single CV Upload và Batch Management. Developer tách thành hai milestone M4 và M5
độc lập để triển khai, review và kiểm thử từng chức năng riêng biệt. Đây là thay
đổi đã được phê duyệt, không phải sai lệch phạm vi hoặc vi phạm workflow. Bảng
bên dưới thay thế cách đánh số cũ M5 OCR worker / M6 tích hợp Java–Python.
Quyết định này không miễn các yêu cầu workflow khác, không cho phép bắt đầu M6
hoặc merge PR #6.

| Milestone | Phạm vi |
| --- | --- |
| 1 | Lưu trữ dữ liệu trong cơ sở dữ liệu (đã hoàn thành) |
| 2 | Nền tảng ứng dụng backend (đã hoàn thành) |
| 3 | Hạ tầng lưu trữ file dùng chung (thay đổi của giai đoạn này) |
| 4 | Upload một CV — PR #5 |
| 5 | Quản lý batch — PR #6; triển khai, review và kiểm thử độc lập với M4 |
| 6 | Tích hợp OCR Worker — milestone tiếp theo; cần phê duyệt scope chi tiết trước khi triển khai |
| Tương lai | Đánh giá POC — cần phê duyệt riêng số thứ tự, thời điểm và phạm vi |
