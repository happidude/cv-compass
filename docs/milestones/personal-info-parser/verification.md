# Kiểm chứng lát cắt 3 - Parser

- Developer giao tiếp tục trong chat ngày 2026-11-03; PR #25 đã merge trước khi bắt đầu.
  Base main `69930b951659c56e709a5f24757c2012de5f3731`, nhánh `feature/personal-info-parser`.
  Mức 3: Implementer → Tester → Reviewer chỉ đọc, tối đa 3 vòng; mỗi lát cắt một PR.
- Phạm vi AC-8..AC-12, AC-25, R-1..R-7 và Q2/Q3; 7 file Java, một fixture tổng hợp.
  Không đọc CV/data, không sửa publication/UI/config/migration; giữ hai file riêng của developer.
- Parser phân vùng, thu candidates/evidence, validate và chọn hoặc abstain cho sáu field.
  Evidence code-point offsets kiểm được với raw OCR; không sửa raw nguồn. Gender chỉ từ nhãn R-7;
  giá trị ngoài danh sách giữ trong alternatives với normalized null và issue, không selected khi INVALID.
  fullName bỏ danh xưng. Location giữ dấu câu. Summary qua trang chưa chắc thì AMBIGUOUS.
- Experience v3 tái dùng engine v2; integration đối chiếu kết quả và bất biến raw OCR.
  Test đăng ký và tạo job v3 bằng fixture SQL trong schema riêng để process/complete/replay;
  activate/enqueue/claim vận hành vẫn chặn v3, chưa mở publication trước lát cắt 4.
- Test-first FAIL đúng vì thiếu engine. Tester phát hiện PIP-T1 (chức vụ thành tên) và
  PIP-T2 (khoảng năm thành phone); Implementer sửa validation, giữ nguyên hai regression, đã PASS.
- Affected cuối Implementer 58 PASS. Tester bổ sung acceptance: 6 field tắt, invalid alternative,
  bảy danh xưng, evidence CRLF/astral Unicode. Parser 30, experience 13, mapping integration 24 PASS.
- Full gate cuối: `spotlessApply` rồi `check --rerun-tasks --console=plain --warning-mode=all`:
  **471 test, 59 lớp, 0 failure/error/skip**, gồm Modulith; 0 compile warning, 7 task thực chạy, 1m46s.
  Java 25/PostgreSQL 17 cổng15439, schema/storage cô lập; không DB dev/drop/reset.
  Runtime dùng baseline YAML sanitized và process-only import/UTC/socket như lát cắt 2.
  Integration đầu lỗi vì container dừng; đã start đúng container test rồi chạy lại; log lỗi đầu đã bị
  ghi đè, chỉ transcript còn bằng chứng, không khẳng định có artifact lỗi đó.
- Bằng chứng ignored dưới backend/build/reports/: personal-info-parser-test-first.log,
  personal-info-parser-tester-regression.log, personal-info-parser-tester-r2-full.log,
  personal-info-parser-tester-r2-spotless.log, personal-info-parser-tester-r2-full-xml/,
  personal-info-parser-tester-r2-final-summary.json, personal-info-parser-tester-r2-final-sha256.txt.
  Fingerprint 8 file không đổi sau full gate; manifest SHA256
  `d56912142620291eedfd5f6420acfa341c68d77342a2d9dd1814ef010be38fae`.
- Reviewer vòng 1: PIP-R1 heading thành tên, PIP-R2 inline summary bị cắt, PIP-R3 phone mở ngoặc bị bỏ qua. Ba regression FAIL trước sửa, sau sửa PASS; Tester thêm kiểm ownership reference/recruiter/company và NFD/fullwidth colon. Full gate 466 cũ giữ riêng; gate 471 là source cuối.
- Reviewer/vòng/source, link/anchor và CI head cuối ghi trong PR; pending không PASS.
  Secret scanner NOT VERIFIED do thiếu công cụ; accuracy trên CV thật NOT VERIFIED, không báo accuracy.
  Worker/frontend N/A vì không đổi. PR Draft, con người nghiệm thu; không agent merge/Ready.

---
# Kiểm chứng lát cắt 2 - Config và version

- Developer giao ngày 2026-11-01; chỉ [AC-4..AC-7](spec.md#lát-cắt-2--config-và-version),
  Q3/Q4, R-7 và quyết định bổ sung đã duyệt. PR #24 đã merge; base main sau fetch
  `940802c418f3072fcc274defc7e975f7752a85b0`, nhánh `feature/personal-info-config-version`.
- Commit đầu `458bfc5` ghi bốn quyết định và shape gender, không đổi AC/R. Developer duyệt
  tăng từ 10 lên **12 file code/test/config/migration** để cập nhật hai test migration ghim version/count.
  Thêm spec/verification; giữ application.yaml modified và experience-config-v2.json untracked của developer.
- Mức 3 (version/contract/migration), tối đa 3 vòng: Implementer → Tester → Reviewer chỉ đọc.
  Chưa nghiệm thu thay con người; source/vòng/finding và CI head cuối ghi trong PR.
  Không đổi parser/publication/UI/proposal, DTO experience v2, CI, formatter/dependency hoặc Flyway cũ;
  không đọc thư mục dữ liệu/CV, private env hay file import local. Không bắt đầu lát cắt 3.

## Validate, restart và giới hạn v3

[JSON mẫu v3 tổng hợp](../../../backend/src/main/resources/mapping/personal-info-config-v3.json)
đủ sáu field, labels Việt-Anh, sectionHeadings, excludedRegions và priority hữu hạn; nhúng
ExperienceDefinition v2. Gender chỉ dùng bốn nhãn R-7 và EXPLICIT_LABEL. Không có regex/script/SQL
thực thi. Revision JSON phải khớp revision register và không tái dùng cho nội dung khác.

Từ backend với Java 25, validate mẫu qua unit test:

```powershell
.\gradlew.bat test --tests com.cvcompass.backend.mapping.dto.MappingVersionsTests
```

File operator riêng được trusted Java/test harness gọi
`MappingVersions.config(Files.readString(path), objectMapper).validate()`; không có endpoint/CLI admin mới.
V3 chỉ đọc/validate/register; activate báo MAPPING_V3_ACTIVATION_NOT_AVAILABLE, enqueue báo
MAPPING_V3_ENQUEUE_NOT_AVAILABLE ở service/repository, không đổi active config hoặc tạo job v3.
Không trỏ bootstrap vận hành vào v3. Khi lát cắt 3 được giao mới thử process/activate trong schema/storage
cô lập; activate vận hành chờ lát cắt 4. Chốt chặn của lát cắt này chưa mở cho cả test lẫn vận hành.

Restart v1/v2 giữ config ID khi cùng revision/nội dung; thay nội dung cần revision mới. Reader/claim/process
v1/v2 không đổi, không viết lại result/digest hoặc nâng job cũ. Map v3 sắp thứ tự ổn định qua restart.
Bật mapping thiếu definition/revision báo MAPPING_DEFINITION_REQUIRED/MAPPING_REVISION_REQUIRED;
scan thiếu active config báo MAPPING_ACTIVE_CONFIG_REQUIRED. Lỗi cấu hình chung giữ message legacy
an toàn, log chỉ code MAPPING_CONFIG_INVALID, không cause/body/path/exception message tự do.

## Local trên payload cuối

- Test-first compile FAIL đúng vì thiếu DTO/constructor mới. Affected cuối **72 test PASS**;
  full gate **440 test, 58 lớp, 0 failure/error/skip**, gồm Modulith; Java 25.0.4.1/PostgreSQL 17.
  `spotlessApply` rồi `check --rerun-tasks --console=plain --warning-mode=all`: 7 task thực chạy,
  2m15s; không compile warning. Gradle chỉ gợi ý configuration cache, không đổi config để che.
- DB dùng một lần riêng cổng 15439; mỗi lớp sở hữu schema/storage test. Không dùng DB dev/default URL,
  không reset/drop DB có sẵn. Docker ban đầu chưa sẵn, đã khởi động bản cài hiện có và provision test.
- Runtime cuối dùng application.yaml từ Git base làm fixture trong build ignored, bỏ duy nhất optional
  spring.config.import; mọi dòng khác giữ nguyên byte, không đọc import target. Giữ mặc định feature
  và cấu hình riêng từng test, không dùng blanket feature overrides làm vô hiệu hóa test explicit true.
  JAVA_HOME/PATH/socket/UTC/config location chỉ đặt trong tiến trình kiểm thử.
- CFG-T1: Bootstrap đổi thông báo legacy; Implementer khôi phục message cố định, giữ diagnostic mới.
  CFG-T2: Tester cập nhật hai test migration theo phạm vi 12 đã được duyệt; giữ toàn bộ assertion khác.
  Hai finding đã kiểm chứng bằng affected/full gate; Reviewer kiểm độc lập trên source cuối.
- Các lần full gate lỗi trước được giữ riêng: runtime kế thừa bật OCR; lần blanket override sau đó
  ảnh hưởng fixture M11/OCR. Đã thay bằng fixture Git base và chạy lại toàn bộ, không skip/giảm assertion.
  Một lỗi CK_OCR_ATTEMPT_TIMES ban đầu không tái diễn ở affected 72/full 440; không sửa M11 ngoài phạm vi.
- Log/XML/fingerprint mới: backend/build/reports/personal-info-config-version/ (ignored), gồm
  tester-full-baseline.log, tester-spotless-baseline-final.log, tester-full-baseline-xml/,
  tester-final-summary.json, runtime-fixture-audit.json, tester-final-code-sha256.txt.
  Hash 12 file code không đổi sau full gate; manifest SHA256
  `406f2025f91a35b3f25a3c9d2a6bf689020c5c89cf829e77a3272447fcc75b7b`.
- Link/anchor, frozen 35 khối AC/R, diff/stage/fingerprint docs, CI đúng head và Reviewer ghi ở PR.
  CI pending không PASS; PR Draft, không Ready/merge. Worker/frontend N/A vì không đổi component.
  Secret scanner NOT VERIFIED: gitleaks/detect-secrets/trufflehog không có trong môi trường; không cài mới.

## Open Questions của PR

- Đề xuất thêm bước CI chạy unittest backend/tools vì Persistence CI chưa chạy suite đó;
  khuyến nghị task riêng duyệt chính xác workflow path/ý định trước khi sửa. PR này không sửa CI.
- Patch/no-op/provenance khi mutation/publication vẫn chờ quyết định ở phần phụ thuộc về sau;
  lát cắt này chỉ config/result reader, không triển khai mutation/publication.

---

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

- Test-first: 3 test gender mới ban đầu FAIL vì thiếu field/fixture/implementation; 17 test cũ giữ nguyên; thêm regression GENDER-1 sau vòng 1.
  Test CLI gặp quyền temp sandbox ở lượt đầu, đã chạy lại với quyền exec phù hợp, không đổi assertion.
- Local: Python 3.14.6 hiện có (đúng dòng runtime repo); `python -B -m unittest discover -s tools -p test_evaluate_personal_info.py`:
  **21 test PASS**, 0 failures/errors/skips. Chỉ synthetic; không đọc dữ liệu private trong suite.
  Kiểm nhãn rõ và quy tắc so raw/normalized; prediction sai normalized/null sai tính FP/FN,
  gold sai semantics vẫn bị từ chối, không tự sửa prediction; danh xưng không nhãn là FP khi prediction đoán;
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

Vòng 1 trên b4931f49: GENDER-1 Medium — prediction normalized sai làm reject toàn report.
Primary sửa comparator để tính FP/FN, giữ validation gold và thêm regression; vòng 2 kiểm delta
và finding trên head mới, kết quả tại PR.
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
