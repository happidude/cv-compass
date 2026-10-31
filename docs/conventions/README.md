<a id="cv-compass-conventions"></a>

# Convention CV Compass

Convention component đã duyệt vẫn là tham chiếu implementation chi tiết:
[backend](backend.md), [worker](worker.md), [frontend](frontend.md).
Đọc convention của mọi component được sửa. Code mới bắt buộc tuân theo;
cụm “this pass/phase” nói về lượt format lịch sử, không cấp quyền bỏ quy tắc.

<a id="shared-requirements"></a>

## Yêu cầu dùng chung

Giữ runtime contract, SQL, tên serialize, thứ tự import có side effect/lazy,
hành vi transaction/retry và giá trị literal. Theo cấu trúc component hiện có;
không tạo layout layer toàn cục hoặc đổi tên API hàng loạt chỉ để thống nhất style.
Comment giải thích lý do không hiển nhiên và giữ directive của công cụ.
Chỉ dữ liệu tổng hợp; không log CV text, token, request body hoặc đường dẫn riêng tùy ý.
Không suy ra unused/dead code chỉ bằng tìm kiếm văn bản.

Formatter hiện có là nguồn chuẩn:
- Java: Spotless/google-java-format AOSP, 4 spaces/width 100.
- Python: Ruff formatter/linter, single quotes/width 100, hint public/boundary cho code mới.
- Frontend: Prettier, 2 spaces/double quotes/semicolons/width 80 và ESLint hiện có.

Không đổi cấu hình công cụ, dependency hoặc artifact loại trừ/canonical nếu chưa
duyệt phạm vi rõ ràng. Không blanket-fix lint hoặc normalize mọi file trong repo.
Tách thay đổi chỉ format khỏi hành vi. Áp dụng .editorconfig ở root và .gitattributes
theo phạm vi; OCR artifact canonical giữ nguyên định danh byte/hash.

<a id="workflow-and-provenance"></a>

## Workflow và nguồn gốc

[Workflow agent](../AGENT_WORKFLOW.md), [quy trình Git](../CV_COMPASS_GIT_WORKFLOW.md)
và [kiểm chứng](../VERIFICATION.md) hiện hành áp dụng cho task mới.
Khảo sát, inventory baseline, phần loại trừ mỗi lượt, kết quả lịch sử và phát biểu
ủy quyền cũ đã tách vào [lịch sử](../history/conventions-2026-10-26.md).
Bằng chứng áp dụng gốc vẫn trong [REFACTOR_REPORT.md](REFACTOR_REPORT.md);
không phải PASS hiện tại. File component giữ ví dụ theo ngữ cảnh và phần lịch sử
để diễn giải.
