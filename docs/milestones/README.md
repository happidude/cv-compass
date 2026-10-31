# Danh mục milestone và task

Một thư mục chứa `spec.md` và `verification.md`; bắt đầu từ mẫu
[spec](../templates/spec.md) và [kiểm chứng](../templates/verification.md).
Task bảo trì không cần gán số milestone ứng dụng.

| Task | Bằng chứng duyệt spec | Kiểm chứng |
| --- | --- | --- |
| Bảo trì workflow agent | [Spec](agent-workflow/spec.md): task rõ ràng của developer, 2026-10-31 | [Bằng chứng](agent-workflow/verification.md) |
| Parser thông tin cá nhân | [Spec](personal-info-parser/spec.md): task docs trong chat, 2026-10-31; quyết định Q1–Q5 đã duyệt; **Active** theo xác nhận của developer trong chat ngày 2026-10-31 | [Bằng chứng docs](personal-info-parser/verification.md) |

Không tạo nhãn Active/hoàn thành từ source có sẵn, log cũ, tên branch hoặc PR tồn tại.
Chỉ ghi trạng thái khi developer xác nhận và có bằng chứng spec/PR/commit hỗ trợ.
Phê duyệt và nghiệm thu là hai sự kiện khác nhau. [Roadmap](../roadmap.md)
ghi milestone parser thông tin cá nhân Active theo xác nhận trên; trạng thái các milestone khác giữ nguyên.
Không đưa lịch sử và kiểm chứng từng milestone vào AGENTS.md.
