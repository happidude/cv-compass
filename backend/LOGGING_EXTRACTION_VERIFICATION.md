# Logging and native extraction — local evidence (2026-10-25)

Approved scope: shared backend/worker operational logging, native extraction
quality, synthetic ground truth/regression tests, configuration/docs and measured
worker image rebuild. Developer explicitly authorized commit/push/PR, no merge,
and waived the live UI → worker → DB → UI check. No frontend/runtime data changed.

Base `f2b7dd8c559bd5319870fc5c0de81ee94440b158`; branch
`codex/logging-native-extraction`. Worktree:
`C:\Users\NguyenVanHoang\.codex\worktrees\logging-native-extraction\cv-compass`.
Original main checkout's uncommitted application-dev.yaml was preserved.
Tests ran on the uncommitted final source identified below; publishing its exact
content does not imply the old base commit contained these changes.

## Exact source and environment

* Backend source/test/YAML/build settings manifest SHA256:
  `a5d21ba26d54a60ae82d9703c6350a3b19ee0353753fd8dbd2e079a421ae0446`.
  Local file `backend/build/source-evidence.json`, generated from sorted Java/YAML
  source and Gradle build/settings file SHA256s (no private config/environment).
* Worker image source manifest SHA256:
  `aa1db6091c1e3ca65cd0a4576ae0d9ecacc2300d8805906ee3d6b180fac6d194`.
  Local `worker/build/logging-quality-4/source-manifest.json`; source-version records
  the base commit plus `dirty=true`, not a fabricated final revision. Source was
  checked unchanged against this manifest after build and full verification.
* Backend: Microsoft JDK25.0.4.1, Windows/PowerShell, repository Gradle9.7.1 wrapper,
  PostgreSQL17.11 (existing provisioned service). Private `TEST_DB_PASSWORD` was
  loaded from the existing ignored config without printing it. Existing fixture
  helpers allocate/clean only their owned random schema/storage; runtime schema
  was not used as a test schema.
* Worker: Ubuntu24.04/WSL2, CPython3.14.8, Docker29.6.1, fresh hash-locked host venv,
  pdfplumber0.11.10. Models and engines remain pinned; the dependency image was
  inspected by immutable ID, not replaced or upgraded.

## Commands and results

From this worktree's backend/, with private TEST_DB_* and JAVA_HOME set:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=<existing-short-owned-JVM-tmpdir> -Duser.timezone=UTC'
.\gradlew.bat check --rerun-tasks
```

Actual short tmpdir: `E:\cv-compass\backend\build\tmp\logging-jvm` (generated files
only). Initial run without it hit the documented Windows JDK loopback limitation;
rerun with it executed successfully. Latest full check: **323 tests, 0 failures,
0 errors, 0 skipped** from fresh JUnit XML. `compileJava`, `processResources`,
`compileTestJava`, `test` all executed; `processTestResources NO-SOURCE` is applicable
because there are no test resources. Includes Modulith verification, isolated
PostgreSQL/Flyway/JPA/ingestion state tests, logging JSON/context/privacy and HTTP
correlation tests. Fresh terminal log: `backend/build/logging-full-check.log`.

From worker/ in the provisioned WSL distribution:

```sh
PYTHONPATH=src:tests <python3.14.8> -m unittest test_native_quality test_observability test_pull -v
<python3.14.8> spikes/m6_2_hardening/rebuild_worker.py \
  --baseline <existing-build/local-worker> --output build/logging-quality-4
<python3.14.8> spikes/m6_2_hardening/verify_baseline.py --build build/logging-quality-4
PYTHONPATH=src <python3.14.8> tests/native_quality.py --output build/quality-after.json
```

Provisioned runtime used `/opt/cvcompass-m62/venv/bin/python`; immutable baseline
artifacts came from `/mnt/e/cv-compass/worker/build/local-worker`. Full verifier
installed its own fresh host venv with `--require-hashes`, ran pip check, syntax
compileall with `-W error`, then `tests/run_checks.py --xml <verification/tests.xml>`
with real native/scanned/multilingual PDFs and Docker enforcement/resource tests.
Latest result: **165 tests, 0 failures, 0 errors, 0 skipped**, exit0. Targeted subset
was **52/52**. Cleanup owner `253434789af94ef4b40329a7b18188ef` recorded `leftovers=[]`.
Evidence: `worker/build/logging-quality-4/verification/{tests.xml,full-check.log,
pip-check.log,syntax.log,cleanup.json,environment.json,evidence.sha256}`.

Measured image:
`sha256:556476681929b1bd08c5cb8739a2889cafded1f5d39d2594efaa35d0e05d0686`.
Runtime image:
`sha256:e1e9afccdd9a63d021de3ec9d16edc547380c8342156fa731bbea01a30c8b0fd`.
Dependency image:
`sha256:af84bafd9d1b29a41f825106a24b0aca425832a4491c73f88a10567a930ce6a8`.
Builds1/2 failed on WSL Git metadata and Dockerfile FROM syntax respectively; they
were corrected without changing dependency pins. Build3 passed 161 tests but
predates final privacy fixes and is not the final evidence. Build4 is authoritative.

## Review and warning disposition

One coding agent used Developer/Reviewer roles, two review cycles. Scope review
covered logging timing, transaction rollback, safe error handling, MDC/ContextVar
cleanup, explicit HTTP/thread propagation, exact outcome replay, CLI/output
compatibility, resource/ownership caps, geometry/token accounting and tests.
Schema changes, candidate logic, frontend/search and new infrastructure: not applicable.

| ID | Severity | Location of fix | Initial problem and evidence | Disposition |
|---|---|---|---|---|
| R1-01 | Medium | backend/src/main/java/com/cvcompass/backend/web/OperationalLog.java:30 | A pre-existing MDC field could enter a structured event. Clear/filter per event and restore outer MDC; test injects rawText/stale workerId. | Fixed, local verified |
| R1-02 | Medium | worker/src/cv_compass_worker/pull.py:214 | Recovery event lacked job/document/attempt scope. Scope replay/ack/cleanup without replaying OCR or changing retained bytes. Existing recovery tests and new acknowledgement test pass. | Fixed, local verified |
| R1-03 | High | worker/src/cv_compass_worker/observability.py:107, child.py main | Library warning/exception messages could reach child diagnostics. Preserve warning severity using fixed safe diagnostic records; no raw msg/args/cause/traceback. Synthetic private-message tests pass. | Fixed, local verified |
| R1-04 | Medium | backend/src/main/resources/application.yaml:22 | Map binding stripped the special @ in timestamp rename. Real Boot encoder test reproduced missing timestamp; bracketed key preserves it. | Fixed, local verified |

No unresolved Critical/High/Medium or Low code findings in final review.
No Java compiler deprecation warnings in final check; lint remains enabled.
Fixture FontBBox warning was fixed with a proper descriptor, not silenced. Worker
pip check: no broken requirements; compileall with warnings-as-errors passed.
Git emits LF→CRLF checkout notices for historically CRLF Python blobs under Windows
autocrlf. Modified Python files were saved LF; no warning suppression or broad
renormalization was introduced. `git diff --check` passed. Gradle's configuration
cache suggestion is tooling advice, not a compiler/test warning. Inherited unresolved
GCC/Tesseract production warning remains explicitly deferred and blocking production;
reusing pinned binaries does not fix or conceal it.

## Quality, deployment and exclusions

See [native quality results](../worker/design/NATIVE_TEXT_QUALITY.md): exact fixture
matches **4/8 → 8/8**, tight-gap whitespace **0.125 → 1.0**, unequal-column order
**0.918182 → 1.0**, no corpus metric regression. Keep uncertainty/CID diagnostics.
This is a small synthetic corpus, not a real-CV accuracy claim. No personal CV read,
no broad storage scan, backfill, old extraction overwrite or runtime cleanup.

See [logging convention/config/restart](LOGGING.md). A new runtime profile must use
the actual published source commit, measured child image ID and recomputed pipeline
fingerprint; no registry/pull config was changed and no service restarted in this
task. A tested source manifest/image can be bound to the publication commit after
checking its source content is identical; the old base commit must not be used as
the new worker source pin. Keep pending jobs' immutable snapshots and private spool.

Local automated quality gate: PASS. Live E2E: **NOT VERIFIED, explicitly waived**.
Frontend checks: **NOT APPLICABLE, source unchanged**. CI: **NOT VERIFIED at this
local evidence point**, report separately for the published revision. Production:
**BLOCKED_GCC_WARNING_UNRESOLVED**. PR review does not authorize merge.
