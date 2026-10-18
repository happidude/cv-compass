# Nền tảng lưu trữ dữ liệu trong cơ sở dữ liệu

Module `com.cvcompass.backend.ingestion` tổ chức năm entity của POC trong
`ingestion.entity`, các Spring Data repository cơ bản trong `ingestion.repository`,
và các enum trạng thái/phương thức trong `ingestion.enums`. Đây vẫn là một module
ingestion; giai đoạn nền tảng này không bổ sung service, controller, truy vấn tùy
chỉnh hoặc named interface.

Các ánh xạ dùng tên bảng/cột viết hoa và đặt trong dấu ngoặc kép, ID identity kiểu
`Long`, thời gian kiểu `Instant`, và `@JdbcTypeCode(SqlTypes.JSON)` của Hibernate
cho JSONB. `BaseEntity` là một `@MappedSuperclass` trừu tượng, cung cấp `ID` và
`CREATED_AT` cho cả năm entity. `UpdatableEntity` bổ sung `UPDATED_AT` và chỉ được
`Candidate` sử dụng vì bảng tương ứng có cột này. Các entity sau này chọn lớp cơ
sở phù hợp với schema thực tế; các mapped superclass không tạo thêm bảng hoặc module.
ID và thời điểm tạo chỉ được đọc thông qua API của entity.

PostgreSQL sinh thời điểm tạo. Ứng dụng phải đặt `UPDATED_AT` của Candidate khi
cập nhật dữ liệu. Các quan hệ là một chiều, tải lazy, không cascade thao tác lưu
hoặc xóa. Extraction tham chiếu job bằng khóa chính; khóa ngoại tổng hợp trong V2
đồng thời bảo đảm hai tham chiếu cùng trỏ đến một document.

## Cấu hình

Cấu hình datasource, Flyway và profile của ứng dụng được giữ nguyên. Cả hai profile
đều dùng `ddl-auto: validate`; Spring Boot cung cấp quản lý transaction thông qua
cơ chế tự cấu hình JPA. Flyway quản lý các thay đổi schema.

Các test Gradle dùng UTC để tránh bí danh múi giờ trên máy chạy bị PostgreSQL JDBC
từ chối. Không cần thay đổi dependency của ứng dụng hoặc hạ tầng.

## Kiểm thử tích hợp

Sử dụng dịch vụ PostgreSQL 17 hiện có. Tài khoản chạy test phải có quyền tạo và xóa
schema. Đặt `TEST_DB_PASSWORD` trong shell; `TEST_DB_URL` và `TEST_DB_USERNAME` là
tùy chọn, mặc định dùng kết nối phát triển local hiện có. Không đưa thông tin xác
thực vào hệ thống quản lý mã nguồn.

Cài JDK 25 và đặt `JAVA_HOME` trỏ đến thư mục cài đặt bằng công cụ quản lý JDK hoặc
cơ chế của hệ điều hành. Java toolchain của Gradle nhắm đến Java 25.
Trong thư mục `backend/`, kiểm tra Java và chạy:

```powershell
java -version
.\gradlew.bat check
```

Trên Linux/macOS, chạy `./gradlew check` với cùng các biến môi trường cho test.

`BackendApplicationTests` tập trung vào khởi tạo context, thứ tự migration và xác
thực schema của Hibernate. `RepositoryPersistenceIntegrationTests` kiểm tra việc
lưu/đọc qua repository, quan hệ nullable/lazy, JSONB và từ chối liên kết không khớp.
Mỗi lớp sở hữu một schema có tên ngẫu nhiên khác nhau và khai báo phương thức
cấu hình thuộc tính động riêng, giúp Spring không dùng chung context được cache.
Test xác nhận quyền sở hữu schema bằng `current_schema()` trước khi kiểm tra
persistence. Dữ liệu test được rollback; mỗi lớp chỉ xóa schema của chính mình
sau khi chạy. Nếu thiếu thông tin xác thực, test báo lỗi rõ ràng thay vì sử dụng
schema thông thường của ứng dụng.

`ModulithArchitectureTests` gọi `ApplicationModules.verify()` và xác nhận ingestion
vẫn là module nghiệp vụ duy nhất; test này không cần cơ sở dữ liệu. Các package
con mới chỉ tổ chức mã nội bộ, không thay đổi ranh giới module.
Không sử dụng CV thật làm dữ liệu kiểm thử.

Nếu Java trên Windows báo `Unable to establish loopback connection` với nguyên
nhân `UnixDomainSockets.connect`, hãy dùng một thư mục socket có đường dẫn ngắn,
chỉ chứa ký tự ASCII, cho phiên shell đang chạy trong `backend/`:

```powershell
New-Item -ItemType Directory -Force build/tmp/jvm | Out-Null
$socketDirectory = (Resolve-Path build/tmp/jvm).Path
$env:JAVA_TOOL_OPTIONS = "$env:JAVA_TOOL_OPTIONS -Djdk.net.unixdomain.tmpdir=`"$socketDirectory`" -Duser.timezone=UTC"
.\gradlew.bat check
```

Cách xử lý này chỉ thay đổi biến môi trường của tiến trình và dữ liệu build đã
được Git ignore; không cần thay đổi cấu hình bảo mật hoặc hệ thống. Nếu đường dẫn
workspace dài hoặc chứa ký tự ngoài ASCII, hãy chọn một thư mục local ngắn khác.

## CI và các ràng buộc

GitHub Actions workflow `Persistence CI` chạy Java 25 và `./gradlew check`, sử dụng
dịch vụ PostgreSQL 17 tạm thời và cơ sở dữ liệu riêng `cv_compass_ci`. Thông tin
xác thực trong workflow chỉ là dữ liệu kiểm thử CI. Mỗi lớp test tích hợp vẫn sở
hữu một schema riêng trong cơ sở dữ liệu đó. Workflow chạy khi có pull request
vào main, push lên main hoặc được kích hoạt thủ công; không cần secret production.

Các test ràng buộc kiểm tra tổng bộ đếm, số lần retry âm, SHA256 sai định dạng,
trạng thái không hợp lệ (dùng JDBC để bỏ qua kiểm tra enum của Java), mọi khóa
ngoại và tính nhất quán giữa document của extraction và job. Mỗi câu lệnh bị từ
chối chạy trong một transaction test riêng được rollback; không có truy vấn tiếp
theo nào dùng lại transaction đã bị hủy do lỗi.

## Lưu ý về persistence trong Milestone 2

Ứng dụng vẫn quản lý `UPDATED_AT`. Mã ứng dụng phải đặt rõ giá trị này khi cập nhật
candidate; các service trong tương lai nên dùng Clock theo UTC. Không bổ sung
entity callback hoặc cơ chế auditing tự động. Test tích hợp xác nhận thao tác
cập nhật giữ nguyên ID và thời điểm tạo do cơ sở dữ liệu sinh.

Khi nhiều tác vụ hoàn tất batch đồng thời, việc đọc bộ đếm rồi lưu trong các
transaction riêng có thể làm mất lượt tăng: ràng buộc CHECK bảo vệ phạm vi giá trị,
không ngăn mất cập nhật hoặc thông báo hoàn tất trùng lặp. Trước khi triển khai
xử lý job đồng thời, cần chọn cập nhật bộ đếm nguyên tử trong transaction hoặc
cơ chế khóa optimistic/pessimistic đã được phê duyệt; bảo đảm việc hoàn tất job
có tính idempotent và cập nhật bộ đếm cùng trạng thái suy ra trong một transaction.
PR nền tảng này chưa triển khai xử lý batch hoặc cơ chế khóa.

# Milestone 2: Nền tảng ứng dụng backend

## Package và ranh giới module

```text
com.cvcompass.backend
|-- BackendApplication
|-- ingestion                 (cohesive POC business/persistence module)
|   |-- entity                (JPA entities and mapped superclasses)
|   |-- enums
|   `-- repository
`-- web                       (HTTP infrastructure module)
    |-- ApiResponse           (optional success envelope)
    |-- ApiError               (standard error contract)
    |-- FieldViolation
    |-- error                 (internal MVC advice)
    `-- logging               (internal correlation filter)
```

Spring Modulith nhận diện `ingestion` và `web` là các module đóng. `web` không cho
phép phụ thuộc vào module ứng dụng khác. Các record tại package gốc là API công
khai; phần triển khai xử lý exception và filter vẫn là nội bộ. Ingestion giữ sự
thống nhất vì các ánh xạ document, candidate, job và extraction có quan hệ JPA
trực tiếp. Không truy cập entity xuyên module, không tạo module candidate hoặc
worker mới. Controller sau này có thể dùng các hợp đồng của web; việc tách module
nghiệp vụ mới cần một quyết định kiến trúc riêng. Test kiến trúc kiểm tra cả việc
nhận diện module và `ApplicationModules.verify()`.

## Hợp đồng HTTP

Giai đoạn này không bổ sung endpoint demo hoặc nghiệp vụ cho production. Controller
có thể trả DTO, `ResponseEntity` hoặc `ApiResponse<T>` tùy chọn (`{"data": ...}`)
theo nhu cầu. Không có cơ chế tự động bọc response; response 204 giữ body rỗng.

Lỗi MVC có cấu trúc sau:

```json
{
  "code": "VALIDATION_ERROR",
  "message": "Invalid request",
  "correlationId": "example-request-123",
  "details": [
    {"field": "label", "message": "must not be blank"}
  ]
}
```

`details` rỗng khi không có lỗi validation ở cấp trường. Correlation ID chỉ là
null nếu việc xử lý diễn ra ngoài correlation filter. Validation dùng request DTO
và Jakarta Bean Validation; controller chỉ dành cho test minh họa `@Valid`,
`@NotBlank`, `@NotNull`, `@Size`, giá trị lồng nhau và validation tham số phương thức.
Giá trị bị từ chối không xuất hiện trong metadata lỗi. Thông báo validation phải
ở dạng tổng quát và không nội suy dữ liệu nhạy cảm từ request.

Advice kế thừa `ResponseEntityExceptionHandler` của Spring MVC, giữ nguyên mã
trạng thái và header của framework (bao gồm `Allow` cho lỗi 405). Các trường hợp:

| HTTP | Mã lỗi | Ý nghĩa |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | Lỗi Bean Validation, kèm thông tin trường |
| 400 | INVALID_REQUEST_BODY | Body JSON bị thiếu, sai định dạng hoặc không đọc được |
| 400 | INVALID_REQUEST | Tham số request không hợp lệ/bị thiếu hoặc request không hợp lệ khác |
| 404 | NOT_FOUND | Không tìm thấy handler/tài nguyên |
| 405 | METHOD_NOT_ALLOWED | Phương thức không được hỗ trợ |
| 406 | NOT_ACCEPTABLE | Định dạng response không được hỗ trợ |
| 413 | PAYLOAD_TOO_LARGE | Request vượt quá dung lượng cho phép |
| 415 | UNSUPPORTED_MEDIA_TYPE | Kiểu nội dung đầu vào không được hỗ trợ |
| 4xx khác | REQUEST_ERROR | Giữ mã trạng thái của framework, dùng lý do tổng quát |
| 5xx | INTERNAL_ERROR | Lỗi máy chủ tổng quát, không có chi tiết nội bộ |

Lỗi ngoài dự kiến được ghi log bằng tên lớp exception và vị trí stack, kèm
correlation ID trong MDC. Thông báo và nguyên nhân exception được loại bỏ vì có
thể chứa SQL, thông tin xác thực, nội dung request hoặc dữ liệu cá nhân. Lỗi API
không bao gồm stack trace, tên exception, SQL hoặc thông báo lỗi thô của framework.
Không bổ sung ghi log body request/response hoặc header xác thực.

## Correlation ID và ghi log

Servlet filter chấp nhận một header `X-Correlation-ID` khớp biểu thức
`[A-Za-z0-9][A-Za-z0-9._-]{0,63}` (1–64 ký tự ASCII). ID bị thiếu, trùng lặp,
rỗng, quá dài, chứa ký tự ngoài ASCII hoặc không hợp lệ sẽ được thay bằng UUID.
ID được lưu vào thuộc tính request và khóa MDC `correlationId`, trả trong header
response và trong response lỗi chuẩn hóa. Mẫu log level hiển thị ID này. Filter
xóa khóa MDC của mình trong `finally`, kể cả khi servlet phía sau phát sinh lỗi,
và giữ nguyên các mục MDC khác.

Đây là cơ chế liên kết request đồng bộ. Chưa triển khai truyền MDC vào executor,
tác vụ servlet bất đồng bộ hoặc worker trong tương lai. Correlation ID là định
danh chẩn đoán do client cung cấp, không phải danh tính đáng tin cậy hoặc bằng
chứng phân quyền. Advice xử lý lỗi dispatch của MVC; lỗi container/network ngoài
MVC và lỗi security filter sau này cần được tích hợp riêng khi triển khai.

## Kiểm chứng và môi trường

Tái sử dụng Java 25, Spring Boot 4.1.1, Modulith 2.1.1 và các dependency Gradle
hiện có. Cấu hình cơ sở dữ liệu, migration Flyway và hành vi persistence không
thay đổi. CI hiện có chạy toàn bộ check với PostgreSQL 17.

Trong `backend/`, chỉ chạy các test HTTP/filter/kiến trúc không cần cơ sở dữ liệu:

```powershell
.\gradlew.bat test --tests '*HttpFoundationTests' --tests '*CorrelationIdFilterTests' --tests '*ModulithArchitectureTests'
```

Các test HTTP dùng slice `@WebMvcTest` và controller chỉ dành cho test; không cần
datasource, repository, Flyway hoặc endpoint nghiệp vụ thật. Để chạy toàn bộ test,
đặt `TEST_DB_PASSWORD` (và tùy chọn `TEST_DB_URL`/`TEST_DB_USERNAME`) như hướng dẫn
ở trên, rồi chạy `./gradlew check` hoặc `.\gradlew.bat check`. Các test PostgreSQL
giữ cơ chế sở hữu schema riêng cho từng lớp và rollback/dọn dẹp dữ liệu.

Cấu hình môi trường vẫn dùng `SPRING_PROFILES_ACTIVE` (mặc định dev), `DB_URL`,
`DB_USERNAME` và `DB_PASSWORD` cho ứng dụng. Production phải nhận các giá trị
datasource này từ bên ngoài. Giữ thông tin xác thực trong cấu hình môi trường;
CI chỉ dùng thông tin xác thực của cơ sở dữ liệu kiểm thử tạm thời. Giai đoạn
này không bổ sung xác thực, lưu trữ file, xử lý nền, dịch vụ tracing hoặc API nghiệp vụ.

# Milestone 3: Nền tảng lưu trữ file

## Phạm vi và roadmap đã điều chỉnh

Quyết định phạm vi được developer phê duyệt (2026-10-17): M4 ban đầu bao gồm cả
Single CV Upload và Batch Management. Developer tách thành hai milestone M4 và M5
độc lập để triển khai, review và kiểm thử từng chức năng riêng biệt. Đây là thay
đổi đã được phê duyệt, không phải sai lệch phạm vi hoặc vi phạm workflow. Bảng
bên dưới thay thế cách đánh số cũ M5 OCR worker / M6 tích hợp Java–Python.
Quyết định này không miễn các yêu cầu workflow khác, không cho phép bắt đầu M6
hoặc merge PR #6.

| Milestone | Phạm vi |
| --- | --- |
| 1 | Lưu trữ dữ liệu trong cơ sở dữ liệu (đã hoàn thành) |
| 2 | Nền tảng ứng dụng backend (đã hoàn thành) |
| 3 | Hạ tầng lưu trữ file dùng chung (thay đổi của giai đoạn này) |
| 4 | Upload một CV — PR #5 |
| 5 | Quản lý batch — PR #6; triển khai, review và kiểm thử độc lập với M4 |
| 6 | Tích hợp OCR Worker — milestone tiếp theo; cần phê duyệt scope chi tiết trước khi triển khai |
| Tương lai | Đánh giá POC — cần phê duyệt riêng số thứ tự, thời điểm và phạm vi |

Khoảng 100 CV mẫu và mức ban đầu 10 file cho mỗi request upload là thiết lập
đánh giá/upload của POC, không phải giới hạn do storage áp đặt. Giai đoạn này
không bổ sung endpoint, điều phối upload, thay đổi persistence, validation định
dạng, cloud SDK hoặc service nghiệp vụ.

## Module và hợp đồng

Module storage là module đóng của Modulith, xuất các kiểu FileStorageService,
StorageKey, StoredFile và StorageException tại package gốc. storage.local và storage.config
là nội bộ; storage không khai báo phụ thuộc vào module ứng dụng nào.
Module này không phụ thuộc ingestion, web, JPA, multipart hoặc PDF. Service ứng
dụng sau này dùng constructor injection với FileStorageService, không dùng trực
tiếp adapter.

| Thao tác | Hợp đồng |
| --- | --- |
| store(InputStream) | Đọc theo luồng từ vị trí hiện tại đến EOF. Bên gọi sở hữu và đóng luồng đầu vào cả khi có lỗi. Sinh key mới; trả số byte thực tế và SHA-256 chữ thường sau khi công bố file thành công. |
| read(StorageKey) | Mở InputStream do bên gọi sở hữu; dùng try-with-resources. Đối tượng không tồn tại phát sinh NOT_FOUND. Lỗi mở luồng phát sinh StorageException; lỗi đọc/đóng luồng sau đó có thể phát sinh IOException. |
| delete(StorageKey) | Có tính idempotent khi đối tượng không tồn tại; lỗi khác phát sinh DELETE_FAILED. |
| exists(StorageKey) | Chỉ trả false khi đối tượng không tồn tại; lỗi phát sinh READ_FAILED. |

Key là định danh opaque, phân biệt chữ hoa/thường, gồm 32 ký tự hex viết thường.
Đây là hợp đồng storage, không phải tên file, đường dẫn tuyệt đối, tên provider
hoặc hash nội dung. Bên gọi lưu key.value() và khôi phục bằng new StorageKey(value).
Giá trị không hợp lệ phát sinh INVALID_KEY mà không trả lại key đã cung cấp.
Adapter không chấp nhận write key do bên gọi chọn, tên file gốc hoặc yêu cầu lưu
metadata. Hỗ trợ file rỗng và dữ liệu nhị phân bất kỳ. Không tạo mảng byte chứa
cả file: mã production sao chép bằng buffer cố định 64 KiB và tính SHA-256 tăng dần.

StorageException có enum nguyên nhân nhỏ (INVALID_KEY, NOT_FOUND, UNAVAILABLE,
WRITE_FAILED, READ_FAILED, DELETE_FAILED), thông báo tóm tắt an toàn và nguyên
nhân kỹ thuật nội bộ. Không đưa nguyên nhân ra response hoặc log cho client:
exception của hệ điều hành có thể chứa đường dẫn tuyệt đối. Giai đoạn này không
bổ sung ánh xạ lỗi riêng cho HTTP.

## Cấu hình và dependency injection

Mọi môi trường đều cần cấu hình tường minh:

~~~yaml
cv:
  storage:
    provider: ${CV_STORAGE_PROVIDER:}
    local:
      root-directory: ${CV_STORAGE_ROOT:}
~~~

Đặt CV_STORAGE_PROVIDER=local và CV_STORAGE_ROOT trỏ đến thư mục lưu trữ riêng tư,
có đường dẫn tuyệt đối đã chuẩn hóa, nằm ngoài mọi Git checkout. Không có đường
dẫn riêng cho từng máy hoặc cơ chế tự động dùng cấu hình dự phòng ở production.
Cấu hình thiếu/rỗng, đường dẫn tương đối, thư mục gốc của filesystem, thư mục cha
có liên kết, storage root nằm trong Git checkout hoặc provider không được hỗ trợ
đều khiến ứng dụng không khởi động và trả thông báo cấu hình có thể xử lý được.

StorageConfiguration dùng configuration properties đã được validation và
@ConditionalOnProperty để chọn một bean FileStorageService. Hiện chỉ triển khai
local. Cấu hình root riêng của provider nằm trong package của local adapter.
Không bổ sung factory, service locator, cấu hình cloud hoặc SDK.

## Bảo mật và độ tin cậy của filesystem local

Dùng volume/thư mục local riêng, chỉ do tài khoản dịch vụ ứng dụng và người vận
hành đáng tin cậy kiểm soát. Bảo vệ mọi thư mục cha để bên không đáng tin cậy không
thể thay thế. Trên POSIX, root mới chỉ cho chủ sở hữu truy cập (0700); root đã có
phải cho chủ sở hữu quyền rwx và không cấp quyền cho group/other. Trên Windows,
thiết lập và kiểm tra NTFS ACL chỉ cho phép tài khoản dịch vụ và quản trị viên
đáng tin cậy; adapter không thiết lập hoặc kiểm tra toàn diện ACL của Windows.

Adapter từ chối key sai định dạng, thư mục symlink/reparse point có sẵn, đối tượng
không phải file thông thường và thư mục cha của root có liên kết. Thao tác đọc
mở file bằng NOFOLLOW_LINKS. **Kiểm soát truy cập của hệ điều hành là ranh giới
bảo mật bắt buộc:** kiểm tra đường dẫn trong Java không ngăn được tiến trình local
độc hại thay thế thư mục trong lúc xử lý, sửa file hoặc chèn hard link. Không dùng
chung root với bên ghi không đáng tin cậy. Giới hạn này áp dụng cho cả Windows
và POSIX. Chỉ riêng key không đáng tin cậy từ request không thể chọn đường dẫn
hoặc thoát ra ngoài root được quản lý.

Root được khởi tạo khi ứng dụng khởi động, tạo các thư mục còn thiếu. Quá trình
khởi động kiểm tra hỗ trợ hard link và hành vi chỉ tạo khi chưa tồn tại.
Đối tượng trong POC được lưu theo cấu trúc phẳng. File được ghi theo luồng vào
file tạm riêng tư .write-*.tmp, buộc ghi xuống đĩa, rồi công bố bằng hard link
nguyên tử không ghi đè trong cùng thư mục. Bên đọc chỉ truy cập được đối tượng
hoàn chỉnh đã công bố. Đích đã tồn tại không bị thay thế, kể cả khi UUID trùng.
Các thao tác lưu đồng thời có file tạm và key được sinh riêng. Không dùng phương
án copy/move dự phòng thiếu an toàn: filesystem không hỗ trợ hard link bị từ chối,
kể cả provider/share không cung cấp được bảo đảm này.

Lỗi I/O thông thường giữ lại nguyên nhân và cố gắng dọn file tạm. Lỗi dọn dẹp được
đính kèm dưới dạng suppressed trên nguyên nhân gốc. Nếu dọn file tạm thất bại sau
khi công bố, hệ thống cố gắng xóa bù đối tượng vừa công bố và báo lỗi, không báo
thành công. Không được coi thao tác thất bại là bằng chứng không còn file sót lại
khi filesystem cũng từ chối dọn dẹp.

Nội dung file được buộc ghi xuống đĩa trước khi công bố, nhưng fsync cho thư mục
và độ bền transaction khi mất điện không được bảo đảm một cách portable ở đây.
Tiến trình dừng hoặc mất điện có thể để lại file tạm hoặc đối tượng đã công bố
nhưng chưa trả metadata. Không có tác vụ tự động dọn file sót, backup, migration
hoặc transaction chung giữa file và cơ sở dữ liệu. Người vận hành đáng tin cậy
chỉ kiểm tra file tạm cũ khi đã dừng các tiến trình ghi và dùng quy trình khôi
phục được phê duyệt. Không xóa file tạm còn đang được ghi. Lưu giữ và backup cả
volume đối tượng lẫn metadata trong cơ sở dữ liệu, phối hợp các bản backup và
kiểm thử việc khôi phục. Không ghi log nội dung CV.

## Provider trong tương lai và metadata document hiện có

Adapter tương thích S3 trong tương lai có thể triển khai cùng interface streaming
và mở rộng cấu hình có điều kiện mà không thay đổi mã nghiệp vụ chỉ phụ thuộc
FileStorageService. Adapter phải giữ nguyên ngữ nghĩa đối tượng không tồn tại,
xóa và ghi, cùng hợp đồng key ổn định. Hiện chưa thể chọn cloud provider.

CV_DOCUMENTS.STORAGE_PATH có kiểu TEXT và sau này có thể chứa opaque key thay cho
đường dẫn local tuyệt đối; FILE_SIZE và SHA256 có thể lưu metadata được trả về.
Milestone này không thay đổi mapping/schema hoặc dữ liệu hiện có. Không được
mặc định rằng các đường dẫn đã lưu trước đây đáp ứng quy tắc key mới.

Thay đổi provider được chọn **không di chuyển các đối tượng hiện có** hoặc bảo
toàn khả năng truy cập file local. Trước khi đổi provider sau này, phải chọn di
chuyển toàn bộ có phối hợp (copy, kiểm tra checksum/dung lượng, cập nhật metadata
nếu cần rồi mới chuyển) hoặc thiết kế rõ định danh/cơ chế định tuyến provider
cho từng đối tượng. Dùng nhiều provider có thể cần thay đổi schema/hợp đồng được
phê duyệt; chỉ key không cho biết backend nào chứa đối tượng. S3/MinIO SDK và
công cụ migration vẫn được hoãn.

## Kiểm thử và vận hành

Chạy ./gradlew check --rerun-tasks hoặc .\gradlew.bat check --rerun-tasks với Java 25
và cấu hình test PostgreSQL hiện có. Test tích hợp cung cấp root tạm và provider
tường minh; không truy cập storage CV của ứng dụng. Test storage trực tiếp dùng
thư mục tạm. Test liên kết thư mục trên Windows dùng NTFS junction không cần đặc
quyền; POSIX dùng symbolic link. Lỗi quyền truy cập và lỗi công bố cũng được mô
phỏng có kiểm soát để quyền administrator/root không khiến test thành công giả.

Các kiểm tra bao gồm lưu/đọc lại, luồng rỗng/nhị phân/lớn, digest và số byte,
quyền sở hữu luồng, key không tồn tại/sai định dạng, liên kết, an toàn của root,
ghi dở dang, trùng key/đồng thời, lỗi I/O, chọn bean có điều kiện và ranh giới
module. Các bộ test HTTP và persistence hiện có vẫn bắt buộc.

# Milestone 4: Upload một CV

M4 chỉ xử lý upload độc lập một CV trong mỗi request và tra cứu metadata.
Quản lý batch thuộc M5 riêng biệt (PR #6). M4 không tạo batch giả, processing job hoặc candidate; không thực hiện OCR,
tải file xuống, nhập ZIP hoặc bổ sung storage provider.

## Hợp đồng API

| Phương thức | Đường dẫn | Request | Khi thành công |
| --- | --- | --- | --- |
| POST | /api/documents | multipart/form-data, đúng một file part tên file; không có part hoặc tham số bổ sung | 201, header Location và DocumentMetadata |
| GET | /api/documents/{id} | ID document là số dương | 200 và DocumentMetadata; ID không tồn tại trả 404 |

Ví dụ chạy với ứng dụng đã được cấu hình local:

~~~sh
curl -i -F "file=@/path/to/cv.pdf" http://localhost:8080/api/documents
curl -i http://localhost:8080/api/documents/123
~~~

Response thành công với các giá trị minh họa:

~~~json
{
  "id": 123,
  "originalFilename": "cv.pdf",
  "sizeBytes": 2048,
  "mimeType": "application/pdf",
  "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
  "status": "PENDING",
  "createdAt": "2026-10-15T00:00:00Z"
}
~~~

Response không chứa storage key hoặc đường dẫn vật lý. Tên file chỉ dùng để hiển
thị: bỏ các thành phần thư mục do client gửi; từ chối tên rỗng, chứa ký tự điều
khiển/định dạng hoặc dài hơn 255 đơn vị UTF-16. Chỉ FileStorageService sinh định
danh vật lý. MIME, dung lượng file và checksum do client khai báo không được dùng
để tạo metadata. Các trường bổ sung như checksum/dung lượng bị từ chối.

Lỗi dùng lại ApiError với correlationId và details rỗng:
400 cho multipart không hợp lệ, file rỗng, tên file hoặc ID không hợp lệ;
413 khi vượt dung lượng; 415 khi chữ ký/phần kết thúc PDF không hợp lệ;
404 khi không tìm thấy document; 500 tổng quát cho lỗi storage/cơ sở dữ liệu.
Mã lỗi 413 ở cấp servlet là PAYLOAD_TOO_LARGE; bộ kiểm tra độc lập tại ứng dụng
trả FILE_TOO_LARGE.

## Validation và xử lý theo luồng

Giới hạn file chính xác là 10 MiB (10.485.760 byte, tương ứng cấu hình 10MB của
Spring). Giới hạn tổng request multipart của M4 là 11 MiB để chừa dung lượng cho
framing; vẫn cấm part bổ sung. Giá trị multipart file-size-threshold bằng 0 khiến
nội dung file được ghi vào thư mục tạm của servlet thay vì giữ toàn bộ trong RAM.
Cần cấp thư mục tạm riêng tư, có quyền ghi và đủ dung lượng đĩa.

Ứng dụng tự đếm byte trong lúc storage adapter đọc luồng được mượn, từ chối ngay
byte đầu tiên vượt giới hạn. Dung lượng thực tế và SHA-256 chỉ lấy từ StoredFile
được trả về sau khi file được công bố thành công.

Kiểm tra nội dung yêu cầu header ở byte đầu tiên xác định PDF 1.0–1.7 hoặc 2.0,
và marker %%EOF theo sau chỉ có ký tự trắng hợp lệ của PDF trong 1024 byte cuối.
Validation diễn ra trước khi công bố, bao gồm kiểm tra EOF trong lúc storage đọc,
nên luồng bị từ chối không để lại đối tượng lưu thành công. Dùng buffer/phần đuôi
có kích thước cố định; mã production không gọi MultipartFile.getBytes() hoặc
readAllBytes().

Các kiểm tra này nhận diện mẫu chữ ký/phần kết thúc PDF được hỗ trợ, không bảo
đảm cấu trúc PDF hợp lệ, không có mã độc, hỗ trợ mã hóa hoặc đọc được bằng OCR.
PDF hỏng hoặc độc hại có marker hợp lý vẫn có thể bị bộ xử lý sau này từ chối.
Phân tích/trích xuất và quét đầy đủ nằm ngoài M4; không bổ sung PDF SDK.

## Transaction và xóa bù

CvDocumentService nhận FileStorageService và CvDocumentRepository qua constructor
injection. Phương thức upload lưu file bên ngoài transaction cơ sở dữ liệu.
Sau đó TransactionTemplate với REQUIRES_NEW lưu metadata và hoàn tất commit riêng
trước khi upload trả về. Cách này cũng tránh vô tình tham gia transaction bên ngoài
mà rollback muộn hơn có thể để lại file mồ côi.

Lỗi insert/flush và commit đều được bắt bên ngoài ranh giới transaction.
Service xóa key vừa lưu khi có lỗi. Nếu xóa bù thất bại, lỗi chính được giữ nguyên,
lỗi dọn dẹp được đính kèm dạng suppressed và log lỗi chứa opaque key, loại exception
dọn dẹp cùng correlation ID hiện có trong MDC để người vận hành khôi phục.
Mã này không ghi log tên file gốc, nội dung, thông báo SQL hoặc đường dẫn tuyệt đối.

Storage và PostgreSQL vẫn là hai tài nguyên riêng. Chưa thể bảo đảm nguyên tử khi
tiến trình dừng hoặc kết quả commit không xác định do mất kết nối cơ sở dữ liệu.
Người vận hành phải đối soát đối tượng mồ côi hoặc bản ghi không nhất quán sau
những sự cố đó; không bổ sung dịch vụ dọn dẹp nền hoặc distributed transaction.

## Schema và migration

V3__allow_standalone_cv_documents.sql chỉ bỏ NOT NULL trên CV_DOCUMENTS.BATCH_ID.
Khóa ngoại batch và các liên kết hiện có được giữ nguyên. Quan hệ JPA cho phép
null. Không thay đổi V1/V2; upload mới để batch và candidate là null, trạng thái
PENDING và không tạo processing job. PENDING mô tả document đã upload đang chờ
xử lý trong tương lai, không phải OCR thành công.

## Thiết lập local

Đặt CV_STORAGE_PROVIDER=local, CV_STORAGE_ROOT là thư mục riêng tư có đường dẫn
tuyệt đối ngoài Git checkout, cùng các biến môi trường datasource hiện có.
Chạy backend từ backend/ bằng Java 25 và ./gradlew bootRun (Windows:
.\gradlew.bat bootRun).

File tùy chọn backend/.env.properties đã được Git ignore sẽ được import khi thư
mục làm việc là backend/. File này có thể chứa CV_STORAGE_PROVIDER và
CV_STORAGE_ROOT theo định dạng Java properties. Đây là cấu hình local, tuyệt đối
không commit. Biến môi trường của tiến trình được ưu tiên. Thư mục storage trên
máy được yêu cầu được cấu hình trong file local này, không ghi cứng trong mã ứng dụng.

Thiết lập quyền theo hướng dẫn M3. Khởi động ứng dụng có thể tạo root đã cấu hình;
test luôn thay thế bằng thư mục tạm và không dùng storage CV local. Backup và
khôi phục vẫn là trách nhiệm vận hành.

## Kiểm chứng

Chạy .\gradlew.bat check --rerun-tasks (hoặc ./gradlew check --rerun-tasks) với
Java 25 và TEST_DB_PASSWORD cho môi trường PostgreSQL cô lập hiện có.

CvUploadIntegrationTests khởi động HTTP server thật và gửi multipart request qua
bộ phân tích của servlet. Test kiểm tra nội dung/metadata đã lưu thực tế, tra cứu,
chấp nhận 10 MiB, từ chối quá dung lượng, part trùng/bổ sung, dữ liệu rỗng/không
hợp lệ, 404/validation ID, không tạo batch/job/candidate, schema cho phép batch
null và giữ khóa ngoại. Constraint trigger deferred thật của PostgreSQL kiểm
chứng dọn dẹp sau lỗi commit, không chỉ dựa vào repository mock.

Test service kiểm tra lỗi storage, xóa bù khi insert hoặc commit lỗi, dọn dẹp
thất bại và ghi log an toàn phục vụ khôi phục. Test stream kiểm tra biên dung
lượng chính xác, marker sai, đọc từng byte/theo khối, skip/reset và quyền sở hữu
luồng của bên gọi. Các check persistence/HTTP/storage/Modulith hiện có vẫn bắt
buộc. Dữ liệu tích hợp và root lưu file đều được cô lập và xóa sau test.

# Milestone 5: Quản lý batch đồng bộ

M5 là milestone Batch Management được developer phê duyệt (PR #6), tách khỏi M4
Single CV Upload (PR #5) để triển khai, review và kiểm thử độc lập.
M6 là OCR Worker Integration; phải xác nhận scope chi tiết trước khi triển khai.
M5 không tạo candidate, job hoặc extraction và không gắn các upload độc lập vào batch.

| Phương thức | Endpoint | Kết quả |
| --- | --- | --- |
| POST | /api/batches | 201 + Location + thông tin batch và kết quả mọi item |
| GET | /api/batches/{id} | 200 với thông tin tổng hợp batch; 404 nếu không tồn tại |
| GET | /api/batches/{id}/items?page=0&size=20 | 200 với kết quả phân trang theo fileOrder |
| GET | /api/batches?page=0&size=20 | 200 với danh sách batch theo createdAt DESC, id DESC |

Chỉ số trang bắt đầu từ 0; size trong khoảng 1–100. ID/tham số/cấu trúc part không
hợp lệ trả 400; media type của request không được hỗ trợ trả 415; vượt giới hạn
tổng của parser trả 413; lỗi hạ tầng trả 500. Đợt nhập đã xử lý xong trả 201 kể
cả khi mọi file đều thất bại. Không sử dụng 202.

```sh
curl -i -F 'files=@/path/to/one.pdf' -F 'files=@/path/to/two.pdf' http://localhost:8080/api/batches
curl http://localhost:8080/api/batches/42
curl 'http://localhost:8080/api/batches/42/items?page=0&size=20'
curl 'http://localhost:8080/api/batches?page=0&size=20'
```

Response minh họa khi một phần file nhập thành công:

```json
{
  "batch": {"id":42,"totalFiles":2,"successCount":1,"failedCount":1,
    "status":"PARTIAL_FAILED","createdAt":"2026-10-17T01:00:00Z",
    "completedAt":"2026-10-17T01:00:01Z"},
  "items":[
    {"fileOrder":1,"displayName":"one.pdf","documentId":123,"errorCode":null},
    {"fileOrder":2,"displayName":"two.pdf","documentId":null,"errorCode":"INVALID_PDF"}
  ]
}
```

Truy vấn item trả `{content:[...],page:0,size:20,totalElements:2,totalPages:1}`.
Tên hiển thị dùng lại cơ chế làm sạch của M4; tên không hợp lệ được thay bằng
`file-N` và trả lỗi INVALID_FILENAME. Kết quả chỉ cung cấp mã lỗi an toàn, không
trả thông báo exception nội bộ, đường dẫn vật lý hoặc storage key.

## Giới hạn và validation

`CV_BATCH_MAX_FILES` mặc định 10 (cho phép 1–10);
`CV_BATCH_MAX_FILE_BYTES` mặc định 10485760 (cho phép 1–10485760). Giá trị không
hợp lệ khiến ứng dụng không khởi động. Upload đơn vẫn có giới hạn độc lập,
chính xác 10 MiB.

BatchMultipartConfiguration tính cả hai trần parser của servlet theo
`max(10 MiB, maxFiles * maxFileBytes) + 1 MiB`, mặc định 101 MiB, và luôn ghi file
ra đĩa tạm (threshold bằng 0). Không ghi đè riêng các giới hạn servlet; bean này
là nguồn cấu hình chính. Tomcat có drain budget hữu hạn 101 MiB để trả đủ 413
cho request chunked vượt giới hạn ở mức vừa phải; client gửi quá drain budget
có thể gặp reset kết nối. Trần từng file tại parser được đặt bằng trần request
để một file vượt giới hạn riêng nhưng chưa vượt trần request có thể được ghi
nhận là lỗi độc lập. Validation theo luồng tại ứng dụng thực thi giới hạn thực
của từng file. Endpoint M4 vẫn từ chối part trùng/bổ sung và dùng PAYLOAD_TOO_LARGE
cho part có dung lượng khai báo vượt giới hạn, đồng thời giữ bộ kiểm tra luồng
FILE_TOO_LARGE độc lập.

Batch chỉ nhận file part tên `files`, không nhận text part hoặc query parameter.
Việc phân tích, kiểm tra số lượng và cấu trúc hoàn tất trước khi tạo batch.
Request sai cấu trúc hoặc vượt giới hạn tổng không tạo batch. File được xử lý
lần lượt theo thứ tự trong request với buffer streaming có giới hạn; mã production
không tạo mảng byte chứa cả file, không dùng ZIP, OCR, queue, scheduler hoặc xử lý
nền. TOTAL_FILES của request hợp lệ gồm mọi file đã nhận, kể cả PDF bị từ chối.
Các giới hạn này áp dụng cho từng request, không giới hạn tổng dung lượng đĩa
hoặc mức đồng thời của cả hệ thống. Cần cấp storage tạm riêng tư cho servlet và
storage local cho đối tượng với dung lượng phù hợp.

## Persistence, lỗi và giới hạn khôi phục

V4 thêm CV_IMPORT_ITEMS với khóa ngoại batch, FILE_ORDER dương bắt đầu từ 1 và
không trùng trong batch, DISPLAY_NAME an toàn, khóa ngoại document cho phép null
và ERROR_CODE. Phải có đúng một trong DOCUMENT_ID / ERROR_CODE. File thất bại
không có document giữ chỗ. V1–V3 không thay đổi; CV_IMPORT_BATCHES đã có và giữ
nguyên enum trạng thái.

Batch ban đầu ở PROCESSING được commit độc lập. Storage dùng validation và xóa
bù của M4 bên ngoài transaction DB. Mỗi lần thành công, document + liên kết batch
+ item + bộ đếm (và trạng thái/thời gian kết thúc khi đủ kết quả) commit trong
một transaction REQUIRES_NEW. Item thất bại + bộ đếm lỗi + trạng thái cuối cũng
commit cùng nhau. Mỗi batch có một bên ghi; không có API tiếp tục hoặc sửa batch
hiện có. Không giữ transaction trong lúc stream file. Khi kết thúc, tổng bộ đếm
bằng TOTAL_FILES; trạng thái lần lượt là COMPLETED / PARTIAL_FAILED / FAILED khi
tất cả / một phần / không có file nào thành công. Đây là bộ đếm nhập file, không
phải kết quả OCR.

Lỗi validation, I/O đầu vào hoặc ghi storage riêng từng file được ghi nhận rồi
chuyển sang file tiếp theo. Storage UNAVAILABLE và lỗi DB/transaction dừng xử lý
với 500; các kết quả đã commit trước đó được giữ lại. Nếu DB không ghi nhận được
lỗi, hệ thống không tạo kết quả giả. Batch bị dừng có thể còn PROCESSING với số
item ít hơn tổng; GET trả phần kết quả đã commit. POST có thể thất bại mà client
chưa nhận được ID batch; người vận hành có thể kiểm tra danh sách batch.
Gửi lại POST tạo batch khác và có thể trùng file; chưa có idempotency hoặc retry
tự động.

Xóa bù của M4 xóa đối tượng mới sau lỗi insert/flush/commit; lỗi dọn dẹp không
che lỗi chính và được ghi trong log khôi phục an toàn hiện có. Mất kết nối khi
commit có thể khiến kết quả không xác định: xóa bù có thể xóa đối tượng dù
transaction DB thực tế đã commit. Tiến trình dừng có thể để lại file tạm/file mồ
côi hoặc batch PROCESSING. Không có bảo đảm khôi phục sau crash, scheduler đối
soát hoặc tính nguyên tử giữa các tài nguyên phân tán. Người vận hành đáng tin
cậy phải kiểm tra và đối soát cơ sở dữ liệu/storage đối tượng sau những lỗi này.

## Kiểm chứng

Chạy `./gradlew check` (Windows `.\gradlew.bat check`) bằng JDK 25 với môi trường
test PostgreSQL cô lập như hướng dẫn ở trên. CvBatchIntegrationTests dùng HTTP
multipart thật, schema PostgreSQL riêng, storage tạm, PDF tổng hợp và constraint
trigger deferred để tạo lỗi commit thật. Test M4 và kiểm chứng Modulith hiện có
vẫn thuộc bộ check đầy đủ.

# Tổ chức package backend sau M5

Ứng dụng giữ đúng ba module Spring Modulith: ingestion, storage và web.
Các package theo trách nhiệm trong ingestion vẫn là nội bộ của module đóng;
không bổ sung named interface hoặc module ứng dụng mới.

```text
com.cvcompass.backend
|-- BackendApplication
|-- ingestion
|   |-- controller   CvBatchController, CvDocumentController
|   |-- exception    IngestionExceptionHandler, BatchNotFoundException,
|   |                DocumentNotFoundException, UploadRejectedException
|   |-- service      CvBatchService, CvDocumentService
|   |-- dto          BatchDetails, BatchItem, DocumentMetadata, ImportResult, PageResult
|   |-- entity       BaseEntity, UpdatableEntity, Candidate, CvDocument,
|   |                CvExtraction, CvImportBatch, CvImportItem, CvProcessingJob
|   |-- repository   CandidateRepository, CvDocumentRepository, CvExtractionRepository,
|   |                CvImportBatchRepository, CvImportItemRepository, CvProcessingJobRepository
|   |-- enums        BatchStatus, DocumentStatus, ExtractionMethod, JobStatus
|   |-- config       BatchMultipartConfiguration
|   `-- upload       PdfUploadStream, PdfUploadValidation, UploadFilename
|-- storage          FileStorageService, StorageException, StorageKey, StoredFile
|   |-- config       StorageConfiguration, StorageProperties
|   `-- local        LocalFileStorageService, LocalStorageProperties
`-- web              ApiError, ApiResponse, FieldViolation
    |-- error        GlobalExceptionHandler
    `-- logging      CorrelationIdFilter
```

Controller gọi service và trả DTO. Advice nội bộ ingestion ở mức package-private
chọn package controller, giữ nguyên mã lỗi, HTTP status, correlation ID và thông
báo an toàn cho document/batch, đồng thời tránh vòng phụ thuộc controller/exception.
Lỗi framework dùng chung vẫn nằm trong web. JPA entity và repository vẫn ở dưới
package gốc được ứng dụng quét. BatchMultipartConfiguration và PdfUploadStream
giữ package-private; PdfUploadValidation chỉ cung cấp factory trả InputStream
được mượn và giới hạn mặc định hiện có. UploadFilename chứa logic validation tên
hiển thị không thay đổi, dùng chung cho hai service. Callback persistence của
CvDocumentService là private hoặc package-private trong service.

ImportResult là DTO riêng, giữ nguyên các thành phần JSON `batch` và `items`.
Toàn bộ hành vi endpoint, multipart, thứ tự xử lý, lỗi một phần, transaction,
rollback, xóa bù, mapping và schema không đổi so với commit M5 60a1599.
Đợt refactor không sửa migration V1–V4, cấu hình runtime hoặc dependency.

BackendPackageStructureTests kiểm tra vị trí package đúng trách nhiệm, không có
vòng phụ thuộc giữa các package con ingestion, controller tách khỏi persistence,
visibility của kiểu nội bộ và phạm vi advice. ModulithArchitectureTests hiện có
vẫn xác minh đúng ingestion/storage/web và gọi ApplicationModules.verify().
Chạy `.\gradlew.bat clean check` (hoặc `./gradlew clean check`) sau khi di chuyển
package để class cũ không che lỗi scanning, bean trùng, repository hoặc serialization.

Rà soát rủi ro M5: trần parser có thể cấu hình nhưng tối đa 101 MiB; drain budget
cố định 101 MiB của Tomcat độc lập với các giới hạn được cấu hình thấp hơn.
Điều này giới hạn dung lượng drain; body quá lớn vẫn có thể bị reset kết nối.
Gián đoạn DB có thể để batch ở PROCESSING; commit outcome không xác định có thể
gây lệch file/metadata khi xóa bù. Đây là giới hạn đã ghi nhận của M5, không phải
hành vi mới do refactor package. Không bổ sung khôi phục, đối soát hoặc retry.

# Kiểm chứng local và cảnh báo của test JVM

Trước khi kết luận quality gate, chạy full check trên source cuối từ `backend/`:

```powershell
.\gradlew.bat check --rerun-tasks --console=plain --warning-mode=all
```

Đọc terminal log và JUnit XML mới trong `build/test-results/test/`, cùng báo cáo
HTML trong `build/reports/tests/test/`. Báo cáo Gradle problems chỉ được dùng làm
bằng chứng cho lần chạy tương ứng nếu thực sự được tạo mới. `NO-SOURCE` ở task
`processTestResources` là bình thường vì chưa có `src/test/resources`; task `test`
vẫn phải chạy thật. Không dùng CI hoặc kết quả UP-TO-DATE thay kiểm chứng local.

Build bật `-Xlint:deprecation`. Mockito được nạp bằng `-javaagent` khi test JVM
khởi động, dùng đúng phiên bản được dependency management quản lý; không cần
self-attach động. Mockito inline vẫn thêm `MockMethodDispatcher` vào bootstrap
classpath (kể cả khi agent nạp từ đầu). Vì vậy test JVM chủ động dùng `-Xshare:off`
để không dùng CDS trong cấu hình instrumentation không tương thích. Đây là bỏ tối
ưu chia sẻ class cho test, có thể tăng chi phí startup/bộ nhớ; không đổi assertion,
không suppress warning, không tắt logging, không thêm cờ vào JVM ứng dụng hoặc
Gradle daemon. Các test static mocking filesystem vẫn chạy bằng inline mock maker.

Test tích hợp loại context khỏi Spring Test cache và đóng context trước khi xóa
schema, để callback shutdown của Spring Modulith vẫn truy cập được bảng của nó.
Sau đó chỉ xóa schema và storage do lớp test sở hữu. Test giới hạn cấu hình dùng
HTTP thật với tối đa 2 file và 100 byte/file, đồng thời xác nhận upload đơn M4 vẫn
chấp nhận file lớn hơn giới hạn batch. Test phân trang bao gồm thời gian tạo trùng
nhau; test persistence kiểm tra ràng buộc kết quả, thứ tự và khóa ngoại của item.

Negative test cố ý phát sinh log WARN/ERROR: vi phạm constraint PostgreSQL,
commit thất bại, storage không khả dụng, cleanup thất bại, lỗi HTTP 500/405 và cấu
hình storage không hợp lệ. Không xóa test hoặc che log này. Nếu gate yêu cầu không
có bất kỳ dòng WARN/ERROR nào, kết quả vẫn BLOCKED dù mọi assertion đều pass;
chỉ developer mới có thể quyết định thay đổi điều kiện chuyển milestone.