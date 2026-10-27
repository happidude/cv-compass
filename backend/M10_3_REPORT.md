# M10.3 — Báo cáo triển khai và quality gate

Ngày 27/10/2026. PR [#17](https://github.com/hoangnguyen229/cv-compass/pull/17)
vẫn mở, không merge. Branch `codex/m10-2-mapping-foundation`; worktree
`C:\Users\NguyenVanHoang\.codex\worktrees\m10-2-mapping-foundation\cv-compass`.
Baseline main `d0b7f81d02e2f5910aeea356c2322f91cca7e5bb`; head PR ban đầu
`2df987c3a9ea511ff9edb67fcadcf51d6154cb97`. Checkout gốc được giữ nguyên.

## Hai giai đoạn và cấu hình

Giai đoạn foundation hoàn tất trước khi viết M10.3, commit riêng
`0a0a733eee535c2057808723031d24f3efa77544`.
Local gate **PASS**: 371 tests, 50 suites, 0 failure/error/skipped,
7/7 tasks executed. CI **PASS** trên chính commit đó:
[Persistence CI 40520097375](https://github.com/hoangnguyen229/cv-compass/actions/runs/40520097375).
Chi tiết và manifest ở [FOUNDATION_CONFIGURATION_REPORT.md](FOUNDATION_CONFIGURATION_REPORT.md).

[MAPPING_OPERATIONS.md](MAPPING_OPERATIONS.md) có bảng trước/sau, default,
giới hạn, validation và tác động từng property. `cv.batch.max-files` là nguồn duy
nhất cho số file/batch nghiệp vụ, default 10, hỗ trợ 1–1000. Bỏ cấu hình import
trùng nghĩa; cấu hình cũ bị từ chối khi importer bật. Trang scan/reaper là giới hạn
truy vấn nội bộ cấu hình riêng, tiếp tục bằng cursor; không biến 100 extraction
thành một batch nghiệp vụ. Nhiều OCR revision cùng document vẫn là các input
mapping riêng. Concurrency mặc định 1, độc lập batch/page size. Lease/deadline,
attempt budget và backoff đi qua service/SQL/constraint V8. Restart cùng active
config giữ cursor/upper bound; chỉ đổi config mới bắt đầu sweep lại.

M10.3 nằm trong commit tiếp theo của PR; SHA chính xác được ghi trong PR và bàn
giao sau commit để tránh tự tham chiếu SHA trong chính file này.

## Kết quả triển khai

Config/result schema 2 và engine `experience-mapping/2.0` có revision rule/taxonomy.
Reader dispatch schema tường minh; worker xử lý cả job v1 cũ và v2 mới theo config
đã pin. Migration V9 additive chỉ mở thêm result schema 2. V1–V8 không sửa trong
giai đoạn M10.3. Golden bytes v1 được đối chiếu độc lập bằng DTO/mapper biên dịch
từ commit foundation, giữ record creator/declaration order thực tế của Jackson.
Không đổi digest hoặc diễn giải lại result cũ.

Parser hỗ trợ section/label Việt–Anh, câu employment có mẫu, dòng period|company|title,
dated roles cùng employer, quay lại employer, summary/detail dedup và continuation
có marker qua hai trang ok liền nhau. Ngành chỉ từ dictionary employer có basis;
function/seniority từ title aliases. Giữ raw, mô tả, precision YEAR/MONTH/DAY,
ongoing-as-stated, alternatives và evidence nhiều span theo code point raw page.
NFC/whitespace normalization có ánh xạ về grapheme nguồn. Invalid/conflict/possible
overlap, OCR partial và reading order chưa xác minh đều được đánh dấu.
Mọi kết quả dùng được vẫn NEEDS_REVIEW; không cập nhật candidate.

Chưa hỗ trợ layout không nhãn, narrative tự do, continuation không marker,
khôi phục thứ tự cột hoặc suy luận tên công ty mờ. Không suy ngành từ title,
không suy hiện còn làm từ Present, không suy team size từ headcount.
[MAPPING_EXPERIENCE_V2.md](MAPPING_EXPERIENCE_V2.md) mô tả grammar và bounds đầy đủ.

## Đánh giá chất lượng

7 tài liệu synthetic development tách ID/file với 17 tài liệu heldout synthetic;
heldout có 19 experiences được gán nhãn, parser trả 17. Chỉ commit dữ liệu tổng
hợp, không CV thật hay PII. Evaluator dùng multiset exact-match theo từng tài liệu;
linkage so sánh bộ company/title/period xác định, không ghép ba field từ ba job.
Period ambiguous/invalid không tính như gold period xác định; báo state riêng.

| Chỉ số | TP | FP | FN | Precision | Recall |
| --- | ---: | ---: | ---: | ---: | ---: |
| Employer | 17 | 0 | 2 | 100% | 89,47% |
| Title | 16 | 0 | 2 | 100% | 88,89% |
| Period xác định | 15 | 0 | 2 | 100% | 88,24% |
| Liên kết company–title–period | 14 | 0 | 2 | 100% | 87,50% |

Duplicate experience 0; false employer 0. Raw-field unknown 0/51 (0%), ambiguous
2/51 (3,92%), invalid 1/51 (1,96%); mẫu số là company/title/period của 17 experience
đã dự đoán, **không bao gồm employment bị bỏ sót**. Hai tài liệu có gold nhưng
không parse được: `eval-unlabeled-layout`, `eval-freeform-layout`.
Industry coverage 5/17 (29,41%; unknown 12/17), function coverage 15/17 (88,24%;
unknown 2/17). Hai experience được flag possible overlap.

Rule được chốt trước lần chạy heldout đầu. Sau đó ba lỗi phát hiện qua code review
độc lập được sửa và heldout chạy lại; kết quả cuối không phải một blind evaluation
chỉ mở đúng một lần. Không tune rule theo tên file/người hoặc để đạt score.
Các tỷ lệ trên chỉ mô tả bộ synthetic nhỏ, không có corpus thực đại diện hay nhãn
độc lập. Accuracy production **NOT VERIFIED**, không diễn giải rule score thành
xác suất hoặc dùng số precision 100% làm cam kết production.

## Kiểm thử cuối và evidence

M10.3 local gate **PASS** trên toàn bộ source cuối: **386 tests / 52 suites**,
0 failure/error/skipped; spotlessCheck, full check --rerun-tasks và modules.verify()
đạt. BUILD SUCCESSFUL 1m51s, 7 actionable tasks/7 executed. WSL Ubuntu 24.04,
Java 25.0.4.1, Gradle 9.7.1; PostgreSQL 17.11 container test riêng, port 55432;
helper tạo/xóa schema cô lập, không dùng DB dev. Credentials chỉ ở process env.

```sh
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessApply --rerun-tasks --console=plain
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
```

Init script ignored chỉ chuyển build output/cache sang Linux; không thay dependency,
formatter hoặc test exclusions. Evidence local ignored trong `build/m10-3/`:
`verified-check.log`, `verified-junit/TEST-*.xml`, `heldout-metrics.json`,
`legacy-canonical.log`, `verified-source-manifest.txt`.
SHA-256 manifest source:
`d4c7a03cb9e63c2662487ea6e7409a1bb666a6723c644c6399db9881285b95e0`.
Docs hoàn thiện sau gate, runtime/test source giữ nguyên theo manifest.
Không compiler/deprecation warning mới; gợi ý Gradle cache và advisory Git
LF/CRLF không phải warning bị suppress. CI M10.3 tại thời điểm tạo báo cáo còn
**NOT VERIFIED**, sẽ chạy trên SHA đã push và được ghi rõ trong PR/bàn giao.

## Review: đúng ba vòng

Một người thực hiện tuần tự các vai trò theo workflow, không dùng subagent.
Vòng 1 kiểm foundation trước commit: không finding còn mở.
Vòng 2 phát hiện ba Medium trong ExperienceEngine, đã sửa và thêm regression:

| ID | Vấn đề | Xử lý và xác minh |
| --- | --- | --- |
| M103-R1 | Lặp title khác case/alias làm mất raw hoặc tạo thừa role | So canonical identity; giữ raw đầu và alternatives/evidence; regression PASS |
| M103-R2 | Summary không ngày/ngày coarse có thể tạo employment trùng detail | Link unique hoặc issue ambiguity, so khoảng precision, giữ date alternatives; regression PASS |
| M103-R3 | Continuation bằng employer alias đã khai báo không được liên kết | Dùng canonical employer identity, vẫn yêu cầu marker/trang ok liền nhau; regression PASS |

Vòng 3 rà diff thực tế và file mới: version dispatch, migration additive,
claim/fencing/replay, raw spans, bounds, regression và số liệu/tài liệu. Các fix
được full gate cuối xác minh; không còn Critical/High/Medium hoặc finding phải sửa.
Không mở vòng review thứ tư. Không nới assertion, xóa test hoặc suppress warning.

## Quyết định so với M10.1 và giới hạn

- Giữ experience typed trong immutable result JSONB, chưa materialize bảng search
  hay publish candidate: giữ evidence/version nhất quán; chưa thể dùng trực tiếp
  như index tìm kiếm production.
- Registry mở bằng hai engine/schema rõ ràng và rule/alias literal có bounds:
  dễ tái lập/kiểm soát; thêm grammar mới cần đổi code/version phù hợp.
- Chỉ numeric DMY cùng ISO/month-name; không tự đoán MDY, thiếu ngày hoặc thứ tự cột:
  giảm repair sai nhưng bỏ sót các layout/narrative chưa hỗ trợ.
- Coarse/conflicting dates giữ alternatives, overlap chỉ POSSIBLE; bảo toàn nguồn
  nhưng cần review thủ công. Chỉ nối trang khi có bằng chứng tường minh.
- Taxonomy synthetic là ví dụ cấu hình; không catalog công ty production hay quan
  hệ tập đoàn. Unknown không đồng nghĩa không có kỹ năng/kinh nghiệm.
- Tên field camelCase theo DTO nội bộ hiện hữu; semantics/evidence theo thiết kế,
  chưa công bố thêm HTTP contract. Không AI/NER/embedding, UI/export, thay OCR
  contract/status/production gate hoặc triển khai milestone kế tiếp.

