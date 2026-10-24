# M9 PR #12 revision verification — 2026-10-24

## Version, scope and review

Base: `95e0e7c8f2216052f666ccca53127a0e266bcd80`; branch `feature/cv-management-frontend`; worktree `E:\cv-compass-m9`.
This replaces the previous baseline verification record; that record remains in Git at the base commit.

Reviewed source fingerprint: `3e6619d57c426afc46dce4838d836cc6c27705800a78b4fbf535cf7716a470ba`. Recipe: sort unique paths returned by `git ls-files --cached --others --exclude-standard -- frontend .github/workflows/frontend-ci.yml`, exclude only this document, collect `[relativePath, SHA256(fileBytes)]`, then SHA256 the UTF-8 JSON with separators `(',', ':')`. This identifies 52 files. Application source subset (`frontend/src/`) fingerprint: `796aa0fb46f67f7887dfa1bd5b586a47de7c99c1403c912e7cdb32bffa2899c1`. The live smoke used this same application source.

Changes: nullable LEGACY schema/display and regressions; omit read-request size in documents/batches/items and keep cursor/page; update synthetic responses/request matching; add independent frontend CI and opt-in isolated live browser smoke. Backend/Python sources, contracts, migrations and existing CI workflows are unchanged. Vitest and its lockfile graph were updated from 3.2.7 to 5.0.5 to fix the security advisories discovered when reading CI logs. Runtime dependencies and Vite 7.3.7 are unchanged.

One agent used Developer/Reviewer roles, bounded to three passes. Reviewer pass 1 inspected the actual complete diff against `95e0e7c`, including new workflow, pagination tests and live smoke files. Final evidence/documentation were checked in pass 2. Pass 3 reviewed the complete final diff, including the security tooling update and refreshed reports. No unresolved Critical/High/Medium or Low implementation findings. Schema/transaction/Modulith changes are not applicable. The existing production OCR gate remains closed.

## Local frontend results

Environment: Windows PowerShell, Node 24.22.0, npm 11.19.1, TypeScript 5.9.3, Vite 7.3.7, Vitest 5.0.5, Playwright 1.64.1 / Chromium 156.0.8078.4. Commands ran in `E:\cv-compass-m9\frontend`.

| Executed command/check | Result |
| --- | --- |
| `npm ci` | PASS, fresh install from committed lockfile; audit 0 vulnerabilities |
| `npm run check` | PASS: typecheck, lint, format, fresh unit/HTTP tests, production build |
| `npm run test -- --reporter=default --reporter=junit --outputFile.junit=.ci-reports/unit-junit.xml` | PASS: **22 tests, 0 failures, 0 errors, 0 skipped**, 5 fresh suites; XML inspected |
| `npm run test:browser` | PASS: **30 tests, 0 failures, 0 skipped**; includes 15 axe checks and desktop/tablet layouts |
| Workflow YAML parse via Prettier parser | PASS; manual review verifies read-only permissions, exact PR head checkout, lockfile install, bash pipefail semantics and failure evidence upload |
| `git diff --check` | PASS |

Regressions cover nullable LEGACY without breaking metadata, LEGACY with exact original text/newlines, all three list API requests without size, backend-selected non-20 sizes, cursor beforeId and zero-based page. Existing unknown-enum/error/upload/polling/OCR/keyboard checks remain enabled.

One pre-final legacy test initially used jest-dom's whitespace-normalizing matcher; the test now compares exact DOM `textContent` to original legacyRawText, a stronger preservation check. One pause/resume browser check initially exhausted its 1.5-second test budget before the next interval could fire. Trace showed initial completed reads at 06:02:18.895Z/06:02:19.444Z; visibility resumed about 1.35 seconds after mount, so the next 500ms interval was beyond that budget. The test environment now has a 5-second budget; all pause/resume and deadline assertions remain. Application polling defaults/logic are unchanged. Final browser suite passes.

Local artifacts stay ignored: `.ci-reports/unit-junit.xml`, `.ci-reports/smoke-html/`, `.ci-reports/smoke-results/`, `.ci-reports/smoke-documents.png`, `playwright-report/`, `test-results/`. No CV, runtime data, credentials or build outputs are committed.

## Live isolated backend smoke

**PASS for upload/list/detail/cursor readback, with mock disabled.** Executed:

```powershell
$env:M9_SMOKE_RUN_ID = '<owned-run-id>'
$env:M9_SMOKE_BACKEND_PORT = '18089'
npm exec playwright test -- --config playwright.smoke.config.ts
```

Result: **1 browser test passed, 0 failed/skipped**. Vite used loopback 5185 with `VITE_MOCK=false` and `/api` proxy to real Spring Boot on loopback 18089. No API interception, MSW fallback or backend modification was used. Single upload -> document detail/list; batch of two -> batch detail -> document detail/batch association -> batch history; document cursor load-more all passed with no failed API responses.

Resources: new owned disposable PostgreSQL 17.11 container, database `cvcompass_m9_smoke`, schema `m9_smoke_22781a3c8ca24ce7af4fa76eb5b2980c`, a new empty temporary local storage root. Verified `current_schema()` before reading counts. No development/production datasource, existing storage or real CV was used. Final isolated counts: **3 documents, 1 batch, 2 items, 0 processing jobs, 0 extractions**. Three persisted storage objects, each **609 bytes**, SHA256 `f2de4a6dac53ef0af04d2d3466ad2379035e2925f20a4ed75ce265b180e479bb`, generated synthetic one-page PDF. Storage objects have no `.pdf` extension; the inventory was checked across all files. Backend document default=2 and max=5; the frontend loaded 2 then the third via backend cursor, proving no forced size=20. Batch/items used their existing backend defaults.

Backend environment: Microsoft OpenJDK **25.0.4.1**; unchanged Spring Boot 4.1.1 application, Flyway migrations on owned schema, Hibernate validate; importer/OCR/integration disabled. `gradlew.bat bootJar --console=plain --warning-mode=all` passed with **4 tasks executed**. Initial build/application startup encountered the documented Windows JDK UnixDomainSockets loopback issue; retry used session/JVM `-Djdk.net.unixdomain.tmpdir=E:\cv-compass-m9\backend\build\tmp\jvm` and `-Duser.timezone=UTC`, then startup and smoke passed. Successful backend startup/request logs had no WARN/ERROR; full logs remain in ignored `backend/build/m9-smoke/`. No application JVM/security/dependency source changes were made.

Owned backend PID, immutable container ID/owner label, anonymous volume and exact temporary storage root were verified and cleaned after readback. No other process, container, database, worktree or storage was removed. Credentials were random and process-local, never printed/committed. Full backend/Python suites were not rerun: source unchanged and this is a frontend correction; no JUnit/backend-suite PASS is claimed.

**Actual worker OCR/result production: NOT VERIFIED.** This isolated run has no provisioned worker/profile/images manifest (`worker/build/baseline/images.json` absent). UI read the real NONE/no-job state. PAGE_RESULT/LEGACY display is regression-tested synthetically, not claimed as live OCR evidence. No worker credentials, internal input endpoint or production-gate bypass was introduced. OCR accuracy is not evaluated; GCC/Tesseract still block production readiness.

## Warning investigation and CI

Resolved dependency sources were inspected without modification: Zod **4.6.7** `v4/core/util.js:400` has an explanatory line comment mentioning `@__PURE__`; its IIFE immediately following has its own valid `/*@__PURE__*/` marker. `v4/core/regexes.js:74` has a prose docblock mentioning the same marker; `anchor(dateSource)` later has a valid call annotation. Rollup's `logInvalidAnnotation` classifies the prose-marker positions as `INVALID_ANNOTATION`, warns and removes those comments. These two warnings concern explanatory comments, not malformed JavaScript or a failed schema validation. Builds, strict boundary tests and the real-backend browser path passed. Warning emission is preserved; no onwarn filter, source patch, suppression, Zod/Rollup dependency upgrade/downgrade or relaxed assertion was used. Remaining risk is dependency/tooling annotation handling; not an OCR production-readiness signal.

CI log inspection at `3d260bb` found npm audit warnings: 3 vulnerable dev packages (2 critical, 1 moderate), via Vitest 3.2.7, tinypool and @vitest/mocker. This is security remediation, not an annotation-warning suppression. Official advisories: [mock code injection](https://github.com/advisories/GHSA-qxg6-89mg-rqv8), [tinypool RCE gadget](https://github.com/advisories/GHSA-85c8-ppgw-ccpr). Vitest 4.1.12 was evaluated but failed the existing File upload test in its jsdom compatibility adapter (`makeCompatBlob`, undefined `_buffer`); no relaxed assertion or compatibility shim was retained. Vitest 5.0.5 supports the existing Vite 7 / Node 24 and passes all original tests. `npm audit --json` and final clean `npm ci` both report **0 vulnerabilities**. The final application source fingerprint and resolved runtime/Vite versions match the live smoke; test-tooling-only changes do not alter that exercised application. The live smoke itself ran before this tooling update and is not described as a newly repeated smoke.

The runner's existing FORCE_COLOR/NO_COLOR warning is cosmetic. New source has no lint/typecheck warnings. Git line-ending normalization is scoped to the existing frontend LF policy; no root/global Git policy is changed.

New `.github/workflows/frontend-ci.yml`: Ubuntu 24.04, Node 24 LTS, verified/pinned official checkout/setup-node/upload-artifact action commits, npm cache only (never node_modules), clean npm ci, typecheck/lint/format/tests/build, Chromium install and browser suite. Failure logs/JUnit/HTML/traces/screenshots retained for 7 days. Backend/Python workflows are unchanged. The workflow checks the PR head SHA rather than relying on an earlier commit or merge-ref-only evidence.

CI results for the pushed revision are inspected after publishing; see PR #12's current Frontend CI run and the final handoff for exact commit/run/step/log outcomes. Local PASS is not a CI claim. No merge or deployment is performed.
