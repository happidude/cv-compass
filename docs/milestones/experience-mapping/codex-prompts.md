# Prompt cho Codex — Mapping kinh nghiệm

Dùng sau khi [spec](spec.md) được duyệt (PR docs đã merge). Mỗi prompt là một slice, một Draft PR.
Dán nguyên khối vào Codex; thay `<BASE_SHA>` bằng SHA `main` lúc giao việc.

---

## Prompt E1 — Engine 3.4: dòng tiêu đề kinh nghiệm neo theo kỳ

```text
Bạn là implementer cho repo cv-compass. Đọc AGENTS.md, backend/AGENTS.md, docs/conventions/backend.md,
docs/milestones/experience-mapping/spec.md (slice E1) và backend/MAPPING_EXPERIENCE_V2.md. Mức 3.

Base: <BASE_SHA> (main). Branch mới: feature/experience-grammar-3-4. Mở Draft PR, mô tả tiếng Việt theo template.

Mục tiêu: thêm engine opt-in personal-info-mapping/3.4 làm phần kinh nghiệm nhận được các dòng tiêu đề L1–L5 trong spec.
Làm giống cách PR #30/#32 thêm 3.2/3.3:
- PersonalInfoDefinition: hằng GRAMMAR_V34_ENGINE_VERSION, supportsEngine, fieldNames(3.4) = V33_FIELD_NAMES.
- PersonalInfoEngineV34 (gọi PersonalInfoEngineV31.map(claim, input, VERSION)); route trong MappingService,
  MappingRepository.claim (thêm overload + IN (...) + NOT IN (...)), PublicationService (chấp nhận 3.4),
  PersonalInfoMapper (3.4 dùng normalized cho location/dateOfBirth như 3.3).
- PersonalInfoGrammar: flag v34 kế thừa toàn bộ hành vi 3.3 (v32 = v33 = true khi 3.4).
- resources/mapping/personal-info-config-v3.4.json copy từ v3.3, đổi engineCompatibility/revision; được phép thêm
  heading vào experience.rules (vd. "Work Experience", "Advisory Board", "Other Projects") nếu cần.
- PersonalInfoEngineV31.map tạo ExperienceGrammar: truyền cờ v34 (constructor mới), engine cũ dùng constructor cũ.
  Mọi hành vi mới trong ExperienceGrammar/ExperienceGrammarRows/ExperienceGrammarDates chỉ chạy khi v34.

Hành vi mới (chỉ v34, chỉ trong section DETAIL/SUMMARY đã nhận heading):
1. Dòng tiêu đề neo theo kỳ: dòng có đúng một kỳ (RANGE) và phần còn lại ngắn (≤ 14 từ, ≤ 160 ký tự),
   không phải câu mô tả (không bắt đầu bằng bullet/động từ PROSE, không kết thúc bằng '.') → là dòng tiêu đề,
   kể cả khi tên công ty không có hậu tố pháp lý. Kỳ có thể ở đầu, cuối, hoặc trong ngoặc "( 02/2019 - 10/2024 )".
2. Tách phần còn lại theo dấu phân cách ưu tiên " | ", " / ", " – "/" - " (có khoảng trắng hai bên), ":" ở cuối kỳ.
   Phân loại từng phần: quốc gia/tỉnh (PersonalInfoProvinces.find khớp toàn bộ phần đó, hoặc danh sách quốc gia hữu hạn
   Vietnam/Việt Nam/Singapore/Thailand/Malaysia/Indonesia/Philippines/SEA/APAC…) → bỏ khỏi company;
   giống chức danh (ROLE hoặc alias config) → title; còn lại → company. Không giữ phần nào thì AMBIGUOUS_EXPERIENCE_ROW.
3. Dòng tiêu đề chỉ có title + kỳ (L2) → dòng có nội dung kế tiếp cùng trang, ngắn, không phải mô tả, không có kỳ
   → company (áp dụng tách như mục 2, vd. "SAMPLE CORP | Vietnam" → "SAMPLE CORP").
4. Kỳ đứng riêng một dòng (L5, hoặc "(December 2013 – April 2017)" ngay sau dòng company ở L4) gắn vào khối liền kề
   theo logic pending hiện có; L5: kỳ → title → company ghép thành một kinh nghiệm.
5. company(): chấp nhận tiền tố "Công ty/Công Ty/Cty/CTCP/TNHH/Tập đoàn"; dấu ":" bên trong ngoặc không làm company() false.
6. Ngày tháng: "March 2025", "December 2013" (tháng chữ đủ) đã có; bổ sung nếu thiếu: "Now"/"Present" viết hoa,
   dấu "–"/"—" có/không khoảng trắng.

Không đổi: output 3.0–3.3 (byte-identical; test PersonalInfoGrammarV32Tests.legacyReplay… và test 3.3 phải giữ nguyên),
experience-mapping/2.0, schema result/config, Flyway.

Test (chỉ dữ liệu tổng hợp, tên công ty/người giả): file mới ExperienceGrammarV34Tests với từng mẫu L1–L5 trong spec,
một CV giả nhiều dòng tiêu đề liên tiếp (≥ 5 kinh nghiệm), trường hợp âm (câu mô tả có năm, dòng học vấn sau heading
Education không thành kinh nghiệm), và test "3.3 cùng input không đổi". Integration: MappingIntegrationTests thêm
v34ActivationRoutesNewJobsAndKeepsQueuedV33Pinned; MappingVersionsTests đổi phiên bản bị từ chối thành 3.5.
Thêm docs/milestones/personal-info-parser/grammar-3.4.md (tiêu chí như bảng spec E1).

Check: cd backend; .\gradlew.bat spotlessApply; .\gradlew.bat test --tests "*ExperienceGrammar*" --tests "*PersonalInfoGrammar*"
--tests "*MappingVersions*" --tests "*ExperienceEvaluation*"; cuối cùng .\gradlew.bat check --rerun-tasks.
Ghi PASS/FAIL/NOT VERIFIED trong PR. Không log/commit nội dung CV thật. Không merge, không push main.
Quyết định ngoài spec → mục Open Questions trong PR. Giới hạn ~400 dòng code (không tính test); vượt thì dừng ở
mục 1–3 và ghi phần còn lại vào Follow-up.
```

---

## Prompt E2 — Engine 3.4: chức danh và section ngữ cảnh

```text
Bạn là implementer cho cv-compass. Đọc AGENTS.md, backend/AGENTS.md, spec docs/milestones/experience-mapping/spec.md
(slice E2) và diff của PR E1 (đã merge). Mức 3. Base: <BASE_SHA>. Branch: feature/experience-titles-3-4. Draft PR.

Tiếp tục engine personal-info-mapping/3.4 (không tạo version mới vì 3.4 chưa được kích hoạt ở môi trường nào;
nếu chủ dự án báo 3.4 đã kích hoạt thì DỪNG và hỏi). Chỉ đổi hành vi khi v34.

1. title() chặt hơn: loại dòng bắt đầu bằng bullet/động từ dạng -ing, kết thúc bằng ',' hoặc '.', có ≥ 2 dấu phẩy,
   hoặc > 8 từ mà không có dấu phân cách. Mẫu N1 trong spec không được thành title.
2. Mở rộng ROLE (chỉ v34): supervisor, coordinator, executive, technician, assistant, intern, trainee, associate,
   representative, advisor, buyer, merchandiser, planner, receptionist, purchaser, account manager, AGM, GM, head,
   nhân viên, trưởng nhóm, giám sát, quản lý, kế toán, thực tập sinh… Giữ danh sách hữu hạn, có test.
3. Title kề công ty: khi khối có company mà chưa có title, dòng ngay trước (chưa thuộc khối khác) hoặc ngay sau
   dòng tiêu đề, cùng trang, ngắn, giống chức danh → title. Không lấy dòng đã là company/kỳ/mô tả.
4. Nhãn có bullet: "- Position: X", "• Chức danh: X" → title (bỏ ký tự bullet trước khi so nhãn).
5. Section ngữ cảnh (CONTEXT: advisory, shareholder, projects, dự án, consulting): trong 3.4, dòng tiêu đề hợp lệ
   (mục E1) tạo kinh nghiệm bình thường + issue ROLE_CONTEXT_UNCLASSIFIED; dòng khác vẫn như cũ.
6. Một công ty nhiều chức danh có kỳ riêng vẫn tách dòng (giữ test hiện có).

Test tổng hợp: thêm vào ExperienceGrammarV34Tests các mẫu L4 (title dòng kế), L6, N1, bullet label, nhiều vai trò
cùng công ty. 3.0–3.3 không đổi. ExperienceEvaluationTests không giảm.
Check như E1. Không CV thật/PII. Không merge/push main. Open Questions trong PR.
```

---

## Prompt E3 — Tổng số năm kinh nghiệm (backend)

```text
Bạn là implementer cho cv-compass. Đọc AGENTS.md, backend/AGENTS.md, docs/conventions/backend.md,
docs/contracts/README.md, spec docs/milestones/experience-mapping/spec.md (mục "Quy tắc tổng số năm" và slice E3).
Mức 3 (API contract). Base: <BASE_SHA>. Branch: feature/experience-total-years. Draft PR. Không migration Flyway.

1. Lớp thuần com.cvcompass.backend.candidate.service.ExperienceTotals (không Spring, không clock):
   input = danh sách kinh nghiệm của hồ sơ (id, start date + precision, end date + precision, ongoingAsStated,
   period state trong DATA, deleted, countInTotal, documentId) + map documentId → importedAt (LocalDate).
   Quy tắc Q1–Q4 đúng như spec: khoảng [start.earliest, end.latest] bao gồm hai đầu (dùng precision vì END_DATE
   lưu ngày đầu kỳ); "đến nay" = ngày nhập CV của tài liệu nguồn, dòng không có tài liệu dùng tài liệu mới nhất;
   cắt ngày kết thúc về ngày nhập; hợp các khoảng chồng/liền kề; tổng tháng = tổng
   Period.between(start, end+1 ngày).toTotalMonths() của từng khoảng sau khi hợp.
   Lý do không tính (enum): MISSING_START, MISSING_END, PERIOD_UNRESOLVED (INVALID/AMBIGUOUS), EXCLUDED_BY_USER,
   NO_REFERENCE_DATE, START_AFTER_REFERENCE. Dòng deleted bỏ qua hẳn.
2. Profile DTO thêm experienceSummary { totalMonths, countedIds, skipped[{id, reason}],
   ongoingReferenceDates[], hasYearOnlyPeriods } — tính trong CandidateQueryService.detail, không lưu DB.
3. countInTotal: boolean tùy chọn trong DATA của kinh nghiệm (thiếu = true). CandidateValidator.userExperience cho phép
   key này; API sửa kinh nghiệm hiện có giữ nguyên luồng/khóa phiên bản.
4. Cập nhật docs/contracts (nếu contract hồ sơ được mô tả ở đó) và docs/milestones/experience-mapping/verification.md.

Test: ExperienceTotalsTests (unit) — trùng một phần, lồng nhau, liền kề (31/12 và 01/01), năm-only "2009–2012" = 48 tháng,
"Sep 2025 – Jul 2026" = 11 tháng, đến nay với ngày nhập, kết thúc sau ngày nhập, thiếu start/end, AMBIGUOUS, deleted,
countInTotal=false, dòng HR tạo không có tài liệu. Integration với PostgreSQL 17: chi tiết hồ sơ trả experienceSummary
đúng sau publish và sau khi HR sửa countInTotal. Dữ liệu tổng hợp.
Check: .\gradlew.bat spotlessApply; .\gradlew.bat test --tests "*ExperienceTotals*" --tests "*Candidate*";
.\gradlew.bat check --rerun-tasks. Không merge/push main. Open Questions trong PR.
```

---

## Prompt E4 — Hiển thị tổng năm (frontend)

```text
Bạn là implementer cho cv-compass. Đọc AGENTS.md, frontend/AGENTS.md, docs/conventions/frontend.md,
frontend/DESIGN.md, spec docs/milestones/experience-mapping/spec.md (slice E4) và PR E3 (đã merge). Mức 2.
Base: <BASE_SHA>. Branch: feature/experience-total-ui. Draft PR.

1. schema.ts: profileSchema thêm experienceSummary (optional, để đọc được backend cũ); experienceDataSchema cho phép
   countInTotal (boolean optional).
2. Đầu phần kinh nghiệm ở trang hồ sơ: "Tổng kinh nghiệm: X năm Y tháng" + nhãn "ước tính", "tính đến dd/mm/yyyy"
   (ngày tham chiếu khi có kỳ "đến nay"), cảnh báo khi hasYearOnlyPeriods. Danh sách dòng không tính kèm lý do
   tiếng Việt (map từ enum backend). 0 tháng → "Chưa tính được" + lý do.
3. experience-form.tsx: checkbox "Tính vào tổng kinh nghiệm" (mặc định bật) lưu countInTotal qua API sửa hiện có.
4. Dùng shared/ui kit và token trong app/styles; không thêm dependency.

Test: Vitest cho định dạng "X năm Y tháng", map lý do, schema; cập nhật Playwright m11 nếu bị ảnh hưởng (không xóa
assertion). Mock MSW thêm experienceSummary tổng hợp.
Check: npm exec prettier -- --write src tests; npm run check; npm run test:browser. Không merge/push main.
```

---

## Sau mỗi slice — chủ dự án tự đo trên CV thật (local)

Chạy mapping 3.4 trên bộ CV thử, ghi vào PR **chỉ số tổng hợp** (không tên công ty/ứng viên):
số kinh nghiệm thực tế / parser tìm được / đúng cả công ty + kỳ / đúng chức danh; tổng năm parser so với tự tính tay.
