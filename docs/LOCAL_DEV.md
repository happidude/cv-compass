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
| 2 | Backend (Java 25) | PowerShell hoặc Ubuntu WSL, `backend/` | `http://127.0.0.1:8080` |
| 3 | Frontend (Vite) | PowerShell hoặc Ubuntu WSL, `frontend/` | `http://127.0.0.1:5173` |
| 4 | OCR worker (Python 3.14) | WSL Ubuntu-24.04 | gọi backend qua loopback |
| 5 | Ollama + LLM worker (tùy chọn) | WSL | Ollama `127.0.0.1:11434`; worker gọi backend qua loopback |

Thứ tự bật: **DB → backend → frontend → OCR worker → (Ollama → LLM worker)**. Thứ tự tắt ngược lại,
chờ UI không còn CV `PROCESSING` trước khi dừng worker. Backend phải chạy trước mọi worker.

### Windows PowerShell 5.1

Yêu cầu: Java 25, Node theo engines `^22.22.2 || ^24.15.0`, `npm.cmd`, đã chạy `npm ci` trong `frontend/`;
Docker cho chế độ Dev/Test; WSL distro `Ubuntu-24.04` cho worker. Worker dùng Python 3.14 trong WSL,
OCR có bộ công cụ và file riêng theo [LOCAL_OCR](../backend/LOCAL_OCR.md). LLM cần Ollama như mục 2.
Script không tự cài công cụ. Mỗi service chạy trong một cửa sổ PowerShell riêng.

```powershell
# Xem trước các lệnh, không chạy gì:
.\scripts\dev-up.ps1 -Database Dev -DryRun
# Bật thật (mỗi tiến trình một cửa sổ riêng):
.\scripts\dev-up.ps1 -Database Dev
# Dừng theo thứ tự worker → frontend → backend, giữ DB:
.\scripts\dev-down.ps1
# Chỉ dừng một số service; xem trước trước khi thao tác:
.\scripts\dev-down.ps1 -Service llm-worker,ocr-worker -DryRun
# Dừng cả container DB đã ghi nhận, giữ nguyên volume:
.\scripts\dev-down.ps1 -StopDatabase
```

| Cờ up | Ý nghĩa / mặc định |
| --- | --- |
| `-Database Dev\|Test\|None` | Bắt buộc: compose DB dev / `test-db.ps1 up` / giữ DB có sẵn |
| `-DryRun` | In lệnh đọc được; chỉ kiểm sự hiện diện công cụ/file/biến, không chạy tool, probe hay ghi file |
| `-SkipBuild` | Dùng lại đúng một jar không có hậu tố `-plain` |
| `-SkipBackend`, `-SkipFrontend`, `-SkipOcrWorker` | Bỏ phần tương ứng và preflight của phần đó |
| `-WithLlmWorker` | Mặc định tắt; có token phiên thì chạy `check` đồng bộ, thành công mới `pull` |
| `-WslDistro`, `-WslRepo` | `Ubuntu-24.04`, `~/cv-compass` (clone Linux riêng) |
| `-OcrValuesFile`, `-OcrTokenFile`, `-OcrPullConfig` | `~/cv-ocr-private/values.txt`, `worker.token`, `pull.toml` |
| `-OcrPythonVar` | `$PY` từ values file; có thể truyền đường dẫn Python cụ thể |
| `-BackendPort`, `-FrontendPort`, `-BackendWaitSeconds` | `8080`, `5173`, `240`; port backend phải khớp cấu hình backend |

| Cờ down | Ý nghĩa |
| --- | --- |
| `-Service <mảng>` | Mặc định cả bốn: `backend`, `frontend`, `ocr-worker`, `llm-worker` |
| `-StopDatabase` | Chỉ `docker stop` container theo mode đã ghi; mặc định giữ PostgreSQL |
| `-DryRun` | Chỉ in bước xác minh và lệnh dừng |

Log: `.dev/log/<svc>.log`, lần chạy trước là `<svc>.prev.log`. Record: `.dev/run/<svc>.json`, mode DB:
`.dev/run/database.json`. Worker còn có record Linux trong `${XDG_STATE_HOME:-$HOME/.local/state}/cv-compass-dev/run/win-<svc>.rec`.
`dev-down.ps1` xác minh cả danh tính Windows và Linux trước khi dừng nhóm worker, rồi dừng cửa sổ.
Chờ UI không còn CV `PROCESSING` trước khi dừng worker.

Dev: các `DB_*` đặt trong phiên sẽ ghi đè `backend/.env.properties` (script cảnh báo, không in giá trị).
Test: **phải đặt đủ** `DB_URL/DB_USERNAME/DB_PASSWORD` trong phiên; URL chỉ chấp nhận
`jdbc:postgresql://127.0.0.1:5433/cv_compass_test` hoặc `localhost` tương đương, có thể có query string.
Script không tự ánh xạ `TEST_DB_*`. Nếu đã có bộ User `TEST_DB_*` đúng của DB test, chủ máy tự nạp theo tên:

```powershell
$env:DB_URL = [Environment]::GetEnvironmentVariable('TEST_DB_URL', 'User')
$env:DB_USERNAME = [Environment]::GetEnvironmentVariable('TEST_DB_USERNAME', 'User')
$env:DB_PASSWORD = [Environment]::GetEnvironmentVariable('TEST_DB_PASSWORD', 'User')
.\scripts\dev-up.ps1 -Database Test
```

Nếu chưa có container/bộ biến User, chủ máy chuẩn bị bằng `scripts/test-db.ps1 up` trước rồi nạp vào phiên.
DB test cũng dùng cho gate tự động — đừng chạy gate khi đang chạy dev trên DB test.

Bật LLM tự động (token chỉ trong phiên, không ghi file):

```powershell
$env:CVC_LLM_WORKER_TOKEN = [Net.NetworkCredential]::new('', (Read-Host -AsSecureString 'token')).Password
.\scripts\dev-up.ps1 -Database None -WithLlmWorker
```

Token chỉ được chuyển tới `check` và cửa sổ LLM qua `WSLENV` tạm thời (`CVC_LLM_WORKER_TOKEN/u`), giữ giá trị WSLENV cũ.
Thiếu token thì bỏ LLM, không hỏi nhập. Mặc định chỉ in hướng dẫn chạy thủ công ở mục 2.

### Ubuntu WSL bash

Chạy trong clone Linux riêng. Yêu cầu Java 25, Python 3.14 (mặc định `python3.14`), Node khớp
`^22.22.2 || ^24.15.0`, npm và `frontend/node_modules`, `setsid`, Docker khi dùng dev/test.
Chỉ `--with-llm-worker` mới cần Ollama và port 11434. Công cụ thiếu sẽ báo rõ và bỏ phần phụ thuộc, không tự cài.

```bash
bash scripts/dev-up.sh --database dev --dry-run
bash scripts/dev-up.sh --database dev
bash scripts/dev-down.sh --service llm-worker,ocr-worker --dry-run
bash scripts/dev-down.sh
bash scripts/dev-down.sh --stop-database
```

| Cờ up | Ý nghĩa |
| --- | --- |
| `--database dev\|test\|none` | Bắt buộc; dev chạy compose, test chỉ start container test đã có, none giữ DB |
| `--dry-run` | In toàn bộ lệnh; không chạy check/probe hay ghi runtime |
| `--skip-build` | Dùng lại jar không có hậu tố `-plain` |
| `--skip-backend`, `--skip-frontend`, `--skip-ocr-worker` | Bỏ service và preflight tương ứng |
| `--with-llm-worker` | Chạy check trước pull; mặc định không bật |
| `-h`, `--help` | Trợ giúp |

| Cờ down | Ý nghĩa |
| --- | --- |
| `--service <csv>` | Chọn trong `backend,frontend,ocr-worker,llm-worker`; mặc định cả bốn |
| `--stop-database` | Dừng container dev/test theo record, giữ volume |
| `--dry-run` | Chỉ in lệnh và cách kiểm tra record |

Biến tùy chọn: `CVC_DEV_OCR_VALUES_FILE`, `CVC_DEV_OCR_TOKEN_FILE`, `CVC_DEV_OCR_PULL_CONFIG` (cùng mặc định Windows;
đầu chuỗi `~/` được đổi thành `$HOME/`), `CVC_DEV_BACKEND_PORT=8080`, `CVC_DEV_FRONTEND_PORT=5173`,
`CVC_DEV_BACKEND_WAIT_SECONDS=240`, `CVC_DEV_PYTHON=python3.14`.
Backend Linux cần `.env.properties` riêng trong clone WSL, với đường dẫn Linux. Khi đổi port bằng biến/cờ,
cần tự đặt cấu hình bind backend và URL worker tương ứng. Frontend dùng port đã chọn với `--strictPort`.

Log ở `.dev/log/<svc>.log` và `<svc>.prev.log`; record ở `.dev/run/<svc>.rec`, mode DB ở `.dev/run/database.json`.
Tiến trình nền chạy trong nhóm `setsid`; down xác minh `/proc/<pid>/stat` field 22 và `cmdline` trước mỗi tín hiệu
INT → chờ tối đa 30 giây → TERM → chờ tối đa 10 giây → KILL. Script Bash không sửa/dừng record `win-*`.
Không chạy đồng thời hai lệnh up/down trên cùng clone.

Test cũng yêu cầu ba biến `DB_*` trong phiên và URL test loopback 5433 như Windows. Truyền chúng từ bộ biến riêng
của chủ máy, không in giá trị, không ghi vào script. Nếu bộ `TEST_DB_*` đã được nạp vào phiên Linux:

```bash
export DB_URL="$TEST_DB_URL" DB_USERNAME="$TEST_DB_USERNAME" DB_PASSWORD="$TEST_DB_PASSWORD"
bash scripts/dev-up.sh --database test
```

Ubuntu không tự tạo DB test: nếu thiếu container `cv-compass-postgres-test`, chuẩn bị bằng `scripts/test-db.ps1 up`
trên Windows với cùng Docker engine, rồi thử lại. Script chỉ start và chờ healthy, không reset dữ liệu.

```bash
read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN
bash scripts/dev-up.sh --database none --with-llm-worker
```

Cả hai nền tảng probe TCP `127.0.0.1`, nên WSL cần mạng mirrored để phát hiện port backend/frontend Windows đã dùng.
Record khớp hoặc port bị chiếm: `REFUSED`, bỏ service đó, exit 3. Worker ngoài script chỉ được phát hiện/cảnh báo,
không bị dừng. `dev-down` chỉ dừng PID khớp record, không kill theo tên; không xóa container, volume, PDF hay spool.

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
| `Could not find or load main class .timezone=UTC` | PowerShell 5.1 có thể tách `-Duser.timezone=UTC` nếu mất quote. Lệnh thủ công phải quote `"-Duser.timezone=UTC"`; script dùng mảng `$javaArgs` và cửa sổ `-EncodedCommand` để giữ nguyên quote qua `Start-Process`. |
| `REFUSED <svc>: ...` (exit 3) | PID record còn khớp hoặc port đã có listener. Dùng down đúng nền tảng đã chạy service hoặc kiểm tra tiến trình bên ngoài; không chạy thêm bản sao. |
| `SKIP <svc>: pid <n> does not match record` | PID đã được dùng lại hoặc command đã đổi. Script không gửi tín hiệu tới PID đó và bỏ record cũ; kiểm tra tiến trình bằng tay trước khi chạy lại. |
| `STALE <svc>: ...` | Tiến trình đã thoát; record cũ được dọn. Xem log trước khi thử lại. |
| `SKIP llm-worker: check FAIL (exit n)` | `pull` chưa chạy. Đọc các dòng check, sửa token/cấu hình/backend/Ollama rồi thử lại; không đặt token vào file. |
| `FAIL backend_token code=…` | `UNAUTHORIZED`: token trong cửa sổ không khớp hash backend; `MAPPING_REMOTE_UNAVAILABLE`: REMOTE đang tắt; `TLS_REQUIRED`: gọi HTTP không qua loopback; `BACKEND_PROBE_REJECTED`: địa chỉ không phải backend CV Compass. |
