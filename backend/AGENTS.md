# Quy tắc backend

Kế thừa [root](../AGENTS.md). Sửa Java: đọc [convention](../docs/conventions/backend.md);
chạy backend: [README](README.md); đụng API/storage: [contract](../docs/contracts/README.md)
và policy theo trigger root. Lệnh/gate tại [kiểm chứng](../docs/VERIFICATION.md).

- Java 25/Spring Boot/Gradle wrapper/PostgreSQL 17/JUnit; giữ version hiện tại.
- Feature dưới `com.cvcompass.backend`, controller → service → repository;
  controller mỏng, API DTO, constructor injection, transaction rõ. Ranh giới/DB theo policy.
- Log dùng `web/OperationalLog` và allowlist context an toàn theo policy.
- Spotless/google-java-format AOSP là chuẩn; không đổi cấu hình/file loại trừ.
- JUnit `XTests`/`XIntegrationTests`, method camelCase, test nội bộ cùng package;
  giữ `ModulithArchitectureTests` ở root.
