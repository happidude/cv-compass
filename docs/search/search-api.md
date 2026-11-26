# API tìm kiếm ứng viên D4a

Các endpoint yêu cầu đăng nhập USER hoặc ADMIN. `GET /api/candidates` và
`GET /api/candidates/{id}` giữ nguyên.

`GET /api/candidates/search` nhận `q`, `company` (luôn là tên), `companyId` (id), `title` (id hoặc tên),
`function`, `seniority`, `scope`, `industry`, `province`, `minYears`, `maxYears`,
`cursor`, `limit`. Các nhóm mã và tỉnh có thể lặp tham số; OR trong nhóm và AND
giữa các nhóm. Mã phải ACTIVE, đúng KIND. Tỉnh dùng tên chuẩn, ví dụ `Hà Nội`.
Số năm lọc là `floor(totalExperienceMonths / 12)`, bao gồm hai đầu. Khi có
`minYears` hoặc `maxYears`, hồ sơ chưa có số tháng (`null`) bị loại khỏi kết quả.

`company` luôn là tên dạng text, kể cả chuỗi toàn chữ số như `1080` hoặc `24`.
`companyId` nhận đúng một số nguyên dương kiểu long (tối đa `9223372036854775807`).
Chỉ gửi một trong `company` và `companyId`; gửi cả hai, kể cả `company` trống,
trả 400 với field `companyId`. Khi chọn công ty từ gợi ý danh mục, UI gửi
`companyId`; khi gõ tên tự do, UI gửi `company`.

Tên và từ khóa được fold bằng quy tắc catalog. Từ khóa khớp toàn văn hoặc word
trigram; tên công ty/chức danh ưu tiên toàn bộ entry khớp chính xác tên đã fold
hoặc alias DONE. Chỉ khi không có khớp chính xác mới dùng trigram, lấy tối đa
`resolve-limit` entry theo similarity giảm dần. Ngoài đối chiếu catalog, bộ lọc
có thể khớp chuỗi trong văn bản index khi chưa liên kết catalog. Công ty còn khớp
qua công ty con có parent tương ứng. `companyId` chỉ khớp `COMPANY_IDS` hoặc
`PARENT_COMPANY_IDS`, không tìm chuỗi id trong văn bản; id không tồn tại trả kết
quả rỗng. `company` toàn số vẫn dùng cùng quy tắc tên: NAME_FOLDED/alias DONE,
fallback trigram với `resolve-limit`, và LIKE trong `SEARCH_TEXT`. `title` giữ
hành vi id hoặc tên hiện có; title dạng id chỉ khớp catalog. Các ký tự `%`, `_`, `\`
trong bộ lọc văn bản là ký tự thường, không phải wildcard. Tham số sai trả 400 `ApiError`, với
`details[].field` chỉ tham số; lỗi mã taxonomy ưu tiên FUNCTION, SENIORITY, SCOPE,
INDUSTRY theo thứ tự cố định.

| Đầu vào sai | HTTP / code | `details[].field` |
| --- | --- | --- |
| `companyId=abc`, trống, số thập phân, ≤ 0 hoặc tràn long | 400 / `VALIDATION_ERROR` | `companyId` |
| `companyId` lặp (kể cả cùng giá trị) | 400 / `VALIDATION_ERROR` | `companyId` |
| Có cả `company` và `companyId` | 400 / `VALIDATION_ERROR`; thông điệp yêu cầu chỉ dùng một trong hai | `companyId` |

Ví dụ (sau khi đăng nhập):

```text
GET /api/candidates/search?company=1080
GET /api/candidates/search?company=24&limit=20
GET /api/candidates/search?companyId=22&title=Manager
```

Hai ví dụ đầu tìm tên và văn bản; ví dụ cuối lọc công ty id 22 trực tiếp hoặc
qua tập đoàn mẹ. `company=22&companyId=22` và `companyId=22&companyId=22` đều trả 400.

Response là `{content: [...], nextCursor: string|null, hasNext: boolean}`. Mỗi mục:

```json
{
  "id": 123,
  "fullName": "Hồ sơ minh họa",
  "currentTitle": {"id": 11, "name": "Chức danh minh họa", "aiNormalized": true},
  "currentCompany": {"id": 22, "name": "Công ty minh họa", "parentName": null, "aiNormalized": false},
  "province": "Hà Nội",
  "totalExperienceMonths": 35,
  "experienceEstimated": true,
  "score": 1090.000000,
  "matchReasons": ["COMPANY_DIRECT", "COMPANY_CURRENT", "TITLE_CATALOG", "TITLE_CURRENT"],
  "matchedCompany": {"id": 22, "name": "Công ty minh họa"}
}
```

`currentTitle`, `currentCompany`, `matchedCompany`, tỉnh và số tháng có thể null.
`aiNormalized` là `ORIGIN=AI`. `matchedCompany` ưu tiên công ty khớp trực tiếp,
sau đó tập đoàn mẹ; khớp văn bản đơn thuần không có entry thì null.
Tổng kinh nghiệm là số **DỰ TÍNH**, đồng bộ với `ExperienceTotals`;
`experienceEstimated` luôn true. API không trả hoặc lọc giới tính, ngày sinh, tuổi,
email hoặc điện thoại.

`matchReasons` giữ thứ tự: `COMPANY_DIRECT` (liên kết trực tiếp), `COMPANY_PARENT`
(qua tập đoàn mẹ), `COMPANY_TEXT` (tên trong văn bản), `COMPANY_CURRENT` (công ty
hiện tại hoặc parent khớp), `TITLE_CATALOG` (chức danh liên kết), `TITLE_TEXT`
(chức danh trong văn bản), `TITLE_CURRENT` (chức danh hiện tại), `KEYWORD_TEXT`
(`ts_rank > 0`), `KEYWORD_FUZZY` (word trigram).

`companyId` giữ các lý do `COMPANY_DIRECT`, `COMPANY_PARENT`, `COMPANY_CURRENT`
và thưởng tương ứng; không sinh `COMPANY_TEXT`. `company` giữ ngữ nghĩa xếp hạng
theo tên, kể cả tên toàn số.

Cấu hình dưới `cv.search.query`:

| Property | Mặc định |
| --- | --- |
| `default-limit` | 20 |
| `max-limit` | 50 (không vượt 50) |
| `suggest-limit` | 10 (không vượt 50) |
| `resolve-limit` | 5 (không vượt 50, chỉ giới hạn fallback trigram) |
| `trigram-threshold` | 0.3 (resolve entry và gợi ý, lớn hơn 0, tối đa 1) |
| `keyword-trigram-threshold` | 0.5 (word trigram của `q`, lớn hơn 0, tối đa 1) |
| `weights.company-direct` | 1000 |
| `weights.company-parent` | 600 |
| `weights.title-catalog` | 200 |
| `weights.title-catalog-bonus` | 30 (thưởng chức danh catalog trong bậc công ty) |
| `weights.company-current` | 40 |
| `weights.title-current` | 20 |
| `weights.company-text`, `weights.title-text` | 10 mỗi loại |
| `weights.keyword-text`, `weights.keyword-fuzzy` | 10 mỗi loại |

Điểm nền chọn bậc cao nhất: trực tiếp > tập đoàn mẹ > chức danh catalog > văn bản.
Cộng các thưởng văn bản/hiện tại; trong bậc công ty trực tiếp hoặc tập đoàn mẹ,
khớp chức danh catalog còn cộng `title-catalog-bonus`. Bậc nền chức danh không
cộng lại thưởng này. Điểm toàn văn dùng `ts_rank(...,32)` giới hạn
trong [0,1], nhân trọng số; fuzzy cộng trọng số khi khớp. Cấu hình khởi động bị từ
chối nếu bậc dưới cộng mọi thưởng có thể vượt bậc trên, kể cả làm tròn 6 chữ số.

Kết quả sắp `score DESC, id DESC`. Dùng nguyên `nextCursor` cho trang tiếp theo
và giữ các bộ lọc; cursor mờ base64url chứa score 6 chữ số và id. `hasNext=false`
thì `nextCursor=null`. Phân trang không tạo snapshot: cập nhật index hoặc đổi
trọng số giữa các request có thể thay đổi kết quả.

`GET /api/candidates/search/options` trả `{FUNCTION:[{code,label}], SENIORITY:...,
SCOPE:..., INDUSTRY:..., provinces:[...]}`: chỉ mã ACTIVE, nhãn tiếng Việt, tỉnh
distinct đang có trong index sắp theo tên bằng collation DB.

`GET /api/candidates/search/companies?q=...` trả tối đa `suggest-limit` mục
`{id,name,parentName}` theo prefix hoặc trigram của tên/alias DONE đã fold.
Từ khóa trống trả mảng rỗng. Chuỗi một ký tự vẫn dùng prefix/trigram theo ngưỡng,
giữ giới hạn và thứ tự xác định (tên chính xác trước, similarity giảm dần, tên rồi
id giảm dần khi bằng điểm). Chưa đăng nhập trả 401 cho cả ba endpoint.

Test hiệu năng tạo ít nhất 10.000 hồ sơ tổng hợp, ANALYZE rồi EXPLAIN chính SQL
API cho từ khóa chọn lọc 1%, mảng công ty (`companyId`, case `arrays`) và tên công ty.
Các case phải dùng index
SEARCH_TSV/SEARCH_TEXT, COMPANY_IDS/PARENT_COMPANY_IDS và SEARCH_TEXT tương ứng;
index FUNCTION_CODES không đủ để chứng minh case công ty. Nếu planner chọn scan, test dùng
`SET LOCAL enable_seqscan=off` để chứng minh GIN dùng được. Kết quả đo in
`SEARCH_PERF ...`, không đặt ngưỡng thời gian cố định.
