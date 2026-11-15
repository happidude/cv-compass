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
Docker cho chế độ Dev/Test. Script chỉ quản lý Docker DB, backend và frontend, không tự cài công cụ.
Backend và frontend chạy trong cửa sổ PowerShell riêng; chạy tay [OCR worker](#ocr-worker) và [LLM worker](#llm-worker) sau khi backend sẵn sàng.

```powershell
# Xem trước các lệnh, không chạy gì:
.\scripts\dev-up.ps1 -Database Dev -DryRun
# Bật thật (mỗi tiến trình một cửa sổ riêng):
.\scripts\dev-up.ps1 -Database Dev
# Dừng frontend → backend, giữ DB:
.\scripts\dev-down.ps1
# Chỉ dừng một số service; xem trước trước khi thao tác:
.\scripts\dev-down.ps1 -Service frontend,backend -DryRun
# Dừng cả container DB đã ghi nhận, giữ nguyên volume:
.\scripts\dev-down.ps1 -StopDatabase
```

| Cờ up | Ý nghĩa / mặc định |
| --- | --- |
| `-Database Dev\|Test\|None` | Bắt buộc: compose DB dev / `test-db.ps1 up` / giữ DB có sẵn |
| `-DryRun` | In lệnh đọc được; chỉ kiểm sự hiện diện công cụ/file/biến, không chạy tool, probe hay ghi file |
| `-SkipBuild` | Dùng lại đúng một jar không có hậu tố `-plain` |
| `-SkipBackend`, `-SkipFrontend` | Bỏ phần tương ứng và preflight của phần đó |
| `-BackendPort`, `-FrontendPort`, `-BackendWaitSeconds` | `8080`, `5173`, `240`; port backend phải khớp cấu hình backend |

| Cờ down | Ý nghĩa |
| --- | --- |
| `-Service <mảng>` | Chỉ `backend`, `frontend`; mặc định cả hai, luôn dừng frontend trước |
| `-StopDatabase` | Chỉ `docker stop` container theo mode đã ghi; mặc định giữ PostgreSQL |
| `-DryRun` | Chỉ in bước xác minh và lệnh dừng |

Log: `.dev/log/<svc>.log`, lần chạy trước là `<svc>.prev.log`. Record: `.dev/run/<svc>.json`, mode DB:
`.dev/run/database.json`. `dev-down.ps1` xác minh PID, StartTime và CommandLine trước khi dừng cây tiến trình.
Record worker cũ chỉ được cảnh báo và giữ nguyên; dừng worker thủ công bằng Ctrl+C trong cửa sổ worker.
Nếu record JSON có `kind=wsl`, script chỉ nhắc kiểm tra record bên WSL, không gọi WSL.

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

### Ubuntu WSL bash

Chạy trong clone Linux riêng. Script chỉ quản lý Docker DB, backend và frontend. Yêu cầu Java 25, Node khớp
`^22.22.2 || ^24.15.0`, npm và `frontend/node_modules`, `setsid`, Docker khi dùng dev/test.
Công cụ thiếu sẽ báo rõ và bỏ phần phụ thuộc, không tự cài. Worker chạy thủ công sau khi backend sẵn sàng.

```bash
bash scripts/dev-up.sh --database dev --dry-run
bash scripts/dev-up.sh --database dev
bash scripts/dev-down.sh --service frontend,backend --dry-run
bash scripts/dev-down.sh
bash scripts/dev-down.sh --stop-database
```

| Cờ up | Ý nghĩa |
| --- | --- |
| `--database dev\|test\|none` | Bắt buộc; dev chạy compose, test chỉ start container test đã có, none giữ DB |
| `--dry-run` | In toàn bộ lệnh; không chạy check/probe hay ghi runtime |
| `--skip-build` | Dùng lại jar không có hậu tố `-plain` |
| `--skip-backend`, `--skip-frontend` | Bỏ service và preflight tương ứng |
| `-h`, `--help` | Trợ giúp |

| Cờ down | Ý nghĩa |
| --- | --- |
| `--service <csv>` | Chỉ `backend,frontend`; mặc định cả hai, luôn dừng frontend trước |
| `--stop-database` | Dừng container dev/test theo record, giữ volume |
| `--dry-run` | Chỉ in lệnh và cách kiểm tra record |

Biến tùy chọn: `CVC_DEV_BACKEND_PORT=8080`, `CVC_DEV_FRONTEND_PORT=5173`, `CVC_DEV_BACKEND_WAIT_SECONDS=240`.
Backend Linux cần `.env.properties` riêng trong clone WSL, với đường dẫn Linux. Khi đổi port bằng biến/cờ,
cần tự đặt cấu hình bind backend và URL worker tương ứng. Frontend dùng port đã chọn với `--strictPort`.

Log ở `.dev/log/<svc>.log` và `<svc>.prev.log`; record ở `.dev/run/<svc>.rec`, mode DB ở `.dev/run/database.json`.
Tiến trình nền chạy trong nhóm `setsid`; down xác minh `/proc/<pid>/stat` field 22 và `cmdline` trước mỗi tín hiệu
INT → chờ tối đa 30 giây → TERM → chờ tối đa 10 giây → KILL. Record worker cũ chỉ được cảnh báo và giữ nguyên;
dừng worker thủ công bằng Ctrl+C trong cửa sổ worker.
Không chạy đồng thời hai lệnh up/down trên cùng clone.

Test cũng yêu cầu ba biến `DB_*` trong phiên và URL test loopback 5433 như Windows. Truyền chúng từ bộ biến riêng
của chủ máy, không in giá trị, không ghi vào script. Nếu bộ `TEST_DB_*` đã được nạp vào phiên Linux:

```bash
export DB_URL="$TEST_DB_URL" DB_USERNAME="$TEST_DB_USERNAME" DB_PASSWORD="$TEST_DB_PASSWORD"
bash scripts/dev-up.sh --database test
```

Ubuntu không tự tạo DB test: nếu thiếu container `cv-compass-postgres-test`, chuẩn bị bằng `scripts/test-db.ps1 up`
trên Windows với cùng Docker engine, rồi thử lại. Script chỉ start và chờ healthy, không reset dữ liệu.

Cả hai nền tảng probe TCP `127.0.0.1`, nên WSL cần mạng mirrored để phát hiện port backend/frontend Windows đã dùng.
Record khớp hoặc port bị chiếm: `REFUSED`, bỏ service đó, exit 3.
`dev-down` chỉ dừng backend/frontend có PID khớp record, không kill theo tên; không xóa container, volume, PDF hay spool.

## OCR worker

Chỉ chạy trong terminal WSL sau khi backend báo sẵn sàng. Setup Python/công cụ và cấu hình riêng theo
[backend/LOCAL_OCR.md](../backend/LOCAL_OCR.md), rồi chạy thủ công:

```bash
source <(grep -E '^(PY|BUILD|PROFILE)=' ~/cv-ocr-private/values.txt | sed 's/^/export /')
cd ~/cv-compass
export CV_COMPASS_OCR_TOKEN="$(cat ~/cv-ocr-private/worker.token)"
"$PY" worker/run_pull_worker.py --config ~/cv-ocr-private/pull.toml
```

Dừng bằng **Ctrl+C** sau khi UI không còn CV `PROCESSING`.
Gặp `WORKER_HALTED` / `OPERATOR_RECOVERY_REQUIRED` thì không xóa `halt.json`/`claim.json`, làm theo [Recovery](../backend/LOCAL_OCR.md#recovery-tests-and-safe-stop).

## LLM worker

Chạy thủ công sau khi backend sẵn sàng; Ollama chạy trong WSL, model đã pull; backend đã bật REMOTE.
Tạo `llm-worker/.env` từ `.env.example`, đặt provider/model và URL backend/Ollama; không đặt token trong `.env`.
WSL cần mạng mirrored để gọi backend Windows qua loopback; dùng clone `~/cv-compass` đúng nhánh, đã cập nhật code.

Trong terminal WSL, tạo token mới (chỉ in SHA-256):

```bash
export CVC_LLM_WORKER_TOKEN="$(python3.14 -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf %s "$CVC_LLM_WORKER_TOKEN" | sha256sum | cut -d' ' -f1
```

Dán hash vào `backend/.env.properties`, khóa `cv.mapping.remote.workers.local_llm.credential-sha256[0]`, rồi khởi động lại backend và chờ sẵn sàng.
Token chỉ nằm trong phiên terminal; không ghi vào file, lịch sử lệnh hay chat.
Nếu dùng token cũ, nhập ẩn bằng `read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN`.

Chạy check, thử một việc rồi chạy liên tục:

```bash
cd ~/cv-compass/llm-worker
export PYTHONPATH=src
python3.14 -m cv_compass_llm check
python3.14 -m cv_compass_llm pull --once
python3.14 -m cv_compass_llm pull
```

Dừng bằng **Ctrl+C**. Log chính: `worker_start` → `waiting` → `claimed` → `extraction_done` → `accepted`; lỗi: `failed code=…`.
Chi tiết cấu hình/log xem [llm-worker/README.md](../llm-worker/README.md); bật/tắt REMOTE, token và xử lý lỗi xem [runbook L6](llm/runbook-l6-remote-e2e.md).

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
| `FAIL backend_token code=…` | `UNAUTHORIZED`: token trong cửa sổ không khớp hash backend; `MAPPING_REMOTE_UNAVAILABLE`: REMOTE đang tắt; `TLS_REQUIRED`: gọi HTTP không qua loopback; `BACKEND_PROBE_REJECTED`: địa chỉ không phải backend CV Compass. |
