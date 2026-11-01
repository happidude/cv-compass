# Lát cắt 1 — Gold và công cụ đo

Nguồn: [spec, AC-1..AC-3](spec.md#lát-cắt-1--gold-và-công-cụ-đo),
[quy tắc đo Q5](spec.md#quyết-định-đã-duyệt). Base main sau merge PR #22:
`22005c83ac630f5275071153da980d6216fc8864`; nhánh `feature/personal-info-gold-tools`.
Công cụ chỉ đo file đã được annotation; không trích xuất CV, không gọi worker/DB/HTTP hay parser.

## Chạy local từ backend

Dùng Python 3.14 đã có, không thêm dependency. Nếu Python không nằm trong PATH, thay `python`
bằng đường dẫn interpreter của máy; không sửa cấu hình máy hoặc dùng credential.

```powershell
python -B -m unittest discover -s tools -p test_evaluate_personal_info.py
python -B tools/evaluate_personal_info.py --gold src/test/resources/mapping/personal-info/synthetic-gold.json --predictions src/test/resources/mapping/personal-info/synthetic-predictions.json
```

Lệnh smoke trên **chỉ dùng synthetic**. Không chạy công cụ trên gold thật ở lát cắt này.
JSON stdout chỉ chứa counters/ratio, tên năm field và label allowlist, không value, quote, documentKey,
đường dẫn hay issue tùy ý. Lỗi input/CLI trả code 2 và `EVALUATION_INPUT_REJECTED`, không traceback/body.
`--help` chỉ in hướng dẫn tham số. Không có tùy chọn network hoặc quét thư mục.

## File đo v1

[Schema annotation](../../../backend/src/test/resources/mapping/personal-info/annotation.schema.json),
[gold tổng hợp](../../../backend/src/test/resources/mapping/personal-info/synthetic-gold.json) và
[prediction tổng hợp](../../../backend/src/test/resources/mapping/personal-info/synthetic-predictions.json)
là ví dụ đầy đủ. Version 1 này chỉ của file đo offline, không đổi config/result ứng dụng.

- Gold có `schemaVersion: 1`, `confirmation: DRAFT|CONFIRMED`, `documents`.
  Công cụ từ chối DRAFT trước khi tính mọi metric. Chỉ developer xác nhận gold thật; agent không
  tự chuyển draft thành CONFIRMED. Fixture synthetic có CONFIRMED chỉ phục vụ test comparator.
- Mỗi document có opaque `documentKey`, `layout`, `language`, đủ năm `fields`.
  Field gold có `status: ANNOTATED|UNANNOTATED|DISPUTED` và `acceptedValues`.
  ANNOTATED + mảng rỗng là đã kiểm không có value; hai status còn lại có mảng rỗng và bị loại riêng,
  không tính như gold thiếu. Nhiều acceptedValues chỉ dùng khi developer đã duyệt biến thể trước đo.
- Layout chỉ nhận SINGLE_COLUMN/MULTI_COLUMN/TABLE/MIXED/UNKNOWN; ngôn ngữ VI/EN/MIXED/OTHER/UNKNOWN.
  Không đặt tên layout chứa tên công ty/người. Document key chỉ để join đúng document, không xuất report.
- Prediction có schemaVersion/documents; mỗi field có enabled/state/value/subject/alternatives/issues/evidence.
  State là PROPOSED/NOT_FOUND/AMBIGUOUS/INVALID. Chỉ PROPOSED có value; abstain phải null.
  Field tắt phải NOT_FOUND, null, enabled=false và có issue FIELD_DISABLED.
  Alternative chỉ là các value được đề xuất, không thành nhiều prediction.
- Subject CANDIDATE/REFERENCE/RECRUITER/COMPANY/UNKNOWN là annotation chủ thể của prediction
  được người đối chiếu xác định; không được coi lời tự nhận của parser là xác nhận đúng chủ thể.
  File đo không là DTO output của parser hay public API. Muốn đo sai chủ thể/evidence phải đối chiếu
  chủ thể thực tế trước khi tạo file prediction; công cụ không tự đọc/diễn giải CV để đoán chủ thể.
- Phone biểu diễn `{number, extension}`; number là raw phần số, extension là string hoặc null.
  Người annotation tách extension, không suy quốc gia. Comparator chỉ bỏ space/ngoặc/chấm/gạch ở number,
  giữ `+`, chữ số, đầu 0 và so extension chính xác. Không đổi 00 thành + hay ép +84.
- Các field khác là string: fullName/location dùng NFC + trim/gom whitespace, giữ dấu/hoa-thường/thứ tự;
  email trim ngoài, giữ local-part, lowercase domain; summary NFC + CRLF/CR→LF + trim biên,
  giữ nội dung/ngắt dòng bên trong. Canonicalization không viết lại dữ liệu nguồn.
- Source optional của gold là `{extractionId, pages:[{page, rawText}]}` từ raw OCR bất biến;
  evidence là mảng `{extractionId,page,start,end,text}` với [start,end) Unicode code points; mỗi span có audit riêng `subject` và `supportsValue` (boolean hoặc null chưa đối chiếu).
  Công cụ kiểm extraction/page/range/quote đúng, subject của chính span là CANDIDATE và supportsValue=true do người đối chiếu xác nhận; không lấy chủ thể prediction làm chủ thể mọi span và không suy tọa độ PDF.
  Không có source hoặc thiếu audit chủ thể/support của span thì evidence_unverifiable, không được báo valid. Span sai nguồn/quote, sai chủ thể hoặc supportsValue=false báo invalid. Người đối chiếu chịu trách nhiệm
  xác nhận chủ thể/evidence hỗ trợ value; exact span không thay phán quyết đó.
- Tool yêu cầu tập document key hai bên giống nhau, không duplicate JSON key/document key/page, đủ field và đúng type;
  không silently drop document thiếu prediction. Source text, PII và prediction thật chỉ lưu private.

## Counters và breakdown

Report có `fields` cho từng field và `breakdown.layout/language` với cùng counters:

| Counter | Ý nghĩa |
| --- | --- |
| documents/enabled/disabled | Support toàn bộ document, field bật/tắt; không giấu denominator khi tắt field. |
| excluded_gold/evaluated | Gold chưa phân xử hoặc thiếu annotation / số cặp document-field thực sự được đo (bật và ANNOTATED). Hai cờ disabled/excluded có thể cùng xảy ra, không cộng chúng để tính support. |
| gold/predictions | Số cặp có ít nhất một accepted value / số selected PROPOSED trên tập evaluated. |
| tp/fp/fn | Đúng value + chủ thể: TP; selected sai khi gold có: FP+FN; selected thừa: FP; abstain khi gold có: FN; cả hai trống: không TP/FP/FN. |
| precision/recall | TP/(TP+FP), TP/(TP+FN); mẫu số 0 là chuỗi N/A, không đặt pass threshold. |
| missing/ambiguous/invalid | Số field NOT_FOUND/AMBIGUOUS/INVALID trên tập evaluated. Disabled báo riêng. |
| wrong_reference/wrong_company/wrong_recruiter/unknown_subject | Selected value gán sai hoặc chưa biết chủ thể; đếm riêng, không cộng lặp FP. Trùng text reference vẫn sai chủ thể. |
| evidence_total/valid/invalid/unverifiable | Số span, không phải số document/value; tính trên field evaluated kể cả abstain. Valid đòi đúng nguồn/quote và audit chủ thể/support riêng từng span; chưa có raw OCR/audit báo unverifiable. |

Breakdown mặc định chỉ hiện nhóm có ít nhất **2 document**; tham số `--min-group-size` cho phép
nâng số này, tối thiểu 2. Đây là điều kiện hiển thị support, không là ngưỡng accuracy, không khẳng định
cỡ mẫu đủ thống kê hay cơ chế ẩn danh. Cỡ nhóm vẫn hiện trong report; nhóm nhỏ không xuất, tổng field
vẫn tính đủ. Min-group-size và format file đo là lựa chọn tool cần developer review trong PR.

## Gold nháp private

Agent đã xem PDF hiển thị trong đúng codex-test; mỗi bản nháp và inventory riêng nằm dưới
`D:\cv_compass\data\annotations\draft-NN\annotation-draft.json`; bundle đo bị khóa là
`D:\cv_compass\data\annotations\personal-info-gold-draft.json`, có confirmation DRAFT.
Mỗi wrapper ghi `DRAFT - chưa được developer xác nhận`, source path riêng, trang đã xem,
layout/ngôn ngữ/vùng liên hệ/reference/reading order, ghi chú theo field và vấn đề cần người xác nhận.
Text layer PDF được giữ riêng để đối chiếu, **không phải raw OCR hoặc ground truth**.
Không có raw OCR trong thư mục mẫu nên đối chiếu raw OCR/lỗi OCR là NOT VERIFIED; không sinh raw OCR giả.

Developer kiểm PDF + raw OCR khi có, xác nhận missing/disputed/unannotated, honorific/địa chỉ/ranh giới
summary và các biến thể được chấp nhận. Sau đó developer tạo file gold CONFIRMED riêng theo schema;
không sửa bản nháp thành bằng chứng đã xác nhận. Agent chưa chạy tool trên bundle private.
Held-out nằm ngoài thư mục mẫu và ngoài tầm đọc agent; developer tự chạy khi có gold đã xác nhận.
Không commit/link công khai annotation, native text layer, ảnh render, CV hay report thật.
