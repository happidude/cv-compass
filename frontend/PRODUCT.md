# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Một đến ba người dùng nội bộ: HR/recruiter và admin. Họ làm việc ban ngày trong văn phòng, trên
laptop hoặc màn hình desktop, thường mở PDF gốc song song với hồ sơ để đối chiếu.
Việc chính: nhập CV PDF theo batch, theo dõi OCR, rà soát và xác nhận hồ sơ ứng viên có bằng chứng.

## Product Purpose

CV Compass là hub nội bộ quản lý CV: nhận PDF (khoảng 100 CV/batch, tổng mục tiêu khoảng 100.000 CV),
tách text/OCR cục bộ, map thành hồ sơ ứng viên có evidence, để con người review khi chưa chắc chắn.
Thành công là HR biết ngay việc nào cần xử lý và tin được từng giá trị vì luôn thấy nguồn của nó.
Sản phẩm sẽ có thêm module (review queue, tìm kiếm và xếp hạng, xuất PDF), nên khung điều hướng phải
nhận module mới mà không phải dựng lại.

## Positioning

Mọi giá trị trên hồ sơ đều truy được về trang và đoạn text trong PDF gốc; dữ liệu chưa chắc chắn được
đánh dấu và chờ người xác nhận thay vì bị máy tự quyết.

## Operating Context

- Luồng: Upload CV/batch → OCR (bất đồng bộ, có retry) → mapping → hồ sơ ứng viên → review đề xuất.
- Các mốc tách biệt: OCR xong / mapping xong / trạng thái review / published.
- Chỉnh sửa của HR (USER/CONFIRMED/EDITED) không bị parser ghi đè; mọi thay đổi có lịch sử phiên bản.
- Giao diện tiếng Việt, múi giờ Asia/Ho_Chi_Minh.

## Capabilities and Constraints

- Đã có: đăng nhập phiên, upload đơn/batch, danh sách CV (cursor, không có tổng), chi tiết OCR theo
  trang, lịch sử batch (phân trang), hồ sơ ứng viên với thông tin cá nhân, kinh nghiệm, đề xuất,
  lịch sử thay đổi, xem PDF nguồn theo trang.
- Chưa có (lộ trình, chưa duyệt chính thức): Review queue (P2), Tìm kiếm & xếp hạng (P3),
  Version & xuất PDF (P4). Có thể hiện trên menu ở trạng thái "Sắp có", không bấm được.
- Backend chưa có API thống kê tổng: không hiển thị con số tổng hợp nào không đến từ API.
- Stack cố định: React + TypeScript strict, React Router, TanStack Query, Zod, Tailwind v4 + CSS semantic,
  lucide-react; không thêm dependency khi chưa duyệt.
- Không render OCR thành HTML; không lưu text cá nhân vào trình duyệt.

## Evidence on Hand

Không có dữ liệu ứng viên thật trong repo; mock và test chỉ dùng dữ liệu tổng hợp (synthetic).
Không có logo hay tài sản thương hiệu chính thức.

## Product Principles

- Nguồn trước kết luận: luôn cho thấy giá trị đến từ đâu và đang ở trạng thái xác minh nào.
- Việc cần làm lên trước: màn hình mở đầu chỉ ra cái gì đang lỗi, đang chờ, cần người xem.
- Không bịa, không tự gộp: thiếu căn cứ thì hiển thị là thiếu.
- Một khung, nhiều module: thêm tính năng mới là thêm một mục điều hướng và một feature folder.

## Accessibility & Inclusion

Giữ chuẩn hiện có: không lỗi axe ở 1440/1024/768 px, điều hướng bằng bàn phím, skip link, không cuộn ngang.
