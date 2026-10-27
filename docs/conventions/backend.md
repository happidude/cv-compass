# Convention Backend Java — quy ước chính thức

Trạng thái: **Đã duyệt ngày 26/10/2026**. Phạm vi: [README](README.md). Mọi rule giữ nguyên contract, SQL, thứ tự
side-effect, transaction và dependency version; ví dụ “Sai” dựng minh họa khi không
ghi là đoạn code hiện tại.

## Khảo sát và điểm chưa thống nhất

Bảng dưới ghi nhận trước khi áp dụng; trạng thái cuối tại [REFACTOR_REPORT.md](REFACTOR_REPORT.md).

Base package `com.cvcompass.backend`; module nghiệp vụ `ingestion` chia controller,
service, repository, entity, dto, config, enums, exception, upload, source. `storage`
là infrastructure với adapter `local`; `web` chứa response/error/logging. Không có
lý do để tái tổ chức thành top-level controller/service/repository toàn ứng dụng.

| ID | Ví dụ thực tế | Khác biệt | Chuẩn chính thức / xử lý |
| --- | --- | --- | --- |
| B01 | `backend/src/main/java/com/cvcompass/backend/ingestion/service/OcrWorkerService.java`, so với `.../CvDocumentService.java` | `this.jobs=jobs; this.state=state;` và nhiều câu lệnh một dòng; service cũ dùng spaces, dòng riêng | Giữ indent 4, spaces quanh operator, một statement/dòng; không đổi thứ tự |
| B02 | `.../ingestion/controller/OcrWorkerController.java`, so với `CvDocumentController.java` | Wildcard imports so với explicit; imports nội bộ chưa cùng thứ tự | Explicit imports cho production; 29 file main có wildcard, 86 có explicit (hai tập giao nhau) |
| B03 | `.../ingestion/service/DocumentQueryService.java` | `try { ... } catch ...` nén dòng, `size()+1`, `List.of("success","partial","failed")` | Format lại, không sửa biểu thức/guard |
| B04 | `backend/src/test/java/com/cvcompass/backend/ingestion/service/CvDocumentServiceTests.java` | `@Test void ...` xen annotation dòng riêng; nhiều verify một dòng | Annotation dòng riêng, assertion giữ thứ tự; test camelCase vẫn giữ |
| B05 | `.../ingestion/enums/OcrProtocol.java` | Constants class nằm cùng enum thực | Bất nhất phân loại, chưa có pattern constants-package chủ đạo; giữ đường dẫn |
| B06 | `CvDocumentController.java` và `OcrWorkerController.java` | Success DTO trực tiếp so với `ApiResponse<T>` | Khác biệt contract có chủ đích; giữ từng endpoint, không đồng nhất wrapper |
| B07 | `web/error/GlobalExceptionHandler.java`, `ingestion/exception/IngestionExceptionHandler.java`, local handler trong OCR controller | Nhiều tầng handler với scope/precedence riêng | Giữ precedence và local handler; không gom vào một advice vì style |

Các path viết `.../` trong bảng đều có tiền tố
`backend/src/main/java/com/cvcompass/backend/`.

## Cấu trúc, naming và mapping

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ feature trước, layer bên trong; controller → service → repository cho nghiệp vụ | `ingestion/controller/CvDocumentController` gọi service | Controller thêm query DB trực tiếp (minh họa) | Pattern hiện có; storage/protocol helpers là dependency hợp lệ, không ép layer giả |
| Giữ ranh giới Modulith | `storage/FileStorageService` là contract adapter | Import implementation `storage/local` vào nghiệp vụ mới (minh họa) | Giảm coupling; giữ module metadata và chạy `ModulithArchitectureTests` khi đổi ranh giới |
| Class/file PascalCase; method/field lowerCamelCase; package lowercase | `CvDocumentService`, `originalFilename`, `findById` | `cv_document_service`, `OriginalFilename` (minh họa) | Pattern áp đảo hiện tại; acronym giữ `Cv`, `Ocr`, không mass rename thành `CV`, `OCR` |
| Suffix đúng vai trò, không bắt Entity/DTO có hậu tố máy móc | `CvDocument`, `DocumentMetadata`, `OcrClaimRequest`, `CvDocumentRepository` | Đổi mọi record thành `XDto` hoặc entity thành `XEntity` | Repo dùng entity domain name, DTO theo ý nghĩa; tránh reflection/bean/JSON rename |
| Entity chỉ persistence, DTO cho HTTP; mapping thủ công | `DocumentDetails.from(...)`, record DTO và `CvDocument` riêng | Trả thẳng `CvDocument` hoặc thêm MapStruct (minh họa) | Giữ schema response và dependency hiện tại |
| Không rename phần có tên serialize/query/binding chỉ vì naming | Giữ `sizeBytes`, `@Table(name = "\"CV_DOCUMENTS\"")`, `@Column` | Đổi field record rồi mặc định cho rằng Jackson không đổi output | Reflection, record components, JPQL, bean names, Spring parameter binding là runtime contract |

## Controller, validation và lỗi

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Controller giữ orchestration HTTP hiện có; không chuyển parsing/validation trong đợt form | Giữ `MultipartHttpServletRequest`, `@Positive`, signature/trailer validation | Thay bằng một DTO multipart “gọn hơn” | Có thể đổi validation timing, error/status, multipart behavior |
| REST resource path giữ plural hiện có | `/api/documents`, `/api/batches`, `/internal/ocr/v1` | Đổi `/api/documents` thành `/api/cvs` | Không có ủy quyền đổi API |
| Success wrapper theo endpoint; lỗi dùng cơ chế ApiError đang có | `ResponseEntity<DocumentDetails>` và OCR `ApiResponse<Map<...>>` | Wrap tất cả response, đổi 204 thành 200 | Client/tests phụ thuộc shape/status/header |
| Giữ error code/message/status và scope exception handler | `IngestionExceptionHandler` ưu tiên `HIGHEST_PRECEDENCE` | Đổi `NOT_FOUND` thành `DOCUMENT_NOT_FOUND` để hợp frontend label | Đây là contract, không phải sửa convention; không suy luận bug từ code khác tên |
| Chỉ chuẩn hóa format error handling | Xuống dòng từng nhánh switch nhưng giữ case/thứ tự | Đổi catch type, thêm catch hoặc `throw new` để đồng bộ | Thay exception và precedence có thể đổi hành vi |

## Logging, transaction, DI và config

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Dùng safe operational logging; giữ level, event và key hiện tại | `web/OperationalLog.java`: enum event, context allowlist, correlationId | `log.info("CV {}", rawText)` hoặc log token/exception message tự do | Bảo vệ dữ liệu cá nhân; đổi log event cũng bị cấm trong đợt này |
| Level giữ theo call site; policy cho code mới dùng INFO lifecycle, WARN recoverable, ERROR failure nghiêm trọng | Giữ `Level.WARN` của OCR transport failure | Đổi WARN → DEBUG để giảm noise | Policy tương lai không cho phép thay observability hiện tại; không đổi dev/prod JSON logging format |
| Constructor injection, dependency bắt buộc final | Constructor của `DocumentQueryService` | Field `@Autowired` mới (minh họa) | Pattern repo và Spring khuyến nghị constructor injection |
| Lombok giữ theo vai trò hiện có | `@Getter/@Setter/@NoArgsConstructor` trên `CvDocument`, properties | Thêm `@Data` entity hoặc đổi constructor service thành Lombok hàng loạt | Có thể sinh equals/hashCode/toString hoặc đổi constructor metadata |
| Transaction boundary bất biến | `@Transactional(readOnly = true)` trên query service; giữ programmatic transaction ở upload | Di chuyển transactional method vào private helper hay đổi propagation | Proxy, self-invocation, commit/rollback và compensation có thể đổi |
| Config qua properties đang có, giữ prefix/default/env | `UploadProperties`: `@ConfigurationProperties("cv.upload")`, validation | Đổi env key/prefix hoặc đưa default vào constant khác với giá trị mới | Binding và operational configuration là contract |
| Constants UPPER_SNAKE_CASE, enum PascalCase với values UPPER_SNAKE_CASE | `OcrProtocol.MAX_PAGES`, `DocumentStatus` | Đổi enum literal đã persist/serialize hoặc tạo constants framework | Giữ wire/DB value; không di chuyển OcrProtocol trong đợt này |

## Format và import

| Rule | Đúng | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| AOSP: indent 4 spaces, continuation 8 spaces; mở brace cùng dòng, statement mỗi dòng | `this.jobs = jobs;` rồi dòng `this.state = state;` | `this.jobs=jobs; this.state=state;` (B01) | Giữ style phần lớn code, làm diff dễ đọc |
| Block nhiều dòng cho control flow khi formatter hỗ trợ an toàn | `if (...) {` / body / `}` | Nén `try/catch` một dòng (B03) | Dễ thấy guard; formatter chỉ whitespace, thêm brace là đợt thủ công riêng có review scope |
| Import theo output google-java-format: static trước non-static; không chia nhóm non-JDK/JDK thủ công | Static imports trước, non-static sau theo formatter | Wildcard production và thứ tự ngẫu nhiên (B02) | Giữ explicit imports production; không đổi resolution mơ hồ hoặc wildcard static test |
| Line width 100 cố định theo google-java-format (AOSP) | Wrap constructor/record dài, không sửa nội dung string/text block | Cắt string SQL hoặc chỉnh whitespace bên trong text block | Formatter là nguồn chuẩn cho whitespace và wrap, không đặt width riêng |

Java giữ **Spotless + google-java-format, style AOSP**. Trial đã chạy với
Spotless 8.10.4 và google-java-format 1.36.1 trên JDK 25.0.4.1/Gradle 9.7.1.
Spotless tích hợp formatter vào Gradle và kiểm tra format; google-java-format là
formatter chuẩn. Formatter là nguồn chuẩn cho whitespace, wrap và thứ tự import.
Quyết định mới ngày 26/10/2026: cho phép riêng Java xóa unused imports và sắp xếp
imports trong bước formatter, với compile thành công và output `javap -c -p -constants`
giống hệt trước/sau cho toàn bộ main/test classes. Không dùng `-l`.
Python và Frontend vẫn cấm xóa unused imports; ngoại lệ này không áp dụng cho hai phần đó.
Trial 1.37.0 gặp API mismatch (`NoSuchMethodError`); 1.36.1 là default được changelog
Spotless công bố và chạy được. Không đổi công cụ.
Đã tích hợp vào `backend/build.gradle`: từ backend/ chạy `./gradlew.bat spotlessApply`
hoặc `./gradlew.bat spotlessCheck` (WSL dùng `./gradlew`). `check` bao gồm spotlessCheck.
Giữ EOL bằng `LineEnding.PRESERVE`, tắt Javadoc reflow. Sáu test files được targetExclude
vì javap khác sau sorting static imports; toàn bộ source còn giữ đạt javap giống hệt.
Compile/format gate được kiểm chứng riêng; full DB test gate vẫn NOT VERIFIED khi
thiếu PostgreSQL test credentials. Chi tiết exclusions/evidence tại [REFACTOR_REPORT.md](REFACTOR_REPORT.md).
Không cài thêm Checkstyle. Cho phép import cleanup do formatter Java thực hiện;
`reflowLongStrings(false)`, không đổi giá trị/bytes string hoặc text block.
Exclude migration, contracts, generated; mở wildcard production bằng đợt riêng có compile/javap.

## Test

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| `XTests`, integration `XIntegrationTests`; method camelCase mô tả behavior | `configuredStandaloneByteLimitAlsoAppliesDuringStreaming` | Rename toàn bộ test sang snake_case hoặc `test1` | Giữ pattern JUnit hiện tại, không đổi test discovery |
| Arrange → act → assert, fixture synthetic, assertion kiểm tra side-effect | `CvDocumentServiceTests` verify rollback/compensation | Xóa assertion, thêm skip để format gate pass | Test là bằng chứng giữ hành vi |
| Test cùng package khi kiểm tra nội bộ; test architecture/integration root được giữ | `ingestion/service/*Tests`, root `ModulithArchitectureTests` | Chuyển tất cả root tests để “mirror production” | Package ảnh hưởng access và Spring test setup |
| Local gate trên final source và đọc fresh reports | `./gradlew.bat check --rerun-tasks` theo workflow | Coi compile hoặc CI trước đó là full PASS | Cần Java 25, PostgreSQL 17 cô lập, đúng JUnit counts |

## Những việc cố ý chưa làm

Không kết luận có bug runtime từ khảo sát tĩnh. Giữ B05–B07; chưa có bằng chứng
an toàn để đổi classification/handler/wrapper. Không tách `OcrJobService`, controller
hoặc storage lifecycle chỉ theo độ dài. Các method dùng transaction, lock, cleanup,
callback và commit compensation phải được đánh giá riêng nếu sau này muốn refactor.

Căn cứ chính thức: [Spring constructor injection](https://docs.spring.io/spring-framework/reference/core/beans/dependencies/factory-collaborators.html),
[Spotless Gradle](https://github.com/diffplug/spotless/tree/main/plugin-gradle).
Các lựa chọn format còn lại là convention dự án từ code hiện có, không phải yêu cầu Spring.
