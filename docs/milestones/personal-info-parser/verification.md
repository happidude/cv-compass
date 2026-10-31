# Kiểm chứng kế hoạch parser thông tin cá nhân

- Nguồn: task docs của developer trong chat ngày 2026-10-31; bản spec đính kèm được giữ
  nguyên byte làm tiền tố của spec.md, kế hoạch chỉ nối phía sau.
- Base: `6c5d64198cd18a49dbdb6aa86593c5dbcf9dfe85`, origin/main sau fetch.
- Nhánh: `docs/personal-info-parser-plan`; mức 2 do developer yêu cầu, chỉ docs;
  Primary là writer duy nhất, một Reviewer chỉ đọc, tối đa 2 vòng.
- Phạm vi: spec.md, verification.md trong thư mục này và một dòng trong danh mục milestone.
  Không đổi roadmap, AGENTS, code hay CI; không đọc CV/raw OCR/dữ liệu thật.
- Thay đổi local có sẵn, ngoài payload: backend/src/main/resources/application.yaml và
  backend/src/main/resources/mapping/ chưa track. Giữ nguyên, không stage hay làm nguồn phê duyệt.

## Local

- Kiểm byte tiền tố spec so với attachment; kiểm AC-1..AC-23 và R-1..R-6 không đổi.
- Kiểm cấu trúc danh mục: dòng personal-info-parser nằm trong bảng, đúng 3 cột.
- Kiểm link/anchor Markdown trên toàn bộ ba file thay đổi; tham chiếu source trong kế hoạch là
  đường dẫn dự kiến, file mới ghi rõ. Không báo file dự kiến chưa tạo là link hỏng.
- `git diff --check` và `git diff --cached --check`; diff staged chỉ đúng ba đường dẫn được phép.
- App suite: N/A vì không đổi ứng dụng, dependency hay runtime config.
- Dấu vết lệnh, kết quả thực tế và fingerprint payload được ghi trong PR để tránh tự tham chiếu SHA.

## Review và CI

Primary chạy xong check trước khi bàn giao một Reviewer chỉ đọc; Reviewer không chạy tool ghi,
format/build/test, không publish. Kết luận/vòng/source và finding ghi trong PR.
CI phải kiểm đúng commit head sau push; link run/kết luận ghi trong PR sau khi chờ kết thúc.
Tài liệu này không tự tuyên bố CI PASS hoặc milestone hoàn thành.

## Hạn chế và quyết định chờ duyệt

Đối chiếu là đọc source/schema, không chạy DB/worker/frontend; accuracy, worker E2E và acceptance
triển khai: NOT VERIFIED vì ngoài task docs. Chưa có gold xác nhận, chưa chọn phương án dữ liệu.
Năm đề xuất và khoảng trống contract/sequencing trong [kế hoạch](spec.md#kế-hoạch-chi-tiết)
phải được developer duyệt trước code. Milestone chưa Active, không bắt đầu lát cắt sau PR này.