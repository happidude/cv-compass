# Chạy toàn hệ thống local (dev)

Áp dụng cho `develop` từ 14/11/2026, gồm engine LLM REMOTE (L4–L6). Đây là trang tóm tắt **thứ tự chạy**.
Cấu hình chi tiết vẫn nằm ở tài liệu gốc, trang này không chép lại:
[backend/LOCAL_OCR.md](../backend/LOCAL_OCR.md), [backend/RAW_TEXT_V2_LOCAL.md](../backend/RAW_TEXT_V2_LOCAL.md),
[backend/M11.md](../backend/M11.md) (đăng nhập, publication), [frontend/README.md](../frontend/README.md),
[llm-worker/README.md](../llm-worker/README.md), [runbook REMOTE E2E](llm/runbook-l6-remote-e2e.md).

Chỉ dùng PDF tổng hợp khi thử. Mọi `<...>` là chỗ bạn tự điền; không commit/dán giá trị thật (token, mật khẩu, hash cá nhân).

## 1. Thành phần và thứ tự

| # | Thành phần | Chạy ở đâu | Địa chỉ |
| --- | --- | --- | --- |
| 1 | PostgreSQL 17 | Docker | dev `127.0.0.1:5432` (`infra/docker-compose.yml`) hoặc test `127.0.0.1:5433` (`scripts/test-db.ps1`) |
| 2 | Backend (Java 25) | PowerShell, `backend/` | `http://127.0.0.1:8080` |
| 3 | Frontend (Vite) | PowerShell, `frontend/` | `http://127.0.0.1:5173` |
| 4 | OCR worker (Python 3.14) | WSL Ubuntu-24.04 | gọi backend qua loopback |
| 5 | Ollama + LLM worker (tùy chọn) | WSL | Ollama `127.0.0.1:11434`; worker gọi backend qua loopback |

Thứ tự bật: **DB → backend → frontend → OCR worker → (Ollama → LLM worker)**. Thứ tự tắt ngược lại,
chờ UI không còn CV `PROCESSING` trước khi dừng worker. Backend phải chạy trước mọi worker.

Script hỗ trợ (chỉ Windows PowerShell 5.1+):

```powershell
# Xem trước các lệnh, không chạy gì:
.\scripts\dev-up.ps1 -Database Dev -DryRun
# Bật thật (mỗi tiến trình một cửa sổ riêng):
.\scripts\dev-up.ps1 -Database Dev
```

- `-Database Dev`: `docker compose up -d` cho DB dev (`infra/docker-compose.yml`), backend đọc `backend/.env.properties` như thường.
- `-Database Test`: `scripts/test-db.ps1 up`; cửa sổ backend lấy `DB_URL/DB_USERNAME/DB_PASSWORD` từ biến môi trường User
  `TEST_DB_*` (không in ra). DB test cũng dùng cho gate tự động — đừng chạy gate khi đang chạy dev trên DB test.
- `-Database None`: không động tới DB (đã bật sẵn).
- `-SkipBuild`, `-SkipFrontend`, `-SkipOcrWorker`, `-WslDistro`: xem `Get-Help .\scripts\dev-up.ps1 -Detailed`.
- Cuối cùng script **in** các lệnh để chạy LLM worker trong WSL (không tự chạy, vì token phải do bạn nhập).

## 2. Chạy LLM worker

### Điều kiện

- Ollama chạy **trong WSL**, chỉ nghe `127.0.0.1:11434`; model đã kéo (`ollama pull <tag>`), xem [runbook O1](llm/runbook-o1-o2.md).
- WSL cần gọi được backend Windows qua `127.0.0.1:8080` (chế độ mạng *mirrored* của WSL). Kiểm bằng lệnh `check` bên dưới.
- Backend đã bật engine REMOTE (mặc định **tắt**) theo [runbook L6 mục 2](llm/runbook-l6-remote-e2e.md#2-token-và-cấu-hình-backend).
- `llm-worker/.env` tạo từ `llm-worker/.env.example` (`CVC_LLM_PROVIDER=ollama`, `CVC_LLM_MODEL=<tag>`,
  `CVC_LLM_BASE_URL=http://127.0.0.1:11434`, `CVC_LLM_BACKEND_URL=http://127.0.0.1:8080`, `CVC_LLM_LOG_LEVEL=INFO`).
  **Không** đặt `CVC_LLM_WORKER_TOKEN` trong `.env` (worker từ chối với mã `TOKEN_IN_ENV_FILE`).

> **Lưu ý người dùng WSL:** worker chạy từ bản clone riêng trong WSL (`~/cv-compass`), không phải checkout Windows.
> Sau khi PR mới được push/merge, chạy `cd ~/cv-compass && git pull` (đúng nhánh) trước khi chạy worker,
> nếu không WSL vẫn chạy code cũ (vd chưa có log/`check`).

### Token worker (chỉ trong biến môi trường của cửa sổ chạy worker)

Trong terminal WSL sẽ chạy worker, sinh token mới và **chỉ in SHA-256** (dán hash vào `backend/.env.properties`
khóa `cv.mapping.remote.workers.local_llm.credential-sha256[0]`, rồi khởi động lại backend):

```bash
export CVC_LLM_WORKER_TOKEN="$(python3.14 -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf %s "$CVC_LLM_WORKER_TOKEN" | sha256sum | cut -d' ' -f1
```

Token sống tới khi đóng terminal; không ghi vào `~/.bashrc`, file, chat hay commit. Cách sinh bằng PowerShell: runbook L6 mục 2.
Đã có hash trong backend và chỉ cần nhập lại token cũ: `read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN`
(dán, không hiện lên màn hình).

### Lệnh

```bash
cd ~/cv-compass/llm-worker
export PYTHONPATH=src
python3.14 -m cv_compass_llm check        # chỉ đọc: cấu hình, token đã đặt, backend, Ollama + model; OK/FAIL từng dòng
python3.14 -m cv_compass_llm pull --once  # thử: xử lý tối đa 1 việc rồi thoát (không có việc cũng thoát 0)
python3.14 -m cv_compass_llm pull         # chạy liên tục
```

Dừng bằng **Ctrl+C**: nếu đang giữ việc, worker báo backend `WORKER_SHUTDOWN` rồi thoát (mã 130); việc đó được thử lại theo backoff.

### Đọc log

Mỗi sự kiện một dòng trên stderr, ví dụ `2026-11-14 23:05:12 INFO event=claimed attempt=a1b2c3d4 engine=llm-extraction/1.0`.
Thứ tự bình thường: `worker_start` → `waiting` (khi hết việc, nhắc lại khoảng mỗi phút) → `claimed` → `input_fetched`
→ `extraction_block` (từng khối) → `extraction_done` → `submitting` → `accepted`. Lỗi: `failed code=…`,
`backend_error code=…`, `provider_error code=…`, `heartbeat_lost`. `CVC_LLM_LOG_LEVEL=DEBUG` thêm `heartbeat`.
Khi thoát in `stopped` và dòng đếm `claimed=… accepted=… failed=…` (stdout). Log không bao giờ chứa nội dung CV, prompt,
output model, giá trị trường hay token; `attempt` chỉ là 8 ký tự đầu.

### Bật / tắt REMOTE phía backend

- Bật: [runbook L6 mục 2](llm/runbook-l6-remote-e2e.md#2-token-và-cấu-hình-backend) + [cấu hình mẫu](llm/llm-remote-properties-example.md).
- Quay lại 3.5: [runbook L6 mục 5](llm/runbook-l6-remote-e2e.md#5-quay-lại-35) (đặt lại definition/revision 3.5, `CV_MAPPING_REMOTE_ENABLED=false`,
  khởi động lại backend). Job LLM còn `PENDING` sẽ không ai xử lý; không xóa dữ liệu.

## 3. Hiện tượng thường gặp

| Hiện tượng | Ý nghĩa |
| --- | --- |
| Hồ sơ ứng viên **mới** có kinh nghiệm nhưng tab đề xuất (`/proposals`) rỗng | Bình thường: lần xuất bản đầu chèn thẳng vào hồ sơ (`UNVERIFIED`). Đề xuất chỉ xuất hiện khi hồ sơ đã có dữ liệu. |
| UI báo "Lỗi contract…" | Mở console trình duyệt: dòng `[contract]` ghi đường dẫn trường bị từ chối và mã lỗi (không có giá trị). Gửi dòng đó khi báo lỗi. |
| Worker im lặng | Bản clone WSL chưa `git pull`, hoặc mức log quá cao (`CVC_LLM_LOG_LEVEL`). Chạy `check`. |
| `check` FAIL ở backend | Backend chưa chạy, sai `CVC_LLM_BACKEND_URL`, hoặc WSL không ở chế độ mạng mirrored. |
| `FAIL backend_token code=…` | `UNAUTHORIZED`: token trong cửa sổ không khớp hash backend; `MAPPING_REMOTE_UNAVAILABLE`: REMOTE đang tắt; `TLS_REQUIRED`: gọi HTTP không qua loopback; `BACKEND_PROBE_REJECTED`: địa chỉ không phải backend CV Compass. |
