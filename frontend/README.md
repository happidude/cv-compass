# CV Compass frontend — M9

React + TypeScript strict + Vite, npm lockfile, React Router data router, TanStack Query, Zod, Tailwind and a local shadcn/ui Button. Inter Latin/Vietnamese is served from bundled font files.

## Run

Node LTS 22.22.2+ or 24.15+ (verified with Node 24.22.0). From `frontend/`:

```powershell
npm ci
Copy-Item .env.example .env.local
npm run dev
```

Default API base `/api`; Vite proxies to `http://127.0.0.1:8080`. Set server-only `API_PROXY_TARGET` for another dev backend. Backend CORS is unchanged. All `VITE_*` values are public; never put worker/user credentials there. Upload defaults cap at 10 files, 10 MiB/file; configure both lower if deployment limits are lower. Single and batch backend limits must support the configured value. Poll interval, maximum wall-clock duration and display timezone are validated at startup. Default display timezone: Asia/Ho_Chi_Minh.

Set `VITE_MOCK=true` in development for synthetic fixtures and a persistent “Dữ liệu mẫu” banner. MSW is never an API failure fallback. Production rejects mock mode and excludes mock modules and service worker files. Browser demo uploads do not persist new records in the synthetic list.

```powershell
npm run check
npx playwright install chromium
npm run test:browser
```

npm 11.19+ enforces the checked-in install-script policy: esbuild native setup is approved at its locked version; MSW auto-update is denied. The committed dev worker matches the locked MSW package; refresh it explicitly with `npm exec msw init dev-public` when upgrading MSW.

Tests exercise contract/ID safety, cursor/dedupe, retries and OCR rendering. Playwright uses synthetic data only, covers five screens at 1440/1024/768, axe and keyboard navigation. Screenshot output stays ignored under `test-results/`.

## Structure and routes

`app/`: bootstrap/providers/router/layout; `shared/`: UI/config/HTTP/schema/format/polling; `features/documents/` and `features/batches/`: schemas, APIs, queries, pages. ESLint enforces shared → no features and cross-feature public entry points. Presentational components do not call HTTP.

PDF pages 1–5 map to `/documents`, `/documents/upload`, `/batches`, `/batches/:id`, `/documents/:id`. `/` redirects to documents; unknown routes show 404. Sidebar and spacing follow the PDF without its export whitespace. Batches and items store zero-based `page` in URL. Documents use `nextBeforeId`, descending IDs and no invented total pages.

## Verified backend contract at origin/main 8692e2d

| Operation | Shape / behavior |
| --- | --- |
| POST `/api/documents` | One multipart `file`; returns metadata only |
| POST `/api/batches` | Repeated multipart `files`; returns `{batch, items}` |
| GET `/api/documents?size=20&beforeId=…` | `{content:[{metadata,batchId,ingestionStatus,processing}],size,hasNext,nextBeforeId}` |
| GET `/api/documents/{id}` | Flat metadata plus batchId, ingestionStatus, processing, ocr |
| GET `/api/batches?page=…&size=20` | `{content,page,size,totalElements,totalPages}` |
| GET `/api/batches/{id}` | Batch aggregate counters and ingestion status |
| GET `/api/batches/{id}/items?page=…&size=20` | Same page envelope, items: fileOrder/displayName/documentId/errorCode |

IDs arrive as positive safe JSON numbers and become strings at the boundary. Unsafe Long IDs fail contract validation. ApiError uses code/message/correlationId/details, but raw server messages are never rendered. Only safe code/correlation tokens are retained. GET retries network/timeouts/5xx twice; abort/4xx/contract errors never retry. XHR POST has progress for total transmitted bytes and zero retries. A lost/aborted/malformed-success/5xx upload is an unknown outcome requiring inspection before resubmission.

Processing job: NOT_SCHEDULED / PENDING / PROCESSING / COMPLETED / PARTIAL / FAILED. Batch: PENDING / PROCESSING / COMPLETED / PARTIAL_FAILED / FAILED. Unknown enum values remain visibly unknown. OCR NONE shows job context; PAGE_RESULT retains per-page statuses, warnings/errors and separate text/rawText; LEGACY stays one original block. Raw OCR is rendered as text, never HTML or persisted in localStorage.

## Design adjustments / gaps

- No verified public preview/download API: PDF button disabled with explanation; internal worker APIs are never called.
- Batch items lack OCR status: no OCR column and no per-item detail requests. Counters always come from aggregate, not the current page.
- Backend has no ingestion source: no source column. File validation failures remain “Chưa gửi”; only server outcomes create result rows.
- Upload progress is request-level; no invented per-file processing progress. Single upload has no batch link.
- Pending/processing detail resources poll with a finite configured budget; hidden tabs pause, resource changes unmount old observers, manual refresh is available. “Cập nhật lúc” means browser receipt time.

## Production integration (not deployed)

`npm run build` produces static `dist/`. Serve via reverse proxy with `/api` forwarded to the backend and SPA fallback to `index.html` for frontend routes. Or set `VITE_API_BASE_URL` to an explicit public API base at build time and arrange server-side CORS outside this milestone. Keep mock disabled. No secrets, worker token, storage paths, original CV or runtime data belong in this bundle/repository.

Frontend verification does not evaluate OCR accuracy. GCC/Tesseract still block production readiness. See VERIFICATION.md for executed local evidence and remaining blockers.
