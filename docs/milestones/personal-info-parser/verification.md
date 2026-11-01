# Kiểm chứng lát cắt 1 — Gold và công cụ đo

- Spec: [AC-1..AC-3](spec.md#lát-cắt-1--gold-và-công-cụ-đo), mục Phê duyệt và Q5; developer giao
  riêng lát cắt 1 trong chat. PR #22 đã merge; base main mới nhất sau fetch:
  `22005c83ac630f5275071153da980d6216fc8864`.
- Nhánh `feature/personal-info-gold-tools`, working tree `E:/cv-compass`, cwd backend.
  Mức 2 theo yêu cầu developer; Primary kiêm Implementer là writer duy nhất, viết code/test/fixture;
  một Reviewer chỉ đọc sau affected checks, tối đa 2 vòng; không Tester riêng.
- Payload: 3 file Python tools, 3 JSON synthetic/schema và 2 docs trong thư mục milestone.
  Không thay parser/config/DB/UI/CI/roadmap/spec, không bắt đầu lát cắt khác.
- Giữ nguyên application.yaml modified và mapping/ untracked của developer, không stage/publish.

## Local

- Test-first: lần đầu test import FAIL vì implementation chưa có; sau triển khai chạy full suite
  của công cụ bằng Python 3.14.6 local: `python -B -m unittest discover -s tools -p test_evaluate_personal_info.py`.
  Kết quả cuối/số test/source ghi trong PR; bộ test chỉ dùng synthetic, không mở CV/gold thật.
- Test bao phủ comparator từng field, TP/FP/FN/abstain, sai chủ thể trùng value, disabled/unresolved
  denominator, accepted variants, exact document join, evidence nhiều span/code point/nguồn thiếu,
  breakdown support, DRAFT bị chặn, malformed/duplicate JSON, CLI không echo path/value/id.
- Smoke CLI trên synthetic-gold.json/synthetic-predictions.json; AST/JSON syntax checks.
  JSON Schema là tài liệu shape; Python stdlib validator kiểm runtime. Third-party schema checker
  NOT VERIFIED: bundled Python không có jsonschema, không cài dependency mới.
- Ruff NOT VERIFIED: không có trên PATH/các Python local/worker venv; tool venv WSL được convention
  ghi ở /tmp/cv-compass-conventions-tools không tồn tại. Không cài tool, không thay formatter config.
- Sandbox temp hạn chế làm một test CLI lỗi quyền ở lần chạy đầu; suite đã chạy thành công với quyền
  exec phù hợp chỉ cho synthetic tests. Không đổi assertion/bỏ test để xanh.
- Link/anchor của docs, diff-check, staged allowlist và fingerprint cuối được ghi trong PR.
  Backend full gate lấy từ CI head mới; local không chạy Java suite vì tool độc lập không đổi ứng dụng.
  Worker/frontend full suite N/A: không thay source/runtime của hai component này.

## Gold private và giới hạn

- Đã đối chiếu PDF hiển thị đầy đủ của 4 document (29 trang) trong đúng codex-test, không tìm CV nơi khác.
  Agent dùng Poppler sẵn có, chỉ render/ghi annotation vào annotations ngoài Git; không đọc held-out,
  không gọi OCR/network/ứng dụng hoặc gửi CV tới dịch vụ ngoài.
- Đã tạo 4 annotation-draft.json và bundle personal-info-gold-draft.json ở annotations, đều ghi
  `DRAFT - chưa được developer xác nhận` hoặc confirmation DRAFT; inventory/notes theo field nằm private.
  Gold không chắc được DISPUTED/UNANNOTATED, không suy từ tên file hoặc text layer bị che/khuất.
- Raw OCR/lỗi OCR NOT VERIFIED: thư mục mẫu chỉ có PDF, không có raw OCR để đối chiếu. Text layer PDF
  chỉ là đối chiếu riêng, không được coi là OCR hoặc ground truth. Không tạo OCR envelope giả.
- AC-2 developer xác nhận gold: NOT VERIFIED, chủ ý chờ developer kiểm nháp. Agent chưa chạy công cụ
  trên dữ liệu thật, chưa báo accuracy thật, không tune. Metrics smoke chỉ chứng minh logic synthetic.
- Hướng dẫn format/counts/chạy: [công cụ đo](gold-tools.md). Min-group-size mặc định 2 và file đo v1
  được đưa vào Open Questions PR để developer review; không là thay đổi contract ứng dụng/accuracy gate.

## Review và CI

GOLD-1 (Medium) vòng 1: chủ thể prediction không xác nhận được chủ thể từng evidence span. Primary bổ sung audit subject/support riêng từng span và test nhiều span lẫn đúng/sai/thiếu audit; vòng 2 kiểm delta và finding này.
Reviewer chỉ đọc toàn diff/base, test/helper/fixture và docs liên quan, không đọc private data,
không chạy lệnh ghi Git/format/build/test/publish. Kết luận/vòng/finding/source ghi trong PR.
CI phải đúng head mới sau push; pending không PASS. PR Draft/snapshot, không Ready/merge;
raw OCR và gold xác nhận chưa đủ bằng chứng, không kết luận hoàn thành acceptance dữ liệu hay accuracy.
