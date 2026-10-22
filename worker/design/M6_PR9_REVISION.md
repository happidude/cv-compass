# M6 PR #9 — endpoint/staging/build/CI revision

> Lịch sử source `94fee36` trước finding cleanup ownership. Báo cáo nối tiếp hiện hành:
> [M6_PR9_CLEANUP_OWNERSHIP.md](M6_PR9_CLEANUP_OWNERSHIP.md). Không dùng103 tests
> bên dưới làm evidence cho source sau sửa ownership; implementation cũ dùng delta
> cleanup đã được thay, không phải authority để xóa resource.

Base `ee6d4a7b810233f2faaab59ea4e913cba7340537`, branch `feature/ocr-worker`,
worktree `E:/cv-compass-ocr-design`. Phạm vi developer giao: sửa technical baseline
hiện có, cập nhật PR9; không xây lại pipeline, không M7/mapping fields/DB/CV thật.

**OCR accuracy chưa đánh giá. Production BLOCKED do GCC/Tesseract unresolved.**
Technical CI pass không mở production. Quality evaluation/tuning ở backlog riêng.

## Findings và implementation

| ID | Mức | Vị trí/cause | Sửa và verification dự kiến |
|---|---|---|---|
| P9-1 | High | src/cv_compass_worker/docker_sandbox.py:31 resolve_target; pipeline.py:73: DOCKER_HOST gate cũ khác endpoint CLI khi có DOCKER_CONTEXT | Fixed: context metadata resolve một lần rồi explicit --host + environment snapshot; reject trước snapshot/resource/stream; conflicting-env/default-context regressions |
| P9-2 | High | src/cv_compass_worker/docker_sandbox.py:166 prepare_control: Popen/pipe/timeout cleanup cũ chỉ quản lý Docker CLI | Fixed: named writer, bounded feeder/timer, kill/wait/remove trước volume; primary/leftovers/flag; mocked failures và source-failure Docker thật |
| P9-3 | Medium | src/cv_compass_worker/docker_sandbox.py:320 run_scope, :361 run_docker_child; pipeline.py:110: mất cleanup halt nếu ledger không ghi được | Fixed: in-memory flag xuyên exception/value/envelope; diagnostic write không thay primary/completed page; lock/halt khi disk full |
| P9-4 | Medium | spikes/m6_2_hardening/prepare_worker_image.py:6; build_baseline.py:49: stale context/laptop-only image | Fixed: fresh allowlisted contexts, source-built image chain, source/model/font checksums, hash-locked wheels; context/checksum regressions |
| P9-5 | Medium | .github/workflows/worker-ci.yml (artifact trước đây thiếu) | Added: Ubuntu host full gate + Docker child, fresh pip hashes/check, syntax check, JUnit/log/source/image/provenance artifacts và always cleanup; backend CI không đổi |

Precedence tham chiếu [Docker CLI docs](https://docs.docker.com/reference/cli/docker/).
Runtime dependency schema/design JSON vẫn nằm cạnh src; test fixtures và smoke guards
trong tests/spikes, requirements-test.lock phục vụ synthetic generator. Kiến trúc thực
tế và build/run commands: [README](../README.md), [handoff](M6_BASELINE_HANDOFF.md).
Các báo cáo cũ có banner lịch sử; số80/80 không dùng cho source sửa đổi.

## Review workflow và kết quả

Một agent đổi vai Developer/Reviewer, tối đa3vòng cho đợt PR9 này.
Vòng1: rà actual diff cùng caller/config/tests/build/workflow. Sửa thêm helper
create_scope/execute_scope để endpoint cũng được ghim khi gọi độc lập; kiểm tra
source_manifest chỉ đọc tập source allowlist, không quét runtime/storage. Trong
Developer checks phát hiện BuildKit không nhận naked image ID trong FROM: chuyển
build-stage reference sang local tag theo content ID vừa build. Child image vẫn pin
immutable ID. Compiler logs giữ nguyên, không thay optimization/compiler.

Vòng2: actual CLI `DOCKER_CONTEXT=default DOCKER_HOST=tcp://untrusted.invalid:2375
docker context inspect default` trả Host remote, khác context thông thường. Sửa
resolution environment để CLI metadata thấy DOCKER_HOST, còn execution environment
vẫn strip overrides và pin --host. Thêm unit+real-CLI regression, không gọi daemon
remote. Đây là finding đã tái hiện, không chỉ inference từ tài liệu tổng quát.

## Evidence source cuối

Manifest60files (code/tests/schema/config/lock/tooling + worker workflow) SHA256:
`521a39e24695c6e4e43bb0b4a0eb357be423edf95979c3b88b1a436077d82823`.
Đối chiếu Ubuntu snapshot với worktree Windows:60/60khớp. Markdown không nằm trong
manifest để report không self-reference. Không dùng80/80cũ hay101-test intermediate
runs trước default-context fix để xác minh source cuối.

Worker image cuối:
`sha256:c81269663ca395e41b48d45f6cf92602fd1b983eea8235dff10ff36749d490ad`.
Runtime `sha256:5538726558d0b2eebb5120d2a387d8a2ea9eb36e3e71baca22d2070d6994f332`;
dependency image `sha256:826775a3444c7d3b739b3d99215fb216f33b0d61cf41d92e92369d11d5febdc2`.
Từ checkout snapshot trên Ubuntu24.04 WSL, user cvcompass, Docker Desktop4.82.0 /
Engine29.6.1/cgroupv2; CPython3.14.8. Lệnh thực tế:

```sh
/opt/cvcompass-m62/venv/bin/python worker/spikes/m6_2_hardening/build_baseline.py --output worker/build/pr9-final
/opt/cvcompass-m62/venv/bin/python worker/spikes/m6_2_hardening/verify_baseline.py --build worker/build/pr9-final
```

Ubuntu full gate:103tests/0failures/0errors/0skipped,117.168s. Fresh host venv installed
14locked packages, `pip check`: No broken requirements found. Cleanup leftovers[].
JUnit SHA256 `9bd70e5786650a193a9442cca428518b9415de8417495c3eb608dbb52bb144f5`;
full log SHA256 `d90961580b273494f9a07b917b33b4bbaadc0f66e95960e7dd5e52d7f97f2a25`.
Evidence sao chép local (ignored): worker/build/pr9/ubuntu-final/.

Windows full gate cuối:103tests/0failures/0errors/0skipped,81.493s, dùng clean runtime
`worker/build/m6-2-hardening/windows-pr9-final/python.exe -W error
worker/tests/run_checks.py --xml worker/build/pr9/windows-final/tests.xml`,
OCR_TEST_DOCKER_IMAGE là image cuối ở trên; Tesseract/models/font portable đã provision.
Fresh hash-locked install và pip check PASS; no Windows global Python/PATH change.
JUnit SHA256 `9cab5f350ca5d4f5f21d392dcbbf340f645af423c7d6da53cf49044d7cce5afe`;
log SHA256 `b619ccb232572d750821ce7d10ad4259c529ebacd1e40f66f020ec70a2c6c15f`.
Lần cuối đọc trực tiếp Docker labels:0writer/0child/0inputvolume; Ubuntu cleanup.json
leftovers[]. Logs/evidence/runtime/build contexts đều ignored, không force-add.

| Suite (hai OS cùng count) | Tests |
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
| ContractChecks |14|
| SpikeGuardTests |3|

Mỗi suite trên cả hai OS:0failure/error/skipped; XML chứa duration từng suite. Gồm13isolation
checks bắt buộc cũ và source-failure staging mới, cùng22regressions mới về routing,
lifecycle/flags/context/checksum. Negative assertions được giữ; không subset/skip.

Fresh compiler provenance vẫn40 `-Wstringop-overflow` warnings: GCC13.3.0,
Release `-O3 -DNDEBUG -std=gnu++20`, exact Tesseract5.5.3 source SHA256 trong
provisioning.json. Full warning log/compile_commands/CMakeCache/compiler.txt được giữ
trong verification/provenance. Không -Wno-stringop-overflow hoặc optimization/compiler
workaround; production vẫn BLOCKED, không kết luận false positive từ test pass.

Java/Gradle/DB/Modulith N/A vì không thay backend; backend CI độc lập. Lint/type/package
build chưa cấu hình; compileall chỉ syntax. CI source mới sẽ được kiểm tra sau push;
không lấy backend CI xanh làm bằng chứng Python.

Vòng3/final: đọc lại actual diff, new workflow/tooling/tests, source parity60/60,
JUnit/logs mới hai OS và compiler provenance. P9-1..P9-4 fixed and locally verified;
P9-5 implementation/local harness verified, remote CI cần kết quả commit sau push.
Không có Critical/High/Medium baseline implementation finding còn mở. Endpoint special
default case đã được real-CLI regression kiểm tra; không gọi remote daemon. Markdown
links0missing; whitespace check dùng cr-at-eol cho CRLF Windows. Không sửa source sau
full gates.3/3vòng review dùng trong invocation này; không external audit.

Known limits: host supervisor hard memory cap, hard host-I/O deadline/VPS thực,
toàn bộ malformed/hostile input safety và crash recovery chưa được chứng minh.
Cleanup fault injection được phân biệt với test Docker thật. Gate production vẫn
refuse trước xử lý PDF; không suppression/bypass, không claim accuracy.
