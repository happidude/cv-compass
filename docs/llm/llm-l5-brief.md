# Brief L5 — LLM worker kéo việc từ backend (cho Codex, task `llm-l5-pull-worker`)

Mục tiêu/acceptance: thêm vào `llm-worker/` (chỉ thư viện chuẩn) lệnh `pull`: claim **1 job mỗi lần** từ backend `/internal/mapping/v1`
→ tải OCR input → `extract(...)` (L1a) với provider đã cấu hình (L1b/llm-l1-config) → heartbeat trong khi chạy → gửi result **bản gọn**
`cv-compass.llm-mapping/1.0` hoặc failure. Retry/backoff; mất kết nối hoặc lease hết hạn **không** tạo kết quả trùng.
Worker không chạm DB, không ghi CV ra đĩa, không in/log nội dung CV/prompt/output/token. Mọi unittest PASS (cũ + mới), backend giả
`http.server` trong tiến trình; không cần backend/Ollama/CV thật.
Spec: `docs/llm/l4-remote-mapping-plan.md` mục 3–4, 7–9 + contract thật trong code L4 (controller/DTO dưới
`backend/src/main/java/com/cvcompass/backend/mapping/**` — CHỈ ĐỌC). Code L4 khác plan → theo code, ghi `open_questions`.
Chủ dự án duyệt 14/11/2026 (#13 bản gọn, #14 worker sinh lease token, #15 http chỉ loopback, #16 `.env`, #17 lease/heartbeat).
Base: `agent/llm-l4-l6` sau L4 @ commit chứa brief này (worktree `E:\cv-compass-wt\llm-l5-pull-worker`).
Mức/lý do: 3 — HTTP có xác thực, dữ liệu cá nhân, đồng thời (heartbeat song song LLM), idempotency.
Path được sửa: `llm-worker/src/**`, `llm-worker/tests/**`, `llm-worker/.env.example`, `llm-worker/README.md`.
Loại trừ: `llm-worker/ruff.toml`, backend, `worker/**` (chỉ tham khảo `worker/` pull OCR, không import), file dependency, path bảo vệ.
Check: trong `llm-worker/`: `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests -v` (sandbox không có Python → NOT_RUN + lý do).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn, ghi `open_questions`.

## Ngữ cảnh đã biết

- `config.py` (llm-l1-config): `ENV_KEYS` → `CVC_LLM_*`, thứ tự CLI > env > `.env`, `.env` ≤ 64 KiB, key `CVC_LLM_*` lạ/trùng → lỗi,
  `default_env_file()` = `llm-worker/.env`, mã lỗi cố định, không in giá trị. **Mở rộng** module này (không viết parser thứ hai).
- `pipeline.extract(document, provider, max_blocks=)` → `ExtractionResult(fields, experiences, issues, prompt_version, provider_name)`;
  `fields` gồm personal (`fullName, gender, location, dateOfBirth, summary`) và contact tất định từ `contacts.py` (`email`, `phone`, `url`, có thể nhiều).
  `Evidence(page, start, end, text)` offset = chỉ số `str` Python (= code point), khớp backend. `ocr_input.parse_document(text)`.
  `prompts.PROMPT_VERSION`. Gender worker có `OTHER` (backend tự xử lý). Ngày dạng `YYYY`/`YYYY-MM`.
- Ánh xạ sang bản gọn (plan mục 4): 7 key personal; `email`/`phone` = giá trị contact **đầu tiên** theo (page, start), còn lại + mọi `url`
  → `other` với `label` = `email`/`phone`/`url`; không có → `null`. `other` không vượt `definition.other.maxItems` từ claim
  (vượt → giữ theo thứ tự, thêm issue `OTHER_TRUNCATED`); `other.enabled=false` → `[]`. Field `enabled=false` trong `definition` → `null`.
  Experience evidence: chỉ key cho giá trị khác null (`title`, `company`, `period`).
- Contract (theo plan, đối chiếu code L4): header `Authorization: Bearer`, `Idempotency-Key` (claim), `X-Lease-Token` (mọi lệnh,
  kể cả claim); claim 201/200(replay)/204; heartbeat `PUT .../lease`; `HARD_DEADLINE_EXCEEDED`, `STALE_ATTEMPT`, `LEASE_EXPIRED` (409);
  422 `INVALID_RESULT`/`INVALID_EVIDENCE`; 413; 503 `MAPPING_REMOTE_UNAVAILABLE`; failure allowlist plan mục 3.

## Contract thật L4 (Primary đọc từ code `agent/llm-l4-l6` @ 95981fe — ưu tiên hơn plan khi khác)

- Mọi response 2xx bọc `{"data": {...}}`; lỗi `{"code","message","correlationId","details"}` (chỉ dùng `code`).
- Claim 201/200: `data` = `attemptId` (UUID), `engineVersion`, `inputSha256`, `leaseExpiresAt`, `hardDeadlineAt` (ISO-8601 instant),
  `heartbeatInterval` (ISO-8601 duration, vd `PT30S`), `definition` (config v4: `schemaVersion`, `engineCompatibility`, `revision`,
  `fields.<name>{enabled,description,type,aliases}`, `other{enabled,description,maxItems}`), `status`. Header `Location`.
- Claim body (key chính xác): `{"protocol":"cv-compass.llm-mapping/1.0","engineVersions":["llm-extraction/1.0"],"provenance":{"model","promptVersion","workerVersion"}}`.
- Input: `data.pages = [{"page": n, "raw_text": "..."}]` — **không** có `status`/`schema_version`. Dựng `Document` trực tiếp (trang có `raw_text`
  rỗng coi như không dùng được); không ép qua `parse_document` dạng OCR gốc nếu nó đòi các key đó (viết hàm chuyển đổi nhỏ, có test).
- Lease: `PUT .../lease` body `{}` → `data.leaseExpiresAt`. Result → `data.status = "ACCEPTED"`. Failure → `data.status = "FAILED"`.
- Mã lỗi header: lease token sai/thiếu → 409 `INVALID_LEASE_TOKEN`; `Idempotency-Key` sai → 422 `UNSUPPORTED_PROTOCOL`.
  `issues[].field` chỉ `null`/7 key personal/`experiences`/`other`; `issues[].code` không được trùng mã server (`FIELD_DISABLED`, `GENDER_UNSUPPORTED`, …).
- Claim có thể trả 204 ngay cả khi còn job (server vừa loại một job hỏng) → sau 204 do loại job, poll lại bình thường; không coi là lỗi.

## Cấu hình (`llm-worker/.env`, đã ignore; chỉ commit `.env.example` với giá trị giả)

| Biến | Bắt buộc với `pull` | Mặc định | Kiểm |
| --- | --- | --- | --- |
| `CVC_LLM_BACKEND_URL` | có | — | `https://host[:port]` hoặc `http://` **chỉ** `127.0.0.1`/`localhost`/`[::1]`; không user/pass/path/query/fragment → lỗi `BACKEND_URL_NOT_ALLOWED` |
| `CVC_LLM_WORKER_TOKEN` | có | — | **chỉ từ biến môi trường tiến trình** (không `.env`, không tham số CLI); regex `[A-Za-z0-9_-]{43,256}`; có trong `.env` → lỗi `TOKEN_IN_ENV_FILE` |
| `CVC_LLM_WORKER_VERSION` | không | `dev` | regex provenance plan mục 9 |
| `CVC_LLM_POLL_S` | không | `5` | 1..300 (chờ khi 204/503) |
| `CVC_LLM_HTTP_TIMEOUT_S` | không | `10` | 1..120 |
| `CVC_LLM_MAX_HTTP_ATTEMPTS` | không | `3` | 1..10 |

Biến cũ (`CVC_LLM_MODEL`, `CVC_LLM_BASE_URL`, `CVC_LLM_TIMEOUT_S`, …) giữ nguyên. Heartbeat theo `heartbeatInterval` server trả trong claim.
Lỗi cấu hình chỉ in tên biến + mã, không bao giờ in giá trị.

## SPEC

1. `backend_client.py`: `urllib.request` với `ProxyHandler({})`, **không theo redirect** (`BACKEND_REDIRECT`), giới hạn đọc response
   (input ≤ 16 MiB, còn lại ≤ 64 KiB). Retry chỉ lỗi mạng/timeout/503, tối đa `MAX_HTTP_ATTEMPTS`, backoff tăng có trần, **gửi lại đúng byte cũ**
   và cùng header. 4xx khác → không retry, trả mã cố định (từ `code` JSON nếu hợp lệ, không giữ body).
2. `pull.py`, một attempt:
   a. Sinh `lease_token = secrets.token_urlsafe(32)` và `Idempotency-Key = uuid4()` **trước** khi gửi; retry claim dùng lại cả hai.
   b. 204 → ngủ `POLL_S` (với `--once`: thoát 0). 201/200 → kiểm `engineVersion` = `llm-extraction/1.0`, khác → failure `UNSUPPORTED_ENGINE`.
   c. `GET input` → `parse_document`; lỗi → failure `INVALID_INPUT`. `inputSha256` lấy từ claim, không tự tính.
   d. `extract(...)` chạy trong thread chính; thread heartbeat mỗi `heartbeatInterval`. Heartbeat 409/401 → cờ dừng: **không** gửi result,
      không gửi failure (attempt đã mất). Còn < 1 heartbeat tới `hardDeadlineAt` mà chưa xong → bỏ kết quả, gửi failure `PROVIDER_TIMEOUT`.
   e. Dựng bản gọn (mục Ngữ cảnh); > 1 MiB → failure `RESULT_TOO_LARGE` (không cắt dữ liệu). Provenance `{model, promptVersion, workerVersion}`
      giống hệt lúc claim.
   f. `PUT result`: 200 xong. Mất kết nối → retry cùng byte (server idempotent theo `OUTCOME_SHA256`). 409 `OUTCOME_CONFLICT`/`STALE_ATTEMPT`/
      `LEASE_EXPIRED` → dừng attempt, không gửi lại. 422 → đếm theo mã, không retry.
   g. `ProviderError` → failure `PROVIDER_ERROR`/`PROVIDER_TIMEOUT`; output LLM sai schema → `INVALID_OUTPUT`; exception khác → `INTERNAL_ERROR`.
      Body failure chỉ `{"code": …}`.
   h. Sau attempt: bỏ tham chiếu input/result.
3. CLI: `pull [--env-file PATH] [--once]`. Không có flag token. Ctrl+C: đang có attempt → failure `WORKER_SHUTDOWN` (best effort) rồi thoát 130.
   stdout chỉ in số đếm/mã (`claimed=… accepted=… failed=…`).
4. README + `.env.example`: mục L5 (token chỉ qua biến môi trường do chủ dự án cấp; backend http chỉ loopback; không commit `.env`).

## Test tối thiểu (backend giả `ThreadingHTTPServer` trên 127.0.0.1:0, provider giả)

- Config: thiếu `BACKEND_URL`/token → mã + tên biến; `http://` không loopback, có path/userinfo → lỗi; `https://` máy khác → OK;
  token trong `.env` → lỗi; không có flag CLI nhận token; giá trị không bao giờ xuất hiện trong stdout/stderr.
- Luồng đủ: claim → input → result, kiểm header (Bearer, `X-Lease-Token` = token đã sinh, `Idempotency-Key`), payload đúng 7 key personal,
  contact đầu tiên vào personal, contact còn lại/url vào `other`, field tắt → null, `other` bị cắt theo `maxItems` + issue.
- Claim lỗi mạng rồi thành công → cùng Idempotency-Key và lease token. Result mất kết nối rồi retry → cùng byte.
- 204 → không gọi input; 503 → chờ rồi thử lại.
- Provider chậm → có ≥ 1 heartbeat; heartbeat 409 → không gửi result/failure; gần hạn chót → failure `PROVIDER_TIMEOUT`, không gửi result.
- Provider lỗi → failure đúng mã, body chỉ có `code`. 409 result → không gửi lại.
- Redirect không được theo; biến proxy trỏ địa chỉ chết vẫn gọi được backend giả.
- Không rò rỉ: marker trong OCR text/prompt/output/token không có trong stdout/stderr/log. Không tạo file ngoài thư mục tạm của test.
