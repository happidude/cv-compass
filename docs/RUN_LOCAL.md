# Chạy CV Compass local

Trang duy nhất để bật/tắt/reset/kiểm tra hệ thống trên máy dev. Đối chiếu code `develop` ngày 25/11/2026
(Flyway V16, REMOTE LLM mapping, chuẩn hóa catalog D5). Mọi `<...>` là chỗ bạn tự điền.
Không dán token, mật khẩu, hash hay đường dẫn CV vào chat/commit. Chỉ dùng PDF tổng hợp khi thử.

```text
PostgreSQL (Docker) → backend + frontend (scripts/dev-up.ps1) → OCR worker (WSL) → llm-worker pull + normalize (WSL, Python 3.14, Ollama)
```

Tham chiếu thêm: cờ script [LOCAL_DEV.md](LOCAL_DEV.md), cấu hình/tối ưu LLM [llm-worker/README.md](../llm-worker/README.md),
OCR chi tiết [backend/LOCAL_OCR.md](../backend/LOCAL_OCR.md), recovery spool
[backend/LOCAL_OCR.md#recovery-tests-and-safe-stop](../backend/LOCAL_OCR.md#recovery-tests-and-safe-stop).

## 1. Yêu cầu máy

| Ở đâu | Công cụ |
| --- | --- |
| Windows | Docker Desktop (Engine running; Settings → Resources → WSL integration bật Ubuntu-24.04), JDK 25, Node `^22.22.2 \|\| ^24.15.0` + `npm.cmd`, `curl.exe` |
| WSL Ubuntu 24.04 | `python3.14` (Python 3.14.8; `python3` của Ubuntu là 3.12, không dùng), `git`, `liblept5`, `unzip`, Docker qua Docker Desktop |
| WSL | Ollama chạy ở `127.0.0.1:11434`, đã `ollama pull qwen3:4b` |
| WSL | Mạng **mirrored** để gọi backend Windows qua `127.0.0.1`; clone riêng `~/cv-compass` đúng nhánh, đã `git pull` |

## 2. Cấu hình lần đầu

Mỗi thành phần có **một** file mẫu được track; chép rồi điền `<...>`:

| Thành phần | Mẫu | File thật (không commit) |
| --- | --- | --- |
| Backend | [backend/.env.properties.example](../backend/.env.properties.example) | `backend/.env.properties` |
| Frontend | [frontend/.env.example](../frontend/.env.example) | `frontend/.env.local` |
| llm-worker | [llm-worker/.env.example](../llm-worker/.env.example) | `~/cv-compass/llm-worker/.env` (WSL) |
| OCR worker | không có `.env`; cấu hình qua `pull.toml` + `pipeline.toml` + biến `CV_COMPASS_OCR_TOKEN` | `~/cv-ocr-private/` (WSL), xem [Phụ lục A](#phụ-lục-a-dựng-profile-ocr-worker) |

Mẫu chia 4 nhóm: **A** cơ bản, **B** đang bật, **C** tắt có lý do, **D** kết nối. Điểm cần nhớ:

- Đường dẫn Windows trong `.env.properties` luôn dùng `/` (`D:/...`).
- Đăng nhập: dùng đúng khóa `cv.security.provision.*` (dạng `CV_SECURITY_PROVISION_*` trong file bị bỏ qua).
  Tạo file bcrypt hash (lệnh hỏi mật khẩu, không hiện ra):
  `docker run --rm -it -v "<D:\thư-mục-riêng\secrets>:/out" httpd:2.4 sh -c "htpasswd -nBC 12 <tên> | cut -d: -f2- > /out/admin.hash"`.
- llm-worker trên máy chạy thật nên bật thêm `CVC_LLM_CTX_CHARS_PER_TOKEN=3.5` + `CVC_LLM_CTX_GUARD=true` và
  `CVC_NORM_BATCH=10` (mẫu giữ giá trị an toàn vì test khóa file mẫu, xem comment trong mẫu).
- `TEST_DB_*` không đặt trong `.env.properties`: test backend chỉ đọc biến môi trường thật.

## 3. Bật / tắt

Thứ tự bật: **DB → backend → frontend → OCR worker → Ollama → llm-worker `pull` → llm-worker `normalize`**.
Tắt ngược lại; chờ UI không còn CV `PROCESSING` trước khi Ctrl+C worker. Tắt backend trước OCR worker
sẽ làm worker ghi `halt.json` (xem [Lỗi hay gặp](#8-lỗi-hay-gặp)).

**Windows PowerShell** (DB + backend + frontend, mỗi tiến trình một cửa sổ):

```powershell
.\scripts\dev-up.ps1 -Database Dev -DryRun   # xem trước
.\scripts\dev-up.ps1 -Database Dev           # bật
.\scripts\dev-down.ps1                       # tắt frontend → backend, giữ DB
.\scripts\dev-down.ps1 -StopDatabase         # tắt cả container DB, giữ volume
```

Log ở `.dev/log/<svc>.log`. Bản bash cho WSL và mọi cờ: [LOCAL_DEV.md](LOCAL_DEV.md).

**OCR worker** (terminal WSL 1, sau khi backend sẵn sàng):

```bash
source <(grep -E '^(PY|BUILD|PROFILE)=' ~/cv-ocr-private/values.txt | sed 's/^/export /')
ls -la ~/cv-ocr-private/spool                 # có halt.json thì xử lý trước (Lỗi hay gặp)
cd ~/cv-compass
export CV_COMPASS_OCR_TOKEN="$(cat ~/cv-ocr-private/worker.token)"
"$PY" worker/run_pull_worker.py --config ~/cv-ocr-private/pull.toml
```

**llm-worker** (terminal WSL 2 cho `pull`, terminal WSL 3 cho `normalize`; mỗi terminal tự nạp token, [mục 6](#6-token-worker)):

```bash
cd ~/cv-compass/llm-worker && export PYTHONPATH=src
read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN   # dán token, Enter (không hiện)
python3.14 -m cv_compass_llm check            # kiểm Ollama + backend + token
python3.14 -m cv_compass_llm pull             # terminal 2; thử 1 việc: pull --once
python3.14 -m cv_compass_llm normalize        # terminal 3; thử 1 lô: normalize --once
```

Log `pull` bình thường: `worker_start` → `waiting` → `claimed` → `extraction_done` → `accepted`; lỗi là `failed code=…`.

## 4. Kiểm tra nhanh

PowerShell 5.1 bỏ dấu `"` khi truyền tham số cho chương trình ngoài, nên **luôn gửi SQL qua stdin**:

```powershell
@'
SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1;
SELECT count(*) AS users FROM "IDENTITY"."USERS";
SELECT "KIND", count(*) FROM "CATALOG"."ENTRIES" GROUP BY 1 ORDER BY 1;
SELECT "KIND", "STATE", count(*) FROM "CATALOG"."ALIASES" GROUP BY 1, 2 ORDER BY 1, 2;
SELECT (SELECT count(*) FROM "CANDIDATE"."CANDIDATES") AS candidates,
       (SELECT count(*) FROM "SEARCH"."CANDIDATE_INDEX") AS indexed,
       (SELECT count(*) FROM "SEARCH"."CANDIDATE_INDEX" WHERE "HAS_AI_NORMALIZATION") AS ai_normalized;
'@ | docker exec -i cv-compass-postgres psql -U cvcompass -d cv_compass
```

| Dòng | Kỳ vọng |
| --- | --- |
| Flyway | `16`, `success = t`. Lấy theo `installed_rank`, **không** `ORDER BY version` (version là chuỗi: `9` > `16`) |
| `users` | ≥ 1 sau lần khởi động đầu có provision |
| `ENTRIES` | Có `COMPANY`/`TITLE` sau khi import seed |
| `ALIASES` | `PENDING` giảm dần khi `normalize` chạy; `FAILED` cần xem mã lỗi |
| `indexed` | Bằng `candidates` sau rebuild |

WSL gọi được backend: `curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/api/auth/csrf` → `200` (`000` = không thông mạng).

## 5. Reset DB và việc sau reset

**Không hoàn tác được** → sao lưu trước. Dừng worker, frontend, backend (`dev-down.ps1`), giữ container DB chạy.

```powershell
$stamp = Get-Date -Format 'yyyyMMdd-HHmm'
$backup = '<D:\thư-mục-backup-riêng>'        # ngoài git, không lên cloud: chứa dữ liệu ứng viên
docker exec cv-compass-postgres pg_dump -U cvcompass -d cv_compass -Fc -f /tmp/cv_compass.dump
docker cp cv-compass-postgres:/tmp/cv_compass.dump "$backup\cv_compass-$stamp.dump"   # docker cp, không dùng > (hỏng file nhị phân)
docker exec cv-compass-postgres rm /tmp/cv_compass.dump
Get-Item "$backup\cv_compass-$stamp.dump"     # phải > 0 byte

@'
DROP DATABASE cv_compass WITH (FORCE);
CREATE DATABASE cv_compass OWNER cvcompass;
'@ | docker exec -i cv-compass-postgres psql -U cvcompass -d postgres
```

Thư mục PDF gốc (`CV_STORAGE_ROOT`) và spool OCR: **đổi tên cất đi**, không xóa
(`Rename-Item <storage> "<storage>-old-$stamp"`; WSL: `mv ~/cv-ocr-private/spool ~/cv-ocr-private/spool-old-$(date +%Y%m%d-%H%M)`, rồi `mkdir -m 700` spool mới).

Sau reset, theo thứ tự:

1. **Admin tự provision**: đảm bảo 3 dòng `cv.security.provision.*` + file hash có mặt, bật backend
   (`dev-up.ps1 -Database Dev`). Flyway chạy V1 → V16, user được tạo lúc khởi động
   (lỗi `USER_PROVISIONING_FAILED` = sai đường dẫn/nội dung file hash). Đăng nhập UI được thì xóa 3 dòng
   provision + file hash, khởi động lại backend.
2. **Nạp catalog seed** và 3. **dựng lại index** (cần user `ADMIN`; seed `catalog-seed/0` nằm ngoài repo).
   Dùng `curl.exe` (trong PowerShell 5.1 `curl` là alias của `Invoke-WebRequest`):

```powershell
$api = 'http://127.0.0.1:8080'; $jar = Join-Path $env:TEMP 'cvc-admin.cookies'
function Get-Xsrf { (curl.exe -s -b $jar -c $jar "$api/api/auth/csrf" | ConvertFrom-Json).token }
$cred = Get-Credential -UserName '<tên-đăng-nhập>' -Message 'CV Compass admin'
$x = Get-Xsrf
curl.exe -s -o NUL -w "login %{http_code}`n" -b $jar -c $jar -H "X-XSRF-TOKEN: $x" `
  --data-urlencode "username=$($cred.UserName)" `
  --data-urlencode "password=$($cred.GetNetworkCredential().Password)" "$api/api/auth/login"   # kỳ vọng 204
$x = Get-Xsrf                                  # token CSRF đổi sau đăng nhập
curl.exe -s -b $jar -c $jar -H "X-XSRF-TOKEN: $x" -H 'Content-Type: application/json' `
  --data-binary '@<D:/thư-mục-riêng/catalog-seed.json>' "$api/api/admin/catalog/import"     # {"created":..,"updated":..,"unchanged":..}
curl.exe -s -b $jar -c $jar -H "X-XSRF-TOKEN: $x" -X POST "$api/api/admin/search/rebuild"   # {"rebuilt":..,"failed":0}
Remove-Item $jar
```

Import idempotent (chạy lại chỉ tăng `unchanged`). Rebuild chạy lại sau khi `normalize` xử lý xong hàng đợi;
backend cũng tự rebuild hằng đêm (`cv.search.nightly-cron`, mặc định 02:30).

4. Bật OCR worker, llm-worker (`pull`, `normalize`) như [mục 3](#3-bật--tắt), kiểm tra bằng [mục 4](#4-kiểm-tra-nhanh).

## 6. Token worker

Backend chỉ giữ **SHA-256** của token; token chỉ sống trong biến môi trường của terminal chạy worker.

| Worker | Biến môi trường | Khóa hash trong `backend/.env.properties` |
| --- | --- | --- |
| OCR | `CV_COMPASS_OCR_TOKEN` (đọc từ `~/cv-ocr-private/worker.token`, quyền 600) | `cv.ocr.integration.workers.local_worker.credential-sha256[0]` |
| llm-worker `pull` | `CVC_LLM_WORKER_TOKEN` | `cv.mapping.remote.workers.local_llm.credential-sha256[0]` |
| llm-worker `normalize` | `CVC_LLM_WORKER_TOKEN` (cùng token) | `cv.catalog.normalization.workers.local_llm.credential-sha256[0]` |

Tạo token mới cho llm-worker (WSL; chỉ in hash):

```bash
export CVC_LLM_WORKER_TOKEN="$(python3.14 -c 'import secrets; print(secrets.token_urlsafe(32))')"
printf %s "$CVC_LLM_WORKER_TOKEN" | sha256sum | cut -d' ' -f1
```

Dán hash vào **cả hai** khóa `local_llm`, khởi động lại backend. Lưu token vào trình quản lý mật khẩu của bạn,
không ghi vào file, `~/.bashrc`, lịch sử lệnh hay chat. Lần sau nạp lại: `read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN`.

Kiểm khớp hash (so với dòng trong `.env.properties`, không in token):

```bash
printf %s "$CVC_LLM_WORKER_TOKEN" | sha256sum | cut -d' ' -f1
tr -d '\n' < ~/cv-ocr-private/worker.token | sha256sum | cut -d' ' -f1    # token OCR
```

Dùng `printf %s`, **không** `echo` (echo thêm ký tự xuống dòng → hash sai → 401). Token: 43–256 ký tự `A-Z a-z 0-9 _ -`.

## 7. Đo bench (tóm tắt)

Chạy offline trong WSL tại `~/cv-compass/llm-worker`, `PYTHONPATH=src`, chỉ với dữ liệu tổng hợp/đã duyệt.
File `--config` chỉ được chứa khóa provider/extraction (và `CVC_NORM_BATCH` cho `bench-norm`); **không** dùng
thẳng `.env` của `pull` (có `CVC_LLM_BACKEND_URL` → `BENCH_INVALID_CONFIG_KEY`).

```bash
python3.14 -m cv_compass_llm bench --input <ocr-dir> --gold <gold-dir> --output <out-dir-rỗng> \
  --config base=<base.env> --config candidate=<candidate.env> --repeat 2 --warmup 1
python3.14 -m cv_compass_llm bench-norm --gold <norm-gold-dir> --config local=<norm.env> --repeat 2 --json
```

Chi tiết tham số, gold, cột strict/loose và cách chọn `CVC_NORM_BATCH` theo lease: [llm-worker/README.md](../llm-worker/README.md).

## 8. Lỗi hay gặp

| Hiện tượng | Nguyên nhân / cách xử lý |
| --- | --- |
| Worker/bench lỗi lạ ngay khi chạy, `python3 --version` ra 3.12 | Gọi nhầm `python3` của Ubuntu. Luôn gọi `python3.14` (OCR: `"$PY"` từ `values.txt`) |
| `FAIL ... code=MISSING_CONFIG` cho `CVC_LLM_WORKER_TOKEN` | Terminal mới chưa nạp token: `read -rs CVC_LLM_WORKER_TOKEN && export CVC_LLM_WORKER_TOKEN` |
| `TOKEN_IN_ENV_FILE` | Có khóa token trong `llm-worker/.env`: xóa dòng đó, chỉ dùng biến môi trường |
| Worker nhận `401` | Hash trong backend không khớp token (băm bằng `echo`, dán sai khóa/thiếu khóa `local_llm` của normalize, quên khởi động lại backend). Kiểm theo [mục 6](#6-token-worker) |
| OCR worker nhận `403` | Tên profile khác giữa `pull.toml` và backend |
| llm-worker nhận `503 MAPPING_REMOTE_UNAVAILABLE` / `NORMALIZATION_UNAVAILABLE` | Backend chưa bật `CV_MAPPING_REMOTE_ENABLED` / `CV_CATALOG_NORMALIZATION_ENABLED` |
| Gọi `/api/admin/**` bị `401`/`403` | Chưa đăng nhập, user không phải `ADMIN`, hoặc thiếu header `X-XSRF-TOKEN` (lấy lại sau đăng nhập) |
| `normalize` log `LEASE_LOST` | Lô xử lý lâu hơn lease: đặt `CV_CATALOG_NORMALIZATION_LEASE_DURATION=600s` và/hoặc giảm `CVC_NORM_BATCH` (10). Lô bị bỏ, alias được thử lại |
| SQL báo `relation ... does not exist` / `syntax error` từ PowerShell | PowerShell bỏ dấu `"` trong `-c "..."`. Gửi SQL qua `@' ... '@ \| docker exec -i ...` như [mục 4](#4-kiểm-tra-nhanh) |
| WSL không thấy `/mnt/d` (ổ D:) | Automount bị tắt có chủ đích để cách ly CV thật. Đặt dữ liệu bench/seed cần cho WSL trong `~`, hoặc chạy lệnh đó bên PowerShell |
| `unzip: command not found` khi giải nén gói dữ liệu | `sudo apt-get install -y unzip` |
| `WORKER_HALTED` / `OPERATOR_RECOVERY_REQUIRED` | Có `halt.json` trong spool (thường do tắt backend trước worker). Không xóa file; làm theo [recovery](../backend/LOCAL_OCR.md#recovery-tests-and-safe-stop) |
| Backend không lên: `Invalid OCR deployment registry` | Khối profile OCR thiếu/sai, tên profile ở `profiles[0]` không có khối `profiles.<tên>.*` |
| `invalid value for parameter "TimeZone"` / `main class .timezone=UTC` | Chạy java tay mà thiếu/mất quote `"-Duser.timezone=UTC"`; dùng `dev-up.ps1` |
| `password authentication failed` | `DB_PASSWORD` khác mật khẩu lúc tạo volume (`POSTGRES_PASSWORD` chỉ áp dụng khi volume mới) |
| `REFUSED <svc>` (exit 3) từ dev-up | Service đã chạy hoặc port bị chiếm; tắt bằng `dev-down` đúng nền tảng |
| `check` FAIL ở backend | Backend chưa chạy, sai `CVC_LLM_BACKEND_URL`, hoặc WSL không ở mạng mirrored |

## Phụ lục A. Dựng profile OCR worker

Làm khi dựng máy mới hoặc đổi image OCR. Kết quả: 7 thông số ghi vào `~/cv-ocr-private/values.txt` (riêng tư)
rồi dán vào khối OCR của `backend/.env.properties`. Lệch thông số: token → `401`; profile → `403`;
image/commit/fingerprint → `422`; engines → `422 INVALID_PROVENANCE`.

```bash
# Chuẩn bị (Docker Desktop đang Engine running; umask phải 0022, không dùng umask 077 cho cả terminal)
sudo apt-get install -y liblept5
cd ~/cv-compass && git checkout <nhánh> && git pull && git status --short   # phải sạch
mkdir -p ~/cv-ocr-private && chmod 700 ~/cv-ocr-private
export PROFILE=<local-vN>                                           # tên mới, chữ/số/.-_
export BUILD=$HOME/cv-compass/worker/build/$PROFILE-$(date +%Y%m%d-%H%M)
printf 'PROFILE=%s\nBUILD=%s\n' "$PROFILE" "$BUILD" >> ~/cv-ocr-private/values.txt

# Build + kiểm định image (lâu, cần mạng; lỗi thì chạy verify_baseline.py --cleanup-only --build "$BUILD")
python3.14 worker/spikes/m6_2_hardening/build_baseline.py --output "$BUILD"
python3.14 worker/spikes/m6_2_hardening/verify_baseline.py --build "$BUILD"
export IMAGE_ID=$(python3.14 -c "import json,sys;print(json.load(open(sys.argv[1]))['worker'])" "$BUILD/images.json")
export WORKER_COMMIT=$(python3.14 -c "import json,sys;print(json.load(open(sys.argv[1]))['commit'])" "$BUILD/source-version.json")
export PY=$(ls -d "$BUILD"/host-venv-*/bin/python | head -1)
printf 'IMAGE_ID=%s\nWORKER_COMMIT=%s\nPY=%s\n' "$IMAGE_ID" "$WORKER_COMMIT" "$PY" >> ~/cv-ocr-private/values.txt

# Token mới (không in) + hash
(umask 077; python3.14 -c "import secrets,sys;sys.stdout.write(secrets.token_urlsafe(32))" > ~/cv-ocr-private/worker.token)
export CRED_SHA=$(tr -d '\n' < ~/cv-ocr-private/worker.token | sha256sum | cut -d' ' -f1)

# pipeline.toml + fingerprint (sửa pipeline.toml dù 1 ký tự → tính lại fingerprint, sửa pull.toml và backend)
mkdir -p -m 700 ~/cv-ocr-private/spool
cat > ~/cv-ocr-private/pipeline.toml <<TOML
mode = "diagnostic"
adapter = "docker"
docker_image = "$IMAGE_ID"
tesseract = "$BUILD/verification/tesseract"
models = "$BUILD/verification/models"
TOML
export FINGERPRINT=$(PYTHONPATH=$HOME/cv-compass/worker/src "$PY" -c "from pathlib import Path;from cv_compass_worker.config import Config;print(Config.load(Path('$HOME/cv-ocr-private/pipeline.toml')).fingerprint())")
echo "FINGERPRINT=$FINGERPRINT" >> ~/cv-ocr-private/values.txt

# pull.toml (local: synthetic_only=false + local_only=true; origin chỉ loopback, không / cuối)
cat > ~/cv-ocr-private/pull.toml <<TOML
enabled = true
synthetic_only = false
local_only = true
origin = "http://127.0.0.1:8080"
credential_env = "CV_COMPASS_OCR_TOKEN"
spool_root = "$HOME/cv-ocr-private/spool"
pipeline_config = "$HOME/cv-ocr-private/pipeline.toml"
profile_revision = "$PROFILE"
worker_commit = "$WORKER_COMMIT"
image_id = "$IMAGE_ID"
config_fingerprint = "$FINGERPRINT"
TOML
```

`engines-json` (#7) lấy từ một lần OCR thử trên PDF **tự sinh** bằng `worker/run_worker.py --synthetic-only`
(fixture `native`/`scan`/`combine` trong `worker/tests/fixtures`), rồi trích `result.json["engines"]` thành JSON 1 dòng
vào `~/cv-ocr-private/engines.json`. Kỳ vọng bắt đầu `{"python":"3.14.8",...` và `models` có đúng 3 model.
Request thử phải dùng `schema_version` `cv-compass.ocr/2.0`. Cuối cùng in khối dán vào backend (không chứa token):

```bash
cat <<OUT
CV_OCR_LOCAL_PROFILE_REVISION=$PROFILE
cv.ocr.integration.workers.local_worker.credential-sha256[0]=$CRED_SHA
cv.ocr.integration.workers.local_worker.profiles[0]=$PROFILE
cv.ocr.integration.profiles.$PROFILE.result-version=cv-compass.ocr/2.0
cv.ocr.integration.profiles.$PROFILE.worker-commit=$WORKER_COMMIT
cv.ocr.integration.profiles.$PROFILE.image-id=$IMAGE_ID
cv.ocr.integration.profiles.$PROFILE.config-fingerprint=$FINGERPRINT
cv.ocr.integration.profiles.$PROFILE.engines-json=$(cat ~/cv-ocr-private/engines.json)
OUT
```

Thử 1 việc: upload 1 PDF tổng hợp trên UI rồi `"$PY" worker/run_pull_worker.py --config ~/cv-ocr-private/pull.toml --once`.
Tham số timeout/giới hạn khác của `pull.toml` để mặc định (xem `worker/src/cv_compass_worker/pull_config.py`).
