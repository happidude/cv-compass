<a id="backend-agent-rules"></a>

# Quy tắc agent cho backend

Kế thừa [quy tắc root](../AGENTS.md). Trước khi sửa, đọc
[convention backend](../docs/conventions/backend.md), [README](README.md),
contract liên quan và spec đã duyệt.

- Java 25, Spring Boot, Gradle wrapper, PostgreSQL 17, JUnit; giữ các version hiện tại.
- Logic nghiệp vụ thuộc developer: chỉ triển khai phạm vi Java được duyệt rõ ràng.
- Tổ chức theo feature dưới `com.cvcompass.backend`; controller → service → repository.
  Controller mỏng, API DTO, constructor injection và transaction rõ ràng.
- Tôn trọng public interface của Modulith; không tạo vòng phụ thuộc hoặc đổi ranh giới module khi chưa duyệt.
- Spring sở hữu ghi database. Identifier viết hoa có quote, BIGINT identity,
  TIMESTAMPTZ, JSONB; chỉ dùng Flyway. Không sửa migration đã áp dụng; schema mới cần duyệt.
- Dùng `web/OperationalLog` và allowlist context an toàn. Không log nội dung CV,
  body, token, đường dẫn tùy ý hoặc exception message tự do.
- Thay đổi API/security/storage/dữ liệu cá nhân/transaction cần phạm vi được duyệt rõ ràng.
- Spotless/google-java-format AOSP là nguồn chuẩn; không đổi cấu hình hoặc file loại trừ.
  Từ backend: `./gradlew spotlessApply`, sau đó `./gradlew check --rerun-tasks`
  (Windows: `.\gradlew.bat`).
- JUnit `XTests`/`XIntegrationTests`, tên method camelCase; test nội bộ cùng package.
  Giữ `ModulithArchitectureTests` ở root.
- Sửa source/test backend phải chạy affected tests rồi full local check trên source cuối,
  dùng schema/storage PostgreSQL cô lập. Theo [kiểm chứng](../docs/VERIFICATION.md);
  đọc số liệu XML mới và warning. Không dùng dữ liệu development/runtime.
