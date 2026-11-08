# Engine personal-info-mapping/3.3

Chủ dự án yêu cầu trong chat ngày 08/11/2026 sau khi test 4 CV thật trên 3.2 (phần Thông tin cơ bản).
Mức 3 vì đổi logic trích xuất dữ liệu cá nhân, thêm field và routing theo engine đã ghim.
Config mẫu `personal-info-config-v3.3.json`, revision `personal-info-grammar-v33-r1`. Job 3.0–3.2
chạy tiếp theo config/engine đã ghim; kết quả của chúng không đổi. Operator cần đăng ký và kích hoạt
config 3.3 khi chủ dự án quyết định.

| Hạng mục | Tiêu chí chấp nhận |
| --- | --- |
| A · Ngày sinh | Field mới `dateOfBirth` (chỉ 3.3). Nhãn: Date of birth, DOB, Birthday, Ngày sinh, Năm sinh… Normalized giữ đúng độ chính xác đã viết: `YYYY`, `YYYY-MM`, `YYYY-MM-DD`; không tự điền ngày/tháng. Số dạng `dd/mm/yyyy` đọc ngày trước; nếu cả hai cách đọc đều hợp lệ thì gắn `DATE_OF_BIRTH_DAY_FIRST_ASSUMED`; chỉ đọc tháng trước khi bắt buộc (`DATE_OF_BIRTH_MONTH_FIRST`). Ngày không tồn tại → `INVALID_DATE_OF_BIRTH`. |
| B · Ngày sinh không nhãn | Một dòng chỉ chứa một ngày đầy đủ, năm 1940–2015, trong vùng cá nhân/liên hệ trang 1 → đề xuất với `UNLABELED_SINGLE_VALUE`; nhiều giá trị khác nhau → `AMBIGUOUS`. |
| C · Tỉnh/thành | Normalized của `location` là tên tỉnh/thành được nhắc **cuối cùng** trong địa chỉ (HCMC/HCM/TP.HCM/Ho Chi Minh City → Hồ Chí Minh, HN/Hanoi → Hà Nội…). Raw và evidence giữ nguyên địa chỉ đầy đủ. Không nhận ra tỉnh → giữ text đầy đủ + `LOCATION_PROVINCE_UNRECOGNIZED`. Danh mục gồm 63 tỉnh trước 2025 và tên mới (Huế); không tự quy đổi tỉnh cũ sang tỉnh sau sáp nhập. |
| D · Địa chỉ không nhãn | Dòng bắt đầu bằng số nhà, cách dòng email/điện thoại tối đa 4 dòng, trang 1, ngoài vùng tham chiếu/tuyển dụng/công ty; nối tối đa 3 dòng tới khi gặp tên tỉnh, phải có dấu phẩy. Giá trị dừng ngay sau tên tỉnh (cắt chữ của cột bên cạnh). Một giá trị → `UNLABELED_SINGLE_VALUE`. |
| E · Tóm tắt qua trang | Section tóm tắt đi tiếp sang trang sau khi dòng đầu trang sau là bullet cùng kiểu, hoặc câu đang viết dở (chữ thường, dòng trước chưa kết câu). Bỏ qua header/footer lặp. Kết quả gắn `SUMMARY_CROSS_PAGE`; các trường hợp khác giữ cách 3.2. |
| F · Tóm tắt không tiêu đề | Khi không có section tóm tắt: một đoạn văn xuôi duy nhất (≥ 2 dòng, ≥ 20 từ, kết thúc bằng dấu câu) ở đầu trang 1 trước mọi heading → đề xuất với `UNLABELED_SINGLE_VALUE`. |
| G · Họ tên | Dòng số điện thoại/ngày sinh đứng riêng cũng xác nhận vùng header (để nhận tên khi CV không có nhãn). Thêm từ chỉ chức danh (Supervisor, Specialist…) để dòng chức danh dưới tên không thành ứng viên tên. |
| H · Publication | Với kết quả 3.3, hồ sơ lưu `location` = tên tỉnh/thành và `dateOfBirth` = ngày ISO; nguồn đầy đủ nằm ở metadata field. Engine cũ giữ cách lưu raw. Bảo vệ USER/CONFIRMED/EDITED không đổi. |
| I · Tên hiển thị | Khi chưa có họ tên, danh sách/tổng quan/hồ sơ hiển thị tên file nguồn đầu tiên thay vì `Ứng viên #id`. API danh sách thêm `sourceFilename` (nullable). |

Kiểm chứng bằng dữ liệu tổng hợp (`PersonalInfoGrammarV33Tests`, integration routing/publication).
So sánh kết quả 3.1/3.2 trước và sau thay đổi trên cùng đầu vào. Không đưa CV thật vào repo.
