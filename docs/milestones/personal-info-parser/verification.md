# Kiểm chứng quyết định parser thông tin cá nhân

- Developer giao ghi quyết định Q1–Q5 trong chat tiếp nối PR #22; task hiện tại chỉ docs.
- Base main của PR: `6c5d64198cd18a49dbdb6aa86593c5dbcf9dfe85`; source trước lần sửa này:
  `fc40ca40e8bd1db528b29bbea0adb5141891898a`, local và origin đã đối chiếu bằng fetch.
- Nhánh giữ nguyên: `docs/personal-info-parser-plan`. Agent commit/push thường, giữ PR Draft.
- Workflow mức 2: Primary sửa → check cấu trúc → một Reviewer chỉ đọc; bộ đếm reset,
  tối đa 2 vòng mới. Reviewer không chạy lệnh ghi Git, build/format/test hay publish.
- Agent chỉ sửa spec.md, verification.md trong thư mục này và docs/milestones/README.md.
  Agent giữ nguyên application.yaml modified và mapping/ untracked của developer, không stage chúng.
- Agent không đọc CV/raw OCR/dữ liệu thật. Quyền B chỉ có hiệu lực từ lát cắt 1, không áp dụng PR docs.

## Local

- Agent đối chiếu nguyên văn AC-1..AC-23 và R-1..R-6 với source fc40ca4; chỉ AC-7 được phép
  đổi theo trình tự validate/read → test cô lập → activate vận hành đã developer duyệt.
- Agent kiểm link/anchor Markdown trên toàn ba file, cấu trúc bảng danh mục và đủ bảy lát cắt.
- Agent kiểm mục Quyết định đã duyệt không quá 40 dòng, xóa hai mục đề xuất cũ và mọi lựa chọn bị loại;
  Open Questions chỉ còn câu hỏi precedence tổng quát Q2. Chi tiết shape patch/no-op/provenance được
  đề xuất để developer duyệt ở đầu PR lát cắt 2/4/5 theo quy trình đã chốt, không là câu hỏi PR hiện tại.
- Kích thước spec trước: 35.540 byte / 382 dòng; sau: 34.069 byte / 343 dòng
  (UTF-8 local; số dòng tính bằng tách LF, gồm phần tử cuối sau newline; Git chuẩn hóa line ending).
- Dự toán file code không đổi: lát cắt 0..6 lần lượt 0/3/10/7/10/7/5.
  Migration mở CHECK nằm ở lát cắt 2; migration KIND riêng nằm ở lát cắt 4.
- Agent chạy `git diff --check`, `git diff --cached --check` và kiểm allowlist cả diff lần sửa này
  lẫn diff PR so với base main. App suite: N/A vì chỉ docs, không thay ứng dụng/dependency/runtime.
- Lệnh/kết quả thực tế, fingerprint payload, kết luận review và CI head mới được ghi trong PR.

## Review, CI và giới hạn

Primary chờ writer/check dừng trước khi giao Reviewer chỉ đọc; kết luận/vòng/finding theo source
được ghi trong PR. CI của head cũ fc40ca4 không chứng minh head mới; agent chờ CI mới và ghi run/head
thực tế trong PR, không tự tuyên bố PASS khi pending. Reviewer không được chạy git write-tree.

Developer đã chọn B và duyệt state/version/migration/comparator trong [quyết định](spec.md#quyết-định-đã-duyệt).
Developer còn cần chốt precedence tại [Open Questions](spec.md#open-questions).
Gold chưa được kiểm chứng ở task docs; accuracy, worker E2E và acceptance triển khai: NOT VERIFIED,
ngoài phạm vi. Agent không sửa roadmap, không đánh dấu Active, không Ready/merge và không bắt đầu lát cắt.
