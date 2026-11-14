# Brief L6 — Definition `llm-extraction/1.0`, cấu hình REMOTE (mặc định tắt), runbook E2E (cho Codex, task `llm-l6-config-runbook`)

Mục tiêu/acceptance:
1. File definition `backend/src/main/resources/mapping/llm-extraction-config-v1.json` (config schema v4, `engineCompatibility`
   `llm-extraction/1.0`, `revision` = `llm-extraction-v1-r1`): đủ field lõi `fullName, email, phone, location, summary, gender, dateOfBirth, experiences`,
   mỗi field có `enabled`, `description` (tiếng Anh, ngắn, nói rõ "chỉ lấy nguyên văn có trong CV, không suy đoán"), `type` đúng
   (`STRING`/`ENUM_GENDER`/`DATE_PARTIAL`/`EXPERIENCE_LIST`), `aliases` (nhãn tiếng Việt + tiếng Anh thường gặp, vd "Họ và tên", "Full name",
   "Ngày sinh", "Date of birth", "Giới tính", "Kinh nghiệm làm việc", "Work experience" …); ngăn `other` bật, `maxItems` 20, description rõ.
   Parse + `validate()` qua `MappingVersions.config` thành công — có **test** (`backend/src/test/...`) nạp đúng file resource này, kiểm engine,
   revision, đủ field, `other.maxItems`; và test file **không** bật được làm config v3 (engine sai) — dùng đường parse sẵn có.
2. Cấu hình bật REMOTE **mặc định TẮT**: không đổi giá trị mặc định nào trong `application.yaml` (`cv.mapping.remote.enabled=false` đã có từ L4).
   Thêm file mẫu `docs/llm/llm-remote.env.properties.example`: khối thuộc tính cần thêm vào `backend/.env.properties` của chủ dự án để bật
   (`CV_MAPPING_ENABLED`, `CV_CANDIDATE_PUBLICATION_ENABLED`, `cv.mapping.definition-file=<repo>/backend/src/main/resources/mapping/llm-extraction-config-v1.json`,
   `cv.mapping.revision=llm-extraction-v1-r1`, `CV_MAPPING_REMOTE_ENABLED=true`, `cv.mapping.remote.workers.local_llm.credential-sha256[0]=<sha256 hex>`),
   chỉ giá trị giữ chỗ `<...>`, không giá trị thật.
3. Runbook 1 trang `docs/llm/runbook-l6-remote-e2e.md` (tiếng Việt, cho chủ dự án tự chạy; agent KHÔNG chạy E2E thật):
   a. Điều kiện: backend/OCR worker/frontend chạy như `backend/README.md` (không chép lại, chỉ dẫn link); Ollama local với model đã kéo (runbook O1/O2).
   b. Sinh token worker + SHA-256 bằng lệnh PowerShell một dòng (vd `[Convert]::ToBase64String` 32 byte ngẫu nhiên → base64url bỏ `=`;
      hash bằng `Get-FileHash`/`SHA256`), đặt hash vào `.env.properties`, token chỉ vào **biến môi trường** của cửa sổ chạy worker (`$env:CVC_LLM_WORKER_TOKEN`).
      Nhắc: không dán token vào file/chat/commit.
   c. Bật REMOTE theo file mẫu (mục 2) rồi khởi động lại backend; dấu hiệu đúng: Flyway tới V13, mapping đăng ký revision `llm-extraction-v1-r1`.
   d. `llm-worker/.env` từ `.env.example` (`CVC_LLM_MODEL`, `CVC_LLM_BACKEND_URL=http://127.0.0.1:8080`, …); chạy `pull --once` để thử, rồi `pull`.
      Lệnh chạy chính xác lấy từ `llm-worker/README.md` (L5) — đọc README, không đoán.
   e. E2E: upload **PDF tổng hợp** (không CV thật) → OCR → mapping job `llm-extraction/1.0` → worker nộp → publication → UI hồ sơ ứng viên; bảng
      "thấy gì là đúng / lỗi thường gặp" (401 token sai, 403 TLS_REQUIRED khi không loopback, 503 khi REMOTE tắt, 422 INVALID_EVIDENCE, job FAILED sau hết lượt).
   f. **Quay lại 3.5**: đặt lại `cv.mapping.definition-file` = `personal-info-config-v3.5.json`, `cv.mapping.revision` = revision trong file đó
      (đọc từ file, không đoán), `CV_MAPPING_REMOTE_ENABLED=false`, khởi động lại backend; job LLM còn PENDING sẽ không được ai xử lý (ghi rõ hệ quả, không xóa dữ liệu).
   g. Câu SQL chỉ-đọc để xem trạng thái (đếm `CV_MAPPING_JOBS` theo `ENGINE_VERSION`/`STATUS`, `CV_MAPPING_ATTEMPTS` theo `STATUS`) — không UPDATE/DELETE.
   Giữ ≤ khoảng 120 dòng.
Spec: chủ dự án duyệt 14/11/2026 (L6: definition lõi + ngăn "khác" có description/type/aliases; bật REMOTE mặc định tắt; runbook E2E + quay lại 3.5;
agent không chạy E2E với Ollama thật). Plan `docs/llm/l4-remote-mapping-plan.md` mục 2, 7, 8.
Base: `agent/llm-l4-l6` sau L5 @ commit chứa brief này (worktree `E:\cv-compass-wt\llm-l6-config-runbook`).
Mức/lý do: 2 — config/docs + 1 test; không đổi hành vi mặc định.
Path được sửa: 3 file mới ở trên + 1 file test mới dưới `backend/src/test/java/com/cvcompass/backend/mapping/**`.
Loại trừ: mọi code `src/main/java/**`, `application.yaml`, `llm-worker/**` (chỉ đọc README/.env.example), migration, path bảo vệ, dependency.
Lưu ý: description/aliases trong definition hiện **chưa** được worker dùng trong prompt (prompt cố định `PROMPT_VERSION`); đưa vào prompt cần duyệt riêng —
ghi một dòng trong runbook mục "Giới hạn", không tự làm.
Check: từ `backend/`: `.\gradlew.bat spotlessApply compileTestJava --offline` (không cần DB); test mới là unit test thuần (không Spring/DB) để Codex
tự chạy được: `.\gradlew.bat test --offline --tests "<TênTest>"` (nếu sandbox chặn → NOT_RUN + lý do). Không commit/push; không CV thật/PII/secret.
