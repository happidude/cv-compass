# Grammar 3.4

Engine tự chọn bằng cấu hình **personal-info-config-v3.4.json**, revision
**personal-info-grammar-v34-r1**. Khi kích hoạt, job mới dùng 3.4 và job đã xếp
hàng giữ engine đã ghim. Schema kết quả và cấu hình giữ nguyên.

Trong section kinh nghiệm hoặc tóm tắt kinh nghiệm đã nhận diện, parser dùng
một kỳ để neo dòng ngắn. Kỳ có thể ở đầu, ở cuối hoặc nằm trong ngoặc. Dấu
phân cách có thể tách chức danh, công ty và địa danh. Địa danh đứng riêng
trong một phần sẽ bị bỏ khi lấy tên công ty. Dấu gạch chỉ tách phần chữ khi
không có dấu gạch đứng hoặc gạch chéo ngoài ngoặc. Dòng công ty không ghi kỳ
cần kỳ hoặc chức danh liền kề nếu parser phải suy ra công ty. Địa chỉ và câu
mô tả dài có dấu phẩy không được nhận là công ty.

| Mẫu tổng hợp | Kết quả |
| --- | --- |
| Sales Manager / SAMPLE GROUP (Hose : SMP) Aug 2024 – Now | Giữ ngoặc trong tên công ty và đánh dấu kỳ đến nay |
| Regional Sales Manager (SEA) Sep 2025 - Jul 2026, dòng sau SAMPLE CORP \| Vietnam | Lấy công ty cùng trang từ dòng sau, bỏ quốc gia |
| 2020 – 2025 Sample Tea House hoặc 2018 – 2020: Sample Group | Nhận công ty neo theo kỳ dù thiếu hậu tố pháp lý |
| Sample Works – Long An ( 02/2019 - 10/2024 ) | Bỏ phần tỉnh, giữ kỳ tháng |
| Sample Works – Long An, dòng sau (December 2013 – April 2017) | Bỏ phần tỉnh và gắn kỳ đứng riêng vào công ty |
| Jun 2018 - May 2020, dòng sau Logistics Coordinator, rồi SAMPLE LOGISTICS \| Vietnam | Ghép kỳ, chức danh và công ty thành một kinh nghiệm |

Câu mô tả và phần học vấn không tạo kinh nghiệm từ quy tắc dòng tiêu đề.
Một chức danh ngắn có thể đứng ngay trước hoặc ngay sau tiêu đề công ty.
Dòng nhãn có bullet như **- Position:** và **• Chức danh:** cũng cung cấp
chức danh. Các chức danh có kỳ riêng ở cùng một công ty tạo các kinh nghiệm riêng.

Trong section ngữ cảnh như **Advisory Board / Share Holder** và **Other
Projects**, chỉ dòng tiêu đề hợp lệ mới tạo kinh nghiệm. Mỗi dòng như vậy có
issue **ROLE_CONTEXT_UNCLASSIFIED** để HR xem lại.
Digest replay của 3.3 dùng cùng input tổng hợp để kiểm tra tính tương thích với
kết quả trước khi thêm engine. Các test replay và đánh giá cũ tiếp tục áp dụng.
