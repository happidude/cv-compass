# M6 PR9 — cleanup ownership revision (2026-10-22)

Base `94fee36d88959a188182df78c5b1c19cf4d2225f`, branch `feature/ocr-worker`,
worktree `E:/cv-compass-ocr-design`. Scope: finding cleanup của verification harness,
không rebuild pipeline/M7/mapping/DB/CV thật. User cho phép commit/push PR9, chưa merge.
Đã đọc AGENTS.md/workflows local ignored hiện hành; không force-add.

**OCR accuracy chưa đánh giá; production BLOCKED do GCC/Tesseract unresolved.**
Technical pass không mở gate, không suppress warning hoặc đổi optimization/compiler.

## Finding và quyết định

OWN-1 High, `spikes/m6_2_hardening/verify_baseline.py` cleanup cũ (base dòng19–32):
set(resource_after)-set(resource_before) với scope label chung có thể xóa resource
của run khác vừa tạo trên cùng daemon. Asset prefix cũng không là ownership.

Sửa: mỗi verification UUID32hex mới, state version/run_id/local endpoint ghi exclusive
và fsync trước tạo resource. Trusted test subprocess nhận CV_COMPASS_VERIFICATION_RUN_ID;
Docker writer/child/input volume và asset mang label verification-run riêng. PDF child
không nhận ownership env/socket. Cleanup discover theo run_id, inspect xác nhận exact
owner/scope/identity; remove immutable container ID trước volume. Endpoint phải trùng
state; không dùng snapshot delta/label chung/prefix. Không xóa resource foreign/legacy.

`--cleanup-only` dùng cùng cleanup và owner state, không cần build manifest khớp để
recovery sau lỗi. Giữ state/evidence, không cleanup khi own run đang active. Không có
state và chưa có verification thì no-op (build chưa tới verification); có evidence
hoặc legacy snapshot nhưng thiếu state thì refuse. Daemon discovery/inspect/removal
và cleanup evidence write errors là failure/nonzero, không empty-success. Original
verification exception vẫn được raise sau finally; cleanup report ghi leftovers.

Trust boundary: daemon/state do trusted operator quản lý; UUID không là authentication
chống người cùng quyền daemon cố ý giả ownership. Không có universal crash recovery.
State cũ chỉ có snapshot không được nâng cấp bằng phỏng đoán hoặc xóa label chung.

## Developer / Reviewer và verification

Một agent, tối đa3reviewpasses cho invocation này. Regression có unit fault injection
và hai run Docker thật: cleanup-only run A xóa A, container/volume run B còn nguyên;
finally dọn từng run bằng đúng ownership. Giữ toàn bộ negative assertions cũ.

Review1: actual incremental diff/source/callers + committed milestone against main
5c06070; loại delta/prefix authority, kiểm label/identity/endpoint, state-before-create,
negative paths. Developer bổ sung lỗi report-write và missing/legacy state nonzero.
Regression cuối9/9PASS, không nới assertion. Review2: source/docs/config/workflow và
imports fresh-host, không có finding Critical/High/Medium mới. Source đóng băng trước
full gates. Review3/final: đối chiếu fresh XML/logs, source parity, staged bytes/docs;
OWN-1 fixed/verified, không còn finding Critical/High/Medium trong scope.3/3passes,
không external audit; transaction/DB/Modulith không áp dụng vì không thay backend.

## Evidence source cuối

Manifest61files (runtime/test/config/schema/lock/tooling + workflow, không Markdown để
tránh self-reference) SHA256:
`b6fa223627dc792689d0ce2360cf1ecc27eda0bd7c0afc5c4f3cb5e5e6a5dc4f`.
Ubuntu snapshot61/61khớp worktree Windows; trước commit đối chiếu raw staged Git bytes.
Local base94fee36 + source changes verify_baseline.py, docker_sandbox.py,
test_verification_ownership.py;4Markdown docs/report riêng. Không source edit sau gate.

| Environment | Tests | Failures | Errors | Skipped | Full runner time |
|---|---:|---:|---:|---:|---:|
| Ubuntu24.04 WSL2 host + source-built Docker child |112|0|0|0|127.085s|
| Windows11 portable CPython3.14.8 + same child image |112|0|0|0|84.988s|

Đã đọc XML/log mới của17suites, mỗi suite0failure/error/skipped trên cả hai OS:

| Suite | Tests mỗi OS |
|---|---:|
| AdditionalBehaviorTests |11|
| DockerEnforcementTests |12|
| EncryptionTests |3|
| AdditionalEngineBoundaries |4|
| ExitContractTests |9|
| HardeningReviewTests |5|
| ContractRuntimeTests |3|
| PipelineTests |12|
| ReviewRegressionTests |5|
| BuildContextTests |3|
| CleanupPropagationTests |4|
| EndpointTests |8|
| StagingTests |7|
| RealVerificationOwnershipTests |1|
| VerificationOwnershipTests |8|
| ContractChecks |14|
| SpikeGuardTests |3|

Ubuntu commands từ `/home/cvcompass/m6-pr9-source` trong distro riêng
CVCompass-Ubuntu-24.04; CPython3.14.8, Docker Desktop Linux Engine29.6.1/cgroupv2:

```sh
/opt/cvcompass-m62/venv/bin/python worker/spikes/m6_2_hardening/build_baseline.py --output worker/build/ownership-final
/opt/cvcompass-m62/venv/bin/python worker/spikes/m6_2_hardening/verify_baseline.py --build worker/build/ownership-final
```

Verifier tạo fresh host venv, offline --no-cache-dir --require-hashes install14packages,
pip check PASS, syntax compileall (không lint/build), full runner -W error. Source-built
worker image `sha256:ccbd8ef7f14cab5dc64f0e2ac85d449fb0e80e7ff872b758efc0e015c1d802ae`.
Trusted runtime/models/font và dependency hashes không đổi. Ownership state run_id
`2ec7034fd24c4c7696880f036b29138d`, cleanup leftovers[]. Evidence local ignored:
worker/build/ownership-ubuntu-evidence (copy report/provenance, không runtime/CV).
JUnit SHA256 `cb6f80c817e57b61e5122ad9381a293596bdad2c83157f145503c54c7f89e10e`;
full log `47ee29535f42f6e1b89be7d9081e853addf55b0c9f0bd227fcdd5a152a28ef01`.

Windows cwd E:/cv-compass-ocr-design, fresh `provision_windows.ps1 -CleanName
windows-ownership-final`: download hashes + fresh install --require-hashes14packages,
pip check PASS, PATH restored/no global Python change. Full command:

```powershell
& worker/build/m6-2-hardening/windows-ownership-final/python.exe -W error worker/tests/run_checks.py --xml worker/build/ownership-windows-final/tests.xml
```

OCR_TEST_DOCKER_IMAGE là image mới ở trên, OCR_TEST_TESSERACT/MODELS/FONT trỏ portable
provisioned assets từ baseline; OCR_TEST_REPORT_DIR=worker/build/ownership-windows-final.
Run_id `dc5ce8c328b24d4795db3c341b0dc6ae` được generate riêng, ghi state và đặt
CV_COMPASS_VERIFICATION_RUN_ID cho trusted supervisor trước full suite. Local wrapper
đã chạy tests thành công nhưng cleanup import đầu tiên thiếu build_baseline do embedded
Python._pth không thêm script directory. Sửa bootstrap của wrapper local (không sửa
repo source) và chạy riêng cleanup-only, exit0/leftovers[]:

```powershell
& worker/build/m6-2-hardening/windows-ownership-final/python.exe -c "import sys; sys.path.insert(0,'worker/spikes/m6_2_hardening'); import verify_baseline; verify_baseline.main()" --build worker/build/ownership-windows-final --cleanup-only
```

Không rerun full suite vì repo source không đổi. JUnit SHA256
`c98ae376410abf3a480779278ac1f6391f9d62eb53a48fb76836e56deb0841be`;
full log `8a59c7bbcd06f21c88a8e6c3a7c3e2a99023703131a8ebbd7aeb2b4fcfc27168`.
Windows evidence ở ignored worker/build/ownership-windows-final; initial wrapper
bootstrap failure được phân biệt với test PASS, không gọi toàn wrapper ban đầu PASS.

Fresh Tesseract source5.5.3 build provenance vẫn40 `-Wstringop-overflow` warnings,
GCC13.3.0-6ubuntu2~24.04.1, Release -O3/-DNDEBUG/gnu++20. Full compiler log/compile DB
giữ nguyên; không suppress hoặc đổi binary/gate. pip update notice không là test
failure, không tự nâng pip. OCR accuracy chưa đo; production vẫn BLOCKED.
CI cho commit mới sẽ báo riêng trên PR sau push, không dùng CI94fee36 làm evidence.

103-test evidence source94fee36 chỉ lịch sử tại [previous revision](M6_PR9_REVISION.md).
Java/Gradle/PostgreSQL/Modulith N/A vì Python-only, backend CI báo riêng. Compileall
chỉ syntax; lint/type/package build chưa cấu hình. Accuracy/corpus/tuning ở backlog.
