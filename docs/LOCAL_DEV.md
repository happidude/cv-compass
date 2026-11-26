# Tham chiếu script dev-up / dev-down

Hướng dẫn chạy local (thứ tự bật/tắt, cấu hình, reset DB, token, worker, lỗi hay gặp) nằm ở [RUN_LOCAL.md](RUN_LOCAL.md). Trang này chỉ giữ tham chiếu cờ và hành vi của `scripts/dev-up.*`/`dev-down.*`.

## Windows PowerShell 5.1

Yêu cầu: Java 25, Node theo engines `^22.22.2 || ^24.15.0`, `npm.cmd`, đã chạy `npm ci` trong `frontend/`;
Docker cho chế độ Dev/Test. Script chỉ quản lý Docker DB, backend và frontend, không tự cài công cụ.
Backend và frontend chạy trong cửa sổ PowerShell riêng; chạy tay OCR worker và llm-worker sau khi backend sẵn sàng ([RUN_LOCAL.md](RUN_LOCAL.md)).

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

## Ubuntu WSL bash

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

## Worker và lỗi hay gặp

OCR worker, llm-worker và bảng lỗi hay gặp: xem [RUN_LOCAL.md](RUN_LOCAL.md).
