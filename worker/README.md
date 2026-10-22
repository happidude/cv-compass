# CV Compass — M6 offline PDF → page text baseline

PR [#9](https://github.com/hoangnguyen229/cv-compass/pull/9), branch `feature/ocr-worker`.
Baseline dùng thử với fixture tổng hợp. **OCR accuracy chưa đánh giá. Production vẫn
BLOCKED do GCC/Tesseract unresolved.** Technical CI pass không mở production gate.

Pipeline hiện có: snapshot/hash PDF → child preflight → native text theo trang →
PDFium render/Tesseract vie+eng khi cần → validated page results → cleanup/publication.
Không mapping business fields, candidate parsing, DB, HTTP integration hoặc M7.

## Build và full gate từ checkout sạch

Linux/Ubuntu24.04 x86_64 (Windows dùng Ubuntu WSL), CPython3.14.8 có pip/venv, Git,
Docker local Linux cgroupv2. Host cần Leptonica runtime (`sudo apt-get install liblept5`).
Docker build cần mạng để cài Ubuntu build libraries và tải artifact chính thức; PDF
children chạy offline/networknone. Không thay Python/PATH global Windows.

Từ repository root:

```sh
python3.14 worker/spikes/m6_2_hardening/build_baseline.py
python3.14 worker/spikes/m6_2_hardening/verify_baseline.py
# Chạy cả khi verification bị lỗi/cancel:
python3.14 worker/spikes/m6_2_hardening/verify_baseline.py --cleanup-only
```

Builder tạo fresh runtime/dependency/worker context trong `worker/build/baseline`,
build từ Ubuntu image pin digest, CPython/Tesseract archives, models pin SHA256 và
font có checksum. Dependency wheels tải theo hai hash lockfiles rồi cài offline
`--require-hashes` + `pip check`. Không cần image có sẵn trên laptop. Context được
xóa/recreate có kiểm tra target nằm dưới worker/build, không giữ source đã xóa.
Image IDs, source manifest/version và full compiler/build logs ở output directory.

Verifier tạo venv host mới, cài lockfiles với hashes, chạy pip check, syntax compileall
và **full `tests/run_checks.py`**, gồm Docker isolation tests. Missing prerequisite
phải fail; `run_unprivileged_checks.py` chỉ là subset lịch sử. Compileall chỉ là syntax
check, chưa có linter/type checker hoặc Python package build. Mỗi lần full gate cần
verification directory mới; giữ evidence lần trước ở output khác, không ghi đè report.

Mỗi verification tạo UUID `run_id` mới và ghi exclusive `resources.owner.json`
(version/run_id/local endpoint) trước khi tạo resource. `CV_COMPASS_VERIFICATION_RUN_ID`
truyền ownership tới trusted test supervisors; writer/child/input volume và asset
container mang label riêng `cv-compass.verification-run=<run_id>`. Không truyền biến
này hoặc Docker socket vào PDF child. Không tự đặt/tái sử dụng run_id giữa các lượt.
Cleanup (kể cả `--cleanup-only --build <đúng output>`) chỉ discover theo run_id,
inspect xác nhận owner/scope, xóa container theo immutable ID trước volume; kiểm tra
endpoint trùng state. Không snapshot diff/label chung/name prefix làm authority.
Giữ state cùng evidence; không gọi cleanup-only khi lượt đó còn chạy. Missing/legacy
state hoặc endpoint khác không được dùng để dọn tự động. Inventory/inspect/remove/
evidence-write failure trả nonzero, ghi leftovers trong cleanup.json/stderr để operator
xử lý; không xóa resource của lượt khác. Chi tiết [ownership revision](design/M6_PR9_CLEANUP_OWNERSHIP.md).

`.github/workflows/worker-ci.yml` dùng đúng hai script trên trên Ubuntu host + Docker
child cho worker/workflow changes. Backend CI không đổi. JUnit/logs/provenance được
upload kể cả thất bại; cleanup step có `always()`. Chi tiết acceptance và evidence
source mới ở [PR9 revision](design/M6_PR9_REVISION.md); số80/80 cũ là lịch sử.

## Chạy CLI dùng thử

Xem [handoff](design/M6_BASELINE_HANDOFF.md) cho config/request và fixture. Dùng
CPython3.14.8 với dependencies đã cài; Docker image là ID vừa build trong images.json.

```sh
python worker/run_worker.py --config /private/config.toml \
  --workspace /private/workspace --request /private/request.json \
  --output /private/new-result.json --verification-report /private/new-verification.json \
  --synthetic-only
```

`mode="diagnostic"`, `adapter="docker"` cho bounded trial. `--synthetic-only` là xác
nhận operator, không phát hiện CV thật. Production mode bị từ chối trước xử lý PDF;
không có flag bypass. Không dùng CV thật, D:, credentials hoặc inbox/storage thật.

| Exit | Result được giao | Status |
|---:|---|---|
| 0 | Có | success: có ok, còn lại ok/blank, không document error |
| 1 | Có | partial: có ok và page/not_processed/document error |
| 2 | Có | failed: không có ok, kể cả toàn blank |
| 3 | Không | result absent hoặc target cũ giữ nguyên |

Kiểm tra exit/status/stderr receipt delivered/output cùng nhau. Text NFC/LF,
raw_text giữ output method đã chọn. Output mới, atomic no-clobber; report/result
không commit nguyên tử cùng nhau. Exit0/confidence không chứng minh accuracy.
Trần v1:50trang,50.000Unicode codepoints mỗi text/raw_text/trang; config chỉ giảm,
không truncate âm thầm, phần loại có OUTPUT_LIMIT. Tổng text+raw500.000,
result/engine8MiB, worktmpfs256MiB. Cleanup lỗi chỉ warning, giữ trang đã xong,
primary error và lock/halt để operator xử lý resource còn sót.

## Kiến trúc và ranh giới

`run_worker.py` bootstrap src; `src/cv_compass_worker` chứa CLI/config/contracts,
pipeline/PDF/OCR/layout và native/Docker adapters. `design/*.json` là runtime dependency:
config/contracts đọc schema/canonical limits theo relative path; phải có trong checkout
và image, chưa đóng gói wheel. `tests/` tạo synthetic PDFs và bounded resource helper;
`spikes/m6_1/*.py` có smoke/guard tests được full runner import. Build/verify scripts
ở `spikes/m6_2_hardening/` là test/provision tooling; dependency test dùng
requirements-test.lock. Không tái cấu trúc pipeline chỉ để đổi tên thư mục.

Endpoint resolution: named DOCKER_CONTEXT ưu tiên DOCKER_HOST, rồi current context theo
[Docker CLI docs](https://docs.docker.com/reference/cli/docker/). Resolve/validate local
unix/npipe trước snapshot/resource/stream. Context đặc biệt `default` có endpoint
in-memory lấy từ DOCKER_HOST; resolver giữ host khi đọc metadata để reject đúng remote.
Mọi command trong lượt xử lý dùng cùng
explicit `--host` và environment snapshot, không thừa hưởng routing overrides mới.

Supervisor ngoài child scope. Writer có tên xác định, bounded pipe wait/watchdog,
kill/wait/remove daemon container trước input volume; Popen/stream/timeout đều vào
cleanup. Cleanup flag truyền bằng memory qua prepare/scope/child/pipeline kể cả disk
full làm ledger không ghi được. Ledger chỉ là evidence/recovery aid, không điều kiện
duy nhất để halt intake.

PDF child:768MiB/no swap/CPU2/PIDs64, nonroot/read-only/networknone, capdropALL,
no-new-privileges, input volume RO, tmpfs work256MiB + shm1MiB riêng. Không Docker
socket/credentials/host storage mount. Native adapter chỉ diagnostic với fixture thường,
không hard isolation. Host supervisor memory cap/hard host-I/O deadline/VPS thật chưa
kiểm chứng; abrupt supervisor death có thể để resource/lock cần operator dọn.

Accuracy evaluation/tuning ở [backlog riêng](design/OCR_QUALITY_BACKLOG.md).
GCC warning vẫn unresolved độc lập; không suppress, đổi compiler/hạ optimization hay
bypass để tạo PASS. Historical reports có banner dẫn tới trạng thái hiện hành.
