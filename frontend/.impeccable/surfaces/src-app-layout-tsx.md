---
version: 1
slug: "src-app-layout-tsx"
primary_target: "src/app/layout.tsx"
related_targets: ["src/features"]
---

# App shell và các màn hình làm việc

Phạm vi: toàn bộ app sau đăng nhập (Tổng quan, Hồ sơ ứng viên, Quản lý CV, Upload, Lịch sử nhập) và màn đăng nhập. Mode: Operate.
Người dùng: 1–3 HR/admin, ban ngày, desktop/laptop. Việc: nhập CV, theo dõi OCR, review hồ sơ có bằng chứng.
Ràng buộc: không thêm dependency, giữ text/role mà test dựa vào, không hiện số liệu không đến từ API, chỉ giao diện sáng.
Module chưa có (Review queue, Tìm kiếm & xếp hạng, Xuất PDF) hiện mờ "Sắp có", không bấm được.

## Direction contract

THESIS: CV Compass đọc như một tờ bản đồ khảo sát được chú giải: mỗi CV là một tuyến đi qua các trạm xử lý, mỗi giá trị có toạ độ trong PDF gốc. Từ chối lưới card trắng + một màu tím nhấn của SaaS mặc định.
OWN-WORLD: rail trái màu mực khảo sát xanh lục đậm; nền giấy bản đồ xanh xám rất nhạt; bề mặt làm việc trắng; đường kẻ mảnh màu đồng mức; trạng thái theo ký hiệu bản đồ: xanh rừng = xong, đất son = đang/chú ý, đỏ khảo sát = lỗi, xám chì = chưa có. Inter, số tabular.
STORY: HR mở app, thấy ngay việc cần xử lý, đi theo tuyến của từng CV tới hồ sơ, đối chiếu từng giá trị bằng toạ độ trang/đoạn rồi xác nhận.
FIRST VIEWPORT: rail 240px (thương hiệu, nhóm Làm việc, nhóm Sắp có), thanh trên có breadcrumb và tài khoản; vùng chính: tiêu đề Tổng quan + nút Upload CV góc phải; khối "Cần xử lý" bên trái rộng (CV mới nhất kèm tuyến xử lý); cột phải: hồ sơ mới, batch gần đây.
FORM: Bản đồ khảo sát, ứng viên số 7 trong danh sách tự lập (theo roll). seed:5f5edfd3ae117401. Dấu ấn: "tuyến xử lý" (RouteLine: trạm Lưu file → OCR → Hồ sơ) và chip toạ độ evidence "Tr.3 · Đ.1".
FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance
