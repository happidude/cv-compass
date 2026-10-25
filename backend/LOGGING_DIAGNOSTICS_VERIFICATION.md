# PR14 logging diagnostics — latest local evidence

Base: `9e214ec9caf36c2a9db11c0a169712b22c30e507`, PR14 head.
Local branch renamed to `fix/logging-diagnostics` following the bug-fix convention.
Developer clarified that successful publication should update the existing remote
head `codex/logging-native-extraction`/PR14 rather than create a second PR.
No commit/push/merge has occurred in this follow-up.

Source fingerprint SHA256:
`ec0cb5412f9524f1d4188f838977d8ff085613b39afdfc328e1418ef89a32087`.
Local manifest: `backend/build/diagnostics-source-evidence.json`, covering backend
Java/YAML and worker Python sources plus the new halt regression module. No private
configuration, credentials, real CV or spool data is included.

## Changes

* `worker/src/cv_compass_worker/diagnostics.py`: typed reason enum, ValueError
  subtype, immutable safe HTTP metadata and operation-boundary annotation.
* `pull_http.py`: distinguish missing/invalid credential, connection refusal,
  timeout/deadline, TLS, HTTP401/403/other rejection, protocol/body and HTTP cleanup;
  retain the generated failed-request correlation ID before connection/TLS setup.
  No header/URL/payload/error message is logged. Existing retry catches, attempts,
  sleeps, body bytes and lease checks are retained.
* `pull_config.py`, `pull.py`: classify config/claim/lease/receipt/spool/cleanup
  at their source. Preserve underlying heartbeat failure diagnostics, task IDs
  on rejection/recovery, original exceptions and error ownership. Generic historical
  halt markers remain `OPERATOR_RECOVERY_REQUIRED`; no marker/journal is removed or
  automatically recovered. Logging-init failure has a safe direct JSON stderr fallback.
* `observability.py`: allowlist exceptionType/status and emit one safe halt at the
  responsible scope. Arbitrary exception/cause/note text is never parsed or logged.
* `SafeStackLocations.java`, `OperationalLog.java`, `GlobalExceptionHandler.java`,
  `application.yaml`: typed application-only stack locations in text/JSON, with
  bounded frames/length/inspection/cause traversal and cycle protection. No
  Throwable.toString/printStackTrace/direct Throwable logging; no client contract change.
* Regression modules: `test_halt_diagnostics.py`, `SafeStackLocationsTests.java`,
  strengthened `OperationalLogTests`, `LoggingJsonTests`, `HttpFoundationTests`.
  Configuration/convention and safe examples are in [LOGGING.md](LOGGING.md).

Native extraction/layout, pipeline, child, OCR schemas/engines, corpus/ground truth
and quality thresholds are unchanged from the base. Verified with path-scoped Git
diff; no frontend, DB migration or runtime registry change.

## Executed checks

Environment: Windows/PowerShell, Microsoft JDK25.0.4.1 and Gradle wrapper;
Ubuntu24.04 WSL, provisioned CPython3.14.8/dependencies at
`/opt/cvcompass-m62/venv/bin/python`. Database tests use only their existing owned
temporary schema/storage helpers. Private TEST_DB_* was loaded without printing values.

From backend/, with JAVA_HOME and private TEST_DB_* set:

```powershell
$env:JAVA_TOOL_OPTIONS='-Djdk.net.unixdomain.tmpdir=<existing-short-owned-tmpdir> -Duser.timezone=UTC'
.\gradlew.bat check --rerun-tasks
```

**Full backend: FAILED execution / BLOCKED environment, NOT VERIFIED as a gate.**
Fresh XML: 329 tests, 112 failures, 0 errors, 0 skipped. Thirteen database integration
suites could not initialize; cause chain includes PostgreSQL PSQLException and
ConnectException. This is not a passing run and the old 323 PASS does not validate
this source. No test/assertion was disabled or relaxed. Original reports preserved
under `build/diagnostics-blocked-full-results`; terminal log
`build/diagnostics-backend-full.log`. All four applicable tasks executed freshly.

Affected independent backend checks were then rerun:

```powershell
.\gradlew.bat test --tests '*SafeStackLocationsTests' --tests '*OperationalLogTests' `
  --tests '*LoggingJsonTests' --tests '*HttpFoundationTests' `
  --tests '*CorrelationIdFilterTests' --tests '*ModulithArchitectureTests' --rerun-tasks
```

**Targeted backend: PASS, 34 tests, 0 failures/errors/skipped**, fresh XML and
`build/diagnostics-backend-targeted.log`. Includes response privacy, application
frames, causes/suppressed text exclusion, frame/character/cause budgets, cycle,
unavailable metadata, typed allowlist, JSON/text and context restoration.
`processTestResources NO-SOURCE` is applicable (no test resources); other tasks execute.

From worker/ on the provisioned Python:

```sh
<python> -W error -m compileall -f -q src tests
PYTHONPATH=src:tests <python> -W error -m unittest \
  test_halt_diagnostics test_observability test_pull test_native_quality -v
<python> spikes/m6_2_hardening/rebuild_worker.py \
  --baseline <existing-local-worker-build> --output build/logging-diagnostics-1
```

**Targeted worker: PASS, 65 tests, 0 failures/errors/skipped**, terminal evidence
`worker/build/diagnostics-regressions-final.log`; syntax/warnings-as-errors PASS.
HTTP cases use real temporary loopback servers/ports for refusal/deadline/401/403;
TLS and orchestration have explicit test doubles. These are not backend runtime E2E.
The subset preserves retry/replay/lease/cleanup/CLI contracts and native ground truth.

**Image rebuild/full worker verifier: BLOCKED / NOT VERIFIED.** The rebuild stopped
while inspecting the pinned prerequisite image because Docker WSL integration is
unavailable (`docker` reports it could not be found in the distro). A read-only process
check found no running Docker Desktop/backend. No child image, full-suite PASS or new
provenance/fingerprint is claimed. The old PR14 image is not labeled as this source.
An initial Windows-only worker subset lacked jsonschema; it failed, and was replaced
by the real provisioned Python runs above, not treated as PASS.

## Review and operational boundaries

One agent switches Developer/Reviewer, at most three passes. Two review passes
were used. Review1 identified Medium issues in the initial follow-up: new cleanup metadata could influence
the pre-existing retry guard; a concurrent HTTP deadline could mask an independently
classified spool/lease error. These were fixed, with regression tests asserting the
original attempt count and reason precedence. An unclassified non-network exception
at the HTTP boundary now remains UNEXPECTED_WORKER_FAILURE, avoiding a guessed
network reason. Final source review covers the actual diff/new files, metadata
ownership, privacy and unchanged extraction paths. No unresolved confirmed
Critical/High/Medium source finding remains; the unavailable full verification is
a separate gate blocker, not a passing check.

No Java deprecation/compiler warnings observed; existing lint remains enabled.
Python warnings were not suppressed. Git LF/CRLF checkout notices remain a Windows
tooling condition; `git diff --check` passes without weakening whitespace checks.
Gradle configuration-cache advice is not a source warning.

**Current gate: BLOCKED / NOT VERIFIED for full acceptance.** Required next step:
restore the existing Docker/PostgreSQL verification prerequisites, rerun full backend
check, rebuild/verify the new child image, inspect fresh XML/cleanup and measure
provenance. This task's explicit no-service-restart instruction prevents silently
starting them; developer input was requested. Publication is held under workflow
sections3/6 while the full gates remain unverified.

No runtime registry change, service restart, real-CV processing, backfill or runtime
E2E was performed. CI for the new source is NOT VERIFIED/unpublished. The old PR14
CI/image is not evidence for these changes. GCC/Tesseract production gate remains
BLOCKED_GCC_WARNING_UNRESOLVED.
