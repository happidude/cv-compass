# Engine personal-info-mapping/3.2

Chủ dự án duyệt trong chat ngày 08/11/2026. Mức 3 vì thay đổi logic trích xuất dữ liệu cá nhân và
routing theo engine đã ghim. Config mẫu là `personal-info-config-v3.2.json`, revision
`personal-info-grammar-v32-r1`. Config và kết quả dùng schema 3. Job 3.0/3.1 tiếp tục chạy theo
config và engine đã ghim. Môi trường ứng dụng cần đăng ký và kích hoạt config riêng khi chủ dự án quyết định.

| Hạng mục | Tiêu chí chấp nhận |
| --- | --- |
| A · Config | Thêm alias địa chỉ `Location`, `Nơi ở`, hai nhãn và heading tóm tắt tiếng Việt, cùng heading giáo dục `Abilities and Qualifications`. |
| B · Địa chỉ | Giữ giá trị cùng dòng. Dừng nối trước bullet, nhãn dạng chữ có dấu hai chấm hoặc heading đã biết. |
| C · Điện thoại | Giữ raw và evidence. `+84 (0)` về số nội địa với `PHONE_TRUNK_NOTATION_NORMALIZED`. Mã nước khác giữ dấu cộng, mã nước và chữ số theo quy tắc hiện có, với `PHONE_TRUNK_NOTATION_KEPT`. |
| D · Tóm tắt | Tách đoạn theo heading, nối các section theo thứ tự đọc bằng một dòng trống và giữ evidence từng đoạn với `SUMMARY_MULTI_SECTION`. Một section dùng cách xử lý 3.1. |
| E · Liên hệ không nhãn | Xét riêng từng field khi grammar chưa tạo candidate từ nhãn hoặc vùng cá nhân/liên hệ. Loại vùng tham chiếu, tuyển dụng và công ty. Một giá trị hợp lệ khác biệt có mặt ở trang 1 được đề xuất với `UNLABELED_SINGLE_VALUE`. Nhiều giá trị khác biệt trả `AMBIGUOUS` cùng alternatives. |

Fallback không nhãn chỉ được bật cho field có priority `SECTION_CONTEXT`.

Kiểm chứng bằng dữ liệu tổng hợp. So sánh SHA-256 kết quả đã tuần tự hóa của 3.0 và 3.1 với
baseline trước khi sửa trên cùng đầu vào. Kiểm tra tích hợp bao phủ routing, replay và provenance
khi dùng PostgreSQL 17 cô lập. Xem kết quả kiểm chứng trong Draft PR.
