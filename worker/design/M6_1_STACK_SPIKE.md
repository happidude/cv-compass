# M6.1 — Contract review đã chốt và component spike

Ngày2026-10-18. Branch docs/m6-1-ocr-design, worktree E:/cv-compass-ocr-design.
Base/HEAD: 5c06070955e2313f041be47ac313d97b2c079bea (main đã merge PR#8).
Các file thiết kế/checker/spike chưa commit/push. Không M7, không pipeline worker đầy đủ.

## 1. Ba quyết định đã áp dụng

- success: >=1page ok, còn lại ok/blank, document errors rỗng.
- partial: >=1page ok và có failed/not_processed page HOẶC document error, vẫn giao JSON hợp lệ. Thêm result-partial-document-error.json với mọi page ok, giữ pages byte-for-byte về cấu trúc từ success example.
- failed: không page ok, gồm allblank; không ép đổi page đã thành công và không đòi thêm document error nếu page error đã mô tả nguyên nhân.
- Output cap: thiết kế giữ completed pages còn vừa, entry bị loại/chưa chạy phải OUTPUT_LIMIT, không truncate text/raw_text; không vừa envelope tối thiểu thì exit3/no delivery. Reference checker xác nhận example page preservation và serialization không cắt JSON; CHƯA triển khai algorithm packing toàn pipeline.
- Cleanup failure chỉ warning; có regression giữ status success và pages bất biến.
- Canonical ceilings nằm ocr-v1.limits.json:50pages, 50.000Unicode code points/mỗi text và raw_text. Schema projection/config reference resolver đọc cùng authority; config chỉ giảm và bị reject trước mock callback PDF. Frozen v1/anti-drift/schema/config boundary tests có cả non-BMP/combining sequences; không đếm UTF-16 units hoặc UTF-8 bytes như ký tự.
- Isolation chỉ trong verification report riêng, không thêm fields tùy tiện vào OCRv1. Diagnostic not_enforced chỉ ordinary synthetic, không CV thật/resource bomb. Production guard spike từ chối trước engine/fixture; không giả rằng đã implement production adapter.

## 2. Kết quả thực thi trên source cuối

| Suite/check | Windows | Ubuntu |
|---|---|---|
| Contract/schema/semantic unittest |14tests,0failures,0errors,0skipped;0.260s|14tests,0failures,0errors,0skipped;0.497s|
| Guard unittest |3tests,0failures,0errors,0skipped;0.002s|3tests,0failures,0errors,0skipped;0.002s|
| Component smoke |16checks,0failures,0errors|16checks,0failures,0errors|
| Recognition/OSD stderr |0lines/0lines|0lines/0lines|
| Source PDF unchanged + temp cleanup |Verified|Verified|

Contract tests dùng jsonschema Draft202012Validator.check_schema +7examples và8negative mutations; semantic matrix/state/output/cap checks. 17unittest methods/platform có nhiều subTest cases, không gọi subTest là riêng một OCR test. Unicode schema boundary50.000/50.001 và pages50/51 đã thực thi. Production refusal có unit assertions không gọi child/native_fixture và Windows CLI thực tế trả3 với0checks trước engine.

Windows Python sources cũng qua `python -W error -m py_compile` cho verify_contract.py, stack_smoke.py, test_stack_smoke.py. Không warning source Python cuối. Đây không phải Gradle/JUnit suite; không bịa XML. Backend Java/build/migration không sửa nên Gradle check NOT APPLICABLE trong scope này, không tái dùng M5.1 tests làm evidence.

## 3. Môi trường/pin thực tế và provenance

| Thành phần | Windows local | Ubuntu container local |
|---|---|---|
| OS | Windows11 build10.0.26300 |Ubuntu24.04.5LTS, Docker Desktop/WSL2 kernel6.18.33.2, glibc2.39 |
| Python |3.14.8 upstream embedded, tool folder riêng |3.14.8 build source upstream, không PGO |
| Tesseract |5.5.3.20260724 upstream Windows binary |5.5.3 build source tag |
| Leptonica |1.87.0 |1.82.0, platform dependency khác nhau đã ghi rõ |
| pdfplumber/pdfminer.six |0.11.10 /20260107 |Cùng pin |
| pypdfium2/PDFium |5.14.0 /156.0.8076.0 |Cùng pin |
| Pillow/jsonschema |13.0.0 /4.26.0 |Cùng pin |

Host x86_64,12logical CPUs; Ubuntu bị quota2CPU, Windows không hard quota. Không dùng timings đơn lẻ để so throughput hai OS.

Windows Python3.14.6 có sẵn chỉ dùng bootstrap venv. Actual tests/spike chạy binary embedded3.14.8, ._pth trỏ đến site-packages venv riêng; không nâng Python global. Tesseract installer được verify release digest rồi chỉ giải nén bằng7-Zip administrative-extracted vào build, không chạy Tesseract installer. Không cài service hoặc thay PATH hệ thống.

Upstream Windows installer SHA256: bee9e3434bd94fd65387d9be28cd467a41f61b1275383b55b0f59a1331270ae4 (đối chiếu GitHub release asset digest).
Python embedded ZIP SHA256 ghi nhận: a93abe456ab01bd96d7a085b3cdb6566b3063f4241360d114142fbdb07f0a310.
Linux Python source tar SHA256 ghi nhận: c2215904f02b175596dc49351585104f4bc20341e1c47378b26a2c274360ce73.
Linux Tesseract source tar SHA256 ghi nhận: 9218e62793116d42a9f6d14cd9348518b27f382096eea3d0f2d1a24616bb5884.
Các hash ghi nhận không được gọi là đã verify Sigstore/GPG; chưa có full supply-chain audit.

Models cùng immutable tessdata_fast revision87416418657359cb625c412a48b6e1d6d41c29bd trên cả hai OS, hash được kiểm tra trước fixture:

| Model | SHA256 |
|---|---|
| eng |7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2|
| vie |79df64caf7bcfb2a27df5042ecb6121e196eada34da774956995747636d5bfa1|
| osd |9cf5d576fcc47564f11265841e5ca839001e7e6f38ff7f7aacf46d15a96b00ff|

DejaVuSans2.37 font cùng hash7da195a74c55bef988d0d48f9508bd5d849425c1770dba5d7bfc6ce9ed848954.
Hai synthetic fixture files cũng byte-identical giữa hai OS:
- native.pdf:690bd9b28818cb40f3dfeb9a1ec1b32b47e9b286e7fe17f85f60da46d754e50e.
- scan.pdf:950d0d4270a1f4fb207331189bbfddedf353a86d37f272bf87c25c0ec7795b7d.

Dependency freeze hai OS trùng cả13packages, lưu riêng trong build reports; không coi requirements direct pins là production lockfile có đủ wheel hashes.

## 4. Diagnostic và limits thực sự áp dụng

| Thuộc tính | Windows | Ubuntu |
|---|---|---|
| Adapter |none |docker_cgroup_v2_observed |
| Status |not_enforced |enforced cho cgroup settings quan sát được |
| Requested |memory1GiB,2CPU,pids64 |memory1GiB,2CPU,pids64 |
| Applied |không có OS hardcaps |memory.max1073741824,cpu.max200000/100000,pids.max64 |
| Extra Docker settings |không |read-only root,network none,nonroot65532,cap-dropALL,no-new-privileges,tmpfs256MiB |
| resource_safety_verified |false |false |

Ubuntu image digest sha256:6b5d23e3d54c6dd0a6223a2e6c0839bedc9041255a470ef42ff2de14088cd749; base Ubuntu digest pin trong Dockerfile. docker-applied-settings.json ghi readback trước chạy; report trong container đọc cgroup thực tế. Đây là evidence cấu hình đã áp dụng, KHÔNG phải OOM/kill/process-tree recovery hoặc proof resource safety production. Không resource-bomb/malicious fixture đã chạy ở bất kỳ OS.

Spike không có external PDF input argument: tự sinh đúng hai fixture thông thường trong private TemporaryDirectory rồi xóa. Không chạm D:, không mount inbox/storage/.env/DB, không gửi OCR text ra ngoài. Network chỉ dùng provisioning public packages/models; Ubuntu smoke network none. Windows không có OS deny-network/hard isolation, chỉ dùng local known artifacts và ordinary synthetic. Child env allowlist không truyền DB/cloud credentials.

## 5. Kết quả component và timing tham khảo

16checks: Python pin, Tesseract semver,3model hashes, native page count/text, scan không native text, bounded render dimensions, English recognition, Vietnamese dấu, synthetic email/phone, TSV confidence thật, OSD, PDF hash unchanged, cleanup.

| Stage | Windows ms | Ubuntu ms |
|---|---:|---:|
| Render |263.147|416.670|
| OCR |1079.406|1034.333|
| Ordinary fixture run |2872.858|3277.804|

Một invocation cuối/platform, không warm/cold benchmark, không p95/SLA. Chưa đo peak RAM toàn cây process. Ground truth spike là strings do người viết fixture xác định trước khi engine chạy; không lấy OCR output làm đáp án. Không CER/WER corpus/reading-order/mixed-page quality claim; không dùng16checks để khẳng định chất lượng CV thật.

## 6. Lỗi phát hiện/sửa và warning còn lại

| Mục | Nguyên nhân và hành động | Kết quả |
|---|---|---|
| PdfPage/PdfBitmap TypeError |Exact pin không hỗ trợ context manager; dùng close/finally đúng lifecycle |Windows/Ubuntu đều render và cleanup pass |
| TSV FileNotFoundError |Model dir riêng chỉ có weights, thiếu aliases configs txt/tsv; bật hai output bằng -c tessedit_create_txt=1 /tessedit_create_tsv=1 cùng invocation |TSV/text cùng lần OCR, đủ confidence assertions; không bỏ test |
| Ảnh DPI |Ghi metadata PNG300DPI thay vì để engine tự estimate |Recognition/OSD stderr cuối0 |
| Version assertion Linux |Prefix Linux không có v, Windows có v và suffix build; parse tuple chính xác5.5.3 |Có negative tests reject5.5.30/5.5.2/5.6.3/rc1 |
| Child inherited environment |Review phát hiện copy toàn env; đổi allowlist +OMP1 |Guard test không truyền dummy DB/AWS credentials |
| Ubuntu model download fail-fast |Shell loop có thể bỏ qua lỗi một language; thêm exit1 khi curl fail |Rebuild Dockerfile cuối pass, runtime verify đủ3hash |
| IPv6 provisioning timeout |PowerShell/default pip bị timeout, curl4 hoạt động; dùng IPv4 cho download/provision child, không tắtTLS hoặc đổi DNS hệ thống |Pinned packages/models provision thành công |

Raw initial failures giữ trong stack-windows-initial-failure.json, stack-windows-tsv-failure.json, stack-ubuntu-initial-failure.json. TSV BOM từng được xem xét nhưng chưa phải nguyên nhân; final tsv_utf8_bom=false ở hai OS, không khai nhận “đã sửa BOM bug”. utf-8-sig chỉ là decoder compatible.

Provisioning Ubuntu log lần đầu có55warning lines:11update-alternatives missing manpages trên minimal image;1CPython configure dùng bundled libmpdec deprecated cho3.16;1pip-script PATH notice;2pip-root warnings;40GCC -Wstringop-overflow warnings trong Tesseract5.5.3 helpers.h204/205, inlined ReverseN/FReadEndian/DeSerialize uint32.

**Chưa kết luận40compiler warnings là false positive hay lỗi runtime.** Đã đọc upstream byte-swap/deserialization source, không đủ bằng chứng để dismiss. Không tắt warning flags, sửa upstream/đổi compiler để làm log sạch hoặc chạy malicious fixture để thử trong diagnostic. Đây là rủi ro cần điều tra trước chốt binary production; smoke pass không giải quyết nó. Không tuyên bố build hoàn toàn zero-warning. Warning PATH/root/manpage thuộc provisioning, không warning runtime code; CPython bundled-libmpdec còn là quyết định build production sau.

Pip freeze từng có warning cache /.cache không writable; lượt ghi evidence cuối đã cấp PIP_CACHE_DIR=/tmp/pip-cache trong tmpfs writable, không suppress warning. Temporary patch command từng có Python SyntaxWarning do string escape trong code sửa file, không thuộc source cuối; `-W error -m py_compile` source cuối pass. Pip Windows có thông báo version mới, không tự update tooling global để đổi evidence.

Nguồn điều tra warning: upstream https://github.com/tesseract-ocr/tesseract/blob/5.5.3/src/ccutil/helpers.h và serialis.cpp/serialis.h cùng tag. Log build giữ nguyên, không chỉ dựa cache/no-warning rebuild sau đó.

## 7. Logs, commands và cleanup

Các artifacts dưới worker/build/m6-1-spike/ bị ignore, không force-add:
- contract-windows.log, spike-guards-windows.log, stack-windows.json.
- ubuntu-report/ubuntu-final-suite.log, stack-ubuntu.json, docker-applied-settings.json.
- ubuntu-build.log (bao gồm warning gốc), ubuntu-final-build.log, windows-freeze.txt, ubuntu-runtime-freeze.txt.

Commands từ worktree root (Windows runtime đã provision như README spike):

```powershell
& worker/build/m6-1-spike/python3148/python.exe -W error -m py_compile worker/design/verify_contract.py worker/spikes/m6_1/stack_smoke.py worker/spikes/m6_1/test_stack_smoke.py
& worker/build/m6-1-spike/python3148/python.exe worker/design/verify_contract.py
& worker/build/m6-1-spike/python3148/python.exe -m unittest discover -s worker/spikes/m6_1 -p test_stack_smoke.py -v
```

Smoke arguments đầy đủ trong worker/spikes/m6_1/README.md. Ubuntu container chạy ba command tương ứng trên cùng bind-mounted source cuối, cùng font; docker settings ở mục4. Local Docker Ubuntu được chạy thật; không gọi đó là GitHub CI hoặc Ubuntu VPS deployment.

Temporary fixture dirs đã cleanup, container run tự remove và đã kiểm tra không còn tên cv-compass-m61*. Image/build cache/private downloaded tools/models giữ lại dưới build/hoặc Docker cache để tái lập; không remove worktree hoặc đụng container của người dùng.

## 8. Kết luận có giới hạn

- Contract/schema/examples và canonical-limit/reference-semantic checks: PASS theo ba quyết định đã chốt.
- Component compatibility spike Windows/Ubuntu: PASS trên ordinary synthetic fixtures, đúng pins đã ghi.
- Pipeline OCR đầy đủ, packing supervisor/timeouts/crash recovery, quality corpus/CER/WER/reading order, peak-RAM/resource safety, production adapters, real-CV, Ubuntu VPS và CI: NOT VERIFIED/chưa triển khai. Đặc biệt native compiler warning cần điều tra trước quyết định binary production.
- Không chốt toàn M6 đã hoàn thành hoặc production ready. Không M7, không CV thật, không commit/push/merge. Chờ review kết quả và scope tiếp theo.