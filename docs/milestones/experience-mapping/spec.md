# Spec Mapping kinh nghiệm: tổng số năm, công ty, chức danh

## Phê duyệt

- Nguồn: chủ dự án yêu cầu trong chat 08/11/2026 (sau PR #32). Các quyết định Q1–Q4 dưới đây đã được chốt
  trong cùng phiên. **Trạng thái: chờ duyệt.** Merge PR docs chứa file này được coi là duyệt kế hoạch mức 3.
- Base `baf0eba` (main). Mỗi slice dùng branch riêng `feature/experience-*`, Draft PR. Agent không merge.
- Thứ tự ưu tiên của chủ dự án: (1) tổng số năm kinh nghiệm, (2) công ty đã làm, (3) chức danh ở từng công ty.

## Hiện trạng repo (review 08/11/2026)

| Thành phần | Đã có | Chưa có / hạn chế |
| --- | --- | --- |
| Parser `ExperienceGrammar` (+ `ExperienceGrammarRows`, `ExperienceGrammarDates`) | Chạy trong engine personal-info 3.1–3.3. Nhãn `Company:/Position:/Period:`, câu `Worked at … from …`, dòng `period \| company \| title`, nhiều vai trò cùng công ty, gộp summary/detail, continuation có marker, cờ trùng thời gian `POSSIBLE_PERIOD_OVERLAP`. Ngày: năm, MM/YYYY, tháng chữ tiếng Anh, `tháng MM/YYYY`, `Now/Present/nay`. | Công ty chỉ nhận khi có hậu tố pháp lý (`Ltd`, `Group`, `JSC`…) hoặc có trong danh mục config. Không nhận dòng "chức danh + kỳ" rồi "CÔNG TY \| quốc gia"; không nhận "kỳ + tên công ty" không hậu tố; không nhận tiền tố "Công ty / CTCP / TNHH"; không hiểu "Tên – Tỉnh (kỳ)"; chức danh dễ bị nhận nhầm từ câu mô tả có từ "manager/director". |
| Kết quả mapping (`ExperienceMappingResult.Experience`) | company raw/normalized, title raw/alternatives, period start/end (`PartialDate` YEAR/MONTH/DAY, `earliest()`/`latest()`), ongoingAsStated, mô tả, evidence, issues. | Không có tổng số năm. |
| Lưu hồ sơ (`CANDIDATE_EXPERIENCES`, V10) | Cột `COMPANY_RAW`, `TITLE_RAW`, `START_DATE/START_PRECISION`, `END_DATE/END_PRECISION`, `ONGOING_AS_STATED`, `DATA` JSONB, state/origin, `DOCUMENT_ID`. Lần publish đầu chèn trực tiếp (UNVERIFIED); lần sau tạo proposal để HR duyệt. HR sửa/xác nhận/xóa từng dòng. | `END_DATE` lưu ngày sớm nhất của kỳ (vd. `2023-07` → `2023-07-01`), phải dùng precision để lấy ngày cuối. Không có cột/field tổng năm. |
| UI (`experience.tsx`, `experience-form.tsx`) | Thẻ từng kinh nghiệm: công ty, chức danh, kỳ, evidence; form sửa. | Không hiển thị tổng năm. |
| Đánh giá | `ExperienceEvaluationTests` + fixture tổng hợp `development-experiences.json`, `heldout-experiences.json`. | Chưa có số đo trên CV thật. |

Chạy thử engine 3.3 trên 4 CV thật trong chat (không lưu, không commit): khoảng **24** kinh nghiệm thực tế,
parser chỉ ra **5** dòng, trong đó 2 dòng sai công ty/chức danh. 2/4 CV ra `EXPERIENCE_NOT_FOUND`.
Nguyên nhân chính nằm ở các bố cục dòng tiêu đề dưới đây.

## Bố cục cần hỗ trợ (ví dụ tổng hợp)

| ID | Mẫu dòng (dữ liệu giả) | Kỳ vọng |
| --- | --- | --- |
| L1 | `Sales Manager / SAMPLE GROUP (Hose : SMP) Aug 2024 – Now` | title, company (giữ phần ngoặc), kỳ đến nay |
| L2 | `Regional Sales Manager (SEA) Sep 2025 - Jul 2026` + dòng sau `SAMPLE CORP \| Vietnam` | title dòng 1, company dòng 2, bỏ phần quốc gia |
| L3 | `2020 – 2025 The Sample Tea House VN (Sample Group)` / `2018 – 2020: Sample Group` / `2025 – 2026 Công Ty Cổ Phần Mẫu` | company không cần hậu tố; title từ `- Position: …` trong mô tả nếu có, nếu không `TITLE_NOT_FOUND` |
| L4 | `Sample Works – Long An ( 02/2019 - 10/2024 )` hoặc kỳ ở dòng sau `(December 2013 – April 2017)`, chức danh dòng kế `Paint Technician` | company bỏ phần tỉnh/thành, kỳ trong ngoặc |
| L5 | `Jun 2018 - May 2020` / `Logistics Coordinator` / `SAMPLE LOGISTICS \| Vietnam` | ghép 3 dòng thành 1 kinh nghiệm |
| L6 | Section `ADVISORY BOARD / SHARE HOLDER`, `OTHER PROJECTS` chứa dòng dạng L1/L2 | tạo kinh nghiệm, gắn cờ `ROLE_CONTEXT_UNCLASSIFIED` (vẫn tính vào tổng theo Q4) |
| N1 | Câu mô tả `Booking hotel, restaurants, tickets for managers, director,` | **không** thành chức danh |

CV 2 cột bị trộn thứ tự đọc (text từ worker không có tọa độ) chỉ được kỳ vọng ở các dòng tiêu đề còn nguyên.
Sửa thứ tự đọc nằm ở worker, ngoài phạm vi spec này.

## Quy tắc tổng số năm (đã chốt)

- **Q1 — "đến nay"**: kỳ `Now/Present/nay` kết thúc ở **ngày nhập CV** của tài liệu nguồn của dòng đó
  (`importedAt`). Dòng do HR tạo (không có tài liệu) dùng ngày nhập của tài liệu mới nhất đã liên kết;
  thiếu ngày tham chiếu thì chỉ bỏ kỳ đến nay và báo lý do. Kỳ đã kết thúc vẫn tính theo ngày đã ghi.
  Khi có ngày tham chiếu, ngày kết thúc sau ngày nhập CV được cắt về ngày nhập.
- **Q2 — trùng thời gian**: hợp các khoảng (union); phần chồng nhau và khoảng liền kề chỉ tính một lần.
- **Q3 — độ chính xác**: khoảng tính từ `start.earliest()` đến `end.latest()` (bao gồm hai đầu).
  `2009 – 2012` = 01/01/2009–31/12/2012 = **4 năm**; `Sep 2025 – Jul 2026` = 11 tháng.
  Số tháng của mỗi khoảng sau khi hợp = `Period.between(start, end + 1 ngày).toTotalMonths()` (bỏ phần lẻ ngày).
- **Q4 — loại trừ**: không tự loại loại nào. HR có thể bỏ từng dòng khỏi tổng (`countInTotal = false`).
  ACCEPT một đề xuất thay thế giữ lựa chọn đã lưu trên dòng hiện hành.
- Không tính: thiếu ngày bắt đầu; thiếu ngày kết thúc mà không ghi "đến nay"; kỳ `INVALID`/`AMBIGUOUS`
  chưa được HR sửa; dòng đã xóa. Mỗi dòng không tính có lý do hiển thị cho HR.
- Kết quả là **ước tính hỗ trợ HR**, không phải dữ liệu đã xác minh; hiển thị kèm "tính đến ngày …" và
  cảnh báo khi có dòng không tính hoặc có kỳ chỉ ghi năm.

## Acceptance theo slice

| Slice | Mức | Nội dung | Tiêu chí chấp nhận |
| --- | --- | --- | --- |
| **E1** | 3 | Engine `personal-info-mapping/3.4` (config `personal-info-config-v3.4.json`, revision `personal-info-grammar-v34-r1`): dòng tiêu đề neo theo kỳ L1–L5 trong section kinh nghiệm | Các ví dụ L1–L5 ra đúng company/title/kỳ; company không hậu tố được nhận khi đứng cạnh kỳ trong section kinh nghiệm; tiền tố tiếng Việt `Công ty/Cty/CTCP/TNHH/Tập đoàn`; phần quốc gia/tỉnh (tái dùng `PersonalInfoProvinces`, danh sách quốc gia hữu hạn) bị tách khỏi tên công ty; ngoặc chứa `:` không làm hỏng company. 3.0–3.3 giữ nguyên output (digest/test baseline). |
| **E2** | 3 | 3.4: chức danh + section ngữ cảnh | N1 không thành title; title nhận từ dòng kề trước/sau dòng công ty khi giống chức danh (danh sách từ chức danh mở rộng); nhãn có bullet `- Position:`; L6 tạo kinh nghiệm có cờ; một công ty nhiều chức danh có kỳ riêng vẫn tách dòng như cũ. |
| **E3** | 3 | Tổng số năm ở backend (module candidate) | Hàm thuần `ExperienceTotals` theo Q1–Q4; API hồ sơ trả `experienceSummary { totalMonths, countedIds, skipped[{id, reason}], ongoingReferenceDates, hasYearOnlyPeriods }`; tính khi đọc, **không migration**; field `countInTotal` (mặc định true) trong `DATA` của kinh nghiệm, HR sửa được qua API sửa kinh nghiệm hiện có. Test biên: trùng, liền kề, năm-only, đến nay, kết thúc sau ngày nhập, thiếu ngày, dòng đã xóa, dòng HR tạo. |
| **E4** | 2 | UI | Đầu tab kinh nghiệm: "Tổng kinh nghiệm: X năm Y tháng (ước tính, tính đến dd/mm/yyyy)"; danh sách dòng không tính + lý do; công tắc "Tính vào tổng" ở form sửa; công ty và chức danh hiển thị như hiện tại. |

Đề xuất thứ tự: E1 → E2 → E3 → E4 (E3 độc lập với E1/E2, có thể làm song song nếu muốn thấy tổng năm sớm
trên dữ liệu đã có).

## Ngoài phạm vi

Sửa thứ tự đọc 2 cột ở worker; ngành/chức năng/cấp bậc; gộp công ty theo tập đoàn; đọc số năm ứng viên
tự ghi ("8+ years"); lưu tổng năm để search (P3 sẽ materialize khi cần); chạy lại mapping cho dữ liệu cũ.

## Kế hoạch kiểm chứng

- Test-first, chỉ dữ liệu tổng hợp (các mẫu L1–L6, N1 ở trên). Không đưa CV thật/PII vào repo, log, prompt.
- E1/E2: test grammar 3.4 mới; baseline 3.0–3.3 (`PersonalInfoGrammarV32Tests.legacyReplay…`, test 3.3)
  không đổi; `ExperienceEvaluationTests` không giảm chỉ số; integration routing 3.4.
- E3: unit test `ExperienceTotals`; integration API hồ sơ với PostgreSQL 17.
- E4: Vitest + Playwright hiện có, cập nhật test bị ảnh hưởng (không xóa assertion).
- Đo trên CV thật do chủ dự án chạy local sau mỗi slice; chỉ ghi số tổng hợp (không tên công ty/ứng viên).
- Full gate: `.\gradlew.bat check --rerun-tasks`, `npm run check`, `npm run test:browser`. CI NOT VERIFIED khi billing còn chặn.

## Open Questions

1. Chủ dự án đã chốt không dùng danh mục công ty và chức danh local làm alias trong E1–E4.
2. Dữ liệu hồ sơ đã publish trước đó sẽ không tự đổi; khi chạy engine 3.4 trên CV cũ, kinh nghiệm mới thành
   proposal chờ HR duyệt. Nếu chỉ là dữ liệu thử, khuyến nghị reset DB local theo hướng dẫn hiện có.
