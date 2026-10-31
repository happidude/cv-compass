<a id="detailed-agent-policy"></a>

# Policy chi tiết cho agent

Nguồn có hiệu lực: [quy tắc root](../AGENTS.md). File này giữ các kiểm soát toàn repository
từ bản quy tắc root dài trước đây; convention component giữ chi tiết implementation.
Phê duyệt rõ ràng của developer cho spec hiện tại chỉ cho phép phạm vi được liệt kê.

<a id="level-a--human-only-operations-and-safety-invariants"></a>

## Level A — thao tác chỉ dành cho con người và nguyên tắc an toàn

Secrets, môi trường riêng/cấu hình production và CV thật là dữ liệu bí mật.
Không in, log hoặc commit giá trị secret hay CV/dữ liệu cá nhân thật. Có thể track
ví dụ giả nhưng chúng không cấp quyền đọc cấu hình riêng. Phát triển thông thường
không truy cập môi trường riêng, cấu hình production hoặc CV thật.
Không tiết lộ hoặc xem giá trị secret. Tạo/sửa repository secret chỉ dành cho con người,
cũng như merge, GitHub settings/branch protection/rulesets, releases/tags và thao tác
database/server/deployment production. Agent không bao giờ thực hiện các thao tác này,
bất kể task có cho phép hay không.
Không gửi nội dung CV tới AI bên ngoài hoặc dịch vụ bên thứ ba. Không gọi dịch vụ ứng dụng
bằng credential thật trong phát triển thông thường.

Không làm yếu assertion, bỏ check/test, giảm coverage, xóa assertion hoặc thêm
blanket suppression để có kết quả xanh. Không bypass hook, sửa Flyway migration
đã áp dụng, force push, push main, viết lại lịch sử, xóa remote branch hoặc dọn dẹp
filesystem/SQL/Git có tính phá hủy. Không cài global tool, đổi cấu hình máy hoặc sửa
ngoài repository. Sửa file quy tắc phải có sự cho phép của task.
Không tự xóa local branch/worktree.

<a id="ci-and-security-hook-scope"></a>

## Phạm vi CI và security hook

Chỉ được đổi CI workflow và security hook khi spec đã duyệt liệt kê chính xác
đường dẫn và thay đổi dự định. Giữ các check bắt buộc; không làm yếu, bỏ hoặc
suppress check để che lỗi. Nếu chưa có phạm vi đó, ghi đề xuất dưới Open Questions
của PR và tiếp tục phần không phụ thuộc.
Thao tác Level A chỉ dành cho con người vẫn bị cấm với agent trong mọi task.
Lượt sửa workflow agent này chỉ đổi quy tắc, không thực hiện thao tác nào trong
số đó hoặc sửa CI/security hook.

<a id="level-b--explicit-scope-approval-required"></a>

## Level B — cần phê duyệt phạm vi rõ ràng

Trước khi thêm schema/migration/index/đổi kiểu; đổi dependency/runtime;
cấu hình formatter/linter/build/Docker/application; contract/status/error;
security/auth/token/role/CORS/CSRF; logic dữ liệu cá nhân; ranh giới transaction,
async/scheduled job/cache/queue; shared module, ranh giới module, kiến trúc/
cấu trúc repository, refactor hơn khoảng mười file code; xóa hoặc di chuyển/
đổi tên hàng loạt file/class/endpoint/field/env key: kiểm tra spec đã duyệt
có bao gồm hành động đó hay chưa.

Nếu đã bao gồm, thực hiện mà không hỏi lại. Nếu ngoài phạm vi hoặc chưa rõ,
ghi bối cảnh/lựa chọn/khuyến nghị dưới Open Questions của PR và không làm phần
phụ thuộc. Tiếp tục phần độc lập. Kiến trúc mới cần ADR ngắn sau phê duyệt.
Quyền riêng cho task có thể bao gồm tổ chức lại rules/docs như nhiệm vụ workflow
agent này; quyền đó không cho phép đổi ứng dụng.

<a id="level-c--within-approved-scope"></a>

## Level C — trong phạm vi đã duyệt

Triển khai code/test trong phạm vi, chạy format/lint/test hiện có, cập nhật tài liệu
liên quan, tạo branch riêng và commit/push/mở PR khi được phép.
Trách nhiệm test và handoff sửa lỗi chỉ được quy định tại
[Thực hiện và bàn giao tại root](../AGENTS.md#thực-hiện-và-bàn-giao).
Không coi kế hoạch milestone hoặc implementation có sẵn là sự cho phép.

<a id="architecture-data-and-privacy"></a>

## Kiến trúc, dữ liệu và quyền riêng tư

Modular monolith + Python worker riêng; giữ ranh giới module. Spring Boot sở hữu
ghi DB, vòng đời job, kiểm tra, chuẩn hóa và liên kết ứng viên.
Host worker chỉ dùng internal HTTP; PDF child không truy cập DB/HTTP.

Giữ PDF gốc, raw OCR và kết quả cấu trúc. Không ghi đè nguồn, bịa field,
giả định công việc ghi “Present” trong CV cũ vẫn còn hiện tại hoặc cộng trùng
kinh nghiệm chồng lấn. Giá trị chưa biết để null/bỏ qua. Liên kết ứng viên có thể
nullable; không unique tên/email/điện thoại hoặc tự gộp danh tính. Xử lý thất bại
không bao giờ xóa bản gốc. Upload thành công độc lập với OCR thành công.
Spring kiểm soát retry và quyền sở hữu độc quyền của worker. Kiểm tra kiểu/kích thước
file và đường dẫn an toàn.

Chỉ dùng cơ chế log an toàn và allowlist hiện có. Không log toàn bộ CV,
request body, token, đường dẫn tùy ý hoặc exception tự do. Xử lý CV thật và
logic quyền riêng tư cần phạm vi được duyệt rõ ràng. Test local dùng dữ liệu
tổng hợp và storage/schema cô lập. Giữ các gate production/accuracy OCR chưa
giải quyết; chỉ technical PASS không mở các gate đó.

Database: PostgreSQL 17, Flyway, identifier UPPERCASE_SNAKE_CASE có quote,
BIGINT identity, TIMESTAMPTZ, PK/FK rõ ràng, JSONB theo thiết kế.
Không Hibernate auto schema update, lưu PDF binary trong DB hoặc tạo bảng search suy đoán.

Search/ranking/embeddings/vector DB, matching/merging, taxonomy/normalization,
workflow tuyển dụng/đối tác, báo cáo và xử lý phân tán cần duyệt riêng.
Không tự đưa Kafka, RabbitMQ, Redis hoặc Kubernetes vào.

Secret/dependency scanning trong CI là mục tiêu, không phải khẳng định đã có coverage.
Sửa CI/security hook cần phạm vi đã duyệt rõ ràng như trên;
nếu chưa có, ghi đề xuất trong docs/PR và không thực hiện.
Báo ngay secret bị lộ để con người xoay vòng, không sao chép lại giá trị.
