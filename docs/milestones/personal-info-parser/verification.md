# Kiểm chứng chốt spec parser thông tin cá nhân

- Developer chốt spec, precedence Q2 và xác nhận milestone Active trong chat ngày 2026-10-31.
- Base main của PR: `6c5d64198cd18a49dbdb6aa86593c5dbcf9dfe85`; source lần chốt:
  `a5022d80f3fea9bf341b256fbb9269a7963fffa6`, local/origin khớp sau fetch.
- Nhánh giữ nguyên `docs/personal-info-parser-plan`; agent commit/push thường, giữ PR #22 Draft.
- Workflow mức 2, tối đa 2 vòng mới: Primary sửa → check cấu trúc → một Reviewer chỉ đọc.
  Reviewer không chạy lệnh ghi Git (kể cả write-tree), build/test/format hay publish.
- Phạm vi duy nhất: spec.md, verification.md trong thư mục này, docs/milestones/README.md,
  docs/roadmap.md. Agent giữ nguyên application.yaml modified và mapping/ untracked của developer.
- Agent không đọc CV/raw OCR/dữ liệu thật, không truy cập thư mục private hay held-out.

## Local

- Agent đối chiếu nguyên văn toàn bộ AC-1..AC-23 và R-1..R-6 với head a5022d8.
  AC-7 đã được chỉnh ở lần trước; task này giữ nguyên cả AC-7, không chỉnh acceptance/rule nào.
- Agent kiểm link/anchor trên toàn bốn file, cấu trúc bảng và đủ bảy lát cắt.
- Agent ghi ngày nguồn 2026-10-31, đường dẫn private/chủ CV bằng câu thường và đường dẫn held-out
  ngoài tầm đọc; agent chỉ giữ placeholder cơ sở cho phép đúng developer yêu cầu:
  `<ĐIỀN: mô tả cơ sở cho phép dùng CV>`. Agent không tự điền hoặc coi placeholder là bằng chứng đã có mô tả.
- Agent ghi sáu bước precedence đã duyệt, Open Questions là “Không còn câu hỏi mở”,
  xóa các câu cũ nói precedence còn chờ duyệt. Dự toán code vẫn 0/3/10/7/10/7/5.
- Agent ghi riêng parser thông tin cá nhân Active trong spec/roadmap/danh mục theo xác nhận chat;
  không ghi hoàn thành, lát cắt đã làm hay thay đổi trạng thái milestone khác.
- Agent chạy git diff --check, git diff --cached --check và kiểm allowlist cả diff lần này
  lẫn diff PR so với base main; chỉ bốn file docs được phép.
- Lệnh/kết quả thực tế, fingerprint payload, kết luận review và CI head mới được ghi trong PR.

## Review, CI và giới hạn

Primary chờ writer/check dừng trước khi giao một Reviewer chỉ đọc. Kết luận/vòng/finding và source
được ghi trong PR. Agent chờ CI của head mới, không dùng CI a5022d8 thay bằng chứng mới.
App suite local: N/A vì chỉ docs; triển khai/accuracy/worker E2E: NOT VERIFIED vì ngoài task.

[Spec và quyết định](spec.md#quyết-định-đã-duyệt) đã ghi precedence; [Open Questions](spec.md#open-questions)
không còn câu hỏi mở. Chi tiết shape patch/no-op/provenance vẫn theo quy trình trình duyệt ở đầu PR
lát cắt 2/4/5 đã chốt. Milestone Active không là nghiệm thu; agent không Ready/merge hay bắt đầu lát cắt.
