<a id="contract-map"></a>

# Bản đồ contract

Mục lục này xác định vị trí contract có sẵn; không thay đổi hoặc phê duyệt nội dung.

| Bề mặt | Nguồn hiện có | Lưu ý |
| --- | --- | --- |
| Điều phối OCR HTTP | [Tích hợp M7](../../worker/design/M7_INTEGRATION.md), DTO/controller/test OCR backend | Có lịch sử v1; đọc thêm phần mở rộng raw-text |
| Kết quả OCR v1/v2 | [Raw-text v2](../../worker/design/OCR_V2_RAW_TEXT.md), worker/design/ocr-v1.schema.json, ocr-v1.limits.json, ocr-v2.schema.json, ocr-v2.limits.json | Runtime asset canonical; giữ đường dẫn và byte parity với bản sao backend |
| Mapping | [Mapping](../../backend/MAPPING.md), [experience v2](../../backend/MAPPING_EXPERIENCE_V2.md) | Trộn implementation/spec; không tự chứng minh nghiệm thu milestone |
| Ứng viên/auth/public API | [M11](../../backend/M11.md), [hướng dẫn backend](../../backend/README.md), boundary schema/test frontend | M11 mô tả experienceSummary và data.countInTotal. Ví dụ public API trước đây có trước yêu cầu session/CSRF |

File từng được tham chiếu `docs/POC_OCR_API_CONTRACT.md` không có trong checkout này.
Không dựng lại contract đã duyệt từ giả định. Trước khi đổi tích hợp,
đối chiếu nguồn hiện có, shape/status/auth/test thực tế và phê duyệt của developer;
ghi khác biệt trong Open Questions của PR. Tham chiếu lịch sử đến file thiếu vẫn
là bằng chứng khoảng trống cũ, không phải dependency hiện hành cần đọc.
