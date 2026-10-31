<a id="verification"></a>

# Kiểm chứng <task hoặc milestone>

<a id="source-and-scope"></a>

## Source và phạm vi
- Spec/phê duyệt:
- Base và commit đã test/review chính xác hoặc fingerprint payload:
- File thay đổi và ánh xạ acceptance criterion → bằng chứng:
- Môi trường/version công cụ, resource cô lập (không credential/đường dẫn chứa dữ liệu CV):

<a id="executed-checks"></a>

## Check đã thực hiện
| Lệnh và thư mục làm việc | Source | Kết quả | Tests / failures / errors / skipped | Bằng chứng |
| --- | --- | --- | --- | --- |
| <lệnh thực tế> | <commit/fingerprint> | PASS / FAIL / NOT VERIFIED / N/A | <số liệu mới hoặc N/A> | <log/report, không dữ liệu riêng tư> |

## Review
- Số vòng (1–3), Reviewer, base/source, diff thực tế gồm file mới:
- Finding: ID/severity/file:line/ảnh hưởng/bằng chứng/cách sửa/cách xử lý:
- Warning đã sửa/còn lại, nguyên nhân/ảnh hưởng/lý do hoãn:
- Acceptance criteria đã đạt hoặc chưa giải quyết:

<a id="delivery-and-limitations"></a>

## Bàn giao và giới hạn
- Local gate:
- Kết quả CI và head mới chính xác (tách khỏi local và các lần bàn giao trước):
- Lệnh/event/kết quả native custom-agent dispatch (parser PASS là bằng chứng riêng):
- Lệnh/phạm vi/kết quả secret scanner hoặc NOT VERIFIED kèm bước tiếp theo:
- PR template chính đã track; bản nháp M10.1 riêng tư ignored và không nằm trong commit:
- Application/dependency/build/formatter/CI không đổi so với base PR khi có yêu cầu:
- Phần chưa kiểm chứng, lý do và bước tiếp theo:
- Nghiệm thu production/accuracy/dữ liệu thật (không bao giờ suy từ technical PASS):
- Link PR/commit:
- BLOCKED nếu sau vòng 3 còn vấn đề; không tự chấp nhận:

## Open Questions
- Vấn đề ngoài spec: bối cảnh, lựa chọn, khuyến nghị, phần phụ thuộc chưa thực hiện.
