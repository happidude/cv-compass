> LỊCH SỬ trước hardening. Source/evidence PR9 hiện hành: [M6_PR9_REVISION.md](M6_PR9_REVISION.md), cách chạy: [handoff](M6_BASELINE_HANDOFF.md). Không dùng số test/source bên dưới cho source hiện tại.

# M6.2 — Báo cáo kiểm chứng (2026-10-18)

**Technical checks của phạm vi đã chạy: PASS. M6/production quality gate: BLOCKED.**
Không tuyên bố hoàn tất M6; chưa OCR CV thật, không business mapping/DB/M7.

## A. Source, môi trường và workflow

- Branch `feature/ocr-worker`, worktree `E:/cv-compass-ocr-design`, HEAD/base main sau
  PR8 `5c06070955e2313f041be47ac313d97b2c079bea`. Source mới chưa commit; giữ toàn bộ
  M6.1 design/spike đã có. Main checkout không đổi; không commit/push/merge/xóa worktree.
- Source manifest37 file code/schema/examples/config/locks/scripts SHA256:
  `912d2a1de63b18610903df088e05001ad4884d399016f585e8f104bfdd0b89c6`. File `build/m6-2/final-source-manifest.json`;
  đã đối chiếu từng hash sau final tests, không thay executable source tiếp.
  Markdown evidence tách khỏi digest (không self-reference).
- Đọc AGENTS.md, AI_DEVELOPMENT_WORKFLOW.md và CV_COMPASS_GIT_WORKFLOW.md ở checkout
  gốc vì các tài liệu đó local/ignored; đọc design và source spike trước sửa. Không
  force-add, không đụng Java business logic. User đã duyệt M6.2 standalone; cách gọi
  roadmap cũ không cấp quyền M7. Developer plan trước code; một agent đổi vai review,
  dùng3/3passes, chi tiết M6_2_REVIEW.md. Không external-audit claim.
- Windows11 build26300/PowerShell7, embedded CPython3.14.8 riêng, không đổi global.
  Ubuntu24.04.5 container trên Docker Desktop/WSL2 kernel6.18.33.2/glibc2.39,
  **không phải Ubuntu VPS hay GitHub runner**. Image ID:
  `sha256:6b5d23e3d54c6dd0a6223a2e6c0839bedc9041255a470ef42ff2de14088cd749`.
- Cùng pins pdfplumber0.11.10/pdfminer.six20260107/pypdfium2 5.14.0 (PDFium156.0.8076.0),
  Pillow13.0.0/jsonschema4.26.0. Tesseract Windows5.5.3.20260724/Leptonica1.87.0;
  Ubuntu Tesseract5.5.3/Leptonica1.82.0. Cùng3 model exact revision/hashes trong report.
  pypdf6.21.1 chỉ sinh fixture AES; không runtime dependency.
- Model/exe/font và runtime build cô lập dưới ignored build/. `requirements.lock`
  khóa13runtime packages với SHA256 wheel từ PyPI; `requirements-test.lock` khóa
  fixture generator. Windows `python -m pip check`: No broken requirements found.
  Lock hash đã tạo từ metadata chính thức; **chưa fresh-install toàn lock bằng
  --require-hashes trên cả hai OS**, không dùng việc tạo lock làm bằng chứng đã install.
- Java/PostgreSQL/Gradle/problems report/migrations/Modulith: NOT APPLICABLE cho
  thay đổi Python-only; không dùng kết quả backend cũ làm evidence worker.

## B. Lệnh thực thi và JUnit mới

Working directory cho mọi lệnh: `E:/cv-compass-ocr-design`.

```powershell
$env:OCR_TEST_REPORT_DIR = "$PWD/worker/build/m6-2/windows"
& worker/build/m6-1-spike/python3148/python.exe -W error worker/tests/run_checks.py --xml worker/build/m6-2/windows/tests.xml 2>&1 | Tee-Object worker/build/m6-2/windows/full-check.log
& worker/spikes/m6_2/run_ubuntu_checks.ps1
```

Runner Ubuntu ghi đầy đủ Docker settings, chạy network none/read-only/nonroot với
cgroup/tmpfs; cài fixture wheel offline vào /tmp rồi thực thi cùng run_checks.py.
Không cache test, không skip theo môi trường, không dùng CI hoặc report cũ làm PASS.
Test log giữ nguyên; -W error ở Python test/supervisor không phải suppress warning.

| OS | Suite | Tests | Failures | Errors | Skipped | Duration(s) |
|---|---|---:|---:|---:|---:|---:|
| windows | test_additional.AdditionalBehaviorTests | 11 | 0 | 0 | 0 | 16.454 |
| windows | test_encryption.EncryptionTests | 3 | 0 | 0 | 0 | 0.073 |
| windows | test_pipeline.ContractRuntimeTests | 3 | 0 | 0 | 0 | 0.014 |
| windows | test_pipeline.PipelineTests | 12 | 0 | 0 | 0 | 22.511 |
| windows | test_review_regressions.ReviewRegressionTests | 5 | 0 | 0 | 0 | 1.321 |
| windows | verify_contract.ContractChecks | 14 | 0 | 0 | 0 | 0.182 |
| windows | test_stack_smoke.SpikeGuardTests | 3 | 0 | 0 | 0 | 0.002 |
| ubuntu | test_additional.AdditionalBehaviorTests | 11 | 0 | 0 | 0 | 17.980 |
| ubuntu | test_encryption.EncryptionTests | 3 | 0 | 0 | 0 | 0.055 |
| ubuntu | test_pipeline.ContractRuntimeTests | 3 | 0 | 0 | 0 | 0.016 |
| ubuntu | test_pipeline.PipelineTests | 12 | 0 | 0 | 0 | 21.499 |
| ubuntu | test_review_regressions.ReviewRegressionTests | 5 | 0 | 0 | 0 | 1.067 |
| ubuntu | verify_contract.ContractChecks | 14 | 0 | 0 | 0 | 0.282 |
| ubuntu | test_stack_smoke.SpikeGuardTests | 3 | 0 | 0 | 0 | 0.002 |

Tổng **51/0/0/0 mỗi OS**,102lượt test methods (bao gồm17contract/spike guards mỗi OS,
không phải102real-engine cases). Suite có cả unit/failure injection và real engines.
Thời gian là suite wall time, chạy song song hai OS; **không phải benchmark OCR**.

JUnit Windows UTC `2026-10-18T13:08:02.404016+00:00`, SHA256 `9165ef048b51b07f4cf04ed0d51125464b84df5fb1fc51d366d9a2d9874e2257`.
JUnit Ubuntu UTC `2026-10-18T13:08:08.178426+00:00`, SHA256 `2222097893b6e9a05d0b07c24e4414bf4894e27d2885655377b623a7049513d4`.
Đã đọc terminal log và XML mới: không failure/error/skipped, không Python warning
trong final terminal logs. Giữ negative tests, không xóa hoặc nới assertion.

## C. Technical correctness — đã triển khai/đo

| Yêu cầu | Evidence / giới hạn |
|---|---|
| Reject encrypted empty/nonempty password | AES256 fixtures do pypdf sinh, pdfminer preflight thật3tests/OS trước pipeline; final suite còn test qua supervisor với cả2password; FILE_ENCRYPTED, pages[], nguồn không đổi |
| CLI/config/schema | TOML strict, unknown/type/NaN/concurrency/caps validation; CLI max_pages51 bị reject trước snapshot; canonical50pages/50k codepoints và schema chống lệch; safe stderr |
| Native/scan/mixed | Real native extraction, scan EN/VI có dấu/dummy email/phone, file native+OCR+blank; mixed cùng trang không để text layer bypass scan body |
| Thứ tự/trang/rotation | Native2column fixture kiểm tra word conservation và precedence; PDF metadata rotation90 qua real OCR. Chưa exhaust180/270/blur/nearblank |
| Status/output | success/partial/failed/allblank; partial với all pages ok + doc error có contract example; whole-page prefix/OUTPUT_LIMIT, config giảm text cap, không truncate; minimum envelope không vừa => exit3/no JSON |
| Sequential supervisor | Child riêng preflight/page, safe environment, child IPC validate, page lỗi không poison trang sau; process crash17 và real watchdog .001s test; timeout process được reap trước remove dir |
| Error/failure tests | Real Tesseract nonzero với empty modeldir => OCR_FAILED; missing model/exe, input hash/byte cap; TEMP_LIMIT guard; fake fatal/IPC lỗi có regression rõ, không gọi đó là real engine failure |
| Cleanup/publication | Nguồn nguyên vẹn; no-overwrite atomic hardlink trên cùng filesystem; cleanup failure giữ warning/success và lock khi final cleanup fail; final cleanup recovery có test |
| 50/51pages | Real preflight tại boundary50 và reject51; không phải full50page OCR/performance test |
| Limits chưa kiểm chứng đủ | Byte guard đã test bằng cap giảm, chưa thực thi full10MiB boundary; resource/native parser bomb, hard deadline I/O, full-document exhaustion và descendants sau crash chưa kiểm chứng |

Sau review đã sửa cumulative document cap trước nhận page; remaining pages dùng đúng
fatal cause; cleanup giữa pages không tự biến OCR thành failed. Developer checks còn
sửa unclosed stream/Pillow view, duplicate warning, placeholder total, raw newline và
stage/total timings lỗi. Rerun full suite cuối sau mọi code/EOL change; source hash khớp.

## D. Warning — tách khỏi technical PASS

- **40 GCC -Wstringop-overflow diagnostics vẫn còn, production binary BLOCKED.**
  GCC13.3.0, Release -O3/-DNDEBUG, source tag/hash và full compiler invocation,
  diagnostic, source locations, upstream PR/discussion: xem M6_2_GCC_INVESTIGATION.md.
  Recompile serialis.cpp exit0 tái hiện warnings; không suppress, không đổi compiler,
  không áp patch chưa review. Upstream PR4527 open/unmerged không đủ kết luận an toàn.
- Không runtime compile ở Python pipeline. Final test/supervisor terminal logs không
  có unexpected warning/error. Real negative Tesseract stderr được giữ tạm bounded
  private rồi cleanup, map safe code; CLI report ghi count, không dump paths/text.
  CLI native smoke ở cả2OS ghi stderr_lines=0. Không suy ra toàn OCR corpus luôn sạch.
- Git core.autocrlf=true: bảo vệ shell/Dockerfile bằng worker/.gitattributes, sửa2EOF
  blank lines. Còn notice LF→CRLF cho file lịch sử M6.1 spikes/m6_1/requirements.txt;
  không đổi nội dung file đã có chỉ để hết log. Đây là thông báo Git EOL, không compile
  hoặc runtime defect. Không tắt Git warnings. Whitespace errors của toàn change=0.

## E. Resource isolation — chưa cấp production

Report isolation nằm riêng, **không thêm vào OCR JSONv1**:
`build/m6-2/windows/cli-verification.json`, `build/m6-2/ubuntu/cli-verification.json`.

| Môi trường | Requested / applied | Kết luận |
|---|---|---|
| Windows | Requested1GiB/2CPU/64pids/temp256MiB; taskkill diagnostic, không Job Object áp quotas | not_enforced, resource_safety_verified=false |
| Ubuntu | Docker requested và inspect/cgroup observed memory.max1073741824, swap0, cpu.max200000/100000, pids64; tmpfs256MiB, networknone, rootreadonly, user65532 | Aggregate container settings applied; adapter tổng thể vẫn not_enforced vì chưa separate child budget/quota và resource safety chưa thử |

Không dùng fixture độc hại/resource bomb hoặc CV thật trong diagnostic. Chưa test
OOM enforcement, peak process-tree RAM, Windows descendants sau parent/child crash,
separate child group, hard tmp quota, cleanup timeout trên filesystem treo. Polling có
thể overshoot; process group/taskkill không phải security sandbox. Production luôn
refuse INVALID_CONFIGURATION trước PDF, không có flag bỏ qua warning/isolation gate.

Startup/CLI real native smoke thành công, result schema hợp lệ, native method,
stdout/stderr public rỗng. Các TemporaryDirectory được dọn; cleanup-fault fixtures do
test owner dọn sau assertion, không tự recovery trong worker. Docker test containers
--rm dừng/xóa sau run; giữ images/runtime/reports ignored để đối chiếu. Không đụng
CV storage thật/schema dev hay D:. Kill supervisor/power loss vẫn có thể để lock,
temp hoặc orphan; operator phải kiểm tra, không bảo đảm crash recovery/exactly-once.

## F. Quality evaluation và gate

**Quality evaluation NOT PERFORMED**: chưa corpus CV thật, manual ground truth,
CER/WER/reading order/contact evaluation, calibration thresholds, peak RAM hoặc cold/
warm benchmark. Fixture assertions không chứng minh chất lượng CV thực tế.

**CI NOT VERIFIED** trên source cuối: chưa commit/push/PR M6.2 được phê duyệt; không
kế thừa CI/ủy quyền milestone trước. Requirement phải verify upstream warning/production
binary, hard isolation và các mandatory security/quality checks còn BLOCKED/NOT VERIFIED.
Do đó chưa READY FOR M6 completion/production, chưa M7. Không tự merge.

## G. Artifact map

- README.md/config.example.toml: cách chạy, request/result và resource/exit policy.
- src/cv_compass_worker/: config/contracts/pipeline/child/pdf/ocr/layout/sandbox.
- tests/: technical fixtures, behavior/failure regressions, fresh JUnit runner.
- design/M6_2_GCC_INVESTIGATION.md và M6_2_REVIEW.md: diagnosis/review evidence.
- build/m6-2/{windows,ubuntu}/{full-check.log,tests.xml,cli-verification.json}.
- build/m6-2/ubuntu/docker-applied-settings.json; gcc/{compiler.txt,serialis-command.txt,
  compile_commands.json,CMakeCache.txt,serialis-diagnostic.log,source/,upstream-*}.
- build/m6-2/final-source-manifest.json và untracked-whitespace-check.json.

Build artifacts ignored, không commit binary/model/PDF/credential. Main/source M5
không thay đổi, chưa có commit/PR/link CI mới để báo pass.
