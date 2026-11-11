# Mapping operations — foundation correction

Baseline PR #17: 2df987c3a9ea511ff9edb67fcadcf51d6154cb97. This document supersedes
the fixed operational defaults described in the original M10.2 report.

| Property | Before | Current default / supported range | Meaning |
| --- | --- | --- | --- |
| cv.batch.max-files | 10, cap 10 | 10 / 1–1000 | Sole maximum files in a business upload/import batch |
| cv.import.batch-size | Separate 10, silently min with max-files | Retired; startup rejects it when importer enabled | Set cv.batch.max-files instead, including environment CV_BATCH_MAX_FILES |
| cv.batch.max-file-bytes | 10 MiB | unchanged, 1–10 MiB | File validation limit |
| cv.mapping.scan-page-size | fixed 100 | 100 / 1–10000 | Metadata extraction IDs in one short transaction |
| cv.mapping.reaper-page-size | fixed 100 | 100 / 1–10000 | Expired jobs inspected per reaper turn |
| cv.mapping.concurrency | fixed 1 per instance | 1 / 1–16 | Outstanding mapping tasks, independent of batch/page size |
| cv.mapping.scan-delay | unvalidated 30s | 30s / 1ms–1d | Delay between scan pages |
| cv.mapping.worker-delay | unvalidated 1s | 1s / 1ms–1d | Delay between bounded worker submissions |
| cv.mapping.reaper-delay | unvalidated 15s | 15s / 1ms–1d | Recovery polling |
| cv.mapping.lease-duration | SQL fixed 120s | 120s / 1ms–1d | Lease selected at each claim |
| cv.mapping.hard-deadline | SQL fixed 120s | 120s / 1ms–1d, >= lease | Absolute attempt deadline, no heartbeat |
| cv.mapping.max-attempts | SQL/check cap 3 | 3 / 1–1000 | Snapshotted on each new job; V8 preserves old jobs' budget 3 |
| cv.mapping.retry-backoff | SQL fixed 30s | 30s / 1ms–1d | Backoff applied when scheduling retry |

Environment names are the uppercase underscore form, e.g. CV_MAPPING_SCAN_PAGE_SIZE.
Mapping properties validate even with automatic mapping disabled. Delays used by
the scheduler and typed properties share the same property keys. API/file upload
retains streaming and a disk-backed multipart parser; raising max-files also raises
the finite request byte ceiling (max-files * per-file bytes + overhead).

A business batch counts uploaded source files/documents. It does not count
extractions: one document may have multiple stored OCR revisions. The scanner reads
the complete extraction history with a fixed upper bound and durable keyset cursor,
enqueues one job per extraction/config/engine and reconciles subsequent sweeps.
It never creates, splits, resizes or renames an import batch, and never changes OCR
status. If a batch's extractions span metadata pages, later ticks resume the cursor
until the sweep covers them. Raising business batch size never raises concurrency.

Repeated bootstrap/activation of the SAME active configuration preserves cursor
and upper bound. Switching active configuration resets both and begins a full sweep.
Pending jobs retain their immutable extraction/config identity and attempt budget.
Lease/deadline are fixed timestamps selected at claim; later property changes do
not extend an already granted lease. The reaper and worker failures honor each
job's snapshotted budget. V8 is additive; V7 is unchanged.

The bounded worker pool has no growing submission backlog: a semaphore limits
outstanding tasks to configured concurrency. Shutdown stops submissions, waits for
already started tasks, and leaves uncompleted attempts recoverable by lease expiry.
Per-instance concurrency is not a global cluster limit.

Invariants remain fixed: supported result/config schema and engine capabilities,
Unicode evidence semantics, 50-page/500k-code-point OCR admission, 32 configured
fields, per-value and total result limits. Upper safety ceilings on files/pages,
concurrency and attempts are invariants; selected operating values are properties.
No OCR protocol limit or production gate is changed.

Foundation verification commands and evidence: [FOUNDATION_CONFIGURATION_REPORT.md](FOUNDATION_CONFIGURATION_REPORT.md).

## Kích hoạt 3.5

Engine `personal-info-mapping/3.5` đã được nối vào runtime (claim, xử lý, publish giống 3.4)
nhưng **mặc định chưa kích hoạt**: không đổi `CV_MAPPING_ENABLED` hay file cấu hình thì hệ thống
vẫn chạy như cũ. Việc kích hoạt là thao tác vận hành của con người, không tự động.

Cấu hình cần đặt (property trong `MappingBootstrap`; có thể đặt bằng biến môi trường tương ứng):

| Property | Giá trị |
| --- | --- |
| `cv.mapping.enabled` (`CV_MAPPING_ENABLED`) | `true` |
| `cv.mapping.definition-file` (`CV_MAPPING_DEFINITION_FILE`) | đường dẫn file `personal-info-config-v3.5.json` (bản trong `backend/src/main/resources/mapping/`) |
| `cv.mapping.revision` (`CV_MAPPING_REVISION`) | `personal-info-grammar-v35-r1` |

Khi khởi động, bootstrap đăng ký và activate config 3.5; việc đổi config active reset cursor và
bắt đầu quét đầy đủ như mô tả ở trên. Revision đã đăng ký là bất biến: không sửa nội dung file
mà giữ nguyên revision.

Lưu ý: `cv.mapping.definition-file` là **đường dẫn hệ thống tệp** (`MappingBootstrap` đọc bằng
`Files.newInputStream`), không phải đường dẫn classpath. Khi chạy từ jar, phải đặt file JSON ra
ngoài jar (ví dụ sao chép từ `backend/src/main/resources/mapping/`) và trỏ tới đường dẫn đó.

Quay lại 3.4: đặt `cv.mapping.definition-file` về file `personal-info-config-v3.4.json` và
`cv.mapping.revision` về `personal-info-grammar-v34-r1`, rồi khởi động lại. Kết quả 3.5 đã sinh ra
không bị xóa hay ghi đè. Job 3.5 đang PENDING lúc rollback vẫn được claim và xử lý bằng 3.5
(engine đã pin theo job), chỉ job mới enqueue sau rollback dùng 3.4.

Trước khi deploy (việc của con người): kiểm tra có job `ENGINE_VERSION='personal-info-mapping/3.5'`
đang `PENDING` hay không; có thể đã bị kẹt từ trước vì validate đã chấp nhận 3.5 nhưng chưa có
đường claim, và sau deploy chúng sẽ được xử lý.

Hệ quả khi kích hoạt và các lưu ý:

- Activate 3.5 đổi config active, nên cursor bị reset và scan chạy full sweep. Scan enqueue job 3.5
  **mới** cho **toàn bộ** extraction hiện có (ràng buộc unique extraction/config/engine); khối lượng
  xấp xỉ số extraction hiện có, cần tính tải trước khi bật.
- Job và kết quả cũ (3.0–3.4) giữ nguyên, không bị replay hay ghi đè.
- Publish kết quả 3.5 cho ứng viên đã có dữ liệu HR đã sửa/xác nhận không ghi đè dữ liệu đó mà
  sinh proposal để HR duyệt.
- Job đang xếp hàng ở 3.4 (hoặc cũ hơn) giữ engine đã pin và vẫn được xử lý bằng đúng engine đó.
- Job 3.5 chỉ được claim khi `engineCompatibility` của config job khớp engine của job.
