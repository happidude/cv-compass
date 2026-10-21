# M6.2 Hardening — verification report

Ngày2026-10-18, cập nhật nghiệm thu2026-10-21. **M6 baseline dùng thử: đủ evidence
kỹ thuật theo phạm vi mới. Production-ready: BLOCKED do GCC/Tesseract unresolved.**
Windows và Ubuntu WSL full technical suite PASS trên source fingerprint bên dưới.
**OCR accuracy chưa đánh giá**; quality evaluation/improvement chuyển backlog riêng,
không là điều kiện nghiệm thu baseline. Xem [bản bàn giao](M6_BASELINE_HANDOFF.md).
Không CV thật, business mapping/candidate parsing/DB/normalization business/M7.

## A. Source và workflow

Branch `feature/ocr-worker`, worktree `E:/cv-compass-ocr-design`, base/HEAD
`5c06070955e2313f041be47ac313d97b2c079bea`. Giữ thay đổi M6.1/M6.2 đã có;
không commit/push/merge/force-add hoặc thay main. Chỉ worker/ thay đổi.
Đọc AGENTS/workflows đầy đủ, designM6.1, verification/GCC investigation và source.
Quyết định trước implementation: M6_2_HARDENING_DECISIONS.md. Single-agent Developer/
Reviewer, tối đa3passes iteration mới; record M6_2_HARDENING_REVIEW.md.

Source manifest54file code/schema/examples/config/locks/scripts/CPP SHA256:
`59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`. `build/m6-2-hardening/final-source-manifest.json`, đối chiếu từng hash sau
final tests. Markdown evidence riêng để không self-reference. Baseline trước iteration
ở previous-source-manifest.json. Không dùng kết quả source trước fix làm final PASS.

Worker image: `sha256:783dc8e152463ddaf10af2be459cf1c5e1073d654f95aab0f641b0c8de866629`.
Ubuntu subset test image: `sha256:9b09768a96ef13c2518f14ae531fefca65b64662a6354ff592fb7a5adbfd154d`.
Images chứa snapshot source cuối; child không mount host source/storage/socket.
Docker CLI/daemon sẵn trên Windows (29.6.1); không cài daemon/service/global PATH.
Ubuntu ở Docker Desktop Linux/WSL2, không phải Ubuntu VPS thật; deploy VPS NOT VERIFIED.

## B. Fresh installation từ hash lockfiles

Windows embedded CPython3.14.8 được copy runtime chuẩn sang windows-clean-final,
python314._pth chỉ stdlib/current clean site-packages/worker source. Không trỏ .venv
hoặc build M6.1 site-packages. Bootstrap pip chỉ là installer/`pip --python <clean>`;
14package distributions thực tế đều inside clean prefix (13runtime+1fixture pypdf).

```powershell
& worker/spikes/m6_2_hardening/provision_windows.ps1 -CleanName windows-clean-final
# Trong script, interpreter đích là windows-clean-final/python.exe:
# pip --python <clean-python> install --no-index --find-links <wheelhouse> --only-binary=:all: --require-hashes -r worker/requirements.lock -r worker/requirements-test.lock
# pip --python <clean-python> check
```

Ubuntu tạo **venv mới /opt/cv-venv**, include-system-site-packages=false, cài toàn bộ
runtime/test bằng --require-hashes/--no-index từ Linux wheelhouse. Có thể có packages
cũ trên filesystem base image nhưng không nằm trên sys.path/không dùng chúng:
15distributions trong venv mới (14như Windows + pip từ ensurepip), đều inside prefix.
Build install log có Processing+Installing+Successfully installed, không chỉ satisfied.
`pip check` hai môi trường: No broken requirements found. Dependencies lock không đổi pin.

Lệnh build offline: `docker build --network none -t cv-compass-m62-clean-deps:local
worker/build/m6-2-hardening/deps-context`; Dockerfile.dependencies và context copy đúng
lockfiles/wheels được lưu. Không runtime downloads. Python chuẩn3.14.8 hai OS.

Environment/package versions/locations và model hashes tại
`build/m6-2-hardening/{windows,ubuntu}/environment.json`.
Tesseract executable SHA256 Windows `c66f0f12ed76f6aa455dac97684bbc86756d6a732380bee09122454cfda3f420`;
Ubuntu `42f720ddd55deb9bf2263f4d789a70f65f2a36c31a1645b582a53e4e424f9869`. Đây là executable hash, không tuyên bố toàn bộ DLL/
shared-library SBOM. Ba model exact revision87416418657359cb625c412a48b6e1d6d41c29bd:

- eng: `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2`
- vie: `79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1`
- osd: `9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff`

Package versions hai OS đồng nhất: pdfplumber0.11.10/pdfminer20260107/PDFium wrapper5.14.0/
Pillow13.0.0/jsonschema4.26.0, Tesseract numeric5.5.3; Leptonica Windows1.87.0/Ubuntu1.82.0.
Mô hình/compiler provenance không suy từ Python pin. Binary production vẫn BLOCKED.

## C. Exit contract đã đồng bộ

| Exit | JSON status | delivered | Output |
|---:|---|---|---|
| 0 | success | true | result mới, schema hợp lệ |
| 1 | partial | true | result mới, schema hợp lệ |
| 2 | failed | true | result mới, schema hợp lệ |
| 3 | không có result status được giao | false | absent hoặc existing target giữ nguyên |

Request/config invalid nhưng có version/opaque IDs đã validate đủ cho failed envelope
thì2. Invalid identity/JSON hỏng/argument lỗi/envelope không vừa/publication lỗi thì3.
Receipt stderr chỉ code/delivered/status; không thêm field vào OCR JSONv1/schema/version.
Invalid config không chi phối fallback caps. Không suy accuracy từ exit0.
Đã test code+JSONstatus+receipt+output bằng CLI subprocess thật; nguyên file output cũ
được giữ khi no-clobber fail. Examples design/cli-exit-examples.json, README/design đồng bộ.

## D. Final commands và JUnit trước Ubuntu WSL update

Số liệu Ubuntu subset trong mục này là lịch sử ngày2026-10-18; full Ubuntu WSL mới
ở mục I thay thế trạng thái subset/13 checks chưa chạy.

Từ E:/cv-compass-ocr-design, Windows dùng clean-final interpreter. OCR_TEST_DOCKER_IMAGE
là immutable worker image ở trên, OCR_TEST_REPORT_DIR=.../windows; TESSERACT/MODELS/FONT
chỉ fixture paths, không credentials.

```powershell
& worker/build/m6-2-hardening/windows-clean-final/python.exe -W error -m compileall -f -q worker/src worker/tests worker/spikes/m6_2_hardening
& worker/build/m6-2-hardening/windows-clean-final/python.exe -W error worker/tests/run_checks.py --xml worker/build/m6-2-hardening/windows/tests.xml
```

Ubuntu command trong nonroot/read-only/networknone/memory1GiB/CPU2/PIDs64 container,
chỉ report RW mount, **không Docker socket**:

```sh
python -m pip check
python /opt/worker/spikes/m6_2_hardening/record_environment.py > /report/environment.json
python -W error /opt/worker/tests/run_unprivileged_checks.py --xml /report/tests.xml
```

Full Windows80tests; Ubuntu67tests được chọn minh bạch, **không gọi là full gate**.
Trong lần chạy lịch sử này,13mandatory Docker checks chưa thực hiện; mục I ghi lần chạy
2026-10-20 đã thực hiện đủ, không skip.
List exact method IDs: build/m6-2-hardening/ubuntu/blocked-checks.json.

| Environment | Suite | Tests | Failures | Errors | Skipped | Duration(s) |
|---|---|---:|---:|---:|---:|---:|
| windows | test_additional.AdditionalBehaviorTests | 11 | 0 | 0 | 0 | 10.492 |
| windows | test_docker_enforcement.DockerEnforcementTests | 11 | 0 | 0 | 0 | 38.797 |
| windows | test_encryption.EncryptionTests | 3 | 0 | 0 | 0 | 0.056 |
| windows | test_hardening_contract.AdditionalEngineBoundaries | 4 | 0 | 0 | 0 | 17.576 |
| windows | test_hardening_contract.ExitContractTests | 9 | 0 | 0 | 0 | 7.729 |
| windows | test_hardening_review.HardeningReviewTests | 5 | 0 | 0 | 0 | 0.033 |
| windows | test_pipeline.ContractRuntimeTests | 3 | 0 | 0 | 0 | 0.006 |
| windows | test_pipeline.PipelineTests | 12 | 0 | 0 | 0 | 11.704 |
| windows | test_review_regressions.ReviewRegressionTests | 5 | 0 | 0 | 0 | 0.814 |
| windows | verify_contract.ContractChecks | 14 | 0 | 0 | 0 | 0.168 |
| windows | test_stack_smoke.SpikeGuardTests | 3 | 0 | 0 | 0 | 0.002 |
| ubuntu | test_encryption.EncryptionTests | 3 | 0 | 0 | 0 | 0.072 |
| ubuntu | test_pipeline.ContractRuntimeTests | 3 | 0 | 0 | 0 | 0.007 |
| ubuntu | test_pipeline.PipelineTests | 12 | 0 | 0 | 0 | 9.701 |
| ubuntu | test_additional.AdditionalBehaviorTests | 11 | 0 | 0 | 0 | 8.333 |
| ubuntu | test_review_regressions.ReviewRegressionTests | 5 | 0 | 0 | 0 | 0.562 |
| ubuntu | test_hardening_review.HardeningReviewTests | 5 | 0 | 0 | 0 | 0.012 |
| ubuntu | verify_contract.ContractChecks | 14 | 0 | 0 | 0 | 0.134 |
| ubuntu | test_stack_smoke.SpikeGuardTests | 3 | 0 | 0 | 0 | 0.002 |
| ubuntu | test_hardening_contract.ExitContractTests | 9 | 0 | 0 | 0 | 6.127 |
| ubuntu | test_hardening_contract.AdditionalEngineBoundaries | 2 | 0 | 0 | 0 | 9.435 |

Windows total80/0/0/0, terminal87.381s. Ubuntu subset67/0/0/0, terminal34.387s.
Durations không phải benchmark OCR. JUnit SHA256 Windows `3cea11187f38ae58ea36637cc60988af30c3ed201a77089fb3dbb5d348d5ec57`;
Ubuntu `1e78d1f1627f598b1be6169a861cb403e9ba679ac658b7060f37d8c8fd79ed10`. Đọc toàn terminal logs/XML mới, không cache/up-to-date.
Final terminal logs không có unexpected Python warning/error. Negative cases giữ nguyên.
Lint/type tasks **NOT APPLICABLE**: baseline chưa cấu hình linter/type checker, không bịa
PASS; compileall chỉ syntax. Java/Gradle/PostgreSQL/migrations/Modulith/problems report
N/A vì Python-only, không dùng CI/backend checks cũ.

## E. Isolation requested/applied/tested

Chọn common Docker Linux adapter thay Windows Job Object theo yêu cầu mới. Host
supervisor ở ngoài child container, giữ khả năng trả safe lỗi khi child OOM. Trước
bất kỳ staging/volume/source read nào, reject remote Docker endpoint, yêu cầu local
unix/npipe/Linux cgroupv2. Image pin SHA256/pullnever. Không Docker API credentials/socket
vào PDF scope; trusted staging writer chỉ stream bounded bytes, không parse PDF.

Default child memory805306368(no swap), CPU2000millicores, PIDs64; inspect HostConfig
và cgroup values thực tế khớp. /work + /work/engine tmpfs tổng268435456bytes,
engine mount reserve8388608default; /dev/shm1MiB riêng. No network/rootreadonly,
user65532/capdropALL/no-new-privileges/core0/logdriver-none; volume input mới duy nhất RO.
Không inbox/storage/DB mount. Host supervisor chưa có hard256MiB cap; scope này là child
container tree, không tuyên bố host hoặc toàn VM được capping1GiB.

Windows host supervisor đã chạy enforcement thật trong Linux scopes:

| Bounded probe | Cgroup memory.peak(bytes) | Kết quả |
|---|---:|---|
| cpu | 27,668,480 | success |
| crash_descendant | 16,146,432 | ENGINE_CRASH |
| descendant | 17,788,928 | PAGE_TIMEOUT |
| oom | 67,108,864 | RESOURCE_LIMIT |
| output | 15,368,192 | OUTPUT_LIMIT |
| output_quota | 14,716,928 | OUTPUT_LIMIT |
| permissions | 14,610,432 | success |
| pids | 23,756,800 | RESOURCE_LIMIT |
| temp | 20,090,880 | TEMP_LIMIT |

OOM64MiB: memory.peak67108864, memory.events.oom_kill=1, Docker OOMKilled=true;
supervisor/test runner tiếp tục sống, RESOURCE_LIMIT. Peak là accounting toàn cgroup
(process tree+cache/tmpfs), không chỉ RSS, không quality/performance benchmark.
CPU probe .5CPU có cpu.max50000/100000 và nr_throttled>0; PIDs8probe có pids.events.max>0;
temp/output quota có ENOSPC/free bytes0; assertions phân biệt các cause an toàn.

Timeout với detached descendant và child crash17: whole scope kết thúc, parent wait/
remove, không process chạy trong scope sau đó. Kill supervisor riêng: independent
watchdog1.5s vẫn kết thúc scope, Docker State.Running=false/Pid0/ExitCode0. Không hứa kill
tức thì; stopped container+RO input volume còn tới khi test owner dọn. Observations ở
isolation-supervisor-death.json. Không recovery scheduler/exactly-once/crashproof.

Final inspection: không còn container labelocr-child hoặc input volume labelocr-input.
Cleanup fault/disk-full ledger tests là **failure injection**, không giả là Docker daemon
mất kết nối thật. Chúng chứng minh giữ primary error/completed pages, CLEANUP_FAILED,
lock/ledger và halt intake. Daemon/FS treo/hard I/O deadline **NOT VERIFIED**, không claim.

Real technical boundaries: full10MiB PDF hợp lệ với isolated engine; +1byte reject trước
child; rotations180/270 và nearblank qua native/render/OCR thật; document timeout thật.
Giữ priorpages/partial/OUTPUT_LIMIT/source digest/no-overwrite có regression. Chưa hostile
PDF/resource bomb hoặc real CV. Native diagnostic không được gọi là production isolation.

Privacy: receipt/operator logs chỉ safe metadata. Child output JSON là private IPC
container.response.json, Docker logging disabled; *.log không chứa synthetic PDF text/
source filename/path trong real-container assertion. Raw engine diagnostics chỉ ở bounded
private tmpfs và cleanup, không dump ra user log. Không tuyên bố audit mọi malformed PDF.

## F. GCC kết luận có phạm vi

**UNRESOLVED**, production binary BLOCKED. Full serialis.cpp vẫn40diagnostics cùng
GCC13.3.0/CMake3.28.3/-O3/-DNDEBUG/stdgnu++20 và source5.5.3/hash9218e627...5884.
Full compile flags/source locations/diagnostic và callchain nằm gcc-full/, không lọc.

Reduced ReverseN/FReadEndian/DeSerialize:uint32 không tái hiện warning; optimized assembly
lưu gcc/reproducer.s. ASan+UBSan517bounded cases exit0 với cùng GCC/-O3. Không phải
minimal reproducer thành công cho warning, không đủ kết luận compiler false positive
hoặc mọi input an toàn. Không suppress/đổi compiler/hạ optimization/patch/pin mới.
Không áp PR4527 trộn web interface; nó chưa merge, không phải accepted fix.
Chi tiết/căn cứ upstream: M6_2_GCC_INVESTIGATION.md. Diagnostic compiler paths là source
public/build evidence, không CV filenames hay business data.

## G. Blockers và readiness

1. GCC full-TU/binary unresolved; production refusal không có bypass flag.
2. Ubuntu VPS thật/hard I/O deadline/host supervisor resource cap chưa kiểm chứng đầy đủ.
3. Quality evaluation NOT PERFORMED: **OCR accuracy chưa đánh giá**. Corpus, ground
   truth, CER/WER, contact/reading-order evaluation và tuning ở
   [backlog riêng](OCR_QUALITY_BACKLOG.md), không chặn nghiệm thu baseline theo quyết
   định2026-10-21. Technical pass không chứng minh accuracy.
4. CI NOT VERIFIED: không commit/push theo yêu cầu; main không đổi. Chưa merge/M7.

Fresh-install và full technical enforcement hai OS đủ cho bàn giao baseline dùng thử.
Production tiếp tục BLOCKED; cần giải quyết GCC theo bằng chứng trước khi xét mở gate.
Quality evaluation là backlog riêng chưa thực hiện. M7 chưa bắt đầu.

## H. Artifacts

README; M6_2_HARDENING_DECISIONS.md; M6_2_HARDENING_REVIEW.md; cli-exit-examples.json.
build/m6-2-hardening/: final-source-manifest.json, runtime-images.json,
windows-clean-final/{download.log,install.log,pip-check.log,environment.json},
ubuntu-fresh-install.log, {windows,ubuntu}/{tests.xml,environment.json},
windows/full-check.log, ubuntu/unprivileged-check.log, windows/isolation-*.json,
gcc-full/ full diagnostic+compiler+source, gcc/ commands+assembly+ASan logs.
Builds/models/wheels/binaries/PDF fixtures đều ignored, không force-add docs local.

## I. Verification update — Ubuntu WSL host supervisor (2026-10-20)

Phần này thay thế trạng thái Ubuntu subset/13 checks BLOCKED ở các mục D, G cho đúng
source fingerprint dưới đây. Kết quả test ghi ngày2026-10-20; phạm vi nghiệm thu mới
ngày2026-10-21 ở phần đầu báo cáo và bản bàn giao.

### Environment và fresh install

- Import riêng Ubuntu 24.04 LTS WSL2 tên `CVCompass-Ubuntu-24.04` tại
  `E:/wsl/cv-compass-ubuntu-24.04`; distro mặc định vẫn là `docker-desktop`.
- Rootfs official Ubuntu Noble WSL SHA256
  `2a790896740b14d637dbdc583cce1ba081ac53b9e9cdb46dc09a2f73abbd9934`.
- Docker Desktop integration chỉ bật cho distro mới; option integration với default
  distro vẫn tắt. Supervisor chạy ngoài child container qua `/var/run/docker.sock`;
  PDF child không mount socket, không privileged, không credential/inbox/storage/D:.
- Docker Desktop 4.82.0, Engine 29.6.1, Linux cgroup v2; WSL kernel 6.18.33.2.
- Tạo user không đặc quyền `cvcompass`, member group `docker`; test tree riêng tại
  `/opt/cvcompass-m62`. Windows Python/PATH và WSL default không đổi.
- CPython 3.14.8/Tesseract 5.5.3/models/font được trích từ immutable supervisor image
  `sha256:9b09768a96ef13c2518f14ae531fefca65b64662a6354ff592fb7a5adbfd154d`;
  Ubuntu packages cài/đổi chỉ phục vụ runtime: `ca-certificates` upgrade và mới
  `libgif7`, `libopenjp2-7`, `libwebpmux3`, `liblept5`, `libleptonica-dev`
  (`fonts-dejavu-core` đã có).
- Venv mới `/opt/cvcompass-m62/venv`, `PYTHONNOUSERSITE=1`; install offline từ Linux
  wheelhouse với `--no-index --only-binary=:all: --require-hashes` cho cả hai lockfile.
  Kết quả `pip check`: `No broken requirements found.` Fresh-install log SHA256
  `c97de19f1a33df3953adde26dbbf6383729ff03da43e27ddba741cdc0ebe1b1b`;
  pip-check log SHA256
  `9261363b733079a641c2e4cc9bc46ffa1d8336945a87f807b6cf68847dbc9b09`.

### Source parity và full suite

Source chạy trên ext4 được copy từ worktree bằng exclude `.venv`, `build`, cache.
Đối chiếu từng file với manifest Windows cuối: 54/54 hash khớp, 0 mismatch;
manifest SHA256 `59484426a6144f3818ea8ecc7ef833029d6a9da3d32fdc2bbb4ee2363143e5d4`.
Worker child image vẫn là
`sha256:783dc8e152463ddaf10af2be459cf1c5e1073d654f95aab0f641b0c8de866629`.
Fresh Windows rerun trên cùng manifest: **80/0/0/0**, 86.543s; JUnit SHA256
`8d45ca2fbe5e7a084b230971d98603754220a992278e08b7f24c5dd0253bee51`,
full log SHA256
`d51c1cf3e61562fb10ac308d96e6160a4e8a7369db993fdf78025b3de53cb375`.

Lệnh chính chạy bằng user `cvcompass`, supervisor là process WSL ngoài container:

```sh
python -W error /opt/cvcompass-m62/source/worker/tests/run_checks.py \
  --xml /opt/cvcompass-m62/evidence/full-current/tests.xml
```

| Ubuntu WSL suite | Tests | Failures | Errors | Skipped | Duration(s) |
|---|---:|---:|---:|---:|---:|
| test_additional.AdditionalBehaviorTests | 11 | 0 | 0 | 0 | 11.044931 |
| test_docker_enforcement.DockerEnforcementTests | 11 | 0 | 0 | 0 | 27.163337 |
| test_encryption.EncryptionTests | 3 | 0 | 0 | 0 | 0.095655 |
| test_hardening_contract.AdditionalEngineBoundaries | 4 | 0 | 0 | 0 | 16.116121 |
| test_hardening_contract.ExitContractTests | 9 | 0 | 0 | 0 | 6.169631 |
| test_hardening_review.HardeningReviewTests | 5 | 0 | 0 | 0 | 0.009413 |
| test_pipeline.ContractRuntimeTests | 3 | 0 | 0 | 0 | 0.004204 |
| test_pipeline.PipelineTests | 12 | 0 | 0 | 0 | 9.678741 |
| test_review_regressions.ReviewRegressionTests | 5 | 0 | 0 | 0 | 0.472933 |
| verify_contract.ContractChecks | 14 | 0 | 0 | 0 | 0.114680 |
| test_stack_smoke.SpikeGuardTests | 3 | 0 | 0 | 0 | 0.001925 |

Tổng Ubuntu: **80 tests, 0 failures, 0 errors, 0 skipped**, terminal 70.875s;
XML sum 70.871571s. Full-check log SHA256
`6e4a79837032596386e4c2dcb9d9a7b383095f5b2b2d35de7a9391fc5a794098`;
JUnit XML SHA256
`286ad5fb5466e24c224cb061e45188c07c81c3d9e735d2e966bd07f726e54617`.
Đã đọc log/XML mới, không dùng cache, skip hay CI cũ.

Đủ 13 Ubuntu isolation checks trước đây thiếu đã chạy: 11 method trong
`DockerEnforcementTests`, cộng document-timeout real watchdog và 10 MiB exact/+1
isolated-engine. JSON evidence xác nhận network/write/credential denial; RAM/swap,
CPU throttling, PID event, tmpfs/output quota, OOMKilled, timeout/crash descendants,
supervisor-death watchdog và real isolated PDF pipeline đúng assertions. Hai encrypted
PDF cases (password rỗng/khác rỗng) cũng chạy bằng real engine. Snapshot label trước,
sau và cuối đều 0 container/0 volume; không còn test resource do run tạo.

Artifacts mới (ignored, không force-add):
`worker/build/m6-current/ubuntu-wsl-20261020/` gồm install/pip logs, platform/runtime
inventory, full log, JUnit XML, source fingerprint, hashes và isolation JSON.

### Gate còn lại và rollback

Technical correctness và child resource-safety hiện PASS trên Windows/Ubuntu cho exact
source trên. **OCR accuracy chưa đánh giá** (không CV thật/ground truth/CER-WER),
chuyển backlog riêng theo quyết định2026-10-21. GCC/Tesseract warning vẫn
**UNRESOLVED**; production binary vẫn **BLOCKED**. Không suppress warning, không hạ optimization/đổi compiler/patch upstream,
không bypass production gate. Ubuntu VPS thật và hard host-I/O deadline vẫn NOT VERIFIED.

Hoàn tác môi trường (chỉ khi developer quyết định, chưa thực hiện): tắt toggle Docker
integration của `CVCompass-Ubuntu-24.04`; `wsl --unregister CVCompass-Ubuntu-24.04`
sẽ hủy toàn bộ distro; sau đó mới xóa `E:/wsl/cv-compass-ubuntu-24.04` và cache tải
`E:/wsl/cv-compass-download`. Để chỉ gỡ test/runtime mà giữ distro, xóa đúng
`/opt/cvcompass-m62` và user `cvcompass` sau khi bảo đảm không còn process; packages
Ubuntu có thể purge theo danh sách trên và `apt autoremove` sau review. Không thực hiện
các bước phá hủy này trong verification hiện tại.
