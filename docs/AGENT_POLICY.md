# Policy chi tiết cho agent

**Tài liệu tham chiếu, không đọc mặc định.** [Root](../AGENTS.md) là nguồn quy tắc chung;
file này chỉ bổ sung quyền và kiến trúc/dữ liệu khi task chạm tới.

## Level A — tham chiếu an toàn

Thao tác chỉ con người làm, bảo mật và CI/security hook: [quyền tại root](../AGENTS.md#quyền-và-an-toàn).
Ví dụ giả có thể track, không cấp quyền đọc cấu hình riêng. Báo secret lộ để con người xoay vòng,
không sao chép giá trị. Secret/dependency scanning là mục tiêu, không mặc nhiên có coverage.

## Level B — cần phạm vi được duyệt rõ

Kiểm tra spec trước: schema/migration/index/kiểu; dependency/runtime;
formatter/linter/build/Docker/application config; contract/status/error; security/auth/token/role/CORS/CSRF;
xử lý CV thật/logic dữ liệu cá nhân; transaction/async/scheduled job/cache/queue; shared module/ranh giới module,
kiến trúc/cấu trúc repo; refactor > khoảng 10 file code; xóa file hoặc di chuyển/đổi tên hàng loạt
file/class/endpoint/field/env key. Kiến trúc mới cần ADR ngắn sau phê duyệt.
Xử lý phạm vi chưa rõ/ngoài spec theo root.

## Level C — phạm vi đã duyệt

Code/test/docs, công cụ hiện có và branch/commit/push/PR theo quyền tại root;
tổ chức rules/docs không cấp quyền đổi ứng dụng.

## Kiến trúc và dữ liệu

- Modular monolith + Python worker riêng, giữ public interface Modulith, không tạo vòng phụ thuộc.
  Spring Boot sở hữu ghi DB, vòng đời job/retry/quyền worker độc quyền, validation,
  chuẩn hóa/liên kết ứng viên. Host supervisor chỉ internal HTTP đã thống nhất;
  PDF child offline, không DB/HTTP/credential/Docker socket.
- Giữ PDF gốc, raw extraction/OCR và kết quả cấu trúc; không ghi đè nguồn, bịa field,
  tự gộp danh tính hoặc unique tên/email/điện thoại. Liên kết ứng viên nullable;
  chưa biết để null/bỏ qua. Không coi “Present” cũ vẫn hiện tại hoặc cộng trùng kinh nghiệm chồng lấn.
  Thất bại không bao giờ xóa bản gốc; upload thành công độc lập OCR. Kiểm tra kiểu/kích thước/đường dẫn an toàn.
- Chỉ cơ chế log/allowlist hiện có; không log dữ liệu cá nhân/nội dung CV/body/token/đường dẫn tùy ý/exception tự do.
  Test dùng dữ liệu tổng hợp, schema/storage cô lập, không development/runtime/production.
  Technical PASS không mở gate production/accuracy OCR chưa giải quyết.
- PostgreSQL 17, chỉ Flyway; UPPERCASE_SNAKE_CASE có quote, BIGINT identity,
  TIMESTAMPTZ, PK/FK rõ, JSONB theo thiết kế. Không Hibernate auto-update, PDF binary trong DB,
  bảng search suy đoán. Giữ nguyên byte/đường dẫn runtime schema/limits và build provenance.
- LLM/parser/prompt, search/ranking/embedding/vector DB, matching/merging, taxonomy/normalization,
  tuyển dụng/đối tác/báo cáo/phân tán/hạ tầng mới hoặc tính năng tương lai cần duyệt riêng;
  không tự thêm Kafka/RabbitMQ/Redis/Kubernetes.
