# OCR quality evaluation/improvement — backlog riêng

Trạng thái baseline/kiến trúc/lệnh hiện hành: [handoff](M6_BASELINE_HANDOFF.md) và
[PR9 revision](M6_PR9_REVISION.md). Backlog này không là technical evidence.

Quyết định developer2026-10-21: corpus, ground truth, CER/WER và tuning chưa thuộc
nghiệm thu M6 baseline PDF → text theo trang. **OCR accuracy chưa đánh giá.**
Backlog này ghi việc cần làm khi có yêu cầu riêng, không tự cho phép đọc CV thật,
dữ liệu D:, gửi dữ liệu ra ngoài hoặc triển khai model/pipeline mới.

| ID | Công việc tương lai | Evidence đầu ra khi được giao |
|---|---|---|
| Q1 | Chốt dữ liệu được phép dùng, privacy/retention, nhóm EN/VI/mixed/native/scan/layout khó và split document calibration/held-out | Corpus inventory private với quyền sử dụng, coverage, không PII trong Git/log |
| Q2 | Tạo/kiểm tra ground truth độc lập từ trang hiển thị, ghi phần unreadable và thứ tự block | Annotation policy/version, adjudication và quality checks; không lấy OCR output làm đáp án |
| Q3 | Đo CER/WER giữ dấu Việt, native/scan và từng strata; đo blank/reading-order/contact occurrence khi được yêu cầu | Numerator/denominator, failures/outliers và breakdown, không chỉ average |
| Q4 | Chốt tolerance/ngưỡng với developer trước held-out run | Tiêu chí được duyệt; các target M6.1 trước đây là đề xuất lịch sử |
| Q5 | Tuning/A-B có kiểm soát: native usability, image coverage, layout, orientation, model/profile | So sánh accuracy/latency/resources trên cùng split/pins; không âm thầm thay baseline |
| Q6 | Reverify sau thay source/config/model đã duyệt | Technical regressions theo ảnh hưởng, full gate khi source thay đổi, hash/provenance và quality report riêng |

Không item nào được coi là đã đo/đạt. Các câu chữ mẫu được nhận ra trong synthetic
tests chỉ xác minh đường xử lý, không phải corpus accuracy evaluation. Tesseract
mean-word-confidence cũng không thay thế CER/WER hay human ground truth.

GCC/Tesseract unresolved được theo dõi tại [GCC investigation](M6_2_GCC_INVESTIGATION.md)
như blocker production độc lập, không chuyển nó thành quality backlog để bỏ qua.
Không ấn định milestone hoặc bắt đầu M7 qua tài liệu này.
