# Chạy lại mapping và khôi phục chỉ mục

Dùng khi đổi revision cấu hình mapping hoặc cần xử lý lại một phạm vi CV đã có.
Phải có cấu hình mapping hiện hành. CV mới/scan có priority 0; job chạy lại mặc định
priority 10, được claim sau CV mới ở cả worker local và remote.

## Phiên quản trị và tạo lượt chạy

Dùng session ADMIN và CSRF như admin catalog import. Ví dụ Bash với `curl`, `jq`;
`BASE` là địa chỉ môi trường đã được phép thao tác. Cookie và thông tin đăng nhập
chỉ giữ cục bộ, không đưa vào Git hoặc log. Ví dụ dùng tài khoản tổng hợp.

```bash
BASE=http://localhost:8080
COOKIE_JAR=$(mktemp)
CSRF=$(curl -fsS -c "$COOKIE_JAR" "$BASE/api/auth/csrf" | jq -r .token)
curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" \
  --data-urlencode 'username=synthetic-admin' \
  --data-urlencode 'password=synthetic-test-only' "$BASE/api/auth/login"
CSRF=$(curl -fsS -b "$COOKIE_JAR" -c "$COOKIE_JAR" "$BASE/api/auth/csrf" | jq -r .token)
curl -fsS -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" \
  -H 'Content-Type: application/json' -d '{"scope":"ALL"}' \
  "$BASE/api/admin/mapping/remaps"
```

Các body thay thế:

```json
{"scope":"BATCH","batchId":123}
{"scope":"DATE_RANGE","from":"2026-11-01","to":"2026-11-25"}
{"scope":"ISSUE","issueCode":"INVALID_PHONE"}
```

Khoảng ngày tính trên `INGESTION.DOCUMENTS.CREATED_AT` (timestamptz), gồm cả hai ngày
theo `Asia/Ho_Chi_Minh`: từ 00:00 ngày `from` đến trước 00:00 ngày sau `to`.
ISSUE xét result có ID mới nhất của từng extraction; tìm mã trong mảng `issues`
ở mọi cấp bằng JSON path. Mã hợp lệ gồm mã engine hiện có hoặc mã worker đã lưu
trong result. Mã lạ, thiếu tham số và khoảng ngày ngược trả 400 `ApiError`.
Batch không có extraction phù hợp tạo lượt chạy rỗng.

Tập extraction giống scan `OcrExtractionReader`: mọi extraction đã lưu (kể cả
legacy/lỗi OCR; engine phân loại đầu vào), thêm điều kiện phạm vi. Run chốt config
và cận ID extraction khi tạo. Extraction có ID lớn hơn cận này chờ lượt khác;
bộ lọc ISSUE được đánh giá khi quét từng lô.

## Theo dõi, dừng và tiếp tục

```bash
RUN_ID=123
curl -fsS -b "$COOKIE_JAR" "$BASE/api/admin/mapping/remaps/$RUN_ID"
curl -fsS -b "$COOKIE_JAR" "$BASE/api/admin/mapping/remaps"
curl -fsS -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST \
  "$BASE/api/admin/mapping/remaps/$RUN_ID/pause"
curl -fsS -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST \
  "$BASE/api/admin/mapping/remaps/$RUN_ID/resume"
```

`selected` là số extraction đã xét, bằng `queued + skipped`; `skipped` là job đã
tồn tại với cùng extraction/config/engine. `completed`, `failed`, `pending` đếm
job thuộc run; `pending` gồm PENDING và PROCESSING. DONE nghĩa là đã xếp hết phạm
vi, job có thể còn pending. Pause chỉ ngừng xếp thêm; job đã xếp vẫn tiếp tục.
Pause/resume tuần tự với từng lô; DONE giữ nguyên. Cursor, bộ đếm và enqueue commit
cùng giao dịch; lỗi một lô cho phép thử lại mà không mất cursor.

Properties `cv.mapping.remap.*` (có validation, mặc định):

| Property | Mặc định | Ý nghĩa |
| --- | --- | --- |
| enabled | true | Cho phép scheduler xếp lô |
| delay | 30s | Khoảng cách lượt scheduler, 1ms–1 ngày |
| batch-size | 100 | Tối đa extraction mỗi lô, 1–10000 |
| max-pending | 500 | Trần PENDING+PROCESSING mỗi run, 1–100000 |
| priority | 10 | Độ ưu tiên thấp hơn CV mới, 1–32767 |
| list-limit | 20 | Số run gần nhất trả về, 1–1000 |

Scheduler chỉ chạy khi `cv.mapping.enabled=true` và remap enabled. Lô bị giới hạn
thêm bởi chỗ trống dưới max-pending. API vẫn dùng được khi scheduler tắt.

Chạy lại cùng phạm vi và cùng config không tạo job trùng nhờ unique
`EXTRACTION_ID + CONFIG_ID + ENGINE_VERSION`; chỉ tăng skipped. Thay engine nhưng
không đổi cấu hình cần **revision cấu hình mới** rồi activate qua cơ chế hiện có;
remap không sửa unique hay reset cursor scan. Đổi config sau khi tạo run không
đổi config của run đó.

Kết quả cùng tài liệu cập nhật basic UNVERIFIED/MAPPING và thay bộ kinh nghiệm
chưa được HR sửa/xác nhận. Nếu có kinh nghiệm được HR bảo vệ trong bộ đó, toàn
bộ kinh nghiệm mới thành đề xuất. Basic đã sửa/xác nhận thành đề xuất độc lập.
CV thứ hai vẫn thành đề xuất. Revision/history, chuẩn hóa và sự kiện index chạy
như luồng publish thông thường.

## Requeue alias và dựng lại index

```bash
curl -fsS -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST \
  "$BASE/api/admin/catalog/aliases/requeue?kind=COMPANY&errorCode=LEASE_EXPIRED"
curl -fsS -b "$COOKIE_JAR" -H "X-XSRF-TOKEN: $CSRF" -X POST \
  "$BASE/api/admin/search/rebuild"
```

Requeue có thể bỏ cả hai filter; kind chỉ nhận COMPANY/TITLE. Chỉ alias FAILED
và không thuộc USER được đưa về PENDING, reset attempts/lỗi/lease/thời điểm kết
thúc và cho thử ngay. Trả `{"requeued":n}`; gọi lại khi không có lỗi mới trả 0.
Worker chuẩn hóa phải được bật để xử lý tiếp.

Lỗi index được rollback bằng savepoint JDBC trên JpaTransactionManager, log WARN
chỉ candidateId và lớp lỗi; publish/HR edit vẫn commit. Index có thể thiếu hoặc
cũ đến lần rebuild thành công. Job đêm dựng lại toàn bộ ứng viên, kể cả ứng viên
không có kinh nghiệm ongoing. Endpoint rebuild trả `rebuilt`/`failed`; nếu failed
còn tăng, kiểm tra log an toàn và nguyên nhân trước khi chạy lại. Không sửa bảng
nguồn hoặc xóa PDF/raw extraction để khôi phục index.
