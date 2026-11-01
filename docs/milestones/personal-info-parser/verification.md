# Kiểm chứng bổ sung gender — 2026-11-01

- Nguồn: developer duyệt thay đổi phạm vi trong chat ngày 2026-11-01; [R-7 và AC-24..AC-28](spec.md#bổ-sung-gender--phê-duyệt-ngày-2026-11-01).
  PR #23 đã merge vào main ngày 2026-11-01, merge SHA/base sau fetch
  `1f7e67b16f22dcf223de13fde97b95684c3a505f`.
- Nhánh `feature/personal-info-gender`; working tree `E:/cv-compass`, cwd backend.
  Mức 2 do developer chỉ định; Primary kiêm Implementer là writer duy nhất → affected checks
  → CI → Reviewer chỉ đọc, tối đa 2 vòng; không Tester riêng. Không bắt đầu lát cắt 2.
- Payload: spec và verification; helper/test công cụ đo; schema, hai fixture hiện có và fixture
  gender tổng hợp mới. `evaluate_personal_info.py` tự lấy danh sách FIELDS nên không cần sửa.
  Không đổi parser/config/DB/UI/CI; giữ application.yaml modified và mapping/ untracked của developer.

## Kiểm chứng hiện tại

- Test-first: 3 test gender mới ban đầu FAIL vì thiếu field/fixture/implementation; 17 test cũ giữ nguyên.
  Test CLI gặp quyền temp sandbox ở lượt đầu, đã chạy lại với quyền exec phù hợp, không đổi assertion.
- Local: Python 3.14.6 hiện có (đúng dòng runtime repo); `python -B -m unittest discover -s tools -p test_evaluate_personal_info.py`:
  **20 test PASS**, 0 failures/errors/skips. Chỉ synthetic; không đọc dữ liệu private trong suite.
  Kiểm nhãn rõ và quy tắc so raw/normalized; danh xưng không nhãn là FP khi prediction đoán;
  mâu thuẫn DISPUTED bị loại denominator đến khi phân xử; AMBIGUOUS sau phân xử vẫn FN;
  ngoài danh sách giữ raw/null/issue và abstain; missing/disabled/support, DRAFT và privacy.
- Smoke CLI synthetic, AST/JSON syntax, frozen R-1..R-6/AC-1..AC-23, link/anchor toàn bộ hai docs,
  diff-check/allowlist và fingerprint: kết quả source cuối trong PR.
- Gold/prediction đo v1 nay cần sáu field. Gender value `{raw, normalized}` chỉ là input offline;
  normalized null so raw chính xác; thiếu field bị từ chối an toàn. Các comparator Q5 cũ giữ nguyên.
  [Shape gender](spec.md#file-đo-offline-cho-gender-ac-24) là bổ sung cho hướng dẫn năm field lịch sử
  trong gold-tools.md; file hướng dẫn đó ngoài phạm vi sửa của task này.
- Ruff NOT VERIFIED: không có trên PATH/Python 3.14/bundled/worker venv; không cài tool hay đổi formatter config.
  JSON Schema validator bên thứ ba NOT VERIFIED: Python 3.14 và bundled thiếu jsonschema; chỉ kiểm syntax,
  refs nội bộ, input validation stdlib và unit test, không tuyên bố full JSON Schema validation.
- Backend full gate theo CI trên head mới sau push; pending không PASS. Local Java suite N/A vì
  chỉ công cụ Python offline và docs; worker/frontend suite N/A vì không đổi các component đó.
  Secret scanner native NOT VERIFIED nếu không có tool trong môi trường; kiểm payload không PII
  chỉ là kiểm bổ sung, không thay secret scanning.

## Gold private và support

- Đối chiếu ảnh toàn bộ 4 tài liệu/29 trang trong annotations với PDF đúng codex-test; render mới
  ba trang có nhãn để đối chiếu lại. Dùng skill PDF và Poppler offline; chỉ ghi ảnh trong annotations.
  Không đọc heldout; không gọi OCR/network/DB hoặc gửi nội dung CV tới dịch vụ ngoài khác.
- Thêm gender vào 4 annotation-draft.json hiện có và bundle personal-info-gold-draft.json;
  có trang nhãn và danh sách trang đã đối chiếu trong fieldNotes private. Ba tài liệu có nhãn rõ;
  tài liệu không nhãn là ANNOTATED với acceptedValues rỗng, không suy từ danh xưng.
  Tất cả vẫn DRAFT; kiểm prior fields/status không đổi trước/sau ghi.
- Bản annotation có documentKey `private-draft-03` **tồn tại** trong wrapper draft-03 và bundle;
  không nhầm documentKey với tên thư mục. Không tạo bản gold được xác nhận.
- Support tổng hợp không PII: **email 0/4, phone 0/4, gender có nhãn rõ 3/4**.
  Đánh giá email/phone trên CV thật là **N/A** vì không có giá trị rõ trong tài liệu mẫu.
  Gender có support nhưng accuracy vẫn **NOT VERIFIED**: gold chưa được developer xác nhận,
  chưa có prediction/parser gender. Không chạy công cụ trên gold thật hoặc suy accuracy từ synthetic.
- Raw OCR vẫn NOT VERIFIED: nguồn chỉ có PDF, text layer không là raw OCR hoặc ground truth.
  Gold private/ảnh/text không vào Git; PR chỉ báo số đếm tổng hợp.

## Review và bàn giao hiện tại

Reviewer chỉ đọc toàn diff/base, code/test/fixture/schema và docs liên quan sau writer/check dừng;
không đọc dữ liệu private, không format/build/test/ghi Git/publish. Source, vòng và finding ghi trong PR.
CI chờ đúng head mới; PR tiếng Việt Draft, không Ready, không merge. Open Questions tại spec/PR
về danh xưng, lưu/API, giá trị ngoài nhị phân và dấu câu location chưa được agent quyết định.
Ước lượng lát cắt 4 là 10–11 file tùy shape/migration gender; cần developer duyệt chia lại trước code.

---

# Lịch sử PR #23 — Gold và công cụ đo

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
