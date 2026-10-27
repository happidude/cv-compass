# Báo cáo M10.2 — Mapping Foundation

Ngày 27/10/2026 (Asia/Saigon). Quality gate local: **PASS** trên source cuối bên dưới.
Không merge, xóa worktree hoặc triển khai milestone kế tiếp.

## Baseline và phạm vi

- Đã fetch origin trước khi tạo nhánh: main mới nhất là
  `d0b7f81d02e2f5910aeea356c2322f91cca7e5bb`, chứa convention đã merge.
  `be7fb4388c18c100dfb34f06ebbd594b4734d65b` là baseline thiết kế trước convention.
- Checkout gốc `E:\cv-compass` sạch, branch `docs/code-conventions`,
  HEAD `65638d6e34f1f2b23be4b38941ecfa0cdd29cf65`; giữ nguyên checkout này.
- Branch: `codex/m10-2-mapping-foundation`.
- Worktree: `C:\Users\NguyenVanHoang\.codex\worktrees\m10-2-mapping-foundation\cv-compass`.
- Commit chứa báo cáo này được xác định bằng SHA trong PR/thông điệp bàn giao cuối.
  Không ghi SHA tự tham chiếu vào chính nội dung commit.
- Đã đọc AGENTS, workflow phát triển/Git, convention và cấu hình formatter từ checkout gốc.
  Các tài liệu local bị Git ignore không được force-add.
- Thiết kế đã đọc: `E:\cv-compass\docs\CV_Compass_M10_1_Design.md`;
  SHA-256 `925a940db45d02b69550628f72d26c6aedbfc61f6cf3a567f46fab8675afd352`.
  Tìm kiếm ban đầu bỏ sót do Git ignore; đã sửa thông tin, đối chiếu thiết kế
  và bổ sung các thiếu sót trước quality gate/publication.

## Kết quả

Module mapping đọc named interface `ingestion::ocr-input`; không đọc entity/repository
ingestion. V7 thêm config/settings/jobs/results; không sửa V1–V6, OCR contract/status,
dependency, formatter hoặc sáu test exclusions. Không sửa Python/frontend.

Luồng synthetic đã chạy: OCR job complete qua validator hiện có → extraction đã lưu →
scan/enqueue → claim → Java worker → result/evidence và job completion cùng transaction.
Config invalid/incompatible bị từ chối; config active/result bất biến bằng trigger.
Revision, engine và fingerprint đầu vào được giữ; claim giả không thay nguồn/config.
Quét keyset có upper bound, cursor bền vững và reconciliation từ đầu mỗi sweep.
UUID fencing, DB clock, lease/hard deadline, retry hữu hạn và reaper bảo vệ completion.
Chỉ transient/recoverable DB errors và lease expiry được retry; lỗi còn lại terminal.
Replay cùng result/digest idempotent; khác result/digest conflict.

Partial giữ nguồn chưa đầy đủ, chỉ dùng page ok. Failed/no-ok/legacy có lý do rõ,
không tạo business field hoặc bịa số trang. Evidence dùng raw_text, code point
[start,end), kiểm tra substring; có test non-BMP/ký tự tổ hợp. Không tạo candidate/profile.

Các nhóm file: public OCR interface/adapter; mapping config/DTO/engine/digest/repository/
service/scheduler/worker; V7; safe event enum; năm class test mapping; test architecture,
migration/bootstrap hiện có; fixture JSON tổng hợp; MAPPING.md và báo cáo này.
Git diff/staged file list là danh mục chính xác.

## Bằng chứng local

Environment: WSL CVCompass-Ubuntu-24.04, OpenJDK 25.0.4.1,
Gradle wrapper 9.7.1, PostgreSQL 17.11 trong container test riêng
`cv-compass-m10-test`, bind loopback port 55432. Mỗi class dùng schema/storage
cô lập của helper hiện có; không dùng DB/schema dev. TEST_DB_* được đặt riêng
trong process; không commit credential.

Working directory:
`/mnt/c/Users/NguyenVanHoang/.codex/worktrees/m10-2-mapping-foundation/cv-compass/backend`.

Source đã kiểm chứng: base d0b7f81 + diff M10.2, fingerprint SHA-256 của manifest
`backend/build/m10-2/source-manifest.txt` (mọi file trong backend/src, sorted path + hash):
`9d9ef86461e275225450da0a7aff80126bac85cb5f69c4056525883cec2d802e`.
Không sửa runtime/test source sau full check này; chỉ hoàn tất tài liệu/report và Git.

Lệnh cuối, từ backend (TEST_DB_* đã có trong environment):

```sh
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessApply --rerun-tasks --console=plain --warning-mode=all
/usr/bin/time -v -o build/m10-2/verified-resource-usage.txt bash gradlew --no-daemon -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
```

Init script local chỉ đặt build output vào
`/tmp/cv-compass-m10-2-01a17d65/build`; không đổi build.gradle/task logic/assertion/
formatter/dependency. Source vẫn là worktree trên. Mục đích là tránh I/O metadata
classpath từ Windows qua WSL. Script/logs/output đều ignored, không commit.

| Check cuối | Kết quả |
| --- | --- |
| spotlessApply riêng | PASS, 2 tasks executed |
| spotlessCheck + full check --rerun-tasks | PASS, 7 actionable tasks / 7 executed; 2m20s |
| Fresh JUnit XML | 357 tests / 0 failures / 0 errors / 0 skipped; 49 suites |
| Modulith modules.verify + package rules | PASS trong full suite |
| Flyway upgrade V5 → V6/V7, schema/JPA validation, mapping constraints | PASS trong full suite |
| Concurrency, expiry/reaper race, rollback, replay, reconciliation, source/config mismatch | PASS trong full suite |
| git diff --check | PASS; final staged check cũng được kiểm tra trước commit |

Evidence local không commit:
- `backend/build/m10-2/verified-check.log`.
- `backend/build/m10-2/verified-junit/TEST-*.xml`.
- Native fresh originals: `/tmp/cv-compass-m10-2-01a17d65/build/test-results/test/`.
- `backend/build/m10-2/verified-resource-usage.txt`.
- `backend/build/m10-2/source-manifest.txt`.

Fixture metric: 50 trang, 500.000 code point; engine elapsed 11.421127 ms,
heap-used 202.003.032 byte; tổng peak của các heap pool JVM 282.190.752 byte.
Peak pool sum có thể gồm peak từ test trước và không phải simultaneous RSS
hoặc bộ nhớ riêng một CV. /usr/bin/time của lượt no-daemon: wall 2:20.89,
maximum RSS 777.368 KiB của quá trình kiểm chứng/process descendants, không phải
đo tổng RAM đồng thời mọi tiến trình. Đây là số đo synthetic, không phải benchmark
100k CV hoặc cơ sở hiệu chỉnh lease/accuracy production.

### Các lượt trước không dùng làm bằng chứng final PASS

- Windows Gradle/Java 25: lỗi loopback trước build; native Windows gate NOT VERIFIED.
  WSL final gate ở trên là local execution thực sự, không phải CI thay thế.
- Targeted lượt đầu: 19/10 failures/0 errors/0 skipped do thiếu TEST_DB_PASSWORD.
- Targeted có DB: 19/1/0/0; fixture restart dùng transaction manager không tương thích.
- Targeted mở rộng: 26/1/0/0; kỳ vọng logging thiếu suffix loại exception an toàn
  của OperationalLog. Đã sửa expectation chính xác, vẫn kiểm tra không throwable/private text.
- Full trước sửa assertion migration: 351/1/0/0, 48 suites. Assertion cũ đòi version 6;
  đã cập nhật exact version/history V1–V7, không bỏ assertion.
- Full trước đối chiếu bổ sung thiết kế: 351/0/0/0 (source cũ, không tính final).
- Full sau bổ sung thiết kế trước test provenance cuối: PASS (source trước final).
- Những sửa cuối đều đã được full gate 357/0/0/0 kiểm chứng lại.

Warnings: không có compiler/deprecation warning mới trong log cuối.
Gradle thông báo single-use daemon/configuration-cache chỉ là thông tin.
Git có advisory LF → CRLF theo cấu hình autocrlf có sẵn; không sửa Git config,
không normalize migration/contract cũ và diff --check vẫn PASS.
Không có fresh problems report báo lỗi. Không suppress warning hoặc nới/xóa test.

## Review

Một coding agent đổi vai Developer/Reviewer, **2 vòng**, base review origin/main d0b7f81;
đọc diff committed/unstaged/staged, file mới và code quanh các boundary/transaction.

| ID | Mức | Vị trí hiện tại | Finding và xử lý |
| --- | --- | --- | --- |
| CVMAP-R1 | High | mapping/repository/MappingRepository.java:149 | Lease null sau reaper gây NPE khi đọc stale claim. Đã xử lý null và kiểm tra token; regression sau reaper, trước claim mới và race PASS. |
| CVMAP-R2 | Medium | mapping/config/MappingBootstrap.java:35 | Thiếu test cho đường nạp cấu hình từ file. Đã thêm malformed/oversized, register/activate và private diagnostic redaction; PASS. |
| CVMAP-R3 | Medium | MappingIntegrationTests.java:665 | Fixture partial/failed ban đầu sửa JSON trực tiếp, chưa chứng minh qua OCR validator. Đã seed qua OcrJobService.complete/validator hiện có; PASS. |

Các vị trí mapping là tương đối `backend/src/main/java/com/cvcompass/backend/`;
test ở `backend/src/test/java/com/cvcompass/backend/`.
Vòng 2 xác minh sửa lỗi, toàn bộ diff mới sau đối chiếu thiết kế, schema, provenance,
fencing, config compatibility, terminal/transient failure và bằng chứng final.
Không còn Critical/High/Medium mở; không có Low finding cần theo dõi.
Các category review đều áp dụng; không có category bị bỏ qua để mở gate.

## Quyết định khác hoặc giản lược so với M10.1

Đặc tả M10.2 cho phép slice synthetic tối thiểu; các giản lược được công khai:

| Quyết định | Mặt tốt | Giới hạn |
| --- | --- | --- |
| STRING/PAGE, FIRST_LINE/LITERAL; chưa semantic candidate/experience | Rule hữu hạn, evidence kiểm chứng trực tiếp, không thêm taxonomy/normalization chưa cần | Chưa tách section/kinh nghiệm, field types khác, nhiều span hay taxonomy |
| Không tạo normalized derivative text | Không mất offset/source Unicode; không bịa offset khi normalize | Chưa có matching không dấu/NFC/case-insensitive hoặc qua nhiều dòng/trang |
| Config schema/capability validation = TESTED; policy/normalizer gắn engine version | Version tái lập cho rule tối thiểu; inactive/active rõ ràng | Chưa có workflow UI/test corpus trên từng config hoặc config taxonomy/quality policy riêng |
| FIFO, không priority/backfill operator API | Ít cơ chế, sweep bounded/reconciliation có test | CV mới có thể chờ backlog; chưa benchmark throughput 100k |
| PENDING/PROCESSING/COMPLETED/FAILED; retry wait bằng NEXT_ATTEMPT_AT | Cùng style trạng thái kỹ thuật sẵn có, bảng độc lập OCR | Tên khác RUNNING/SUCCEEDED/RETRY_WAIT dự kiến trong thiết kế |
| Result source/config qua job FK và payload kiểm chứng authoritative | Không có cột metadata dư dễ lệch; test mismatch/claim giả | Đọc metadata cần join job/config hoặc payload |
| File config được nạp khi restart; không admin REST API | Thay field được hỗ trợ không cần deploy; không mở auth surface | Chưa hot reload hoặc UI quản trị |
| Lease/hard deadline cố định 120s, tối đa 3 attempts, backoff 30s | Cơ chế phục hồi tối thiểu có fencing và đo synthetic | Chưa hiệu chỉnh bằng representative workload; không heartbeat |
| In-process Java worker, một task mỗi instance | Đúng modular monolith, không thêm hạ tầng/giao thức | Nhiều app instance có thể xử lý đồng thời; chưa global concurrency cap |
| Failed/legacy tạo technical result có quality/reason, values rỗng | Có thống kê skip bền vững, không poll lại vô hạn | Không tạo business mapping hoặc page giả; cần đường xử lý legacy riêng |

Không thay OCR production gate; chưa có real-CV accuracy/golden set hoặc broad auto-accept.

## Local và CI

Local final: PASS theo fingerprint/commands/JUnit trên.
CI tại lúc lập commit/report: NOT VERIFIED, vì chưa push/tạo PR ở thời điểm ghi file.
Sau push sẽ đối chiếu CI đúng SHA trong báo cáo chat/PR; không dùng CI của commit cũ
để thay local evidence. Các kết quả CI đến sau không nằm trong snapshot file này.

Dừng ở M10.2 để developer review. Không tự mở M10.3.
