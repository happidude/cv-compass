# CV Compass — Python OCR worker M6.2

PDF → text theo từng trang, offline; không business mapping, database hoặc M7.
**M6 baseline dùng thử: đủ evidence kỹ thuật. OCR accuracy chưa đánh giá.**
**Production-ready: BLOCKED do GCC/Tesseract unresolved. Chưa chạy CV thật.**

Phạm vi nghiệm thu điều chỉnh ngày 2026-10-21: baseline PDF → text theo trang với
pipeline hiện có; corpus/ground truth/CER-WER/quality tuning chuyển backlog riêng.
Xem [bản bàn giao và cách chạy](design/M6_BASELINE_HANDOFF.md) và
[quality backlog](design/OCR_QUALITY_BACKLOG.md). Chế độ dùng thử hiện tại vẫn là
`diagnostic` với fixture tổng hợp; quyết định này không mở production gate.

## Chạy và contract

Dùng CPython3.14.8 riêng, Tesseract5.5.3, model eng/vie/osd từ revision
`87416418657359cb625c412a48b6e1d6d41c29bd`. Runtime kiểm tra version/model SHA256;
không cài dependency/model khi đọc PDF. Giữ license/notices của binary và dependency.

```powershell
python -m pip install --only-binary=:all: --require-hashes -r worker/requirements.lock -r worker/requirements-test.lock
python worker/run_worker.py --config <private-config.toml> --workspace <private-workspace> --request <request.json> --output <new-result.json> --verification-report <new-verification.json> --synthetic-only
```

Cấu hình mẫu tại config.example.toml; path phải là absolute của OS. Chạy trong checkout
có src/ và design/, chưa đóng gói wheel. `--synthetic-only` là xác nhận của operator,
không phải bộ phát hiện CV thật. Không dùng real CV hoặc hostile PDF/resource bomb.

Request theo design/examples/request.json: schema_version cv-compass.ocr/1.0,
request_id/document_id opaque ASCII, input_ref32hex.pdf trong workspace private,
expected_sha256 thực của fixture, profile baseline-v1. Không arbitrary path/URL/password.
Result theo design/ocr-v1.schema.json; không đổi schema/version hoặc thêm delivered/isolation
vào OCR JSON. Text NFC/LF, raw_text giữ engine output; không suy diễn/sửa business fields.
Tesseract confidence là trung bình TSV word score hợp lệ, không xác suất/accuracy/CER.

| Exit | Điều kiện | Receipt stderr |
|---:|---|---|
| 0 | Result JSON success đã giao | delivered=true,status=success |
| 1 | Result JSON partial đã giao | delivered=true,status=partial |
| 2 | Result JSON failed đã giao | delivered=true,status=failed |
| 3 | Không giao được result JSON hợp lệ | delivered=false,không status |

Request/config lỗi nhưng có schema version/opaque IDs đã validate đủ để tạo envelope
failed thì xuất failed và trả2. Thiếu identity hợp lệ, JSON hỏng hoặc publication lỗi
thì3. Invalid config không được điều khiển fallback envelope caps. Exit không chứng
minh accuracy OCR. Receipt chỉ code/delivered/status, không PDF text/filename/path.
Không stdout OCR JSON. Examples riêng: design/cli-exit-examples.json.

Result/report phải là file mới. Atomic no-clobber publication trên cùng filesystem;
hai artifact không commit nguyên tử cùng nhau: report có thể tồn tại nếu result write
thất bại. Chỉ receipt delivered=true sau result publication; không suy từ report/file cũ.

## Native diagnostic và Docker adapter

Native Windows/Linux chỉ dành fixture tổng hợp thông thường, chưa hard isolation.
Snapshot stream10MiB, hash/identity recheck, reject symlink/reparse/network source,
không sửa PDF nguồn. Workspace phải private/ACL do operator chuẩn bị; chưa bảo đảm
chống tài khoản có quyền đổi ancestor directories.

Docker adapter dùng Linux containers trên Docker Desktop hoặc Docker local Ubuntu.
Không cài daemon/service/global config. Supervisor ở ngoài PDF container, chỉ dùng
local unix/npipe daemon; remote Docker endpoint bị reject **trước staging input**.
Không truyền Docker socket/config/DB/cloud credentials vào PDF child.

Để cấu hình adapter, bổ sung trong private TOML:

```toml
adapter = "docker"
docker_image = "sha256:<64 hex của worker image đã build/review>"
container_memory_bytes = 805306368
container_cpu_millicores = 2000
container_pids = 64
```

Image phải có sẵn; `--pull never`, runtime không download. Build source bằng
spikes/m6_2_hardening/prepare_worker_image.py và Dockerfile.worker sau clean dependency
image Dockerfile.dependencies. Dockerfile.supervisor chỉ là test harness Ubuntu;
không tự mount socket vào nó. Full Ubuntu checks đã chạy từ host supervisor trong
WSL riêng `CVCompass-Ubuntu-24.04`, với Docker Desktop integration được cấp quyền;
PDF child vẫn không có Docker socket. Evidence ngày 2026-10-20 ở báo cáo mục I.

Mỗi child: hard memory768MiB/swap0/CPU2/PIDs64, nonroot65532/rootreadonly/networknone,
capdropALL/no-new-privileges/core0/logdriver-none. /work và /work/engine là hai tmpfs
có tổng size=max_temp_bytes256MiB; engine reserve=min(engine_output_cap,max_temp/2).
/dev/shm có cap1MiB riêng. Input/control được writer trusted bounded stream vào named
volume private mới, files444 rồi mount read-only trong PDF scope; không bind inbox,
CV storage hoặc output RW. Supervisor được giữ ngoài child memory scope, chưa đặt
hard256MiB budget cho host supervisor. Production vẫn fail closed vì GCC unresolved;
technical suite hai môi trường đã chạy đầy đủ, không có flag bỏ qua gate.

Container watchdog và parent watchdog giới hạn page/document; kill/remove PID scope,
không chỉ kill Tesseract PID. Cgroup memory.peak gồm tree/cache/tmpfs, không phải RSS
supervisor. Polling guards không thay hard quota. Không tuyên bố hard I/O deadline:
Docker/filesystem/cleanup có thể treo ngoài watchdog tính toán.

Nếu supervisor bị kill, container watchdog còn chạy và giới hạn lifetime; không hứa
kill tức thì. Stopped container/readonly input volume/host snapshot-lock có thể còn;
operator phải xác minh ownership rồi dọn. Không scheduler, retry hoặc crash recovery.
Cleanup Docker thất bại không che lỗi chính hay đổi page đã thành công; ghi warning,
giữ private ownership ledger/lock và ngừng intake tới khi operator xử lý.

## Hành vi xử lý và giới hạn

Ưu tiên native theo trang; text sparse/hỏng hoặc image coverage nghi thiếu thì full
page OCR vie+eng/OEM1/PSM3. Geometry/column ordering/OSD còn heuristic, OCR cảnh báo
READING_ORDER_UNCERTAIN. Blank giữ entry; toàn blank là failed. success có ít nhất1ok,
còn lại ok/blank và không docerror; partial có ok và page/doc failure; failed không ok.
Cleanup là warning. Chưa calibration trên CV thật, không có quality readiness claim.

Trần contract v1 tại design/ocr-v1.limits.json:50pages,50.000Unicode codepoints mỗi
text/raw_text. Config chỉ giảm; invalid bị từ chối trước PDF. Tổng text+raw500.000,
result8MiB, engine8MiB, temp256MiB. Vượt output giữ whole-page prefix, phần loại có
OUTPUT_LIMIT, không truncate text. Envelope tối thiểu không vừa thì3/no delivery.
DPI300(config150–400),16Mpixel/6000px mỗi chiều trước render; preflight10s/page60s/
Tesseract40s/OSD5s/document600s, reserve7s cho cleanup/output, concurrency1.

## Kiểm chứng

Fresh-install Windows script tạo interpreter3.14.8 riêng, không pth tới site-packages cũ;
Ubuntu venv include-system-site-packages=false. Cả hai install --require-hashes offline
sau provisioning wheelhouse; pip check và package/binary/model inventory có evidence.
PATH Scripts chỉ đặt trong tiến trình provisioning rồi restore, không thay PATH global.

```powershell
# Source cuối, môi trường Windows sạch; đặt OCR_TEST_DOCKER_IMAGE bằng image SHA256 đã review.
& worker/build/m6-2-hardening/windows-clean-final/python.exe -W error worker/tests/run_checks.py --xml worker/build/m6-2-hardening/windows/tests.xml
```

OCR_TEST_TESSERACT/MODELS/FONT/REPORT_DIR là cấu hình test, không credentials. Missing
mandatory dependency phải fail, không skip. run_unprivileged_checks.py là Ubuntu
**subset**, không full gate. Full suite cuối dùng run_checks.py: Windows 80/0/0/0,
Ubuntu WSL 80/0/0/0, gồm đủ 13 isolation checks từng thiếu (2026-10-20).
Không đưa subset vào CI như full technical check. Chưa có lint/type tool cấu hình ở
baseline; compileall chỉ kiểm tra syntax, không được gọi là lint/type PASS.

Đọc design/M6_2_HARDENING_REPORT.md, M6_2_HARDENING_DECISIONS.md và review record.
M6_2_VERIFICATION.md là evidence iteration trước. GCC vẫn unresolved; 517sanitizer
cases reduced path không chứng minh mọi input an toàn. Chưa CER/WER/manual-ground-truth
quality evaluation: **OCR accuracy chưa đánh giá**, được đưa vào backlog riêng, không
là điều kiện nghiệm thu baseline theo quyết định 2026-10-21. Chưa real CV/M7/commit/
push/merge. Java/Gradle/PostgreSQL N/A. Source fingerprint không đổi thì dùng evidence
đã đối chiếu; không gọi việc đọc lại XML là một lần chạy tests mới.
