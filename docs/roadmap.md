# Roadmap

Developer xác nhận milestone **parser thông tin cá nhân Active** trong chat ngày 2026-10-31,
phạm vi theo [spec đã duyệt](milestones/personal-info-parser/spec.md).
Xác nhận này không ghi hoàn thành, merge hay lát cắt nào đã làm; trạng thái các milestone khác giữ nguyên.
File source, tên branch và số liệu PASS lịch sử không phải phê duyệt.

| Hạng mục | Bằng chứng có sẵn | Phần chưa xác nhận |
| --- | --- | --- |
| Tiếp nhận POC/database | [Bản nháp nền tảng](POC_CV_INGESTION_DATABASE_FOUNDATION.md), [tham chiếu backend](../backend/README.md) | Nghiệm thu/đóng hiện tại và phạm vi còn lại |
| Tích hợp OCR/raw text | [Bản đồ contract](contracts/README.md), [README worker](../worker/README.md) | Trạng thái milestone hiện tại; phê duyệt production/accuracy |
| Mapping | [tham chiếu mapping đã loại dữ liệu riêng tư](specs/mapping-design-reference.md), [tham chiếu mapping](../backend/MAPPING.md) | Nguồn phê duyệt thiết kế và trạng thái đóng milestone hiện tại |
| Ứng viên/review | [Tài liệu M11](../backend/M11.md), [report](../backend/M11_REPORT.md) | Nghiệm thu/đóng hiện tại và phạm vi tiếp theo |
| Parser thông tin cá nhân | [Spec đã duyệt](milestones/personal-info-parser/spec.md); developer xác nhận **Active** trong chat ngày 2026-10-31 | Chưa ghi nhận hoàn thành hay lát cắt nào đã làm |
| Bảo trì workflow agent | [Spec task đã duyệt](milestones/agent-workflow/spec.md) | Bằng chứng bàn giao riêng; không phải milestone ứng dụng |

Đọc [quy tắc danh mục milestone](milestones/README.md) trước khi thêm mục.
Con người phải xác định spec đã duyệt, bằng chứng trạng thái và branch/worktree
trước khi bắt đầu milestone tương lai. Không ngụ ý số thứ tự mới hoặc quyền triển khai.

Kế hoạch branch/worktree cũ là [lịch sử](history/legacy-milestone-plan.md);
không được dùng để chọn task Active.
