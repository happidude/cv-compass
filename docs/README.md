# Mục lục tài liệu

Quyền track/ignore theo [root](../AGENTS.md). Phân loại theo nội dung, không di chuyển theo tên.
Mặc định đọc root + AGENTS component + spec; nguồn kích hoạt chi tiết tại root/component.

| Khi cần | Nguồn tra cứu |
| --- | --- |
| Chọn mức/handoff | [Workflow](AGENT_WORKFLOW.md) |
| Xác định phạm vi cần duyệt, sửa kiến trúc/dữ liệu | [Policy tham chiếu](AGENT_POLICY.md) |
| Branch/commit/PR | [Git](CV_COMPASS_GIT_WORKFLOW.md) |
| Chọn/chạy check | [Kiểm chứng](VERIFICATION.md) |
| Task tối ưu workflow hiện tại | [Spec](milestones/agent-workflow-lean/spec.md), [bằng chứng](milestones/agent-workflow-lean/verification.md) |

| Loại | Nguồn | Cách dùng |
| --- | --- | --- |
| Quy tắc | [Root](../AGENTS.md), AGENTS component, [policy](AGENT_POLICY.md), [workflow](AGENT_WORKFLOW.md) | Yêu cầu hiện hành về phạm vi/quyền/vai trò |
| Convention | [Mục lục convention](conventions/README.md), convention backend/worker/frontend | Quy tắc implementation chi tiết |
| Contract | [Bản đồ contract](contracts/README.md); canonical JSON trong worker/design; backend MAPPING/experience/M11 | Đọc trước tích hợp API/dữ liệu; không di chuyển runtime artifact |
| Vận hành | README component; backend [OCR cục bộ](../backend/LOCAL_OCR.md), [raw-text cục bộ](../backend/RAW_TEXT_V2_LOCAL.md), [logging](../backend/LOGGING.md), [vận hành mapping](../backend/MAPPING_OPERATIONS.md); worker [handoff](../worker/design/M6_BASELINE_HANDOFF.md) | Quy trình chạy/khôi phục; giữ cảnh báo lịch sử rõ ràng |
| Spec milestone/task | [Danh mục](milestones/README.md), [roadmap](roadmap.md), [bản nháp nền tảng POC](POC_CV_INGESTION_DATABASE_FOUNDATION.md), [tham chiếu mapping đã loại dữ liệu riêng tư](specs/mapping-design-reference.md); backend MAPPING/M11; worker M6_1_OCR_WORKER_DESIGN/M7_INTEGRATION/OCR_V2_RAW_TEXT | Giữ nhãn trạng thái có sẵn; không suy ra Active/hoàn thành |
| Kiểm chứng | [Quy trình](VERIFICATION.md), backend *_REPORT / *_VERIFICATION và FOUNDATION_CONFIGURATION_REPORT; frontend VERIFICATION; worker M6_2_VERIFICATION/M6_2_REVIEW/M6_2_HARDENING_REPORT/M6_2_HARDENING_REVIEW và bản ghi PR9; [report format](conventions/REFACTOR_REPORT.md) | Bằng chứng theo commit, không phải nghiệm thu hiện tại |
| Lịch sử | [Mục lục lịch sử](history/README.md); worker M6_1_REVIEW/M6_1_STACK_SPIKE/M6_2_GCC_INVESTIGATION/M6_2_HARDENING_DECISIONS và banner bị thay thế; bằng chứng lịch sử đã tách của backend | Giữ nguồn gốc, không bao giờ cấp quyền task hiện tại |
| Tương lai/backlog | worker/design/OCR_QUALITY_BACKLOG.md, NATIVE_TEXT_QUALITY.md | Đề xuất cần phạm vi được duyệt rõ ràng |
| Template | [Spec](templates/spec.md), [kiểm chứng](templates/verification.md), [PR](../.github/pull_request_template.md) | Điểm bắt đầu tái sử dụng |

Khi đổi hướng dẫn hiện hành, tài liệu trộn nội dung được tách theo phần đã đọc:
quy trình Git tách kế hoạch milestone/worktree lỗi thời; mục lục convention tách
lịch sử khảo sát/áp dụng; hướng dẫn backend tách bằng chứng lịch sử riêng và roadmap cũ.
Các phần contract/vận hành còn dùng vẫn có đường dẫn truy cập.
MAPPING/M11 và thiết kế worker giữ đường dẫn hiện có vì contract và tham chiếu runtime
vẫn đang dùng; phân loại từng phần theo bảng trên, không archive cả file.

## Audit tham chiếu và lưu giữ

Ngày 2026-10-31 đã đọc đường dẫn source/tool và tham chiếu Markdown trước khi tách.
Không có code phụ thuộc hướng dẫn một agent hoặc kế hoạch Git đã bị thay thế.
Nội dung được giữ trong history, đường dẫn workflow cũ trỏ sang bản mới.
File convention chi tiết và runtime design/schema asset vẫn tại chỗ.
Report cũ có bằng chứng GCC/accuracy/cleanup chưa giải quyết vẫn truy cập được,
không bị di chuyển chỉ vì tên M6 có vẻ cũ.

File POC milestone/contract cũ và handoff M7/M8 chỉ có ở local còn thiếu được ghi
trong [roadmap](roadmap.md), [bản đồ contract](contracts/README.md) và lịch sử
đã tách như khoảng trống bằng chứng. Không bịa hoặc âm thầm chứng nhận chúng.
Artifact thiết kế XLSX/PNG/SVG/DOT và capture docs/api-tests hiện có được giữ local,
không publish mới: chưa kiểm chứng nội dung/quyền riêng tư/hiệu lực trong task này.

Bản nháp M10.1 local có nguồn từ khách hàng tiếp tục ignored; chỉ chia sẻ tham chiếu
đã loại dữ liệu riêng tư ở trên. Lần tổ chức tài liệu này không publish đoạn trích CV
hoặc metadata nguồn có thể định danh.
