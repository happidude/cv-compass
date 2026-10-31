# Spec parser thông tin cá nhân

Đọc theo lát cắt: agent chỉ đọc mục "Phê duyệt", "Phạm vi", "Quy tắc parser" khi lát cắt cần,
và đúng mục acceptance của lát cắt được giao. Không đọc các lát cắt khác.

## Phê duyệt

- Nguồn: developer giao trong chat ngày `<YYYY-MM-DD>` (prompt "Triển khai parser thông tin cá nhân").
  Trạng thái milestone: chưa Active cho đến khi developer xác nhận trong [roadmap](../../roadmap.md).
- Base SHA và nhánh: ghi khi bắt đầu từng lát cắt (main mới nhất; tên nhánh theo
  [Git workflow](../../CV_COMPASS_GIT_WORKFLOW.md)). Mỗi lát cắt một nhánh, một PR.
- Quyền publish: commit, push nhánh được giao, mở PR Draft. Agent không bao giờ merge.
- Thay đổi local của developer được giữ nguyên: không reset, stash, discard, stage ngoài phạm vi.
- Database local là dữ liệu test nhưng không có quyền drop hay reset; kiểm thử dùng schema/storage cô lập.
- **Dữ liệu CV thật** (developer chọn MỘT phương án và xóa phương án còn lại):
  - (A) Chỉ dùng CV tổng hợp hoặc đã ẩn danh. Agent không đọc CV thật.
  - (B) Developer xác nhận có quyền dùng các CV mẫu tại `<đường dẫn private ngoài Git>`
    (chủ CV/cơ sở cho phép: `<...>`) và chấp nhận nội dung CV, raw OCR đi qua nhà cung cấp
    mô hình của agent. Agent chỉ đọc đúng thư mục đó, không quét nơi khác, không gửi tới dịch vụ
    ngoài nào khác, không commit hay ghi PII, raw OCR, screenshot, report chứa PII vào Git.
- **Gold và held-out:** gold do developer kiểm tra và xác nhận. Tập held-out tách theo document,
  lưu ngoài Git và ngoài tầm đọc của agent; developer tự chạy script đo trên held-out.

## Phạm vi

Trong phạm vi: backend, test, JSON cấu hình, tài liệu vận hành, frontend tối thiểu cần để xem,
sửa và đối chiếu thông tin mới. Pipeline: OCR → mapping → database → hồ sơ đối chiếu.

Field: `fullName`, `email`, `phone`, `location`, `summary`. Đây là thông tin của ứng viên,
không phải của người tham chiếu, recruiter, khách hàng hay công ty.

Ngoài phạm vi: search, ranking, export, AI/LLM/NER/embedding, thay OCR engine, ngày sinh,
giới tính, CCCD, tình trạng hôn nhân, đổi công nghệ persistence, refactor diện rộng.
Mapper chuyển đổi DTO của candidate đặt tại candidate/mapping theo yêu cầu developer;
không nhầm với module trích xuất OCR hiện có.

## Quy tắc parser

Parser không dùng regex toàn CV rồi lấy match đầu tiên. Quy trình: phân vùng → thu candidate
value và evidence → xác định chủ thể → validate, deduplicate, phát hiện mâu thuẫn → chọn đề xuất
khi đủ bằng chứng, nếu không thì trạng thái NOT_FOUND/AMBIGUOUS/INVALID (định nghĩa ở Open Questions).

- **R-1 Họ tên:** hỗ trợ nhãn Việt–Anh và tên không nhãn ở đầu CV khi đủ ngữ cảnh. Parser không mặc định
  dòng đầu là tên, không lấy chức vụ, tiêu đề CV hay tên reference, không suy tên từ email hay tên file,
  giữ dấu và thứ tự tên, không đảo tên hay sửa lỗi OCR thành tên khác.
- **R-2 Email:** nhận diện, validate và giữ nguyên raw. Parser không sửa O/0, I/1, không lowercase
  local-part mặc định, chỉ nối dòng khi có bằng chứng rõ (giữ evidence từng span), không tự chọn
  khi có nhiều email khác nhau mà thiếu căn cứ.
- **R-3 Điện thoại:** hỗ trợ `+`, ngoặc, khoảng trắng, chấm/gạch và extension. Parser giữ mã quốc gia,
  không ép về +84, không suy quốc gia khi thiếu căn cứ, không tự chuyển E.164 khi thiếu dữ liệu,
  không nhận ngày tháng hay mã hồ sơ thành phone, không sửa ký tự OCR.
- **R-4 Địa điểm:** phân biệt nơi ở của ứng viên với địa chỉ công ty, trường học, reference.
  Làm sạch định dạng có kiểm soát, không đoán tỉnh/thành/quốc gia.
- **R-5 Summary:** trích nguyên đoạn trong section được nhận diện, dừng đúng heading tiếp theo,
  không viết lại, tóm tắt hay ghép kinh nghiệm thành nội dung mới.
- **R-6 Chung:** nhận diện reference bằng heading/label/ngữ cảnh. Header/footer trùng giá trị được
  deduplicate và giữ evidence. Nhiều giá trị khác nhau phải giữ alternatives và lý do chọn/mơ hồ.
  Multi-column hoặc reading order không đáng tin phải được đánh dấu. Parser ưu tiên bỏ trống hoặc
  mơ hồ hơn gán thông tin sai người, và không diễn giải rule score thành xác suất.

## Mức workflow và lát cắt

Mỗi lát cắt: một nhánh, một PR, CI xanh độc lập, tuần tự. Phiên mới cho mỗi lát cắt.
Lát cắt 3 và 4 có thể gộp nếu diff nhỏ (quyết định ở lát cắt 0).

| # | Lát cắt | Mức | Vòng |
| --- | --- | --- | --- |
| 0 | Kế hoạch và đối chiếu spec với code (docs) | 2 | 2 |
| 1 | Gold và công cụ đo | 2 | 2 |
| 2 | Config và version | 3 | 3 |
| 3 | Parser | 3 | 3 |
| 4 | Publication và database | 3 | 3 |
| 5 | UI tối thiểu (nâng mức 3 nếu đổi contract) | 2 | 2 |
| 6 | E2E và đánh giá | 3 | 3 |

## Acceptance

### Lát cắt 1 — Gold và công cụ đo

- **AC-1** Script đo chạy local, không chứa PII, báo riêng từng field: số gold, số dự đoán, TP/FP/FN,
  precision/recall theo quy tắc so sánh đã chốt, số missing/ambiguous/invalid, số gán nhầm
  reference/company, số evidence hợp lệ, và breakdown theo layout/ngôn ngữ nếu đủ mẫu.
- **AC-2** Gold do developer xác nhận; held-out tách theo document, ngoài Git và ngoài tầm đọc của agent;
  annotation có PII chỉ ở vị trí private.
- **AC-3** Fixture tổng hợp phản ánh cấu trúc CV thật nhưng không sao chép PII; không hardcode theo
  tên file hay giá trị của CV mẫu.

### Lát cắt 2 — Config và version

- **AC-4** Config JSON gồm nhãn Việt–Anh, heading section, vùng loại trừ, bật/tắt từng field,
  quy tắc ưu tiên trong khả năng engine, revision và validation rõ ràng. Không cho regex, script
  hay SQL tùy ý.
- **AC-5** Thay đổi không tương thích dùng version mới. Config, result và job v1/v2 cũ vẫn đọc và
  process được; result cũ không bị đổi nghĩa hay ghi lại.
- **AC-6** Bật mapping nhưng thiếu definition/revision/active config thì có diagnostic an toàn,
  dễ hiểu cho operator; không log nội dung file, PII hay exception message tự do.
- **AC-7** Có JSON mẫu hoàn chỉnh (không công ty hay người thật) kèm hướng dẫn activate và restart.
  Nếu cần schema mới: kiểm tra kiểu dữ liệu hiện tại trước, chỉ thêm migration mới, không sửa Flyway đã áp dụng.

### Lát cắt 3 — Parser

- **AC-8** Phân vùng CV: đầu CV, liên hệ, summary, experience, education, reference, vùng loại trừ.
- **AC-9** Mỗi field biểu diễn raw, normalized (khi hợp lệ), state, alternatives, evidence, issues.
  Tái dùng cấu trúc hiện có nếu đủ semantics; không tạo DTO trùng.
- **AC-10** Evidence trỏ extraction/page/raw span, dùng đúng Unicode code-point offsets hiện hành.
  Mọi quote đối chiếu được với raw OCR bất biến; không bịa tọa độ PDF.
- **AC-11** Các quy tắc R-1 đến R-6 được kiểm bằng unit test theo checklist ở mục Kiểm chứng.
- **AC-12** Field thiếu, mơ hồ, invalid hoặc bị tắt không bị đoán; kết quả giữ nguyên nghĩa đã định nghĩa.

### Lát cắt 4 — Publication và database

- **AC-13** Lần đầu: personal info được đề xuất vào profile basic hiện có với state UNVERIFIED,
  origin MAPPING; cột lõi và JSONB đồng bộ trong transaction hiện tại.
- **AC-14** Có personal info hợp lệ nhưng không có experience vẫn tạo hồ sơ nháp. Không tạo profile
  rỗng khi không có gì dùng được, và lưu lý do xử lý rõ.
- **AC-15** Mapping lại không ghi đè USER/CONFIRMED/EDITED. Thay đổi personal info tạo proposal
  (accept/edit/reject) có nguồn document/result, không tái dùng semantics của experience proposal;
  remap không đổi gì không tạo proposal trùng.
- **AC-16** Chạy lại cùng result không tạo profile, proposal hay history trùng. Result cũ hoặc worker
  hoàn thành muộn không làm lùi dữ liệu. Field thiếu, invalid hoặc bị tắt không thành lệnh xóa dữ liệu cũ.
- **AC-17** Lưu audit actor/time/action/version/before/after; giữ expected-version conflict và
  transaction boundaries; PDF gốc, raw OCR, mapping result bất biến. Không gộp ứng viên theo
  tên/email/phone và không thêm uniqueness cho các field đó.
- **AC-18** Sửa thủ công không gắn evidence PDF cũ vào giá trị USER mới; confirm giữ giá trị và evidence
  hiện hành; rule score không tự xác nhận hồ sơ.

### Lát cắt 5 — UI tối thiểu

- **AC-19** Trên màn hình hồ sơ và PDF viewer hiện có: hiển thị personal fields, state, alternatives/issues
  cần thiết; xem nguồn đúng document/page/quote; sửa và xác nhận; review proposal accept/edit/reject.
- **AC-20** Version conflict hiển thị rõ và mutation không tự retry. Không render OCR thành HTML,
  không gửi dữ liệu ra ngoài, không redesign toàn bộ giao diện.

### Lát cắt 6 — E2E và đánh giá

- **AC-21** E2E: PDF tổng hợp → worker OCR thật → mapping → publication → database → UI/PDF đối chiếu.
  Không dùng OCR envelope giả để tuyên bố cả pipeline; integration dùng fixture và worker E2E báo riêng.
- **AC-22** Báo cáo đánh giá theo AC-1 trên gold đã xác nhận. Không tự đặt ngưỡng để tuyên bố đạt; số mẫu ít
  thì ghi rõ giới hạn; synthetic PASS không thay độ chính xác trên CV thật; CV thật chỉ chạy trong
  local mode đã được phép và không bypass production gate.
- **AC-23** Thiếu prerequisite (Docker/WSL, worker, mẫu): ghi NOT VERIFIED với lý do cụ thể.

## Kiểm chứng

- Test-first cho thay đổi hành vi; affected checks mỗi vòng; full gate trên source cuối hoặc CI theo
  [kiểm chứng](../../VERIFICATION.md). Không sửa formatter, bỏ test, giảm assertion hay suppress warning để xanh.
- **Unit parser (AC-11):** Việt/Anh; tên có dấu và Unicode tổ hợp; tên có/không nhãn; dòng đầu là chức vụ;
  liên hệ ứng viên cùng reference; nhiều email/phone giống và khác nhau; phone VN/quốc tế/không mã/extension;
  ngày và mã số không thành phone; email/phone bị OCR lỗi hoặc ngắt dòng; địa chỉ ứng viên/công ty/trường;
  summary đúng ranh giới; header/footer lặp và nhiều trang; sidebar/multi-column; field thiếu/mơ hồ/invalid;
  field bị tắt; evidence nhiều span và Unicode offsets.
- **Integration PostgreSQL (AC-13 đến AC-18, AC-5, AC-6):** chỉ personal info tạo profile; chỉ experience vẫn chạy;
  personal info cộng experience trong một transaction; không có field dùng được thì có kết quả xử lý rõ;
  mapping lại bảo vệ USER/CONFIRMED/EDITED; proposal accept/edit/reject và history; remap/replay không tạo trùng;
  stale result không làm lùi dữ liệu; version conflict và rollback; config/result/job cũ tương thích;
  không có active config thì có diagnostic; đổi revision và restart cho hành vi đúng.
- Báo cáo ngắn gồm baseline/head/PR, quyết định và hạn chế, hướng dẫn JSON/properties/start/restart,
  local và CI đúng source (tách riêng), metrics, giới hạn mẫu, finding còn mở, NOT VERIFIED.

## Open Questions

Cần developer duyệt ở lát cắt 0 trước khi code:

1. Chọn phương án dữ liệu CV (A hoặc B) ở mục Phê duyệt.
2. Định nghĩa chính xác NOT_FOUND, AMBIGUOUS, INVALID và quan hệ với state hiện có.
3. Quyết định version mới và cách tương thích config/result v1/v2.
4. Có cần migration không (agent kiểm tra kiểu dữ liệu hiện tại và đề xuất).
5. Gộp lát cắt 3 và 4 hay giữ tách; quy tắc so sánh TP/FP/FN cho từng field.
---

## Kế hoạch chi tiết

Phần trên đường phân cách được sao chép nguyên văn từ bản developer đính kèm; phần dưới là
kế hoạch và đề xuất chờ duyệt, không thay thế acceptance hay R-1..R-6, không kích hoạt milestone.
Nguồn giao task docs: chat ngày 2026-10-31; không điền ngày vào placeholder phê duyệt triển khai.
Base: `6c5d64198cd18a49dbdb6aa86593c5dbcf9dfe85` (`origin/main` sau fetch).
Nhánh docs: `docs/personal-info-parser-plan`; working tree `E:/cv-compass`, cwd `backend`.
Mức 2 do developer chỉ định cho docs; Primary viết → check cấu trúc → một Reviewer chỉ đọc,
tối đa hai vòng. Không bắt đầu lát cắt triển khai; mỗi lát cắt sau cần phiên/nhánh/PR riêng.

### Đối chiếu source tại base

Các đường dẫn dưới đây tính từ root repo; chỉ đọc source, không đọc database, PDF, raw OCR,
annotation private hay cấu hình runtime riêng. Không lấy thay đổi local của developer làm baseline.

| Bề mặt | Source hiện có | Khoảng trống cần xử lý theo acceptance |
| --- | --- | --- |
| Config v1/v2 | `backend/src/main/java/com/cvcompass/backend/mapping/dto/MappingVersions.java`, `MappingDefinition.java`, `ExperienceDefinition.java` trong cùng thư mục | v1 literal/FIRST_LINE; v2 chỉ rules/taxonomy experience, từ chối key lạ; chưa có config personal fields. Không đổi nghĩa FIRST_LINE thành parser họ tên. |
| Result/evidence | `backend/src/main/java/com/cvcompass/backend/mapping/dto/MappingResult.java`, `ExperienceMappingResult.java`, `MappingOutcome.java` | v1 Value chỉ field/value/evidence; v2 company/title/period có raw/state/evidence, alternatives tùy kiểu. Tái dùng Evidence và Issue; chưa có kiểu personal đủ raw/normalized/state/alternatives/issues. Evidence là [start,end) Unicode code points theo page raw_text. |
| Dispatch/job | `backend/src/main/java/com/cvcompass/backend/mapping/service/MappingService.java`, `mapping/repository/MappingRepository.java` | Service dispatch hai engine; claim SQL chỉ hai engine; job gắn extraction/config/engine với lease/token, unique input. Cần thêm version mà giữ đường chạy cũ. |
| Parser | `backend/src/main/java/com/cvcompass/backend/mapping/service/MappingEngine.java`, `ExperienceEngine.java`, `SourceLine.java` | Literal v1 lấy occurrence đầu; v2 phân section experience/summary/stop và có state PROPOSED/NOT_FOUND/AMBIGUOUS/INVALID. Chưa có phân chủ thể personal theo R-1..R-6. Summary của experience không mặc nhiên là summary cá nhân. |
| Validation/operator | `backend/src/main/java/com/cvcompass/backend/candidate/service/CandidateValidator.java`, `mapping/config/MappingBootstrap.java` | Basic hiện kiểm key/type/length, chưa validate email/phone/ownership. Bootstrap có fixed startup failure và chỉ chạy khi có definition-file; thiếu definition/active config cần diagnostic riêng an toàn. |
| Publication | `backend/src/main/java/com/cvcompass/backend/candidate/service/PublicationService.java`, `candidate/repository/PublicationRepository.java` | Chỉ nhận schema 2 + engine experience/2.0 + NEEDS_REVIEW + experience không rỗng. Transaction, document lock, ledger resultId, stale tuple extraction/config/result đã có; chưa publish personal-only, chưa dedup proposal theo nội dung giữa các result. SKIPPED chưa có lý do chi tiết. |
| Basic/mapper | `backend/src/main/java/com/cvcompass/backend/candidate/dto/Profile.java`, `candidate/service/CandidateService.java`, `candidate/repository/CandidateProfileRepository.java`, `candidate/mapper/CandidateValueMapper.java` | Basic là object chứa string/null; state/origin cấp nhóm. Manual save đồng bộ cột lõi và BASIC, đặt EDITED/USER; confirm giữ giá trị. Mapper chuyển DTO mới phải đặt tại candidate/mapping như spec, không tự di chuyển mapper cũ ngoài phạm vi. |
| Proposal | `backend/src/main/java/com/cvcompass/backend/candidate/dto/Proposal.java`, `candidate/service/CandidateReviewService.java` | Có documentId/resultId/data, nhưng decide xử lý experience/targetExperienceId. Cần phân loại personal riêng; JSONB không có nghĩa tự tương thích API. |
| Audit/concurrency | `backend/src/main/java/com/cvcompass/backend/candidate/service/CandidateRevisionService.java`, `candidate/repository/CandidateProfileRepository.java` | expected version dưới row lock, 409 VERSION_CONFLICT; history actor/action/before/after/time/version và unique candidate/version. Phải giữ transaction, kiểm replay/no-op để không tăng history vô ích. |
| UI/PDF | `frontend/src/features/candidates/schema.ts`, `pages.tsx`, `pdf-pane.tsx`, `pdf-canvas.tsx` | Đã có năm basic field, sửa/confirm, conflict và PDF document/page/quote cho experience. Proposal schema/form chỉ experience; basic chưa có evidence/alternatives/issues từng field. Quote render text; chưa có tọa độ highlight, không suy tọa độ từ offsets. |
| Schema | `backend/src/main/resources/db/migration/V7__create_mapping_foundation.sql`, `V9__allow_experience_mapping_result_v2.sql`, `V10__candidate_profiles_publication_and_users.sql` | Config DEFINITION/result RESULT/proposal DATA/basic BASIC/extensions/history before/after là JSONB. SCHEMA_VERSION result INTEGER bị CHECK IN (1,2). Basic cột VARCHAR(255), phone VARCHAR(50), summary TEXT; validator summary tối đa 10000. BASIC_STATE VARCHAR(16) chỉ UNVERIFIED/CONFIRMED/EDITED; origin VARCHAR(8). Proposal chưa có discriminator. |

### Lát cắt 0 — Kế hoạch docs

- Đường dẫn: `docs/milestones/personal-info-parser/spec.md`, `verification.md` cùng thư mục và
  `docs/milestones/README.md`; **0 file code**, 3 file docs.
- Phụ thuộc: bản đính kèm và main đã fetch; quyền docs đã giao trong chat.
- Rủi ro: nhầm kế hoạch với Active hoặc duyệt semantics. Giữ nguyên toàn bộ bản gốc,
  đưa đề xuất chưa duyệt và mâu thuẫn vào Open Questions của PR, không sửa roadmap.

### Lát cắt 1 — Gold và công cụ đo

- Dự kiến **3 file code** (gồm test): `backend/tools/evaluate_personal_info.py`,
  `backend/tools/test_evaluate_personal_info.py`, `backend/tools/personal_info_gold.py` (mới).
  Fixture tổng hợp và schema annotation dưới `backend/src/test/resources/mapping/personal-info/`;
  hướng dẫn/metrics không PII dưới `docs/milestones/personal-info-parser/`.
- Phụ thuộc: developer duyệt phương án dữ liệu, semantics và bảng so sánh bên dưới; developer
  xác nhận gold và tự giữ/chạy held-out ngoài tầm đọc agent. Dùng thư viện chuẩn, không thêm dependency.
- Rủi ro: script đo khác normalization parser, lộ PII qua failure/report, denominator sai khi abstain.
  Test comparator bằng synthetic; output chỉ aggregate/count và label allowlist, không value/quote/path private.
  Chưa có gold xác nhận thì AC-2/AC-22 NOT VERIFIED; không dùng synthetic làm bằng chứng accuracy thật.

### Lát cắt 2 — Config và version

- Dự kiến **10 file code/config/migration**:
  `backend/src/main/java/com/cvcompass/backend/mapping/dto/PersonalInfoDefinition.java` (mới),
  `PersonalInfoMappingResult.java` (mới), `MappingVersions.java` cùng thư mục;
  `backend/src/main/java/com/cvcompass/backend/mapping/service/MappingService.java`;
  `backend/src/main/java/com/cvcompass/backend/mapping/repository/MappingRepository.java`;
  `backend/src/main/java/com/cvcompass/backend/mapping/config/MappingBootstrap.java`;
  `backend/src/test/java/com/cvcompass/backend/mapping/dto/MappingVersionsTests.java` (mới);
  `backend/src/test/java/com/cvcompass/backend/MappingIntegrationTests.java`;
  `backend/src/main/resources/mapping/personal-info-config-v3.json` (mới);
  một migration mới trong `backend/src/main/resources/db/migration/` để mở CHECK result v3
  (chốt số khi bắt đầu, không sửa V7/V9/V10).
- Phụ thuộc: duyệt version, state, giới hạn output/config và phương án migration; lát cắt 1.
  Kiểu v3 triển khai MappingConfig/MappingOutcome, tái dùng Evidence/Issue thay DTO trùng;
  labels/sections/exclusions/enabled/priority là dữ liệu hữu hạn, không regex/script/SQL tùy ý.
- Rủi ro: reader mới không đủ bảo đảm process job mới. Đề xuất chỉ cho đọc/validate v3 ở PR này,
  giữ v1/v2 process được, chặn activate/enqueue v3 bằng diagnostic cố định đến khi parser sẵn sàng cho test ở lát cắt 3; vận hành chỉ sau publication ở lát cắt 4;
  đây là sequencing cần developer duyệt trong Open Questions, không coi AC-7 vận hành v3 đã hoàn tất.
  Giới hạn 10 gồm test/config/SQL; vượt dự toán phải xin chia lại, không bỏ test.

### Lát cắt 3 — Parser

- Dự kiến **7 file code**:
  `backend/src/main/java/com/cvcompass/backend/mapping/service/PersonalInfoEngine.java`,
  `PersonalInfoRegions.java`, `PersonalInfoCandidates.java`, `PersonalInfoValidation.java` (mới, cùng thư mục);
  `MappingService.java` cùng thư mục;
  `backend/src/test/java/com/cvcompass/backend/mapping/service/PersonalInfoEngineTests.java` (mới);
  `backend/src/test/java/com/cvcompass/backend/MappingIntegrationTests.java`.
  Fixture synthetic dưới `backend/src/test/resources/mapping/personal-info/`.
- Phụ thuộc: lát cắt 2 và quyết định state/version đã duyệt. Sau tích hợp dispatch chỉ activate v3 trong test schema/storage cô lập;
  v3 giữ kết quả experience tương đương engine v2. Chưa activate v3 trong luồng vận hành cho đến lát cắt 4: publication hiện tại chỉ nhận schema 2, sẽ ghi SKIPPED cho v3 và ledger chặn publish lại cùng result.
- Rủi ro: sai chủ thể/multi-column, normalization làm mất raw, offsets UTF-16 nhầm code point,
  vượt length cột lõi. Giữ raw/evidence bất biến; giá trị không vừa giới hạn phải có issue,
  không truncate lặng lẽ. Unit test toàn checklist AC-11 và regression v1/v2; parser không ghi candidate.
  Phân vùng không chắc phải giữ issue/abstain; score không là xác suất.

### Lát cắt 4 — Publication và database

- Dự kiến **10 file code/migration**:
  `backend/src/main/java/com/cvcompass/backend/candidate/service/PublicationService.java`,
  `CandidateReviewService.java`, `CandidateService.java` cùng thư mục;
  `backend/src/main/java/com/cvcompass/backend/candidate/repository/PublicationRepository.java`,
  `CandidateProfileRepository.java` cùng thư mục;
  `backend/src/main/java/com/cvcompass/backend/candidate/dto/Proposal.java`;
  `backend/src/main/java/com/cvcompass/backend/candidate/mapping/PersonalInfoMapper.java` (mới);
  `backend/src/test/java/com/cvcompass/backend/PersonalInfoPublicationIntegrationTests.java` (mới);
  `backend/src/test/java/com/cvcompass/backend/M11IntegrationTests.java`;
  một migration mới trong `backend/src/main/resources/db/migration/` nếu duyệt discriminator proposal.
- Phụ thuộc: lát cắt 3, duyệt proposal schema/basic metadata/no-op và migration.
  Dự kiến giữ basic string/null để UI cũ đọc được; metadata field/evidence/provenance versioned trong
  EXTENSIONS, mapper tại candidate/mapping. Đây là đề xuất contract, chưa phê duyệt.
- Rủi ro: state nhóm che quyền field, extensions mapping hiện bị thay toàn bộ, proposal cá nhân đi vào
  experience path, ghi đè USER, stale/replay và no-op tăng history. Chặn dispatch sai loại,
  bảo toàn metadata, giữ document/profile locks và transaction hiện có; đồng bộ BASIC/cột lõi.
  Test PostgreSQL cô lập: personal-only/experience-only/mixed, empty lý do cố định, replay,
  remap bằng/khác nội dung, bảo vệ state/origin, late result, rollback/conflict, edit bỏ evidence,
  confirm giữ evidence và audit đúng actor/version. Không unique hay gộp định danh cá nhân.

### Lát cắt 5 — UI tối thiểu

- Dự kiến **7 file code**: `frontend/src/features/candidates/schema.ts`, `pages.tsx`, `pdf-pane.tsx`;
  `personal-info.tsx` (mới, cùng thư mục); `frontend/tests/personal-info.test.tsx`,
  `frontend/tests/personal-info.browser.test.tsx` và `frontend/tests/personal-info-fixtures.ts` (mới).
- Phụ thuộc: lát cắt 4 và public API/metadata/proposal discriminator đã ổn định.
  Đề xuất mức 3 vì boundary schema thay đổi; chỉ giữ mức 2 nếu developer xác nhận không đổi contract.
- Rủi ro: Zod loại bỏ metadata, quote nhảy nhầm document, stale mutation tự retry, HTML injection.
  Hiển thị năm field/state/alternatives/issues, sửa/confirm và proposal riêng; dùng document/page/quote
  hiện có, không giả highlight. Kiểm conflict không retry và payload text không render HTML;
  không redesign và không truyền dữ liệu ra ngoài.

### Lát cắt 6 — E2E và đánh giá

- Dự kiến **5 file code/helper**:
  `backend/src/test/java/com/cvcompass/backend/PersonalInfoE2EIntegrationTests.java`,
  `PersonalInfoSyntheticPdf.java` cùng thư mục (mới);
  `frontend/tests/personal-info.e2e.test.tsx`, `frontend/tests/personal-info-e2e-fixtures.ts` (mới);
  `backend/tools/evaluate_personal_info.py` từ lát cắt 1. Report aggregate trong thư mục milestone.
- Phụ thuộc: lát cắt 1–5, Docker/WSL và worker thật theo README component, schema/storage cô lập,
  gold đã developer xác nhận. Developer tự chạy held-out private, không cấp agent quyền đọc tập đó.
- Rủi ro: mock OCR bị tính là E2E, report nguồn cũ, ít mẫu hoặc chưa có accuracy gate.
  Tách fixture integration và worker OCR thật; chứng minh chuỗi synthetic PDF → OCR → mapping → DB
  → UI/PDF với source/run rõ. Báo TP/FP/FN theo field và denominator, không tự đặt ngưỡng;
  thiếu prerequisite ghi NOT VERIFIED. Không sửa CI/hook theo kế hoạch này.

Số file là dự toán, gồm production/test/helper/config/SQL được liệt kê; fixture dữ liệu tổng hợp
và docs được kê riêng, không dùng để giấu code vượt 10. Nếu cần đổi thêm controller/DTO/validator
hoặc shared schema, dừng phần phụ thuộc và đề xuất chia lại trước khi viết code.

### Đề xuất cho năm Open Questions — chưa được duyệt

1. **Dữ liệu (OQ-1):** khuyến nghị A cho triển khai và CI; B chỉ khi developer chủ động chọn,
   xác nhận quyền và chỉ định private scope sau này. Task docs hiện tại cấm đọc CV thật;
   giữ nguyên cả A/B và mọi placeholder trong bản gốc. Chưa có lựa chọn nào được ghi nhận.
2. **State (OQ-2):** khuyến nghị state parser tách khỏi review state của profile.
   PROPOSED = một giá trị ứng viên hợp lệ đủ evidence; NOT_FOUND = không có candidate value thuộc
   ứng viên sau phân vùng; INVALID = có candidate thuộc ứng viên nhưng tất cả đều không qua validation;
   AMBIGUOUS = còn nhiều giá trị hợp lệ khác nhau hoặc không xác định chắc chủ thể/reading order.
   Một giá trị hợp lệ và alternatives invalid chỉ chọn khi evidence đủ phân chủ thể; nếu không thì
   AMBIGUOUS. Field bị tắt: đề xuất NOT_FOUND + issue FIELD_DISABLED và cờ enabled=false để phân biệt
   với tìm không thấy, không sinh deletion. Không dùng INVALID cho vùng reference bị loại bỏ.
   raw/alternatives/evidence vẫn giữ, normalized chỉ cho value hợp lệ; không có selected value
   khi ambiguous/invalid/not-found. Profile review vẫn UNVERIFIED/CONFIRMED/EDITED, origin MAPPING/USER;
   PROPOSED không tương đương CONFIRMED. Lựa chọn khác: state DISABLED riêng (tăng enum/UI/metrics);
   cần duyệt precedence và disabled denominator trước lát cắt 1.
3. **Version (OQ-3):** khuyến nghị config/result schemaVersion=3, engineCompatibility/engineVersion
   `personal-info-mapping/3.0`, revision config bất biến riêng; chứa personal info và experience
   bằng cách tái dùng DTO/logic v2, không viết lại result cũ. Lựa chọn khác: personal result độc lập,
   cần quy tắc phối hợp hai result/publication cùng document nên rủi ro cao hơn.
   Giữ reader/validation/dispatch literal v1 và experience v2, claim/enqueue theo config/engine cũ;
   test register/read/process/complete cả ba, digest result cũ không đổi. Job không có schemaVersion
   riêng, lưu ENGINE_VERSION VARCHAR(128) và CONFIG_ID; không nâng hàng loạt job cũ sang v3.
   Đề xuất staging: lát cắt 2 validate/read v3 nhưng từ chối kích hoạt v3 an toàn;
   lát cắt 3 mới process/activate trong test schema/storage cô lập. AC-7 activate/restart chỉ được kiểm trong test ở lát cắt 3; vận hành chờ lát cắt 4;
   cần developer duyệt cách phân chia này hoặc cho chuyển wiring parser tối thiểu sang lát cắt 2.
4. **Migration (OQ-4):** khuyến nghị có migration mới: INTEGER SCHEMA_VERSION không thiếu dung lượng
   nhưng CHECK IN (1,2) chặn v3, phải mở (1,2,3). JSONB chứa metadata đủ, không cần đổi kiểu cột personal;
   kiểm giới hạn VARCHAR/validator trước publication, không truncate raw. Đề xuất migration thứ hai
   thêm proposal KIND (EXPERIENCE/PERSONAL_INFO), default EXPERIENCE cho hàng cũ; giữ liên kết document/result,
   không diễn giải data cá nhân như experience. Lựa chọn khác: discriminator trong DATA JSONB, không
   thêm cột nhưng vẫn phải version/validate mọi reader. Khuyến nghị cột có CHECK để chặn sai loại sớm.
   BASIC state/origin không thêm state parser; metadata versioned trong EXTENSIONS, không nhét object
   vào basic string/null. Không backfill result, không sửa migration đã áp dụng, không đụng DB hiện tại.
5. **Lát cắt và đo (OQ-5):** khuyến nghị giữ tách 3/4: tổng dự toán 17 file code và khác gate parser/
   transaction, gộp vượt 10. Parser PR chỉ tạo result; publication PR sau mới thay profile/proposal.
   Đo theo cặp (document, field) trên gold đã duyệt; không gộp người giữa document, không fuzzy match.

   | Field | So sánh đề xuất cho TP |
   | --- | --- |
   | fullName | NFC, trim và gom whitespace; giữ dấu, hoa/thường và thứ tự tên; không bỏ dấu/đảo tên. |
   | email | Trim ngoài; local-part so khớp chính xác kể cả hoa/thường, domain lowercase; không sửa OCR. |
   | phone | Bỏ dấu định dạng space/ngoặc/chấm/gạch trong phần số; giữ dấu +, chữ số, mã quốc gia/đầu 0 và extension tách riêng; không suy +84 hay đổi 00 thành +. |
   | location | NFC, trim/gom whitespace; giữ dấu/hoa-thường/thứ tự/thành phần; không suy địa danh. |
   | summary | NFC và thống nhất CRLF/CR thành LF, trim biên; giữ nội dung và ngắt dòng bên trong; không paraphrase/fuzzy. |

   Canonicalization ở đây chỉ cho so sánh, không sửa raw/evidence lưu trữ. Gold ghi rõ value chấp nhận
   được và đúng chủ thể; nhiều cách viết chỉ tính tương đương khi developer duyệt vào gold trước đo.
   Dự đoán là selected value PROPOSED; alternatives không tính thành nhiều prediction/TP.
   Đúng value/đúng chủ thể: TP=1; selected sai khi gold có value: FP=1 và FN=1; có selected khi gold
   không có: FP=1; không selected khi gold có: FN=1. Cả hai không có: không TP/FP/FN.
   AMBIGUOUS/INVALID/NOT_FOUND là abstain, vẫn FN nếu gold có value; field tắt loại khỏi denominator
   chính và báo riêng enabled/disabled/support, không để tắt field làm accuracy trông tốt hơn.
   Gold chưa phân xử/thiếu annotation loại riêng có count, không coi là gold rỗng.
   Precision=TP/(TP+FP), recall=TP/(TP+FN), mẫu số 0 báo N/A; tính theo field, không tự đặt pass threshold.
   Evidence validity đo riêng: extraction/page/range/quote đúng và đúng chủ thể; value trùng reference
   vẫn FP/FN nếu chủ thể sai. Báo nhầm reference/company riêng và không tính một lỗi thành nhiều FP.
   Lựa chọn khác: exact raw hoặc comparator mềm; khuyến nghị bảng trên để tránh thưởng suy diễn,
   đồng thời báo exact-raw phụ nếu developer cần khảo sát sai số định dạng.

### Khoảng trống cần đưa vào Open Questions của PR

- Metadata từng field và proposal discriminator chưa có trong public contract; cần duyệt shape cụ thể
  trước lát cắt 2/4/5. Khuyến nghị giữ basic string/null + metadata versioned, xử lý quyền ở cấp nhóm
  bảo thủ: USER/CONFIRMED/EDITED bất kỳ thì không auto-overwrite nhóm; proposal personal là patch
  chỉ field có value dùng được, field thiếu không là delete. Nếu muốn quyền từng field cần thiết kế thêm.
- Publication SKIPPED hiện không lưu reason riêng; khuyến nghị issue/reason cố định ở result/metadata
  đọc được theo resultId, không log PII; nếu cần query reason ở ledger thì thêm cột/migration và chia lại
  dự toán 10 file trước code. Khóa document và stale tuple hiện có chưa chứng minh mọi race của v3.
- Publication hiện tại sẽ ledger SKIPPED với result v3; replay cùng result bị exists chặn. Khuyến nghị không activate v3 trên luồng vận hành trước lát cắt 4, chỉ test cô lập ở lát cắt 3. Lựa chọn khác là defer future-version publication không ghi ledger, nhưng cần duyệt path/count và semantics mới trước code.
- Spec nói mỗi lát cắt CI xanh độc lập nhưng activate v3 đầy đủ cần parser và publication; đề xuất staging OQ-3
  chưa sửa AC-5/AC-7. Không kết luận acceptance triển khai đã đạt từ PR docs.
- Spec có 5 OQ nhưng câu 5 chứa hai quyết định; task docs hiện không tự chọn A/B, không tự duyệt
  gộp/tách hay comparator. Những khuyến nghị trên chỉ để developer phê duyệt trước code.