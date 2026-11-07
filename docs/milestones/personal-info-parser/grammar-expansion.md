# Mở rộng grammar và clean code có mục tiêu

## Phê duyệt và baseline

Developer giao qua file đính kèm ngày 2026-11-05: ưu tiên personal info rồi experience;
được sửa parser/config mẫu/test/helper/fixture tổng hợp/docs, commit/push và cập nhật PR Draft.
Đây là thay đổi ưu tiên, **không phải hoàn thành lát cắt 6**; không làm chương trình đánh giá,
accuracy, precision/recall, gold/held-out, corpus benchmark hoặc search.

Sau fetch, PR #26 vẫn mở Draft, chưa merge; head thực tế
`f95affd939a29202d2ac429a01498299b23ae5f1`, main base
`69930b951659c56e709a5f24757c2012de5f3731`. Tiếp tục checkout
`E:/cv-compass`, nhánh `feature/personal-info-parser`; giữ application.yaml modified và
experience-config-v2.json untracked của developer, không đọc/stage hai file đó.
Mức 3 vì logic dữ liệu cá nhân, version/replay và persistence; Implementer → Tester →
Reviewer chỉ đọc, tối đa 3 vòng cho phần mở rộng này. Không merge/Ready.

## Khảo sát trước code

| Nhóm checklist | Baseline | Hướng xử lý |
| --- | --- | --- |
| Gender có nhãn rõ; missing/conflict/unknown/disabled | Đã hỗ trợ | Giữ R-7 và regression; không suy danh xưng/ảnh/tên/file |
| NFC/NFD, code-point evidence, alternatives/dedup, raw bất biến | Đã hỗ trợ có giới hạn | Giữ span mapping và kiểm multi-span/CRLF/emoji |
| Alias contact/section nguyên dòng, typo hữu hạn | Config đủ khi quan hệ rõ | Mẫu opt-in mở rộng literal Việt–Anh; không regex operator/fuzzy |
| Tên không nhãn, sidebar/trang sau/cover letter, tên nhiều dòng/nickname/danh xưng cuối | Cần parser | Vùng có giới hạn và evidence; không dùng index 5 dòng/đoán tên pháp lý |
| Inline nhiều field, icon, label/value tách dòng/blank/colon riêng | Cần parser | Token/boundary/span và quan hệ có bằng chứng; không zip label/value mù quáng |
| Địa chỉ nhiều dòng/current vs relocation/company/workplace | Cần parser | Continuation có giới hạn; boundary và ownership/qualifier |
| Email domain ngắt dòng, phone quốc tế/extension/ngoặc | Đã có một phần; cần parser | Giữ raw, không OCR repair/country inference; trunk notation +84 (0) không tạo số khác im lặng |
| Summary raw multiline | Đã hỗ trợ | Normalized parser vẫn collapse whitespace: cần sửa ở handler mới |
| Summary boundary contact/skills/education, PROFILE mixed, intro không heading | Cần parser | Phân đoạn nội dung có căn cứ; giữ paragraphs/bullets/ngắt dòng |
| Giãn chữ heading, tab/spacing | Cần parser | Canonical heading riêng, uncertainty theo span/block bị ảnh hưởng |
| References/company subheading | Đã bảo vệ cho alias cũ | Mở alias nhưng giữ ownership, không bỏ mọi nội dung sau từ reference |
| Experience nhãn explicit và period pipe employer pipe title | Đã hỗ trợ hẹp | Giữ engine 2.0; grammar mới opt-in |
| Period/company/title đổi thứ tự, inline, nhiều dòng/overview/detail/multi-role | Cần parser | Block grammar có bằng chứng, không dùng title header hay company proximity để điền |
| Partial employer/period, reporting-to/client/project/training/education | Contract có partial; parser cần guard | Giữ context/issues; không thêm field/schema/loại việc làm mới |
| Dates YEAR/MM/YEAR/ISO, Present theo nguồn, taxonomy literal | Đã hỗ trợ | Giữ precision/future end/overlap hợp lệ, không dùng ngày hôm nay |
| Cross-page continuation, bilingual dedup | Có marker/alias hẹp | Chỉ nối ok pages và continuation xác minh; dedup không mất role/period khác |
| Layout xen cột, labels tách khỏi value, employer/role không còn quan hệ | Raw text có thể không đủ | AMBIGUOUS/issues; không tuyên bố khôi phục bố cục hoặc tự thêm OCR geometry |

Các giả thuyết đã đối chiếu: normalized summary sai ở PersonalInfoValidation, **không phải**
comparator publication (đã giữ newline). Summary collector chưa dừng contact labels, label matcher
lấy phần còn lại dòng, label riêng chỉ nhìn dòng liền kề. Header name còn index<5 và email cùng
PERSONAL. Một tab/ba spaces hiện đánh uncertain cả page. Đây là khảo sát source, regression
test-first sẽ xác minh từng thay đổi, không coi nhận định review cũ là kết quả chạy test.

### Đối chiếu mẫu local và giới hạn nguồn

Trong đúng thư mục được phép có 4 PDF, không có raw OCR đi kèm. Đã xem trang 1–2 mỗi PDF
và trích text các trang bằng thư viện PDF local; **PDF extraction không phải worker OCR**.
Quan sát cấu trúc: cover-letter-first rồi personal page, bảng label/value có khoảng cách,
địa chỉ nhiều dòng, period trước employer, overview period/company/title và nội dung tiếp trang.
Không khẳng định đã xem hình ảnh mọi trang hoặc đã xác minh thứ tự worker OCR.
PDF/text/render local chỉ ở build ignored; không đưa tên người, liên hệ, công ty, nội dung riêng,
tên file thật, raw OCR hay screenshot vào Git/PR. Fixture mới thay toàn bộ nội dung riêng tư.

## Chia phần triển khai và version

Chỉ tăng config revision không đủ: complete/replay tính lại theo engine job đã pin.
Giữ nguyên handler/helper engine `personal-info-mapping/3.0`, `experience-mapping/2.0` và v1.
Handler mới **opt-in `personal-info-mapping/3.1`**, vẫn config/result schema 3 và cùng DTO shape;
không migration, API/layout field mới, nâng job hay viết lại result. Mẫu mới có revision riêng.
Không tự activate cấu hình trên môi trường ứng dụng. Algorithm 3.1 được hoàn thiện trong task này
trước bàn giao; các commit trung gian là implementation chưa phát hành để vận hành.

1. **G1 — routing/revision/compatibility**: Definition/Result finite engine gate, MappingService,
   MappingRepository, PublicationService chỉ allowlist engine; PersonalInfoEngineV31 mới;
   MappingVersionsTests, MappingIntegrationTests, PersonalInfoPublicationIntegrationTests;
   JSON mẫu personal-info-config-v3.1.json. Không đổi canonical/digest cũ.
2. **G2 — personal grammar**: helper mới theo region/block → literal boundary → candidate/evidence
   → field validation → selection/dedup; không sửa helper 3.0. Chia lượt contacts/summary trước,
   rồi names/ownership. Test mới PersonalInfoGrammarTests và fixture mapping/personal-info/grammar.
   Tái dùng SourceLine khi đúng; không tạo framework/strategy mỗi tình huống.
3. **G3 — experience grammar**: helper ExperienceGrammar cho handler 3.1, test ExperienceGrammarTests,
   synthetic fixture mapping/experience-grammar; nested config experience v2 vẫn là dữ liệu rules/taxonomy.
   Engine experience 2.0 standalone và experience của personal 3.0 giữ nguyên thuật toán.
4. **G4 — gate/bàn giao**: Tester độc lập bổ sung regression thiếu, affected mỗi lượt, full gate trên
   source cuối hoặc CI; Reviewer đọc toàn diff. Commit theo phần logic, push feature, cập nhật PR Draft,
   chờ CI head mới. Không giảm assertion/skip/suppress, không sửa formatter/dependency/CI.

## Acceptance bổ sung

- **G-01** v1/v2/v3.0 reader/config/result/job và canonical/digest/replay giữ nghĩa; job mới 3.1
  chỉ từ config opt-in, mismatch engine/config bị chặn; persistence/publication cũ không đổi bảo vệ.
- **G-02** Alias contact/heading Việt–Anh theo checklist là literal hữu hạn; letter-spacing chỉ xử lý
  heading/label. Không canonicalize khoảng trắng trong raw value; unknown heading không fuzzy match.
- **G-03** Name có/không label, có title/page/blank trước, sidebar/personal trang sau cover letter;
  multi-line chỉ nối khi cùng block có bằng chứng. Không tên từ file/email/reference/company/manager.
- **G-04** Nickname/ngoặc giữ cách hiển thị, không chọn tên pháp lý; bỏ danh xưng đầu/cuối khỏi
  selected name, giữ raw evidence. Mâu thuẫn hoặc không đủ căn cứ thì abstain.
- **G-05** Inline nhiều field, icons/header/contact, label/value cách blank/colon riêng phải có span
  riêng; nhiều label trước nhóm value chỉ ghép khi type/structure chứng minh, trường hợp khác ambiguous.
- **G-06** Location nhiều dòng dừng label/section/website; phân biệt residence với relocation, nơi làm
  và địa chỉ công ty. Không đoán địa danh; giữ dấu câu như nguồn.
- **G-07** Email split chỉ nối có căn cứ, evidence từng span; domain case theo quy tắc cũ, không sửa OCR.
  Phone hỗ trợ quốc tế/ngoặc/extension; +84 (0) giữ raw, không âm thầm bỏ/thêm số trunk: giữ alternative/issue
  nếu notation chưa cho một số normalized duy nhất. Không suy quốc gia/E.164, DOB/ID/budget/count không là phone.
- **G-08** Duplicate giữ evidence; nhiều value khác nhau không có căn cứ chọn phải AMBIGUOUS.
  Gender chỉ bốn nhãn R-7, không suy honorific/ảnh/marital status, unknown raw/null/issue.
- **G-09** Summary giữ paragraphs/bullets/newlines trong raw và normalized phù hợp; dừng contact/education/
  skills/achievement/experience và section khác; PROFILE mixed không gom DOB/contact thành summary.
- **G-10** Intro không heading chỉ khi đủ ngữ cảnh; EXPERTISE/OVERVIEW mixed skills phân đoạn có căn cứ.
  Header/footer/page number không thành summary. Cross-page chỉ nối khi continuation được xác minh,
  không công bố đoạn cắt như đầy đủ; lỗi/gap page không được nối.
- **G-11** Reference alias không là keyword cắt mọi nội dung; contact subheading không đổi ownership.
  Tab/spacing không tự làm cả page ambiguous; chỉ thu hẹp uncertainty khi span/block chứng minh được.
  Sidebar xen cột không thể phân biệt vẫn giữ issue/AMBIGUOUS.
- **G-12** Experience nhận bounded period→company→title, company→title→period, title→company→period,
  inline company/period/ngoặc và pipe/slash có căn cứ; company/title nhiều dòng chỉ theo block evidence.
- **G-13** Overview/detail và bilingual alias chỉ dedup khi company/title/period tương thích duy nhất;
  cùng company nhưng role/period khác giữ riêng, employer period ngoài không tự áp role.
- **G-14** Missing title giữ partial contract; không lấy header/reporting-to/client/supplier/platform/
  project company/training CEO/study/education làm employer hoặc candidate title. Advisory/consulting/
  shareholder/co-founder context giữ raw/issues nếu contract chưa phân loại, không ép nghĩa.
- **G-15** Cross-page description/header continuation có marker + chủ thể và ok pages; không nối chỉ vì
  liền trang. Dates giữ YEAR precision, future end, Present/Now theo nguồn, overlap không tự sửa;
  không suy promotion date/dịch company/title/taxonomy từ filename/client list.
- **G-16** Mỗi grammar mới có positive/negative/ambiguous, đảo thứ tự text; test kiểm value/state/
  alternatives/issues/evidence, Unicode NFD/emoji/CRLF/multi-span, deterministic và input/result limits.
- **G-17** Raw PDF/OCR/result bất biến; remap không đè USER/CONFIRMED/EDITED, missing/invalid/disabled
  không xóa, không merge danh tính. Chạy compatibility/persistence bị ảnh hưởng trên schema riêng.
- **G-18** Config mẫu, grammar/giới hạn/activate-revision được cập nhật; báo local/CI đúng source và
  NOT VERIFIED khi thiếu prerequisite, không báo accuracy hay dùng synthetic để tuyên bố hỗ trợ mọi CV.

## Open Questions

- Không có worker OCR tương ứng trong thư mục được phép: đối chiếu worker reading order chưa kiểm chứng.
  Tiếp tục source và synthetic grammar; không đọc DB/runtime hoặc vị trí riêng khác để tìm thay.
- Layout không đủ quan hệ: đề xuất riêng OCR geometry/contract nếu cần về sau; task này giữ abstain,
  không sửa OCR schema/engine. Tên pháp lý từ nickname và quốc gia/trunk notation không được suy luận.

## Grammar triển khai và cách chọn revision

Handler 3.1 không chạy parser 3.0 để lấy envelope; trực tiếp kiểm source/gating và tạo fingerprint
bằng contract hiện có, sau đó chạy personal/experience grammar mới. Các handler/helper 1/2.0/3.0
và SourceLine cùng config mẫu cũ không đổi. Config mới: backend/src/main/resources/mapping/personal-info-config-v3.1.json.
Operator sao chép mẫu synthetic, thay literal labels/taxonomy theo dữ liệu được phép, cấp revision mới,
validate bằng MappingVersions.config(...).validate(); revision register phải trùng JSON và không tái dùng
cho nội dung khác. Đặt cv.mapping.definition-file vào file trusted, cv.mapping.revision trùng revision,
bật cv.mapping.enabled và restart theo luồng vận hành hiện có. Task không tự activate/remap môi trường ứng dụng.
Job mới pin engine/config/revision 3.1; job cũ vẫn dispatch theo engine đã pin. Không đổi result cũ/digest.

Grammar personal: literal aliases/heading canonical riêng; bounded header/personal context 32 meaningful
lines/4096 code points; inline field boundaries, typed detached labels, multiline address, split email,
phone quốc tế/extension. +84 (0) có issue và không tự biến thành số normalized khác. Nickname giữ display,
honorific Việt trùng token tên chỉ bỏ khi syntax/separator chứng minh. Summary ráp từ selected evidence,
giữ whitespace gap khi gap chỉ là whitespace, loại page noise đã bỏ; không lấy continuous range phủ noise.

Experience: typed period/company/title permutations và inline/parentheses, finite organization/title/config
anchors; missing title giữ partial. Overview/detail và alias song ngữ chỉ ghép unique compatible match;
role/period khác giữ riêng. Reporting/client/prose/education guards và advisory context giữ issues.
Continuation cần marker/employer khớp và chuỗi ok pages; dates giữ precision/future end/Present theo nguồn.
Không có organization/title anchor hoặc layout mất quan hệ thì abstain/issues; không suy từ proximity,
không dịch tên/taxonomy hoặc khôi phục arbitrary columns. Không tuyên bố hỗ trợ mọi CV.

## Bằng chứng và trạng thái kiểm chứng

- G1 test-first 7/2 FAIL trước reader3.1; affected legacy/version 51 PASS.
- G2 test-first 13 cases; personal 15 tests/30 fixture cases và permutations/evidence PASS trước Tester.
- G3 test-first 10/7 FAIL; experience 17 tests/14 fixture cases và permutations PASS.
- Tester tìm GR-T1 (cắt token Anh khỏi tên) và GR-T2 (page noise trở lại raw summary); hai regression FAIL
  trước sửa, giữ nguyên sau sửa. Tester thêm bounds summary/candidate để không truncate hoặc bỏ evidence.
- Source cuối Tester: **87 affected tests/5 classes PASS + 5 architecture/Modulith PASS**, 0 failure/error/skip,
  0 compile warning. Full PostgreSQL local NOT VERIFIED do port15439 không kết nối; không DB dev/reset.
- Logs/XML/summary/manifest ignored backend/build/reports/grammar-tester-final-*; manifest 21 file SHA256
  4d9a741b55b19aa50a8c904fe73830e6d1c144532e72333ef2295ed33c6dec1b. Reviewer/vòng/source và CI head cuối ghi PR.
- Secret scanner NOT VERIFIED nếu công cụ chưa có; không accuracy/corpus/gold report. Lát cắt 6 cũ vẫn chưa làm.
### Lượt sửa Reviewer 1 và source cuối Tester

GR-R1: overview không còn bị mất chỉ vì company trùng với detail role/period khác; link mơ hồ giữ
các role/evidence và issue. GR-R2: company không nhãn trong application/header không tạo employment
khi thiếu employment context. GR-R3: header context cache theo scope, dừng ở 32 meaningful lines/
4096 code points; literal patterns compile một lần. Không thêm timing benchmark.
Test-first ba regression có hai FAIL đúng R1/R2; bounded-header case là kiểm hành vi, không đo tốc độ.
Tester xác minh cả ba và thêm overview link nhiều detail/scope mới sau scope vượt giới hạn.
Source cuối: **97 test / 7 lớp PASS**, gồm legacy và kiến trúc/Modulith; 0 failure/error/skip,
0 compile warning. Full local persistence vẫn NOT VERIFIED (PostgreSQL test không sẵn); CI head cuối
là gate bắt buộc trước bàn giao. Manifest21 file grammar-tester-review-r2-final-sha256.txt SHA256
987ad5a9d05407fbeffdf45f2f6fb971ffa39a19792708cc0837e226650c5ac3. Reviewer vòng 2/CI ghi ở PR.