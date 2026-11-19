# Plan L4 — API nội bộ `/internal/mapping/v1` cho LLM worker (ĐÃ DUYỆT có điều kiện, bản sửa 14/11/2026)

Trạng thái: chủ dự án **duyệt 14/11/2026** kiến trúc **A (pull API)** + migration **M1** (bảng `CV_MAPPING_ATTEMPTS`, file Flyway mới),
với điều kiện sửa plan theo `l4-plan-review.md` (3 High + Medium). Bản này là bản đã sửa; quyết định của chủ dự án ở mục 0.
Phụ thuộc: L2 (registry `IN_PROCESS`/`REMOTE`), L3 (publication theo schema). Sau L4: L5 (pull worker), L6 (definition + runbook).
Không đổi hành vi engine 3.5 và mọi engine `IN_PROCESS` hiện có.

## 0. Quyết định đã chốt (chủ dự án 13–14/11/2026)

| # | Quyết định |
| --- | --- |
| #12 | A + M1. Migration chỉ thêm file mới `V13__…`; không sửa V1–V12 |
| #13 | Worker gửi **bản gọn** (giá trị + trích dẫn nguyên văn + offset). Backend tự kiểm (evidence, ngày, schema) và **tự dựng** `PersonalInfoMappingResult` |
| #14 | Lease token do **worker sinh**, server chỉ lưu hash (giống OCR) |
| #15 | Chưa cần TLS (local). **H1 bắt buộc:** chuỗi bảo mật riêng + token riêng cho LLM worker (lưu hash, không log). Backend: HTTP thường chỉ từ loopback, còn lại `TLS_REQUIRED` (giống OCR). Worker: `http://` chỉ tới loopback, địa chỉ khác bắt buộc `https://` |
| #17 | Lease/hạn chót **riêng** cho REMOTE (lease ngắn + heartbeat gia hạn, hạn chót tổng dài hơn); `IN_PROCESS` giữ nguyên `cv.mapping.lease-duration`/`hard-deadline` |

## 1. Bài toán

LLM worker (`llm-worker/`, chạy cạnh Ollama trên máy có GPU) nhận việc mapping, lấy OCR input, trả kết quả.
Spring Boot sở hữu ghi DB/validation; worker không chạm DB.
`MappingService.complete()` hiện chạy lại `process()` và so (`MAPPING_RESULT_CONFLICT`) — không dùng được cho LLM (không tất định,
backend không có model). REMOTE thay bằng **verifier** (mục 5).

## 2. Engine và cấu hình

- Engine version mới: **`llm-extraction/1.0`**, `MappingProviderKind.REMOTE`. Đăng ký trong `MappingProviderRegistry` bằng provider REMOTE
  mà `map(...)` **luôn ném** `IllegalStateException` (REMOTE không bao giờ chạy trong tiến trình).
- `MappingService.claim()`/`MappingRepository.claim(...)` (in-process) **giữ nguyên danh sách 8 version cố định** — không thêm
  `llm-extraction/1.0`. Test bắt buộc: `claim()` in-process không bao giờ trả job REMOTE (M8).
- Config định nghĩa: `schemaVersion: 4`, `engineCompatibility: "llm-extraction/1.0"`, record mới `LlmExtractionDefinition`
  (parse nghiêm trong `MappingVersions.config`, key thừa/thiếu → lỗi):
  `revision` (chuỗi), `fields` (map tên field → `{enabled, description, type, aliases[]}`; tên field đúng bằng 7 key personal v3.3
  `fullName, email, phone, location, summary, gender, dateOfBirth` + `experiences`), `other` (`{enabled, description, maxItems 0..50}`).
  `type` ∈ `STRING | DATE_PARTIAL | ENUM_GENDER | EXPERIENCE_LIST`. L4 chỉ làm record + validation + fixture test tổng hợp;
  **L6** thêm file `backend/src/main/resources/mapping/llm-extraction-config-v1.json`.
- Kích hoạt: như mọi config (register + activate); scanner enqueue theo `engineCompatibility` của config active — một engine mỗi lúc
  (M8: không chạy song song LLM và 3.5 trên cùng CV; so sánh dùng `llm-worker compare` offline). Quay lại 3.5 = activate lại config 3.5.
- Envelope kết quả: `PersonalInfoMappingResult` **schemaVersion 3** (không đổi CHECK `SCHEMA_VERSION` — V11 đã cho 1,2,3; review L1).
  Record + `CompletedMappings` (L3) nhận `llm-extraction/1.0` với bộ field V33 qua một predicate **riêng**
  (vd `MappingVersions.personalResultEngine(engine)` = `PersonalInfoDefinition.supportsEngine(engine) || LLM`).
  **Không** thêm `llm-extraction/1.0` vào `PersonalInfoDefinition.supportsEngine` (config v3 với engine LLM vẫn bị từ chối — có test).

## 3. Endpoint (`/internal/mapping/v1`, hoạt động khi `cv.mapping.remote.enabled=true`, mặc định `false`)

Theo đúng mẫu `/internal/ocr/v1` (`OcrWorkerController`): response JSON `ApiResponse`, lỗi `{code,message,correlationId,details}`,
`Cache-Control: no-store`, `X-Content-Type-Options: nosniff`.

| Endpoint | Request | Response |
| --- | --- | --- |
| `POST /claims` | Header `Idempotency-Key` (UUID chuẩn), `X-Lease-Token` (worker sinh, `[A-Za-z0-9_-]{43}`). Body (key chính xác): `{"protocol":"cv-compass.llm-mapping/1.0","engineVersions":["llm-extraction/1.0"],"provenance":{…}}` | 201: `attemptId` (UUID public), `engineVersion`, `inputSha256`, `leaseExpiresAt`, `hardDeadlineAt`, `heartbeatInterval`, `definition` (config v4 đã pin). Không có việc → 204. Replay cùng key + cùng body + cùng lease token → 200 cùng attempt (attempt đã kết thúc → trả trạng thái hiện tại). Cùng key khác body (`CLAIM_BODY_SHA256`) → 409 `IDEMPOTENCY_CONFLICT` |
| `GET /attempts/{id}/input` | `X-Lease-Token` | 200 OCR input đã pin (`pages[{page, raw_text}]`, theo schema OCR hiện có); chỉ khi attempt ACTIVE và còn lease |
| `PUT /attempts/{id}/lease` | `X-Lease-Token`, body `{}` | 200 `leaseExpiresAt` mới = `min(now + lease-duration, hardDeadlineAt)`. Quá hạn chót → 409 `HARD_DEADLINE_EXCEEDED` |
| `PUT /attempts/{id}/result` | `X-Lease-Token`, body bản gọn (mục 4) | 200 `{status:"ACCEPTED"}`. Replay cùng `OUTCOME_SHA256` (SHA-256 **body nhận**) → 200; khác → 409 `OUTCOME_CONFLICT` |
| `PUT /attempts/{id}/failure` | `X-Lease-Token`, body `{"code": …}` | 200. `code` ∈ allowlist: `PROVIDER_TIMEOUT`, `PROVIDER_ERROR`, `INVALID_OUTPUT`, `INVALID_INPUT`, `RESULT_TOO_LARGE`, `UNSUPPORTED_ENGINE`, `WORKER_SHUTDOWN`, `INTERNAL_ERROR`. Mã do server sinh (`LEASE_EXPIRED`, `INVALID_EVIDENCE`, …) không nhận từ worker (review L6) |

Mã lỗi cố định: `UNAUTHORIZED`(401), `TLS_REQUIRED`(403), `MAPPING_REMOTE_UNAVAILABLE`(503), `INVALID_LEASE_TOKEN`, `STALE_ATTEMPT`,
`LEASE_EXPIRED`, `HARD_DEADLINE_EXCEEDED`, `INPUT_CHANGED`, `IDEMPOTENCY_CONFLICT`, `OUTCOME_CONFLICT` (409 — `INVALID_LEASE_TOKEN` theo status OCR đang dùng),
`INVALID_RESULT`/`INVALID_EVIDENCE`/`UNSUPPORTED_PROTOCOL` (422), `RESULT_TOO_LARGE` (413). Không message tự do, không nội dung CV.

Giới hạn body (review L5): lệnh điều khiển (claim/lease/failure) ≤ `control-body-bytes` 16 KiB; result ≤ `max-result-bytes` 1 MiB
(cùng trần `MappingService.complete`). Kiểm giới hạn **trước** khi parse.

## 4. Payload kết quả bản gọn `cv-compass.llm-mapping/1.0` (H2, #13)

```json
{
  "protocol": "cv-compass.llm-mapping/1.0",
  "inputSha256": "<64 hex>",
  "personal": {
    "fullName":    {"value": "…", "evidence": {"page": 1, "start": 0, "end": 5, "text": "…"}},
    "gender":      {"value": "MALE|FEMALE|OTHER", "evidence": {"page": 1, "start": 9, "end": 12, "text": "…"}},
    "dateOfBirth": {"value": "YYYY|YYYY-MM|YYYY-MM-DD", "evidence": {"page": 1, "start": 20, "end": 27, "text": "…"}},
    "email": null, "phone": null, "location": null, "summary": null
  },
  "experiences": [
    {"title": "…|null", "company": "…|null", "start": "YYYY|YYYY-MM|null", "end": "YYYY|YYYY-MM|null", "ongoing": false,
     "evidence": {"title": {…}, "company": {…}, "period": {…}}}
  ],
  "other": [{"label": "…", "value": "…", "evidence": {…}}],
  "issues": [{"code": "…", "field": "…|null"}],
  "provenance": {"model": "…", "promptVersion": "…", "workerVersion": "…"}
}
```

- Đúng 7 key personal (object hoặc `null`); experiences ≤ 50; `other` ≤ `definition.other.maxItems` (rỗng nếu tắt); issues ≤ 200;
  mỗi chuỗi ≤ 2000 code point; `issues[].code` regex `[A-Z][A-Z0-9_]{0,63}`; evidence của experience chỉ có key cho giá trị khác null.
  Key thừa/thiếu, sai kiểu → 422 `INVALID_RESULT`.
- Worker **không** gửi configId, revision, extractionId, documentId — backend lấy từ claim đã pin.
- `llm-worker` xuất 5 key personal từ LLM + contact tất định (`contacts.py`: `email`, `phone`, `url`, có thể nhiều). L5 lấy email/phone
  **đầu tiên** cho 7 key personal, phần còn lại + `url` đưa vào `other`; không có → `null` (backend không tự điền). (Sửa 14/11: bản trước ghi nhầm
  là L1a không trích email/phone.)

## 5. REMOTE verifier (thay "chạy lại process()")

Chạy trong `PUT /result`, theo thứ tự; lỗi ở bất kỳ bước nào → không ghi `CV_MAPPING_RESULTS`.

1. **Fencing:** attempt tồn tại, ACTIVE, đúng `WORKER_ID` của principal, `SHA-256(X-Lease-Token)` = `LEASE_TOKEN_HASH`,
   `now < LEASE_EXPIRES_AT` và `now < HARD_DEADLINE_AT` (giờ DB `clock_timestamp()`, không giờ JVM). Attempt đã bị thay → `STALE_ATTEMPT`.
2. **Input:** `inputSha256` = `INPUT_SHA256` đã pin lúc claim; băm lại extraction hiện tại vẫn bằng giá trị đó (như `process()`); khác → `INPUT_CHANGED`.
3. **Kích thước/schema:** mục 3–4.
4. **validateEvidence:** mỗi giá trị khác null có evidence; `page` tồn tại; `0 ≤ start < end ≤ số code point của raw_text`;
   `text` == `raw_text[start:end]` (đổi offset bằng `offsetByCodePoints`, không dùng chỉ số UTF-16, khớp L1a). Với field chuỗi
   (`fullName, email, phone, location, summary`, title, company, other) `value` phải bằng `text` sau chuẩn hóa khoảng trắng; riêng `personal.summary.evidence` nhận object như cũ hoặc mảng 1–8 object theo thứ tự `(page, start)` tăng dần, không chồng nhau (cùng trang: `previous.end <= next.start`), `value` không blank và bằng các `text` nối bằng một khoảng trắng sau chuẩn hóa `(?U)\s+` → một khoảng trắng rồi `strip`; giữ đủ list evidence trong envelope/publication. Các field personal khác chỉ nhận một object (mảng → 422 `INVALID_EVIDENCE`).
   gender/ngày được phép khác dạng chữ (Nam → MALE, `05/1990` → `1990-05`) nhưng phải có evidence hợp lệ.
   Sai bất kỳ giá trị nào → **từ chối cả kết quả** 422 `INVALID_EVIDENCE` (M7): attempt `FAILED`, tính một lượt thử, job theo backoff hiện tại.
5. **Quy tắc ngày:** `YYYY` hoặc `YYYY-MM`, tháng 1–12, năm 1950–2100 (dateOfBirth 1900–2100, nhận thêm `YYYY-MM-DD` hợp lệ lịch), `start ≤ end`, `ongoing=true` ⇒ `end=null`.
   Sai → 422 `INVALID_RESULT` (attempt `FAILED` như bước 4).
6. **Dựng envelope** (backend): `PersonalInfoMappingResult(3, extractionId, ocrSchemaVersion, configId, configRevision,
   "llm-extraction/1.0", "NEEDS_REVIEW", incompleteSource, reason, values, inputSha256, documentId, profileRevision,
   rulesRevision = definition.revision, taxonomyRevision = "none", experiences, issues, personalInfo)`; giá trị không đến từ worker
   lấy cùng nguồn mà `ExperienceEngine`/`PersonalInfoEngineV35` đang dùng. Ánh xạ:
   - personal: có giá trị → `state=PROPOSED`, `raw` = value, `normalized` theo quy tắc chuẩn hóa hiện có (gender `OTHER` → `normalized=null`
     + issue `GENDER_UNSUPPORTED`); `null` → `NOT_FOUND`; field tắt trong definition → `enabled=false` + `FIELD_DISABLED` (đúng invariant record).
   - experiences → `Experience` với `Company/Title.raw` từ worker, `normalized/canonical = null`, mã ngành/chức năng rỗng, state như
     `ExperienceEngine` dùng khi không khớp taxonomy; ngày → `PartialDate(year, month|null, null, YEAR|MONTH)`.
   - other → `values` với `field = "other:" + label` (publication L3 không đọc `values` của v3 → không tự công bố; giữ cho người duyệt).
   - evidence → `MappingResult.Evidence(extractionId, page, start, end, text)`.
   Constructor record ném lỗi → 422 `INVALID_RESULT` (không 500).
7. **Ghi:** một transaction khóa job + attempt (`FOR UPDATE`), kiểm lại bước 1 sau khi khóa, `repository.finish(...)` (digest = SHA-256 canonical
   envelope như `complete()`), attempt `ACCEPTED` + `OUTCOME_SHA256`, job `COMPLETED`; kiểm lại lease sau khi ghi như `complete()`.
   Provenance lưu ở attempt (`PROVENANCE` JSONB, chỉ key allowlist) — không vào envelope.

## 6. Migration `V13__create_mapping_attempts.sql` (M1)

```text
CV_MAPPING_ATTEMPTS
  ID BIGINT IDENTITY PK, PUBLIC_ID UUID UNIQUE, JOB_ID BIGINT FK CV_MAPPING_JOBS,
  ATTEMPT_NO INTEGER CHECK (ATTEMPT_NO >= 1)            -- ≤ MAX_ATTEMPTS của job kiểm trong code (M4); không chép BETWEEN 1 AND 3
  WORKER_ID VARCHAR(128) CHECK regex như OCR,
  CLAIM_REQUEST_ID UUID, CLAIM_BODY_SHA256 CHAR(64), LEASE_TOKEN_HASH CHAR(64),   -- hex regex như OCR (M2)
  LEASE_EXPIRES_AT, HARD_DEADLINE_AT, RECLAIM_AFTER TIMESTAMPTZ NOT NULL,          -- M3
  STATUS VARCHAR(20) CHECK IN ('ACTIVE','EXPIRED','FAILED','ACCEPTED'),
  STARTED_AT TIMESTAMPTZ NOT NULL, FINISHED_AT, END_REASON VARCHAR(40), ERROR_CODE VARCHAR(60),
  OUTCOME_SHA256 CHAR(64), PROVENANCE JSONB NOT NULL (object), CREATED_AT DEFAULT CURRENT_TIMESTAMP
  UNIQUE (JOB_ID, ATTEMPT_NO), UNIQUE (WORKER_ID, CLAIM_REQUEST_ID), UNIQUE (ID, JOB_ID)
  CHECK STARTED_AT < LEASE_EXPIRES_AT <= HARD_DEADLINE_AT < RECLAIM_AFTER
  CHECK trạng thái/kết thúc tương tự CK_OCR_ATTEMPT_END (FAILED do server có thể không có OUTCOME_SHA256)
  UNIQUE INDEX (JOB_ID) WHERE STATUS='ACTIVE';  INDEX (LEASE_EXPIRES_AT, JOB_ID) WHERE STATUS='ACTIVE'
```

- Không đổi `CV_MAPPING_JOBS`. Job REMOTE dùng cột hiện có: claim đặt `STATUS='PROCESSING'`, `ATTEMPTS+1`, `TOKEN` = UUID ngẫu nhiên
  do server sinh (chỉ để thỏa `CK_MAPPING_LEASE`; **không** phải lease token, không trả ra ngoài), `LEASE_EXPIRES_AT`/`HARD_DEADLINE_AT`
  đồng bộ với attempt, `INPUT_SHA256` pin **lúc claim** (review L3: tách phần pin trong `process()` thành hàm dùng chung).
- Tester độc lập: migration áp sạch trên DB test; CHECK/UNIQUE từ chối dữ liệu sai; Flyway validate không đổi checksum V1–V12.

## 7. Lease, hạn chót, reaper (H3, #17)

Cấu hình riêng `cv.mapping.remote.*` (`application.yaml`, env `CV_MAPPING_REMOTE_*`); giá trị theo khuyến nghị #17, chỉnh sau khi đo:

| Property | Mặc định | Ý nghĩa |
| --- | --- | --- |
| `enabled` | `false` | Tắt → mọi route trả 503 `MAPPING_REMOTE_UNAVAILABLE` (filter vẫn chạy) |
| `lease-duration` | `120s` | Mỗi heartbeat gia hạn thêm từng này |
| `heartbeat-interval` | `30s` | Trả cho worker trong claim; phải < `lease-duration / 2` (validate khi khởi động) |
| `hard-deadline` | `15m` | Tổng thời gian tối đa một attempt, tính từ claim; phải > `lease-duration` |
| `reclaim-grace` | `2m` | `RECLAIM_AFTER = HARD_DEADLINE_AT + grace`; job chỉ cấp lại sau mốc này |
| `control-body-bytes` / `max-result-bytes` | `16384` / `1048576` | Mục 3 |
| `workers.<id>.credential-sha256` | rỗng | Tập SHA-256 token worker (chủ dự án cấp ngoài repo); rỗng → không ai xác thực được |

- Reaper REMOTE (cùng scheduler mapping): attempt ACTIVE hết `LEASE_EXPIRES_AT` → `EXPIRED`/`LEASE_EXPIRED`; job về `PENDING` với
  `NEXT_ATTEMPT_AT = max(RECLAIM_AFTER, now + retry-backoff)` hoặc `FAILED` nếu hết `MAX_ATTEMPTS` — cùng transaction với attempt.
  Claim REMOTE chỉ lấy job PENDING có `NEXT_ATTEMPT_AT <= now` (đã bao gồm đệm).
- Reaper in-process hiện có (`MappingRepository.expired`) phải **bỏ qua** job REMOTE (lọc theo engine), để mỗi job chỉ có một reaper. Có test.
- Mọi so sánh thời gian dùng giờ DB.

## 8. Xác thực và luồng dữ liệu (H1, #15, M6)

- `MappingRemoteSecurityConfiguration` (package `mapping.config`): `SecurityFilterChain` **riêng** `securityMatcher("/internal/mapping/**")`,
  thứ tự riêng (trước chuỗi `/api/**`, không trùng `@Order` của chuỗi OCR), STATELESS, tắt form/basic/logout/CSRF **chỉ trong chuỗi này**,
  `anyRequest().hasRole("MAPPING_WORKER")`, filter `MappingWorkerAuthenticationFilter` trước `AnonymousAuthenticationFilter`.
- Filter chép nhỏ từ `OcrWorkerAuthenticationFilter` (module `mapping` không được import `ingestion.config` — review L4; ghi lý do trong comment):
  chạy cả khi tắt (503), loopback-hoặc-TLS (`TLS_REQUIRED`), đúng một header `Bearer [A-Za-z0-9_-]{43,256}`, so SHA-256 với
  `workers.*.credential-sha256` bằng so sánh thời gian hằng; principal = `workerId`. Không log token/hash/body.
- **Test bắt buộc:** không token → 401; token OCR → 401 trên `/internal/mapping/**`; token mapping → 401 trên `/internal/ocr/**`;
  token đúng nhưng tắt → 503; IP không loopback qua HTTP → 403; route `/internal/mapping/khong-ton-tai` thiếu token → 401 (không lộ 404).
- Luồng dữ liệu (M6): REMOTE mặc định tắt; khi bật, CV chỉ tới worker trên **cùng máy** (loopback) cho tới khi có TLS.
  Worker (L5): `http://` chỉ loopback, còn lại bắt buộc `https://`; Ollama vẫn chỉ loopback (L1b).
  **Để sau, có lý do:** cờ admission `synthetic-only` kiểu OCR — chủ dự án đã trả lời #15 (test local), E2E dùng PDF tổng hợp,
  REMOTE mặc định tắt, backend chỉ cho HTTP từ loopback; thêm cờ khi mở cho máy GPU khác, cùng lúc với TLS.

## 9. Provenance (M5)

Claim mang `provenance {model, promptVersion, workerVersion}` (mỗi giá trị regex `[A-Za-z0-9._:/@+-]{1,128}`); lưu `PROVENANCE` của attempt.
Result mang lại cùng provenance; khác → 422 `INVALID_RESULT`. **Để sau, có lý do:** registry profile phía backend (allowlist model/digest theo worker)
— cần khi đo chất lượng chính thức; hiện lưu để truy vết là đủ cho test local.

## 10. Rủi ro đồng thời

| Rủi ro | Xử lý |
| --- | --- |
| Hai worker claim cùng job | `FOR UPDATE SKIP LOCKED` + UNIQUE attempt ACTIVE theo job |
| Kết quả cũ tới muộn sau khi job bị claim lại | Fencing attempt + hash lease token → `STALE_ATTEMPT` |
| Mất response claim | Worker retry cùng `Idempotency-Key` + cùng lease token → 200 cùng attempt (#14) |
| Gửi result 2 lần | `OUTCOME_SHA256` trùng → 200; khác → 409 |
| Input đổi giữa claim và result | Pin lúc claim, so lúc input/result |
| Worker chết / LLM treo | Lease hết → reaper REMOTE; hạn chót tổng 15 phút; đệm 2 phút trước khi cấp lại |
| Worker in-process nhận nhầm job REMOTE | Danh sách version cố định + test (M8) |
| Log lọt CV/token | Chỉ `OperationalLog` allowlist (jobId, attempt public id, mã); test marker |

## 11. Chia việc

- **L4** (mức 3): migration V13, engine/definition v4 (record + validation), chuỗi bảo mật + filter, controller/service/repository REMOTE,
  verifier + dựng envelope, reaper REMOTE, properties, test (unit + integration DB test). Tester độc lập: migration + contract API.
  Ước lượng > 400 dòng code: chấp nhận một PR vì chuỗi bảo mật phải đi cùng route, verifier phải đi cùng endpoint result.
- **L5**: pull worker theo mục 3–4 (brief `_coord/briefs/llm-l5-pull-worker.DRAFT.md` cập nhật theo bản này).
- **L6**: file definition `llm-extraction/1.0`, cấu hình bật REMOTE (mặc định tắt), runbook E2E + quay lại 3.5.

## 12. Còn mở (không chặn)

1. Giá trị lease/hạn chót đo lại sau khi chạy L1b thật (#17).
2. Registry provenance và cờ admission (mục 8, 9) — làm cùng TLS.

## Đối chiếu review (`_coord/l4-plan-review.md`)

| Finding | Xử lý |
| --- | --- |
| H1 chuỗi bảo mật | Mục 8 (chuỗi riêng, role riêng, 503 khi tắt, test chéo token) |
| H2 payload ≠ record | Mục 4–5 (payload gọn, backend dựng envelope, 7 key, gender OTHER, PartialDate) |
| H3 lease chung | Mục 7 (cấu hình riêng, reaper riêng, reaper in-process bỏ qua REMOTE) |
| M1 token / M2 idempotency / M3 reclaim / M4 attempt no | Mục 3, 6, 7 |
| M5 provenance | Mục 9 (lưu + so khớp; registry để sau) |
| M6 luồng dữ liệu | Mục 8 (loopback-hoặc-TLS, mặc định tắt; admission để sau cùng TLS) |
| M7 evidence sai | Mục 5.4 (từ chối cả kết quả) |
| M8 một engine / claim in-process | Mục 2 |
| L1–L6 | L1 mục 2; L2: REMOTE chỉ trả `NEEDS_REVIEW`; L3 mục 6; L4 mục 8; L5 mục 3; L6 mục 3 |
