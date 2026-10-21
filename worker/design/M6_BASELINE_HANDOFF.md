# M6 baseline — bàn giao dùng thử (2026-10-21)

**Baseline dùng thử có đủ evidence kỹ thuật theo phạm vi đã điều chỉnh.**
**OCR accuracy chưa đánh giá. Production-ready: BLOCKED do GCC/Tesseract unresolved.**

Đây là worker/CLI offline nhận một PDF và trả text theo trang. M6 chưa tích hợp
Spring Boot, job lifecycle hoặc persistence. Không mapping business field, candidate
parsing, normalization business, DB hay search. Corpus, ground truth, CER/WER và
quality tuning được tách sang [backlog quality](OCR_QUALITY_BACKLOG.md).

## 1. Phiên bản bàn giao và chức năng

Worktree `E:/cv-compass-ocr-design`, branch `feature/ocr-worker`, HEAD/base
`5c06070955e2313f041be47ac313d97b2c079bea`; worker chưa commit.
Manifest 54 file code/tests/schema/examples/config/locks/scripts có SHA256
`59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`.
Ngày2026-10-21 đối chiếu lại 54/54 file: 0 mismatch; chỉ sửa tài liệu bàn giao.

- Private snapshot streaming với SHA256/identity check, giữ PDF nguồn; reject unsafe
  reference/path/link, file quá 10 MiB và encrypted PDF (kể cả password rỗng).
- Preflight trong child; xử lý tuần tự từng trang, native extraction trước, render
  PDFium + Tesseract vie+eng khi native không usable hoặc nghi thiếu scan body.
- Trả `text`, `raw_text`, method, warnings/errors, timings và engine/model metadata
  theo contract `cv-compass.ocr/1.0`. Text NFC/LF; không tự sửa chính tả/business data.
- Trang blank giữ record; page failure giữ trang trước và tiếp tục khi còn budget.
  Timeout/resource/output nghiêm trọng dừng intake trang còn lại theo safe error.
- Config chỉ giảm trần v1: 50 trang, 50.000 Unicode code points cho từng
  `text`/`raw_text` mỗi trang; tổng text+raw 500.000, result/engine output 8 MiB,
  work tmpfs 256 MiB. Output giữ whole-page prefix, phần loại có `OUTPUT_LIMIT`.
- Watchdog page/document, kill/reap child scope/descendants; cleanup lỗi chỉ warning,
  giữ primary result/error, lock/ownership ledger và halt intake khi cần operator.
- Atomic no-clobber publication. Result và verification report là hai file riêng,
  không có transaction chung; report tồn tại chưa chứng minh result đã giao.

| Exit | Result đã giao | Status/điều kiện |
|---:|---|---|
| 0 | Có | `success`: có trang ok, còn lại ok/blank, không document error |
| 1 | Có | `partial`: có trang ok và page/not_processed/document error |
| 2 | Có | `failed`: không có trang ok, kể cả toàn blank |
| 3 | Không | Không giao được result hợp lệ; output absent hoặc target cũ giữ nguyên |

Operator kiểm tra đồng thời exit, stderr receipt `delivered/status`, result JSON và
file output mới. Exit0 hoặc Tesseract confidence không chứng minh OCR accuracy.

## 2. Cách chạy baseline trên môi trường đã provision

Runtime: CPython3.14.8, pdfplumber0.11.10/pdfminer.six20260107, pypdfium2 5.14.0,
Pillow13.0.0, Tesseract5.5.3; models eng/vie/osd pin revision
`87416418657359cb625c412a48b6e1d6d41c29bd`. Lockfiles có hashes; fresh installation
hai OS và `pip check` đã có evidence. Không download khi xử lý PDF.

Môi trường Ubuntu giữ lại: WSL `CVCompass-Ubuntu-24.04`, user `cvcompass`, runtime,
venv và bản sao source cùng fingerprint tại `/opt/cvcompass-m62`. Docker Desktop
integration chỉ bật cho distro này. Docker Desktop đã dừng sau verification ngày20/10;
các lệnh dưới đây là hướng dẫn chạy khi cần, không được chạy lại trong đợt docs này.

Từ PowerShell, mở Docker Desktop rồi vào đúng distro/user:

```powershell
docker desktop start
wsl -d CVCompass-Ubuntu-24.04 -u cvcompass
```

Trong Bash của distro, kiểm tra local Linux daemon/cgroup v2 và image đã có:

```bash
export PATH=/opt/cvcompass-m62/venv/bin:/usr/bin:/bin
export PYTHONNOUSERSITE=1
export PYTHONPATH=/opt/cvcompass-m62/source/worker/src:/opt/cvcompass-m62/source/worker/tests
docker info --format '{{.OSType}} {{.CgroupVersion}}'
# Expected: linux 2
docker image inspect sha256:783dc8e152463ddaf10af2be459cf1c5e1073d654f95aab0f641b0c8de866629 --format '{{.Id}}'
```

Tạo fixture tổng hợp và request/config trong thư mục private mới; không dùng CV thật,
inbox/storage, credential hoặc dữ liệu D:. Ví dụ này tái sử dụng fixture generator
đã có, không thêm pipeline hay production flag:

```bash
umask 077
export CV_BASELINE_TRIAL=$(mktemp -d /home/cvcompass/m6-baseline.XXXXXX)
python - <<'PY'
import hashlib, json, os
from pathlib import Path
from fixtures import native, scan, combine

root = Path(os.environ['CV_BASELINE_TRIAL'])
digital, scanned = root/'native.pdf', root/'scan.pdf'
native(digital)
scan(scanned, '/opt/cvcompass-m62/runtime/fixture-font.ttf')
source = root/('a'*32+'.pdf')
combine(source, [digital, scanned], blanks=1)
request = dict(type='request', schema_version='cv-compass.ocr/1.0',
               request_id='trial-001', document_id='synthetic-001',
               input_ref=source.name,
               expected_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),
               profile='baseline-v1')
(root/'request.json').write_text(json.dumps(request), encoding='utf-8')
(root/'config.toml').write_text('''mode = "diagnostic"
adapter = "docker"
docker_image = "sha256:783dc8e152463ddaf10af2be459cf1c5e1073d654f95aab0f641b0c8de866629"
tesseract = "/opt/cvcompass-m62/runtime/bin/tesseract"
models = "/opt/cvcompass-m62/runtime/models"
preflight_timeout = 30
page_timeout = 30
''', encoding='utf-8')
print(root)
PY
```

Chạy worker và giữ exit code trước khi đọc receipt:

```bash
python /opt/cvcompass-m62/source/worker/run_worker.py \
  --config "$CV_BASELINE_TRIAL/config.toml" \
  --workspace "$CV_BASELINE_TRIAL" \
  --request "$CV_BASELINE_TRIAL/request.json" \
  --output "$CV_BASELINE_TRIAL/result.json" \
  --verification-report "$CV_BASELINE_TRIAL/verification.json" \
  --synthetic-only 2>"$CV_BASELINE_TRIAL/receipt.json"
worker_exit=$?
printf 'worker_exit=%s\n' "$worker_exit"
cat "$CV_BASELINE_TRIAL/receipt.json"
```

Chạy trong interactive Bash; nếu shell bật `set -e`, tắt nó quanh invocation để vẫn
đọc được receipt khi exit1/2/3. Đọc result/verification tại đúng thư mục vừa in.
Fixture gồm native+scan+blank; behavior của từng trang có assertions ở full suite,
không coi output demo là phép đo accuracy. Mỗi lần thử tạo thư mục/output mới vì
worker không overwrite. Sau dùng thử, operator chỉ dọn thư mục `CV_BASELINE_TRIAL`
do mình tạo sau khi kiểm tra không còn `.ocr-lock`/scope cần điều tra.

`--synthetic-only` là xác nhận operator, không phát hiện CV thật. `mode=production`
vẫn bị từ chối trước PDF. Không dùng native adapter cho resource-bomb; native chỉ
là diagnostic với fixture tổng hợp thông thường, không có hard resource isolation.

## 3. Evidence kỹ thuật được tái sử dụng

Lần chạy cuối ngày2026-10-20; ngày2026-10-21 đọc lại XML/log và kiểm tra hash/source,
không chạy lại suite vì source không đổi và không có technical finding mới.

| Environment | Tests | Failures | Errors | Skipped | Terminal duration |
|---|---:|---:|---:|---:|---:|
| Windows clean runtime + Docker child | 80 | 0 | 0 | 0 | 86.543s |
| Ubuntu24.04 WSL host supervisor + Docker child | 80 | 0 | 0 | 0 | 70.875s |

Mỗi môi trường có11suites: AdditionalBehavior11, DockerEnforcement11, Encryption3,
AdditionalEngineBoundaries4, ExitContract9, HardeningReview5, ContractRuntime3,
Pipeline12, ReviewRegression5, ContractChecks14, SpikeGuard3. Chi tiết counts/duration
theo suite ở [hardening report](M6_2_HARDENING_REPORT.md) mục I và các XML dưới đây.
Ubuntu chạy đủ13isolation checks từng thiếu:11DockerEnforcement + document-timeout
watchdog + 10MiB/+1 isolated engine. Giữ negative tests, không skip bắt buộc.

| Artifact trong worker/build/m6-current/ | SHA256 |
|---|---|
| windows-fresh/tests.xml | 8d45ca2fbe5e7a084b230971d98603754220a992278e08b7f24c5dd0253bee51 |
| windows-fresh/full-check.log | d51c1cf3e61562fb10ac308d96e6160a4e8a7369db993fdf78025b3de53cb375 |
| ubuntu-wsl-20261020/full-current/tests.xml | 286ad5fb5466e24c224cb061e45188c07c81c3d9e735d2e966bd07f726e54617 |
| ubuntu-wsl-20261020/full-current/full-check.log | 6e4a79837032596386e4c2dcb9d9a7b383095f5b2b2d35de7a9391fc5a794098 |

Original full commands: clean Windows interpreter `-W error worker/tests/run_checks.py
--xml .../windows-fresh/tests.xml`; Ubuntu venv interpreter `-W error
/opt/cvcompass-m62/source/worker/tests/run_checks.py --xml
/opt/cvcompass-m62/evidence/full-current/tests.xml`, với OCR_TEST_* trỏ runtime,
font/models và immutable child image đã ghi trong report. Đây là evidence local,
không CI. Java/Gradle/PostgreSQL/Modulith N/A vì worker/docs-only; lint/type tooling
chưa cấu hình. Không suy ra commit-level CI parity khi worker chưa commit.

## 4. Ranh giới vận hành và production

Docker PDF child dùng memory768MiB/swap0, CPU2, PIDs64, networknone/read-only root,
nonroot/capdropALL/no-new-privileges, tmpfs work256MiB và shm1MiB riêng. Input/control
named volume mới, read-only; không bind socket/credentials/inbox/storage thật. Image
pin SHA256/pullnever. Host supervisor ở ngoài child OOM scope, có quyền Docker daemon
được cấp; không có hard memory cap cho chính host supervisor/whole WSL VM.

| Hạng mục | Trạng thái và giới hạn |
|---|---|
| Contract/lifecycle/limits/source integrity | PASS trong phạm vi synthetic technical suite hai OS |
| Child resource enforcement | PASS cho bounded probes RAM/CPU/PID/temp/output/network/read-only; không chứng minh mọi hostile PDF an toàn |
| Crash/timeout/cleanup | Descendants và OOM/watchdog có evidence; cleanup lỗi daemon/FS dùng failure injection, không bảo đảm hard host-I/O deadline |
| Accuracy/layout/rotation | **OCR accuracy chưa đánh giá**; heuristics có thể bỏ/đọc sai chữ/cột/orientation, confidence không phải accuracy |
| GCC/Tesseract binary | **UNRESOLVED — production BLOCKED**; full serialis.cpp có40warnings GCC13.3.0/O3/NDEBUG/gnu++20; reduced ASan/UBSan517cases không đủ kết luận false positive |
| Deployment/recovery | Ubuntu VPS thật, hard I/O deadline và host supervisor cap NOT VERIFIED; kill supervisor có thể để stopped container/volume/lock cần operator cleanup |
| Packaging | Chạy từ source checkout + provisioned runtime/images; chưa có installer/wheel phát hành hoặc full binary SBOM |

Không suppress/hạ optimization/đổi compiler/patch upstream hay thêm bypass để hết log.
Chi tiết bằng chứng compiler/flags/source tại [GCC investigation](M6_2_GCC_INVESTIGATION.md).
Quyết định hoãn accuracy không thay đổi blocker này. Chưa phê duyệt dùng CV thật hay
deployment production. Bản bàn giao baseline được chuẩn bị để developer nghiệm thu;
commit/push/merge và bắt đầu M7 vẫn cần chỉ định riêng.
