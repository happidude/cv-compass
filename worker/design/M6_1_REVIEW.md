> LỊCH SỬ: review source/thiết kế cũ. Hiện hành: [handoff](M6_BASELINE_HANDOFF.md), [PR9 revision](M6_PR9_REVISION.md). Không tái sử dụng số tests cũ cho source mới.

# M6.1 — Review và bằng chứng thiết kế

> HISTORICAL SNAPSHOT: các mục bên dưới trước phụ lục ghi đợt thiết kế ban đầu. Kết quả hiện tại và ba quyết định mới nằm ở phụ lục cuối và M6_1_STACK_SPIKE.md; không dùng số5examples/13checks cũ làm evidence cuối.

Ngày2026-10-18. Base/HEAD: 5c06070955e2313f041be47ac313d97b2c079bea.
Branch docs/m6-1-ocr-design, worktree E:/cv-compass-ocr-design.
Các artifact còn untracked, chưa commit/stage/push. Không có implementation OCR.

## Workflow

Đã đọc AGENTS.md, AI_DEVELOPMENT_WORKFLOW.md, CV_COMPASS_GIT_WORKFLOW.md, database foundation và requirement của task; kiểm tra source main sau PR#8, worker hiện có và backend entity/enums/migrations/controllers/services/storage/config.

Main sạch trước fetch/pull --ff-only tới5c06070. Tạo docs/m6-1-ocr-design worktree riêng. Source backend không đổi. Không force-add docs/AGENTS bị ignore. Contract local proposal nằm worker/design để review, không tự biến thành contract HTTP M7.

Một agent đổi vai Developer/Reviewer, tối đa3passes; không subagents/external audit. Đã trình bày scope/stack/artifacts trước sửa. Không tải CV ra ngoài, không đọc D:, không chạy OCR/cài engine/model.

## Reviewer vòng1

Đã đọc actual new artifacts (ordinary git diff bỏ qua untracked), status và diff main...HEAD/unstaged/cached rỗng; new-file no-index diff và content được đọc riêng. Hai findings thiết kế:

| ID | Severity | Vị trí lúc phát hiện | Vấn đề / bằng chứng | Fix |
|---|---|---|---|---|
| M61-R1 | Medium | worker/design/ocr-v1.schema.json:625 (engines) | Required metadata không có Pillow và worker pipeline/build version; cùng model/config nhưng preprocessing/layout build khác không tái lập đầy đủ | Required pillow/worker_version, đồng bộ doc và toàn bộ result examples |
| M61-R2 | Low | worker/design/examples/result-mixed.json:13 | Native sample quá ngắn so với proposed >=40letter/digit gate; không nhất quán ví dụ và pipeline | Đổi synthetic native text dài hơn; giữ Unicode VN và raw CRLF/text LF minh họa |

Cả hai đã sửa. Bổ sung success/allblank examples để đọc rõ status semantics. Làm rõ technical success khác measured quality, OSD invocation riêng và CER blank/unreadable denominator; sửa formatting/CRLF của docs, không suppress warning.

## Local artifact checks thực tế

PowerShell7.6.5, Test-Json7.0.0.0. Không cần Java/PostgreSQL cho design-only. Python3.12.10 local chỉ kiểm tra --version, không install hoặc chạy OCR.

```powershell
Get-Content worker/design/ocr-v1.schema.json -Raw | ConvertFrom-Json -Depth 100
Get-ChildItem worker/design/examples/*.json | ForEach-Object {
  Test-Json -LiteralPath $_.FullName -SchemaFile worker/design/ocr-v1.schema.json
}
git diff --check
# New artifacts chưa tracked: check từng file với
# git diff --no-index --check -- NUL <new-file>
```

Đã chạy bằng terminal:
- 5positive examples valid: request, mixed-partial, success, encrypted-failed, allblank-failed.
- 8negative mutated payloads bị từ chối: version2.0, ../path traversal, success có errors, page_number0, confidence101, failed page có nonempty text, unsafe RAW_SQL_EXCEPTION code, extra storage_path property.
- Expected validation errors được đếm/kiểm tra là rejection, không xem là unexpected test failure; không có dữ liệu/CV trong payload.
- Semantic assertions trên examples: count/page order, text NFC/LF, failed không có page ok, success/partial phải có page ok. Các cross-field rules còn lại ghi rõ thuộc application validation M6.2.
- Không có unexpected failure. Đây là13design-payload checks, không phải13OCR tests hoặc proof full JSON Schema conformance. Không tuyên bố validator đã kiểm tra mọi schema keyword/metaschema.
- Git từng cảnh báo LF->CRLF khi xem new-file diff; đã chuẩn hóa line endings theo môi trường Windows, không tắt core.safecrlf/che stderr. Không compile/test JVM warning vì không chạy Java.

## Gate và phần chưa kiểm chứng

Gate: **DESIGN READY FOR USER REVIEW — chưa duyệt M6.2**.

Backend Gradle/full check: NOT APPLICABLE vì không Java/build/schema change, không phải backend milestone completion. Python runtime unit/integration/OCR quality/performance/isolation/Windows-Linux parity/CI: NOT VERIFIED vì chưa implementation. Không dùng CI/tests của M5.1 làm bằng chứng M6.

Missing POC_OCR_API_CONTRACT.md là dependency cần tạo/duyệt trước M7, không bịa HTTP endpoints/job semantics. Các D1–D7 trong thiết kế là quyết định pending owner, không phải code defects. Không có commit/PR mới, chưa merge hoặc tự sang M6.2/M7.
## Reviewer vòng2 — kết luận bản cuối

Đã rà lại đủ8artifact mới, toàn bộ scope/contract/pipeline/resource/test matrix và engine metadata sau fix. M61-R1: fixed and verified qua required pillow/worker_version và5examples validate. M61-R2: fixed and verified qua sample native mới đủ dài theo heuristic đề xuất. Không còn Critical/High/Medium hoặc Low đã xác nhận chưa xử lý. D1–D7 vẫn là quyết định xin duyệt, không tự coi đã approved.

Lần kiểm chứng cuối: Test-Json trên5positive examples PASS;8negative payloads bị reject đúng; semantic count/order/NFC/LF checks PASS; git diff --check và no-index --check cho từng file mới PASS. Main checkout sạch, HEAD worktree vẫn5c06070, chỉ8files worker/design chưa tracked. Hai review passes tổng, cùng một agent; không external audit. Source/runtime checks ngoài design vẫn NOT VERIFIED như trên. Không commit/push/merge.
## Đợt cập nhật theo ba quyết định review + spike được giao riêng

Scope mới được người dùng giao rõ: sửa status/schema/examples/caps/diagnostic trước, chạy checks, sau đó spike nhỏ Windows/Ubuntu. Không coi scope này là triển khai toàn M6.2/M7 hoặc ủy quyền Git publishing.

Developer cập nhật:
- partial cho phép mọi pages ok khi có document error; không bắt buộc SUMMARY_PARTIAL. failed được định nghĩa không page ok, không ép lỗi cấp document khi page error đủ.
- Canonical ocr-v1.limits.json, schema hard ceiling50pages/50.000code points per field, config resolver reference chỉ giảm; semantic/schema Unicode/page boundary tests.
- Hai examples mới: partial-document-error giữ nguyên pages từ success; output-limit giữ completed entries và đánh dấu entry thiếu OUTPUT_LIMIT. Minimal envelope không fit -> reference serialization từ chối, không truncate; packing toàn pipeline chưa implement.
- Isolation report riêng với OS/adapter/requested/applied/status. Production spike guard trước PDF; not_enforced không CV thật/bomb.

Reviewer vòng1 của đợt scope mới đọc complete new-file contents/status/diff, phát hiện:

| ID | Severity | Vị trí lúc phát hiện | Evidence và fix |
|---|---|---|---|
| M61-S1 | Medium | worker/spikes/m6_1/stack_smoke.py:71 (env construction) | Copy toàn os.environ có thể truyền credentials tới child. Đổi safe_engine_env allowlist, mock-env regression xác nhận DB/AWS keys không được chuyển |
| M61-S2 | Low | worker/spikes/m6_1/Dockerfile.ubuntu:9 | Shell loop không fail-fast khi curl một model lỗi. Thêm exit1, rebuild image và verify3model hashes ở runtime |

M61-S1/S2 fixed, kiểm chứng Windows/Ubuntu đã chạy lại source cuối. Developer runtime iterations còn sửa PdfPage/Bitmap lifecycle, Tesseract output configs và parse semver khác presentation hai OS; mọi assertions vẫn giữ, có negative version tests. Xem báo cáo spike để biết initial failures/logs, không báo các lượt đó PASS.

External risk M61-EXT1:40GCC stringop-overflow warnings khi build upstream Tesseract5.5.3 helpers.h204/205/endian deserialize. Chưa chứng minh lỗi runtime hoặc false positive, không tự gán defect severity/dismiss. Giữ log, cần điều tra trước chốt binary production; đây là hạn chế/rủi ro đã báo, không tuyên bố production readiness. Những warning provisioning khác được phân loại riêng.

Evidence hiện tại:
- Windows:14contract tests+3guard tests,0failures/0errors/0skipped;16component checks pass; source Python compile -Werror pass.
- Ubuntu24.04.5 container thực: cùng14+3tests và16checks pass, same script/model/font/fixture hashes; không gọi là CI/VPS deployment.
- Windows isolation not_enforced; Ubuntu cgroup settings observed enforced; resource_safety_verified=false ở cả hai. Không resource bomb/real CV.
- Logs/report dưới worker/build/m6-1-spike/ bị ignore, source còn untracked; chưa commit/push/merge. Backend/infra không đổi, Gradle NOT APPLICABLE cho scope worker/design/spike.
- Design review + component compatibility được kiểm chứng có giới hạn; toàn pipeline, production safety/quality/timeout-recovery và M7 NOT VERIFIED/chưa triển khai.
### Reviewer vòng2 — kết luận đợt cập nhật/spike

Đã đọc lại schema partial/failed, authority limits/config tests,7examples, source reference checker, diagnostic script/guards/Dockerfile và báo cáo cuối. M61-S1/S2 fixed and verified, không còn finding code/design đã xác nhận chưa xử lý trong scope. M61-EXT1 là unresolved external compiler-warning risk trước production, không biến thành “đã sửa” nhờ smoke pass.

14contract+3guard tests và16smoke checks mỗi OS pass trên source cuối. New-file no-index whitespace checks pass; có3Git LF->CRLF notices cho input provisioning text dùng LF, đã phân biệt với whitespace defect/compile warning, không tắt core.autocrlf hoặc che stderr tests. Hai Reviewer passes trong đợt scope mới, đúng giới hạn tối đa3; các pass thiết kế cũ là lịch sử task trước. Không subagent/external audit.

Main checkout sạch, worktree chỉ có19intentional files worker/design và worker/spikes/m6_1 chưa tracked. No backend/infra/schema migration changes, no real CV, no M7, no Git publishing. Tất cả resource-safety/production/quality/CI giới hạn giữ nguyên trong M6_1_STACK_SPIKE.md.
