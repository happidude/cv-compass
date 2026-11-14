# Contract kết quả LLM trên API ứng viên

Phạm vi đã duyệt ngày 14/11/2026: Task 1 + Task 2 của `llm-fix-contract`.
Nguồn: [M11](../../backend/M11.md), DTO backend và schema frontend hiện hành.

`MappingRemoteVerifier` chuyển `cv-compass.llm-mapping/1.0` thành envelope v3.
Kinh nghiệm được publication giữ nguyên trong `data` của profile hoặc proposal.
`data.descriptionRaw` là chuỗi, dùng `""` khi không có mô tả, giống engine 3.x;
`descriptionEvidence` là mảng rỗng trong trường hợp này. Theo quyết định Primary
ngày 14/11/2026, riêng schema đọc frontend của `descriptionRaw` nhận
`string | null | undefined` để tương thích hồ sơ/proposal đã lưu trước sửa.
`null` hợp lệ nghiệp vụ, nghĩa là không có mô tả, tương đương `""`.
Backend tiếp tục sinh chuỗi và không thay engine 3.0–3.5.

Lần publication đầu (version 0, chưa có kinh nghiệm) chèn kinh nghiệm vào profile;
`/proposals` rỗng là đúng. Publication sau tạo proposal, giữ kinh nghiệm hiện hành.
Personal field thiếu có state `NOT_FOUND`; field tắt kèm `FIELD_DISABLED`.
Gender `OTHER` giữ raw, normalized null và issue `GENDER_UNSUPPORTED`.
`other` có giá trị và issue cấp envelope có `field: null` nằm trong mapping ở
extensions/lịch sử; chúng không trở thành issue cấp kinh nghiệm.

## Fixture và kiểm chứng

Fixture tại [frontend/tests/fixtures/llm-remote](../../frontend/tests/fixtures/llm-remote/)
được sinh bằng serialization Java từ verifier, publication, query và revision thật
trong `LlmRemoteSerializationTests` (repository giả, không cần DB).
`MappingRemoteIntegrationTests.remoteHttpPublicationMatchesFrontendFixturesForInitialAndExistingProfile`
đi qua MockMvc với security filter chain thật (claim/result có Bearer + lease, GET có session/CSRF), verifier, DB, publication và ba GET API
có session thật; so sánh toàn bộ JSON đã chuẩn hóa với cùng fixture. Lệch là FAIL,
không tự cập nhật fixture được track.

Chuẩn hóa ID số, UUID, timestamp, input hash và profile revision biến động;
sắp xếp kinh nghiệm/proposal theo `data.localId` và loại proposal, skipped theo reason
để loại thứ tự UUID ngẫu nhiên. Ngày nhập nguồn được cố định trước publication để
giữ phép tính kỳ ongoing xác định. Không loại bỏ trường dữ liệu nào.

Các fixture bao phủ kỳ hoàn chỉnh, ongoing, thiếu end, chỉ title, dòng không có
period/evidence, nhiều kinh nghiệm, personal thiếu/tắt, gender OTHER, `other` và
issue cấp envelope. `before-fix-profile.json` là output backend trước sửa, giữ riêng
để test tương thích dữ liệu đã lưu: schema thật hiện parse được profile và proposal
dùng cùng dữ liệu kinh nghiệm có mô tả null. Trước quyết định tương thích, schema
từ chối tại `experiences.0.data.descriptionRaw` đến
`experiences.4.data.descriptionRaw` với mã `invalid_type`; proposal bị
`invalid_union` tại chỉ số 0–4, lỗi con `<index>.data.descriptionRaw` /
`invalid_type` của nhánh EXPERIENCE. Các fixture backend sau sửa vẫn được kiểm tra
không chứa `descriptionRaw: null`, kể cả trong extensions và lịch sử.

Từ `backend/`, chạy:

```powershell
.\gradlew.bat test --offline --tests "*LlmRemoteSerializationTests"
.\gradlew.bat test --offline --tests "*MappingRemoteIntegrationTests.remoteHttpPublicationMatchesFrontendFixturesForInitialAndExistingProfile"
```

Test ghi JSON thật đã chuẩn hóa vào `backend/build/llm-remote-fixtures/` trước khi
so sánh. Khi chủ động đổi contract đã duyệt, xem diff output này rồi cập nhật sáu
fixture initial/subsequent từ output backend; giữ nguyên fixture trước sửa.
Chạy lại cả hai test backend và `npm.cmd run check` từ `frontend/`.
Test integration cần `TEST_DB_*` trỏ vào PostgreSQL test cô lập.

## Chẩn đoán frontend

`decode` ghi một dòng `[contract]` trong cả dev và build thường. JSON hợp lệ nhưng
sai schema chỉ ghi endpoint chuẩn hóa cùng tối đa 20 cặp `{path, code}`. Chỉ số
mảng giữ nguyên; segment chuỗi ngoài `^[A-Za-z0-9_]{1,64}$` thành `?`.
Endpoint chỉ còn path, ID số thành `:id`, segment ngoài `^[A-Za-z_]{1,64}$` (kể cả UUID, `v1`) thành `?`, bỏ query và fragment; không ghi origin.
JSON không parse được chỉ ghi `[contract] INVALID_JSON`; schema tự ném lỗi ghi `[contract] PARSE_ERROR`.
Không ghi message Zod, body, input, received hoặc giá trị payload.

## Quyết định tương thích dữ liệu đã lưu

Chủ dự án đã có hồ sơ/proposal với `data.descriptionRaw = null` trong DB local từ
bản verifier trước sửa. Validator backend chỉ áp cho dữ liệu người dùng gửi; form
tiếp tục gửi chuỗi. Lỗi tương thích chỉ ở đường đọc, nên quyết định đã chốt là
cho phép null riêng ở trường mô tả của schema đọc frontend, không migration và
không sửa `candidate/**` production.

Phần hiển thị mô tả coi null là rỗng. Form sửa dùng `?? ""` khi khởi tạo state và
gửi `descriptionRaw` luôn là chuỗi. Test render đi qua đọc profile cũ, mở form có
ô mô tả rỗng và lưu chuỗi rỗng. Test chẩn đoán `[contract]` tiếp tục dùng lỗi kiểu,
enum và literal khác để xác nhận path/code và không lộ giá trị payload.
