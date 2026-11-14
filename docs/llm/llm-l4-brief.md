# Brief L4 — API nội bộ `/internal/mapping/v1` cho LLM worker (cho Codex, task `llm-l4-remote-api`)

Mục tiêu/acceptance: hiện thực **đúng** `docs/llm/l4-remote-mapping-plan.md` (bản sửa 14/11/2026, mục 2–9) trong backend:
migration V13, engine `llm-extraction/1.0` (REMOTE) + config schema v4, chuỗi bảo mật + filter riêng, endpoint claim/input/lease/result/failure,
verifier + dựng envelope `PersonalInfoMappingResult` v3, reaper REMOTE, properties `cv.mapping.remote.*` (mặc định tắt). Mọi test cũ PASS;
hành vi engine 3.5 và mọi engine `IN_PROCESS` không đổi; REMOTE tắt thì hệ thống chạy y như trước (route chỉ trả 503).
Spec: plan trên (chủ dự án duyệt A + M1 + #13/#14/#15/#17 ngày 13–14/11/2026). Plan là nguồn chính; brief chỉ bổ sung ngữ cảnh.
Base: `agent/llm-l4-l6` @ commit chứa brief này (worktree `E:\cv-compass-wt\llm-l4-remote-api`).
Mức/lý do: 3 — migration, auth/security, API contract, transaction/lease, dữ liệu cá nhân.
Path được sửa:
- `backend/src/main/resources/db/migration/V13__create_mapping_attempts.sql` (file MỚI; không sửa V1–V12)
- `backend/src/main/java/com/cvcompass/backend/mapping/**` (thêm package con `remote` nếu gọn; `api/**` chỉ để `CompletedMappings` nhận engine LLM)
- `backend/src/main/java/com/cvcompass/backend/web/OperationalLog.java` (chỉ thêm Event/context key allowlist mới)
- `backend/src/main/resources/application.yaml` (chỉ thêm khối `cv.mapping.remote` theo plan mục 7, mặc định tắt, `workers` rỗng)
- test mới dưới `backend/src/test/java/com/cvcompass/backend/**` và `backend/src/test/resources/**` (không xóa/làm yếu assertion cũ)
Loại trừ: V1–V12, `ingestion/**` (chỉ đọc làm mẫu), `candidate/**`, `PersonalInfoDefinition.supportsEngine/fieldNames` (không thêm LLM vào đó),
danh sách version trong `MappingService.claim()`/`MappingRepository.claim(...)` (không thêm LLM), engine/grammar, `package-info.java`
(không mở thêm allowedDependencies), frontend, worker, llm-worker, file build/dependency, path bảo vệ.
Check: từ `backend/`: `.\gradlew.bat spotlessApply --offline`, `.\gradlew.bat compileJava compileTestJava --offline`.
Test cần DB (`TEST_DB_*` không có trong sandbox) → ghi `checks_run` NOT_RUN + lý do; Primary/verifier chạy test và full gate.
Không thêm dependency; không commit/push; không CV thật/PII/secret/token thật trong test (token test sinh ngẫu nhiên hoặc chuỗi giả rõ ràng).
Spec mơ hồ → chọn cách an toàn hơn, làm phần độc lập, ghi `open_questions`.

## Ngữ cảnh đã biết (Primary đọc từ code @ `e30bc53`)

- Mẫu OCR để chép cách làm: `ingestion/config/OcrSecurityConfiguration.java` (chuỗi `@Order(1)` `securityMatcher("/internal/ocr/**")`),
  `OcrWorkerAuthenticationFilter.java` (503 khi tắt, loopback-hoặc-TLS, regex Bearer, `hash()` SHA-256 hex, JSON lỗi),
  `OcrWorkerAuthenticationProvider.java`, `OcrIntegrationProperties.Worker.credentialSha256` (Set hex 64, không trùng giữa worker),
  `ingestion/controller/OcrWorkerController.java` (`/internal/ocr/v1`, 201/204/200, `ApiResponse`), `ingestion/service/OcrWorkerService.java`
  (claim idempotent `Idempotency-Key` + `CLAIM_BODY_SHA256`, `X-Lease-Token`), `OcrLeaseReaper.java`, migration `V6__*.sql` (`CV_OCR_ATTEMPTS`).
  Module `mapping` **không** import được `ingestion.config`/`ingestion.service` (`mapping/package-info.java`: chỉ `ingestion::ocr-input`, `web`)
  → chép phần nhỏ cần thiết sang `mapping`, comment lý do. `UserSecurity` dùng `@Order(2)` cho `/api/**`; chuỗi mới đặt order riêng, không trùng 1/2.
- Mapping hiện có: `MappingService` (`scan`, `claim`, `process` — pin `INPUT_SHA256` bằng `repository.pinInput`, `complete` — trần 1 MiB,
  digest `MappingDigests.sha256(MappingDigests.canonical(result))`, `repository.finish(...)`, kiểm `live(job)` lại sau ghi; `fail`, `reap`).
  `MappingRepository.claim(...)` liệt kê cứng 8 version; `expired(...)` là reaper in-process → phải bỏ qua job REMOTE.
  `MappingProperties` (`cv.mapping`, lease/hardDeadline 120s, `retryBackoff` 30s, `maxAttempts`). `MappingProviderRegistry`/`MappingProvider`/
  `MappingProviderKind` (L2). `MappingVersions.config/result` parse theo `schemaVersion` (thêm case 4 cho config).
  `CV_MAPPING_JOBS` (V7/V8): `TOKEN UUID`, `LEASE_EXPIRES_AT`, `HARD_DEADLINE_AT`, `INPUT_SHA256`, `ATTEMPTS ≤ MAX_ATTEMPTS`,
  `CK_MAPPING_LEASE` (PROCESSING cần TOKEN/lease/deadline), `NEXT_ATTEMPT_AT`, claim `ORDER BY NEXT_ATTEMPT_AT, ID FOR UPDATE SKIP LOCKED`.
- Envelope: `mapping/dto/PersonalInfoMappingResult.java` (18 thành phần; invariant gender chỉ MALE/FEMALE; `PersonalField` state/enabled),
  `ExperienceMappingResult` (`Experience`, `Company`, `Title`, `Period`, `PartialDate`, `Issue`), `MappingResult.Evidence(extractionId,page,start,end,text)`
  (offset code point). Constructor dùng `PersonalInfoDefinition.supportsEngine`/`fieldNames` → thêm predicate riêng như plan mục 2, không sửa 2 hàm đó.
  Engine 3.5 dựng envelope ở `PersonalInfoEngine*.java`; `ExperienceEngine` cho cách điền Company/Title khi không khớp taxonomy.
- L3: `mapping/api/CompletedMappings.java` dòng ~47 (`supportsPublicationVersion`) và ~60–70 (`publishable`) — nhận LLM qua predicate riêng.
- Thời gian: mọi so sánh lease/deadline dùng `clock_timestamp()` của DB (như `MappingRepository`), không `Instant.now()`.
- Log: chỉ `OperationalLog` với Event mới + context allowlist (jobId, attempt public id, mã); không log body/token/hash/raw_text.

## Test tối thiểu (thêm, theo mẫu `OcrSecurityHttpTests`, `OcrWorkerIntegrationTests`, `MappingIntegrationTests`)

- Bảo mật (plan mục 8): không token → 401; token OCR trên `/internal/mapping/**` → 401; token mapping trên `/internal/ocr/**` → 401;
  tắt → 503; không loopback qua HTTP → 403; route không tồn tại thiếu token → 401.
- Claim: 204 khi không có job; 201 trả đủ trường; replay cùng key/body/token → 200 cùng attempt; cùng key khác body → 409;
  hai claim đồng thời → một attempt ACTIVE; `MappingService.claim()` in-process không lấy job `llm-extraction/1.0`.
- Input/lease: token sai → lỗi; heartbeat gia hạn nhưng không vượt hạn chót; quá hạn chót → 409.
- Result: payload hợp lệ → `CV_MAPPING_RESULTS` v3 engine `llm-extraction/1.0`, `NEEDS_REVIEW`, publication L3 nhận là publishable;
  evidence lệch 1 code point / text khác → 422 `INVALID_EVIDENCE`, attempt FAILED, không có result; ngày sai → 422; key thừa → 422;
  gender OTHER → normalized null + issue; replay cùng body → 200; khác body → 409; kết quả tới sau khi attempt bị reaper/claim lại → `STALE_ATTEMPT`;
  `inputSha256` khác → `INPUT_CHANGED`; body quá trần → 413. Có test chuỗi chứa ký tự ngoài BMP (emoji/chữ ghép) để kiểm offset code point.
- Failure: mã ngoài allowlist hoặc mã do server sinh → 422; mã hợp lệ → job theo backoff/hết lượt.
- Reaper REMOTE: lease hết → attempt EXPIRED, job PENDING với `NEXT_ATTEMPT_AT ≥ RECLAIM_AFTER`; reaper in-process không chạm job REMOTE.
- Config v4: hợp lệ parse được; key thừa/thiếu → lỗi; config v3 với engine `llm-extraction/1.0` vẫn bị từ chối.
- Log: marker trong raw_text/token không xuất hiện trong log (theo mẫu test OCR hiện có).
- Dữ liệu test: chỉ chuỗi tổng hợp (vd "Synthetic Person", "Meadow Labs").
