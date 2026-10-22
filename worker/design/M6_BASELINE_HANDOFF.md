# M6 baseline — handoff PR #9 hiện hành

PR [#9](https://github.com/hoangnguyen229/cv-compass/pull/9), `feature/ocr-worker`.
Đợt sửa endpoint/staging/build/CI nối tiếp baseline ee6d4a7, giữ pipeline PDF → page
text. Source đã đổi: evidence80/80 cũ chỉ là lịch sử. Kết quả/fingerprint mới và review
record ở [M6_PR9_REVISION.md](M6_PR9_REVISION.md).
Đợt cleanup ownership nối tiếp: [M6_PR9_CLEANUP_OWNERSHIP.md](M6_PR9_CLEANUP_OWNERSHIP.md).
Evidence103 cũ không xác minh source đã đổi của đợt này.

**OCR accuracy chưa đánh giá. Production vẫn BLOCKED do GCC/Tesseract unresolved.**
Baseline dùng thử chỉ synthetic diagnostic; technical pass không chứng minh accuracy,
memory safety mọi input hoặc production-ready. Không M7/mapping fields/DB/CV thật.

## Build/verify tái lập

Prerequisites và hai lệnh Linux/WSL hiện hành ở [README](../README.md):
`build_baseline.py` tạo đủ runtime/dependency/worker image từ checkout;
`verify_baseline.py` fresh-install host venv bằng hashes, pip check và full runner;
`verify_baseline.py --cleanup-only` dọn resource do run tạo. Không dependency image
laptop. Mạng chỉ dùng provisioning; child networknone. Pin/checksums ở
`spikes/m6_2_hardening/provisioning.json` và requirements*.lock; compiler diagnostics
được giữ nguyên trong logs/provenance, không suppress.

Mỗi lượt có UUID run_id riêng trong `resources.owner.json` (exclusive trước resource),
truyền tới trusted test subprocess, label cả writer/child/input volume/asset. Cleanup
lọc run_id và inspect lại owner/scope, kiểm endpoint, remove container ID trước volume.
Không dùng snapshot trước/sau, label scope chung hoặc tên asset làm ownership.
`--cleanup-only --build <output>` phải trỏ state của lượt đã dừng; giữ state để retry.
Thiếu state nhưng có evidence/legacy snapshot thì refuse, không đoán owner. Lỗi
inventory/inspect/remove/report write trả nonzero; xem cleanup.json/stderr, không
tự dọn resource run khác. State chỉ là authority của trusted supervisor, không
chống operator cùng quyền daemon cố ý giả mạo label/state.

Default evidence directory `worker/build/baseline` có source-manifest.json,
source-version.json, images.json, runtime/dependency/worker-build.log; verification/
có tests.xml, full-check.log, fresh-install.log, pip-check.log, syntax.log,
environment.json, isolation probes, compiler provenance và cleanup.json. Lưu directory
cũ trước run mới hoặc dùng `--output`/`--build` trỏ cùng subdirectory mới dưới worker/build.
CI dùng Ubuntu24.04 host và setup-python3.14.8; backend CI giữ nguyên. CI artifacts
chỉ report/provenance, không upload runtime/wheels/CV.

## Fixture trial sau verification

Trong Bash của Ubuntu host đã chạy build/verify, từ repository root, venv host mới
được tạo dưới `worker/build/baseline/host-venv-*`. Dùng chính interpreter đó (thay
đúng path vừa tạo trong command), không đổi Windows PATH global.

```bash
export PYTHONPATH="$PWD/worker/src:$PWD/worker/tests"
export CV_BASELINE_WORKER="$PWD/worker"
umask 077
export CV_BASELINE_TRIAL=$(mktemp -d)
```

Dùng interpreter host venv nói trên chạy đoạn Python tạo fixture/request/config:

```python
import hashlib, json, os
from pathlib import Path
from fixtures import native, scan, combine

worker=Path(os.environ['CV_BASELINE_WORKER'])
root=Path(os.environ['CV_BASELINE_TRIAL'])
build=worker/'build/baseline'
digital,scanned=root/'native.pdf',root/'scan.pdf'
native(digital)
scan(scanned,build/'verification/font.ttf')
source=root/('a'*32+'.pdf')
combine(source,[digital,scanned],blanks=1)
(root/'request.json').write_text(json.dumps(dict(
    type='request',schema_version='cv-compass.ocr/1.0',request_id='trial-001',
    document_id='synthetic-001',input_ref=source.name,
    expected_sha256=hashlib.sha256(source.read_bytes()).hexdigest(),profile='baseline-v1')))
image=json.loads((build/'images.json').read_text())['worker']
(root/'config.toml').write_text('mode="diagnostic"\nadapter="docker"\n'
    +'docker_image='+json.dumps(image)+'\n'
    +'tesseract='+json.dumps(str(build/'verification/tesseract'))+'\n'
    +'models='+json.dumps(str(build/'verification/models'))+'\n'
    +'preflight_timeout=30\npage_timeout=30\n')
print(root)
```

Gọi CLI bằng interpreter host venv, truyền absolute paths trong trial vừa tạo:

```bash
python worker/run_worker.py --config "$CV_BASELINE_TRIAL/config.toml" \
  --workspace "$CV_BASELINE_TRIAL" --request "$CV_BASELINE_TRIAL/request.json" \
  --output "$CV_BASELINE_TRIAL/result.json" \
  --verification-report "$CV_BASELINE_TRIAL/verification.json" \
  --synthetic-only 2>"$CV_BASELINE_TRIAL/receipt.json"
worker_exit=$?
printf 'worker_exit=%s\n' "$worker_exit"
cat "$CV_BASELINE_TRIAL/receipt.json"
```

`python` ở đây phải là interpreter3.14.8 của host venv đã nói trên; dùng absolute path
thay `python` nếu chưa activate. Interactive Bash cần cho phép exit1/2/3 để đọc
receipt (không bật errexit quanh invocation). Kiểm tra đồng thời exit, delivered,
status và result mới. Mỗi lần dùng output mới, không overwrite. `--synthetic-only`
là xác nhận operator, không phát hiện CV thật. Không tự thay `mode=production`.

## Chức năng và giới hạn còn lại

Native extraction và fallback render/OCR offline từng trang, raw_text/text/warnings/
errors/timings theo v1.0; reject encrypted cả password rỗng, unsafe refs, source
hash/identity mismatch. 10MiB/50trang,50.000codepoints mỗi text/raw_text/trang,
output không truncate âm thầm; contract/status/exit table ở README. Schema/limits trong
design là runtime dependency; smoke/test code còn trong spikes được full runner import.

Docker endpoint ghim local theo precedence, writer kill/wait/remove trước volume,
cleanup failure truyền bằng memory dù ledger/disk lỗi; giữ primary và completed page,
lock/halt để operator xử lý. Child có RAM/CPU/PID/tmpfs/network/read-only limits,
không socket/privileged/credential/storage mount. Host supervisor cap/hard I/O deadline,
VPS deployment và crash recovery đầy đủ vẫn NOT VERIFIED. Cleanup sau supervisor
death có thể cần operator kiểm tra owned resource/lock; không hứa crash-proof.

Quality/corpus/ground truth/CER-WER/tuning ở [backlog](OCR_QUALITY_BACKLOG.md).
GCC evidence lịch sử ở [investigation](M6_2_GCC_INVESTIGATION.md); build mới giữ
compiler/flags/full warnings ở provenance. Không suppression/bypass, technical CI
pass không mở gate. Chưa merge hay bắt đầu milestone tiếp theo.
