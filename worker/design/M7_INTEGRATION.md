<a id="m73--m75-integration--implementation-not-acceptance-evidence"></a>

# Tích hợp M7.3 → M7.5 — implementation, không phải bằng chứng nghiệm thu

> Thiết kế/bằng chứng v1 lịch sử. Kết quả mới dùng `cv-compass.ocr/2.0` và chỉ `raw_text`; xem `OCR_V2_RAW_TEXT.md`. Snapshot/kết quả v1 hiện có vẫn hợp lệ.

Phần mở rộng tiếp nhận cục bộ: contract mặc định bên dưới vẫn chỉ dùng dữ liệu tổng hợp.
Deployment cục bộ được cấu hình rõ ràng gửi `synthetic_only=false` và `local_only=true`
trong claim data; worker phải chủ động chọn cùng chế độ trong `pull.toml`.
Snapshot tiếp nhận cục bộ gắn hash/kích thước document thực tế và provenance profile
đã đo với `admission_mode=local`, không bịa `fixture_id`.
Không đổi result schema, auth, lease, fencing, replay hoặc quy tắc cô lập.
Xem [LOCAL_OCR.md](../../backend/LOCAL_OCR.md); không miễn gate production.

Base origin/main `afba013d0e40355b13f4adf9dbd59302d162988d`. Branch riêng
feature/m7-integration. Không migration/broker mới, không sửa migration cũ,
không candidate mapping, frontend hoặc worker DB access. Chưa đánh giá accuracy;
đánh giá chất lượng vẫn ở backlog. GCC/Tesseract chưa giải quyết vẫn chặn production.

<a id="m73-authenticated-backend-http"></a>

## M7.3: HTTP backend có xác thực

Base `/internal/ocr/v1`: POST `/claims`, PUT `/attempts/{uuid}/lease`, GET
`/attempts/{uuid}/input`, PUT `/attempts/{uuid}/result` và `/failure`.
Spring SecurityFilterChain theo phạm vi xác thực trước MVC/body decoding, dùng
stateless ROLE_OCR_WORKER và AuthenticationProvider từng worker; Boot BOM quản lý
security dependency. Public API không khớp giữ policy hiện có, không tắt CSRF toàn cục/
default login hoặc user tự sinh. Disabled→503, credential không hợp lệ/thu hồi→401,
worker/profile không được phép→403, attempt không biết→404, cũ/xung đột→409,
bytes→413, media/encoding→415, range→416, contract/provenance→422.
Chỉ ApiError an toàn; không log request content/token. Dùng TLS trực tiếp trên VPS riêng
hoặc TLS proxy được tin cậy, backend bind loopback. Từ chối cleartext ngoài loopback.
Không public route này, tin forwarded header tùy ý hoặc log Authorization/X-Lease-Token tại proxy.

Bearer secret mỗi worker: 43–256 ký tự base64url, provision ngoài Git. Cấu hình server
giữ SHA256 credential hash và profile allowlist; không nhận worker_id trong request body.
Xoay vòng bằng thêm hash, chuyển worker secret, rồi xóa hash cũ và redeploy.
Hash trùng giữa các worker làm validation fail. Thu hồi cũng từ chối terminal replay.
Không authentication dependency trong PDF child.

Claim body chính xác `{"protocol":"cv-compass.ocr-job/1.0","profile_revision":"<revision>"}`.
Header: UUID canonical Idempotency-Key + X-Lease-Token, 32 byte ngẫu nhiên base64url
không padding, được sinh/lưu trước claim. 201 mới /200 replay có Location,
204 khi rỗng. `ApiResponse.data` có protocol, job_id dạng string, attempt UUID,
attempt_no, server_time/lease_expires_at/hard_deadline_at, synthetic_only=true,
size_bytes, input_path và ocr_request M6 không đổi. Lease PUT chỉ nhận `{}`.

Input kiểm tra lock job→attempt, proof và DB clock sau lock, không gia hạn lease.
Stream storage ngoài transaction; chỉ opaque key, không client path, filename,
redirect hoặc compression. Có Content-Length/X-Content-SHA256; worker kiểm lại
kích thước/hash thực trước OCR. Stream kiểm tra thời gian trôi qua giữa lần đọc;
socket write bị block vẫn là vấn đề triển khai HTTP server/proxy, không khẳng định
hard filesystem I/O deadline. Không transaction kéo dài qua HTTP transfer/OCR.

File từng tham chiếu docs/POC_OCR_API_CONTRACT.md không có trong checkout này.
Xem [bản đồ nguồn contract](../../docs/contracts/README.md) và
[phần mở rộng raw-text v2](OCR_V2_RAW_TEXT.md) trước thay đổi; mô tả v1 lịch sử này
không thay phê duyệt contract. Snapshot schema M6 vẫn giữ cùng v2.
Giữ UTF8 nghiêm ngặt, duplicate/unknown key, depth32, body cap và ngữ nghĩa M6.
Digest là byte UTF8 HTTP chính xác, không phải JSON serialize lại. Metadata deployment
được đối chiếu snapshot tiếp nhận bất biến; lỗi preflight giữ observation null
và model rỗng. Đây là khai báo worker đã xác thực, không phải chứng thực phần cứng từ xa.
Result đã giao là terminal, kể cả OCR fail và cleanup warning.
201 receipt /200 replay chính xác kể cả đã hết hạn; byte khác hoặc fence cũ→409.
JSONB giữ pages/text/raw_text/status/confidence/timings/engines/models/config.
Spring sở hữu toàn bộ ghi DB. Không rebind, ghi đè hoặc bịa extraction.

Failure body: protocol + OcrFailureCode M7.2, không gồm LEASE_EXPIRED chỉ phía server.
Chỉ DOWNLOAD_UNAVAILABLE/SUPERVISOR_TIMEOUT/LEASE_EXPIRED retry; hash/config/
result không hợp lệ thì không. Scheduler đã cấu hình gọi expireDue hiện có,
giữ lock order job→attempt, ngân sách retry/quarantine và hard deadline.
Mặc định vẫn 3 attempt, lease120s/heartbeat30s. Technical test không thể mở gate production.

<a id="m74-ubuntu-host-supervisor"></a>

## M7.4: Host supervisor Ubuntu

Chạy `python worker/run_pull_worker.py --config <absolute-operator.toml> [--once]`.
HTTP thư viện chuẩn, TLS đã validate, origin được pin, không redirect, retry có giới hạn,
absolute socket deadline kể cả trickle response. HTTPS DNS lookup vẫn phụ thuộc
host resolver. HTTP thường chỉ cho phép IP loopback dạng số.

Mẫu TOML ngoài repository, placeholder cố ý không hợp lệ, KHÔNG phải pin đã đo:

```toml
enabled = false
synthetic_only = true
origin = "<configured-backend-origin>"
credential_env = "CV_COMPASS_OCR_TOKEN"
spool_root = "/private/ocr-synthetic-spool"
pipeline_config = "/private/m6-diagnostic.toml"
profile_revision = "<registered-revision>"
worker_commit = "<measured-40-hex-commit>"
image_id = "sha256:<measured-64-hex-image-id>"
config_fingerprint = "<measured-64-hex-M6-config-fingerprint>"
heartbeat_seconds = 30
http_timeout_seconds = 10
download_timeout_seconds = 60
retry_backoff_seconds = 1
poll_seconds = 5
max_http_attempts = 3
max_file_bytes = 10485760
max_result_bytes = 8388608
max_envelope_bytes = 8454144
max_spool_bytes = 67108864
```

Tạo spool Linux riêng ngoài Git, mode0700, thuộc UID supervisor, tách storage/inbox
backend. Dùng M6 build_baseline.py/verify_baseline.py và hash lockfile hiện có,
không dùng image chỉ có trên laptop. Bắt buộc Docker config diagnostic cùng image ID/
fingerprint được pin; không native fallback hoặc flag/config do job cung cấp.
TOML operator đã validate được snapshot riêng trước gọi CLI. Tên biến môi trường
credential phải dùng prefix CV_COMPASS_OCR_; không dùng lại LANG/PATH/HOME vào mục đích khác.
Credential ở supervisor; environment/mount an toàn M6 child không đổi, không chứa token/socket.

Flock độc quyền, mỗi lần một attempt; lưu claim UUID/token trước HTTP. Stream PDF
có giới hạn, kiểm size/SHA256, gọi M6 CLI hiện có trong process cùng heartbeat thread.
Ngân sách lease monotonic bị chặn bởi hard deadline local ban đầu kể cả DB clock
lùi. Mất heartbeat hủy qua vòng đời kill/wait/remove child thuộc M6, kể cả hủy khi
staging; không chỉ kill Docker CLI. Timeout pipeline phải nằm trong hard deadline
còn lại, có dự phòng HTTP trước khi OCR bắt đầu.

Spool result envelope hoàn chỉnh một lần, gửi lại byte giống hệt. Crash/mất receipt
chỉ replay kết quả đã lưu, không OCR lại. Receipt validate danh tính job/attempt.
Cleanup warning gửi terminal result hợp lệ rồi dừng nhận mới, giữ lock/spool.
Kết quả không chắc chắn, lease cũ, quota hoặc lỗi cleanup giữ private state và dừng.
Không tự xóa stale lock, cleanup resource khác, TTL purge hoặc nhận mới khi chưa chắc chắn.
Operator chịu trách nhiệm retention/disk quota. Spool budget cấu hình giới hạn intake;
temp budget M6 đang hoạt động vẫn được kiểm soát riêng bằng cơ chế M6 hiện có.

Khôi phục: dừng supervisor; xem claim.json, thư mục UUID thuộc nó và cleanup ledger M6.
Chỉ giải quyết phần còn sót **thuộc sở hữu** theo quy trình M6. Chỉ xóa halt.json
sau review; restart replay chính xác submission.json/failure.json. CLEANUP_FAILED
warning đã lưu tiếp tục chặn dù lock/ledger đã biến mất. Sau kiểm terminal receipt và
giải quyết resource thuộc sở hữu, archive thủ công thư mục UUID đó cùng claim journal;
không sửa byte result hoặc xóa kết quả chưa được xác nhận để tiếp tục nhận việc.
Thư mục chưa hoàn chỉnh không có outcome cần quyết định thủ công, không tự OCR lại.
Không commit file riêng tư này, gồm token/PDF/result, hoặc để PDF child truy cập.

<a id="m75-synthetic-enrollment-and-atomic-enqueue"></a>

## M7.5: Đăng ký dữ liệu tổng hợp và enqueue nguyên tử

Cả cv.ocr.enabled và cv.ocr.integration.enabled mặc định false. Tích hợp yêu cầu
profile không phải prod, local storage, synthetic-storage-root khớp storage cấu hình,
namespace, workers/profiles không rỗng và manifest fixture được sinh đã biết.
Dùng YAML operator ngoài repository, không phải endpoint đăng ký công khai:

```yaml
cv:
  ocr:
    enabled: false
    integration:
      enabled: false
      namespace: synthetic-fixtures-only
      synthetic-storage-root: /private/backend-synthetic-storage
      workers:
        worker_1:
          credential-sha256: ["<SHA256-of-worker-only-secret>"]
          profiles: ["<revision>"]
      profiles:
        "<revision>":
          worker-commit: "<measured-commit>"
          image-id: "sha256:<measured-image-id>"
          config-fingerprint: "<measured-M6-config-fingerprint>"
          engines-json: '<exact measured M6 engines JSON including model revisions/hashes>'
      fixtures:
        - fixture-id: generated-fixture-001
          sha256: "<measured-generated-PDF-hash>"
          size-bytes: 1234 # replace with actual measured size
          profile-revision: "<revision>"
```

Cấu hình storage root và DB credential bên ngoài theo convention hiện có.
Property cv.ocr.integration có kiểu cung cấp reaper-delay/download-timeout/
control-body cap; application.yaml có binding CV_OCR_*. Registry không có mặc định
nới lỏng. Chỉ synthetic SHA + size đã biết trong namespace riêng được đăng ký;
không theo filename/public boolean/quét toàn storage. PDF không biết giữ ngữ nghĩa
ingestion nhưng không có OCR job.

CvDocumentService gọi enqueue trong metadata transaction hiện có sau callback
persistence single/batch/disk, trước commit. Propagation MANDATORY + saveAndFlush:
enqueue/commit fail rollback metadata/item/counter/history và gọi storage compensation
hiện có. Không khoảng trống after-commit. Admission snapshot gắn generated ID +
fixture ID/hash/size/namespace + deployment manifest. Generation1 uniqueness/composite
FK và legacy row không đổi. Batch counter vẫn chỉ dành cho ingestion.

<a id="verification-boundaries"></a>

## Ranh giới kiểm chứng

Backend: JDK25, `cd backend; ./gradlew check --rerun-tasks`. Test PostgreSQL17 thật
dùng schema cô lập và TEST_DB_URL/USERNAME/PASSWORD riêng. OcrWorkerIntegrationTests
dùng HTTP + PG thật, **không phải** bằng chứng OCR engine/an toàn tài nguyên.

Ubuntu: venv mới, `pip install --require-hashes -r worker/requirements.lock -r
worker/requirements-test.lock`, `pip check`; provision Tesseract/model/font qua M6.
Full `python worker/tests/run_checks.py --xml <new.xml>` cần host + Docker child.
`run_m7_independent_checks.py --xml <new.xml>` rõ ràng là **subset**, không full gate.
compileall chỉ kiểm syntax. Transport stand-in/unit fake không chứng minh tích hợp PG/OCR.
Fingerprint source chính xác, lệnh/số liệu/log ghi ở handoff M7 local;
kết quả M6 80/80 hoặc M7.2 cũ không là bằng chứng cho source này.

Docker hiện BLOCKED do vấn đề runtime đã báo; không cho phép restart,
reconfigure, privileged fallback hoặc resource-bomb. Full Docker suite và luồng
backend→adapter→M6 child→JSONB thật là NOT VERIFIED tới khi khôi phục.
Không commit/push/merge hoặc M8 trong lượt này. Baseline trial không sẵn sàng production.
