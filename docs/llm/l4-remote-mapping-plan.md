# Plan L4 — API nội bộ `/internal/mapping/v1` cho LLM worker (ĐỀ XUẤT, chờ duyệt)

Trạng thái: **chỉ là đề xuất**, chưa có code, chưa có migration. Viết đêm 13/11/2026 để chủ dự án duyệt.
Phụ thuộc: L2 (registry provider `IN_PROCESS`/`REMOTE`). L5/L6 chỉ làm sau khi plan này được duyệt.

## 1. Bài toán

LLM worker (`llm-worker/`, chạy cạnh Ollama trên máy có GPU) cần nhận việc mapping, lấy OCR input, trả kết quả.
Nguyên tắc giữ nguyên: Spring Boot sở hữu ghi DB/validation; worker không chạm DB; CV không đi ra ngoài các máy đã thống nhất.

Khác biệt quan trọng so với engine `IN_PROCESS`:
- `MappingService.complete()` hiện **chạy lại `process()`** và so kết quả (`MAPPING_RESULT_CONFLICT`). Với LLM, cách này không làm được,
  vì kết quả không tất định và backend không có model.
- Vì vậy REMOTE cần một **verifier** thay cho việc chạy lại: kiểm schema, kiểm evidence trên input đã pin, kiểm quy tắc ngày.

## 2. Phương án kiến trúc

| | Mô tả | Ưu | Nhược |
| --- | --- | --- | --- |
| **A. Pull API (khuyến nghị)** | Worker gọi backend: claim → input → heartbeat → result/failure | Giống `/internal/ocr/v1` đã chạy ổn (claim, lease, token, fencing); worker sau NAT vẫn chạy; backend không giữ thread chờ LLM | Thêm endpoint + bảng attempt |
| B. Backend gọi thẳng Ollama | `REMOTE` provider trong backend gọi HTTP tới Ollama | Ít code nhất | Backend chặn thread hàng phút; backend phải thấy máy GPU; CV đi thẳng ra host khác từ backend; khó giới hạn |
| C. Hàng đợi/DB chung | Worker đọc/ghi bảng trực tiếp | Không cần API | **Vi phạm** "Spring sở hữu ghi DB/validation" |

Chọn **A**: tái dùng mẫu OCR đã kiểm chứng; ranh giới quyền rõ ràng nhất.

## 3. Endpoint (dưới `@ConditionalOnProperty cv.mapping.remote.enabled=true`, mặc định tắt)

| Endpoint | Việc | Ghi chú |
| --- | --- | --- |
| `POST /internal/mapping/v1/claims` | Body: `claimRequestId` (UUID, idempotent), `engineVersions` worker hỗ trợ | Trả `attemptId`, `leaseToken` (32 byte base64url, **chỉ trả một lần**), `leaseExpiresAt`, `hardDeadlineAt`, `engineVersion`, `inputSha256`. Không có việc → 204 |
| `GET /internal/mapping/v1/attempts/{id}/input` | Trả OCR input đã pin (`pages`) | Header `X-Lease-Token`; chỉ khi attempt ACTIVE |
| `PUT /internal/mapping/v1/attempts/{id}/lease` | Heartbeat, gia hạn tới tối đa `hardDeadlineAt` | LLM chậm (phút/CV) nên bắt buộc |
| `PUT /internal/mapping/v1/attempts/{id}/result` | Body: `inputSha256`, `schemaVersion`, `result` | Qua verifier (mục 5); idempotent: cùng digest → 200, khác → 409 |
| `PUT /internal/mapping/v1/attempts/{id}/failure` | Body: `code` trong allowlist (vd `PROVIDER_TIMEOUT`, `PROVIDER_ERROR`, `INVALID_OUTPUT`) | Không nhận message tự do (tránh lọt nội dung CV vào log) |

Lỗi trả mã cố định giống OCR (`INVALID_LEASE_TOKEN`, `LEASE_EXPIRED`, `STALE_ATTEMPT`, `INPUT_CHANGED`, `RESULT_TOO_LARGE`, ...).

## 4. Xác thực: token riêng cho LLM worker

- Filter riêng (mẫu `OcrWorkerAuthenticationFilter`), property riêng (vd `cv.mapping.remote.workers[].id` + hash token).
  **Không dùng chung token OCR**, để thu hồi một bên không ảnh hưởng bên kia.
- Lưu **hash** token worker và hash lease token; không bao giờ log token.
- Principal `workerId` gắn vào attempt; mọi lệnh sau claim phải cùng `workerId` + lease token (fencing).

## 5. REMOTE verifier (thay cho "chạy lại process()")

1. **Token/fencing:** attempt ACTIVE, đúng worker, đúng hash lease token, chưa hết lease và hard deadline. Attempt cũ (đã bị claim lại) → `STALE_ATTEMPT`.
2. **Fingerprint input:** `inputSha256` của request = `INPUT_SHA256` đã pin lúc claim (cùng cách `MappingDigests` đang dùng).
   Input đổi → `INPUT_CHANGED`.
3. **Giới hạn kích thước:** body ≤ 1 MiB (đề xuất); độ dài từng field, số experience (vd ≤ 50); sai → `RESULT_TOO_LARGE`.
4. **Schema:** parse nghiêm (không key thừa/thiếu, đúng kiểu) theo `PersonalInfoMappingResult` + version (khớp L3).
5. **validateEvidence:** mỗi giá trị có evidence `{page, start, end, text}`; `text` phải **bằng đúng** `raw_text[start:end]` của page đó
   trong input đã pin (offset code point, như L1a). Thiếu hoặc sai → bỏ giá trị và ghi issue, không bịa.
   Lưu ý: Java dùng UTF-16, cần đổi offset code point sang chỉ số Java (`offsetByCodePoints`).
6. **Quy tắc ngày:** `YYYY` / `YYYY-MM`, tháng 1–12, năm 1950–2100, start ≤ end, `ongoing` chỉ đi với end null.
   Cùng quy tắc với L1a.
7. Lưu qua đường ghi hiện có (`CV_MAPPING_RESULTS`, digest, `QUALITY=NEEDS_REVIEW`), trong transaction khóa job (như `complete()`).

## 6. Có cần migration không?

**Có** (mọi phương án an toàn đều cần). Bảng hiện có: `CV_MAPPING_JOBS` có `TOKEN UUID`, `LEASE_EXPIRES_AT`, `HARD_DEADLINE_AT`,
`INPUT_SHA256`, `ATTEMPTS` (0..3); `CV_MAPPING_RESULTS` có `SCHEMA_VERSION` (CHECK = 1), `QUALITY`, `DIGEST`, `RESULT`.

| | Thay đổi | Ưu | Nhược |
| --- | --- | --- | --- |
| **M1 (khuyến nghị)** | Bảng mới `CV_MAPPING_ATTEMPTS` (ID, PUBLIC_ID, JOB_ID, ATTEMPT_NO, WORKER_ID, CLAIM_REQUEST_ID, LEASE_TOKEN_SHA256, STATUS, LEASE_EXPIRES_AT, END_REASON, timestamps; UNIQUE (WORKER_ID, CLAIM_REQUEST_ID), UNIQUE ACTIVE theo JOB_ID) | Giống `CV_OCR_ATTEMPTS`; claim idempotent; có lịch sử từng lần thử (hữu ích khi đo LLM) | Nhiều code hơn |
| M2 | Thêm cột vào `CV_MAPPING_JOBS`: `WORKER_ID`, `LEASE_TOKEN_SHA256`, `CLAIM_REQUEST_ID` | Ít thay đổi | Mất lịch sử; claim idempotent khó; trộn dữ liệu IN_PROCESS/REMOTE trên một hàng |
| M3 | Không migration, dùng `TOKEN UUID` làm lease token | Không đổi schema | Token lưu dạng rõ, không gắn worker, không idempotent → **không đạt yêu cầu bảo mật** |

Cần kiểm thêm khi làm L5:
- `CV_MAPPING_CONFIGS` và các CHECK ở V9/V11 có cho phép engine version/kiểu kết quả mới không;
- có cần `SCHEMA_VERSION=2` không.

Migration chỉ được thêm mới (không sửa Flyway đã áp dụng).

## 7. Rủi ro đồng thời và cách xử lý

| Rủi ro | Xử lý |
| --- | --- |
| Hai worker claim cùng job | Claim trong transaction `FOR UPDATE SKIP LOCKED`; UNIQUE attempt ACTIVE theo job |
| Worker chậm, lease hết, job bị claim lại, kết quả cũ tới muộn | Fencing theo attempt + hash token → `STALE_ATTEMPT` |
| Gửi result 2 lần (retry mạng) | Idempotent theo digest: trùng → 200, khác → 409 |
| OCR input đổi giữa claim và result | Pin `INPUT_SHA256` lúc claim; so lúc result |
| Worker chết | Lease hết → job về PENDING (tăng ATTEMPTS, tối đa 3) theo lịch như hiện tại |
| Heartbeat vượt hard deadline | Từ chối gia hạn; job timeout có mã cố định |
| Log lọt nội dung CV | Allowlist context `OperationalLog`; không log body/result/token |

## 8. Câu hỏi cần duyệt

1. Chọn kiến trúc A (pull API)?
2. Chọn migration M1 (bảng attempts riêng)?
3. Giới hạn: body 1 MiB, ≤ 50 experience, lease bao lâu, hard deadline bao lâu cho LLM 4B trên RTX 3050?
4. Token worker cấu hình qua property/secret nào trên máy chạy thật (chủ dự án quản lý, agent không tạo secret)?
