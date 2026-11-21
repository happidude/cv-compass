# Brief E12 — Số điện thoại từ LLM: nhận dạng `(+84)` và chuẩn hóa dễ dãi (chỉ đường remote)

Mục tiêu/acceptance: bản ghi thật `(+84) 973 028 568` hiện bị `INVALID_PHONE` → không được publish (publication cần `normalized`). Chủ dự án 21/11/2026: production chạy batch KHÔNG có bước duyệt, "trích xuất thế nào lưu như vậy" → với giá trị LLM đã có evidence trong CV, chỉ từ chối khi chắc chắn không phải số điện thoại.
Chỉ sửa `PersonalInfoValidation.checkRemote` cho field `phone` (engine 3.x/in-process giữ nguyên từng hành vi):
 (1) Bỏ nhãn đầu (`Tel:`, `Phone:`, `Mobile:`, `ĐT:`, `SĐT:`, `Điện thoại:`, `Hotline:`), chấp nhận tiền tố mã nước dạng `(+84)`, `(84)`, `+84 (0)`, `0084`; ký tự phân cách: khoảng trắng, `.`, `-`, `–`, `/`, `(`, `)`.
 (2) Nhiều số trong một giá trị (`0909 123 456 / 0912 345 678`, `;`, `,`, ` - ` giữa 2 cụm đủ dài) → lấy số ĐẦU TIÊN, thêm issue thông tin `PHONE_MULTIPLE_FIRST_KEPT` (không chặn publish).
 (3) `normalized` = `+<mã nước><số>` nếu có mã nước (KHÔNG đổi sang 0), ngược lại các chữ số; giữ `x<ext>` như hiện tại. `+84 (0)9…` → `+849…`.
 (4) Chỉ trả `INVALID_PHONE` khi: sau khi bỏ phân cách còn chữ cái (ngoài nhãn/ext), hoặc số chữ số ngoài 7–15, hoặc là ngày/khoảng năm (giữ các regex ngày hiện có).
 (5) `raw` giữ nguyên văn như hiện tại; issue mới thêm vào `SERVER_ISSUE_CODES`.
Không đổi: email/fullName/location, engine 3.x, contract JSON, migration.
Spec: file này. Mức/lý do: 3 — đổi kết quả publish dữ liệu cá nhân (đường remote).
Path được sửa: `backend/src/main/java/com/cvcompass/backend/mapping/service/{PersonalInfoValidation,MappingRemoteVerifier}.java`, test tương ứng trong `backend/src/test/**`. Loại trừ: engine 3.x, llm-worker, frontend, migration.
Base: origin/develop mới nhất. Branch: `agent/backend-e12-phone-lenient`.
Ngữ cảnh đã biết: `checkRemote` hiện gọi `check(field, raw)` cho mọi field trừ fullName; regex `\+?[0-9(). /-]+` không nhận `(` trước `+` nên `(+84) …` hỏng; `PersonalInfoGrammarValidation.standardPhone` đã có `replaceFirst("^\\(\\+(\\d{1,3})\\)", "+$1")` (tham khảo, không sửa); `PersonalInfoMapper.patch` chỉ publish khi `normalized` là chuỗi.
Test tối thiểu (dữ liệu bịa): `(+84) 912 345 678` → `+84912345678`; `+84 (0) 912 345 678` → `+84912345678`; `Tel: 0912.345.678` → `0912345678`; `0912 345 678 / 0987 654 321` → `0912345678` + `PHONE_MULTIPLE_FIRST_KEPT`; `2019 - 2021`, `12/05/2020`, `abc123` → `INVALID_PHONE`; engine 3.x không đổi (test hiện có giữ nguyên).
Check affected: backend `./gradlew test --tests '*PersonalInfoValidation*' --tests '*MappingRemoteVerifier*'` + spotless/format hiện có.
