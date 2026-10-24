# M9 local verification — 2026-10-24 (Asia/Ho_Chi_Minh)

## Source and scope

- Worktree: `E:\cv-compass-m9`, branch `feature/cv-management-frontend`.
- Base: `8692e2d71a456a581267a3014fce6777fdff1f29` (`origin/main`, merged M8).
- Reviewed source: all 48 intended `frontend/` files, excluding only this evidence document. Source fingerprint: `b3753a5fad4ba99e3ecfedc210a02eeaef20de1ad99f3aee142bfdb409ffed06`.
- Fingerprint recipe: sort unique output of `git ls-files --cached --others --exclude-standard -- frontend`, exclude `frontend/VERIFICATION.md`, make `[relativePath, SHA256(fileBytes)]` pairs, SHA256 of UTF-8 JSON with separators `(',', ':')`.
- Only frontend changes. Backend, worker, infra and ignored local docs/AGENTS were read as needed and were not modified. Main and other worktrees were preserved.
- PDF's five pages were rendered and visually inspected before coding; final 1440/1024/768 screenshots were captured and inspected against that layout. Export whitespace was not reproduced. Route mapping and API inventory/gaps are in README.md.

## Local execution

Working directory for npm commands: `E:\cv-compass-m9\frontend`. Windows PowerShell, Node `v24.22.0`, npm `11.19.1`, TypeScript `5.9.3`, Vite `7.3.7`, Vitest `3.2.7`, Playwright `1.64.1`, Chromium `156.0.8078.4`.

| Command/check | Executed result |
| --- | --- |
| `npm ci --no-audit --no-fund` | PASS, clean install from committed lockfile; no install-script warnings after explicit package policy |
| `npm run check` | PASS: typecheck, ESLint, Prettier, fresh Vitest and production build |
| `npm run test` (inside check) | 4 files, **19 passed, 0 failed, 0 errors, 0 skipped** |
| `npm run test:browser` | **29 passed, 0 failed, 0 skipped**, Chromium; fresh real browser sessions |
| axe within browser suite | No violations across 5 screens × 1440/1024/768 = 15 checks |
| Keyboard/browser behavior | Skip link, file picker, native dialog focus/Escape, single/batch upload, unknown outcome, cursor/load-more failure, URL pagination, OCR variants, hidden/terminal/unmounted/deadline polling verified |
| `npm audit --omit=dev --audit-level=high --json` | 0 runtime advisories (all severity levels) |
| `git diff --check` | PASS |
| Production mock guard | With `VITE_MOCK=true`, `npm exec vite build -- --mode staging` rejected configuration as expected (negative test) |
| Production output scan | No `mockServiceWorker.js`; `rg -l 'setupWorker\|Synthetic_scan\|mockServiceWorker\|MockServiceWorker' dist` found no matches |
| Dev worker provenance | SHA256 `26171599d94d5445297303f3a1f1cedec26835ea3d0057dcfa92ec520cb35cca`, identical to locked `node_modules/msw/lib/mockServiceWorker.js` |

Browser artifacts: ignored `frontend/test-results/screens/` (15 screenshots); HTML report in ignored `frontend/playwright-report/`. These contain synthetic data only. The tests use MSW in dev and HTTP interception in a separate browser project; the real HTTP client, XHR transport and Zod boundary run. No frontend function is replaced by a fake client. Known HTTP fixture shapes were checked against backend DTO/controller/query/exception/config source and OCR validator/worker contract. There was no fallback from live API to mocks.

Java 25/PostgreSQL 17/JUnit/Flyway/Modulith/Python checks: **NOT APPLICABLE** to this frontend-only diff, as explicitly instructed for M9. No Java/Python source, schema, database or infrastructure was changed. No JUnit counts are claimed.

## Review record

One agent switched Developer/Reviewer roles; **2 reviewer passes**, within the maximum of 3. Review base is the commit above; both passes covered actual Git diff, intended new files, requirements, contracts, state transitions, failure handling, data safety, accessibility, tests and maintainability. Backend transaction/schema/module changes were not applicable.

| ID | Severity | Location in final source | Evidence / fix | Disposition |
| --- | --- | --- | --- | --- |
| M9-R1 | Medium | `src/shared/ui/status.tsx:37`, `src/shared/http.ts:75` | Plain object lookup could return inherited values for `__proto__`/`constructor` and break rendering. Use own-property checks; regression covers all status domains and error messages. | Fixed and verified |
| M9-R2 | Medium | `src/shared/polling.ts:38`, document/batch query hooks | Browser test demonstrated hidden-tab polling failed to resume. Subscribe to visibility changes and recompute query options; browser now verifies pause and resume. | Fixed and verified |
| M9-R3 | Medium | `src/mocks/fixtures.ts:58` | Initial OCR fixture included method/warning/error combinations rejected by backend's OCR validator. Aligned blank/failed/not_processed and known codes with actual contract, retaining unknown-enum coverage separately. | Fixed and verified |

During development (before review gate), tests also found and fixed live FileList capture after input reset, CSS link/button contrast and text-link identification. Node-fetch tests were moved to the Node environment to use its native AbortSignal; DOM XHR tests remain in jsdom. No assertion was weakened to hide application failures. The terminal polling test counts completed 200 responses; trace proved React StrictMode's first probe request was aborted (status -1), followed immediately by one successful response. The exact one-completed-response assertion remains. HTTP browser scenarios have default synthetic routes so navigation cannot contact the unavailable backend accidentally.

Reviewer pass 2: fixes and regression evidence rechecked; no unresolved Critical/High/Medium findings. Remaining Low implementation findings: none. Final source checks were rerun after source fixes; CI is recorded separately below. Frontend gate passes locally within M9's scope; this does not establish live integration or production readiness.

## Warnings and verification limits

- Fixed: unsupported ESLint 9 / deprecated jsdom transitive package by selecting supported tool versions; npm unreviewed install scripts by approving locked esbuild native setup and denying MSW auto-update; Git LF/CRLF conversion warnings by scoped frontend `.gitattributes`.
- Remaining: two Rollup annotation warnings in Zod 4.6.7 (`core/util.js`, `core/regexes.js`). Rollup discards misplaced comment annotations; build completes and runtime/schema tests pass. No dependency source patched, warnings filtered or assertions suppressed. No demonstrated runtime defect; upgrading/downgrading unrelated libraries solely to hide these comments was avoided.
- Remaining: Node warns that runner-injected `FORCE_COLOR` overrides host `NO_COLOR` during Playwright execution. Cosmetic terminal-color conflict; not an app/build/test defect; host-wide environment was preserved.
- **Live backend smoke: BLOCKED / NOT VERIFIED.** Connection probe to `127.0.0.1:8080` failed. No real upload or PostgreSQL writes were attempted; next step is to run an isolated backend test instance and smoke with synthetic PDFs/test storage.
- **CI: NOT VERIFIED** here; local results are not CI results. Existing backend CI does not establish frontend acceptance. Inspect checks for the pushed commit separately.
- Public PDF preview/download and batch item OCR status are absent in the verified backend contract; UI handles these gaps as documented. No internal worker endpoint or credential is used.
- Mock uploads return synthetic metadata/results; they do not persist new records in fixture list/detail storage. This dev-only limitation is documented; actual backend persistence remains to be smoke-tested.
- OCR accuracy has not been evaluated. GCC/Tesseract still block production. No merge or deployment is performed.
