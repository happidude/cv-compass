# Lõi trích xuất CV bằng LLM — L1a/L1b

Package Python độc lập theo src layout, chỉ dùng thư viện chuẩn. L1a cung cấp lõi
trích xuất; L1b thêm Ollama local, CLI batch, gold và so khớp tổng hợp, được chủ
dự án duyệt trong chat ngày 12/11/2026.

## Kiến trúc

`ocr_input` đọc OCR JSON UTF-8 strict, tối đa 8 MiB; `segmenter` tạo header và
khối kinh nghiệm giữ offset gốc. `contacts` lấy email/điện thoại/URL bằng regex.
`prompts` đóng khung dữ liệu không tin cậy; `LlmProvider` trả JSON thô.
`validate` kiểm kiểu/cấu trúc nghiêm, chuẩn hóa date và yêu cầu giá trị nằm trong
quote; `evidence` định vị chính xác hoặc chỉ khác khoảng trắng. `pipeline.extract`
gộp kết quả, giới hạn số khối và khử kinh nghiệm trùng.

Dùng `PYTHONPATH=src`, import `parse_document`, `extract` từ `cv_compass_llm`
và `FakeProvider` từ `cv_compass_llm.providers`. Provider giả nhận danh sách
JSON string/dict hoặc `ProviderError`; dict đơn là một response.
Personal response có root `personal`; experience response có root `experiences`.
Schema từng loại nằm trong `schema.py`; parser JSON nghiêm dùng chung ở `json_strict.py`.

Các dataclass frozen giữ dữ liệu trong bộ nhớ. `ExtractionResult.to_dict()`
trả cấu trúc có thể truyền cho `json.dumps`. Evidence dùng chỉ số ký tự Python
trên nguyên bản của từng trang; mọi giá trị bị loại đều có issue với tên field
cố định, không chứa nội dung đầu vào. Date trong kinh nghiệm dùng YYYY/YYYY-MM;
ongoing chỉ phản ánh từ khóa nguồn, không xác nhận người đó còn làm việc hôm nay.

## Chạy kiểm chứng

Trong PowerShell tại `llm-worker/`:

```powershell
$env:PYTHONPATH='src'
py -3.14 -m unittest discover -s tests -v
```

Nếu Ruff đã được provision, dùng `ruff format --check src tests` và
`ruff check src tests`. Không cài dependency hay công cụ để chạy package.
`ruff.toml` sao chép nguyên bản từ worker, không thay đổi cấu hình dùng chung.

## Privacy

Chỉ dùng fixture tổng hợp, không tên/người/công ty thật. Không print/log CV,
prompt, model output, exception của provider hay đường dẫn nguồn. Chỉ gửi dữ liệu
tới provider loopback và backend nội bộ được cấu hình cho `pull`. Ollama chỉ dùng
HTTP loopback, không proxy và không theo redirect.
FakeProvider giữ prompt trong bộ nhớ chỉ để test. Bên gọi chịu trách nhiệm
giới hạn vòng đời dữ liệu và không ghi/public hóa prompt đã ghi nhận. OCR gốc không
bao giờ bị sửa hoặc xóa, kể cả khi đọc/parse/trích xuất thất bại. Gender chỉ để hiển
thị, không dùng lọc hay xếp hạng.

## Quyết định thiết kế

- Evidence chỉ có một page: quote trải qua nhiều trang bị loại với QUOTE_NOT_FOUND;
  không nối trang để tạo evidence giả. Quote nhiều dòng cùng trang được hỗ trợ.
- Summary chỉ giữ giá trị nguyên văn có trong quote; không tạo bản tóm tắt.
  Title/company/location/fullName/summary cho phép khớp không phân biệt hoa thường
  và gộp khoảng trắng, nhưng luôn lưu đoạn nguyên văn từ nguồn; không so khớp mờ hay bỏ dấu.
  Casefold/lower chỉ dùng khi giữ độ dài từng ký tự; NFC chỉ dùng khi giữ độ dài.
  dateOfBirth nhận ngày đầy đủ hợp lệ lịch và chuẩn hóa thành YYYY-MM-DD;
  D/M/YYYY, D-M-YYYY, D.M.YYYY đọc ngày trước. Nếu ngày và tháng đều ≤ 12,
  giữ giá trị kèm DOB_DAY_FIRST_ASSUMED. YYYY/YYYY-MM và các dạng tháng/năm
  được chuẩn hóa nhưng không suy ra phần ngày/tháng còn thiếu; năm hai chữ số bị loại.
- Date khác độ chính xác được so theo khoảng có thể có; cùng năm không suy ra tháng.
  Khi period không hợp lệ, bỏ cả start/end/ongoing và evidence period, giữ role/company.
- Trong mode `blocks`, dòng đơn dài hơn 6000 ký tự không thể cắt ở ranh giới dòng: bỏ dòng đó và sinh
  BLOCK_LINE_TOO_LONG. Không ép cắt giữa dòng hoặc gửi khối vượt giới hạn.
- Gender chỉ được giữ khi quote có biên token và dòng OCR gốc là giá trị đứng riêng
  hoặc nhãn Giới tính/Gender với dấu hai chấm hoặc gạch nối; không suy từ tên/địa danh.

## E6 — Header cá nhân và ranh giới công việc

Trong mode `blocks`, header lấy từ trang đầu tiên trong ba trang `ok` đầu có
email/điện thoại theo regex contacts hoặc nhãn thông tin cá nhân; nếu không có,
dùng trang `ok` đầu tiên. Header luôn giữ tối đa 3000 ký tự từ đầu trang đã chọn.
`fullName` là tiêu đề chung (`curriculum vitae`, `cv`, `resume`, `résumé`,
`sơ yếu lý lịch`, `lý lịch`) sau casefold và gộp khoảng trắng bị loại với
`QUOTE_NOT_FOUND` field `fullName`, áp dụng cả blocks và whole.

Khi khối kinh nghiệm vượt 6000 ký tự, ưu tiên cắt trước ranh giới công việc gần
nhất phía trước: dòng có date kèm dấu/từ chỉ khoảng, từ present hoặc mở đầu bằng
`From`/`Từ`. Nếu không có ranh giới phù hợp, cắt theo dòng như cũ; không khối nào
vượt 6000 ký tự và không lặp dòng giữa các khối. Offset và nội dung nguồn giữ nguyên.

## L1b — Ollama local và CLI batch

Chạy từ `llm-worker/`, với Ollama local và model đã được chuẩn bị riêng. Ví dụ
dưới đây dùng các thư mục dữ liệu tổng hợp ngoài repo; mỗi lượt `run` cần output
mới hoặc rỗng, nằm ngoài repo và tách khỏi input:

CLI tìm repo chứa package bằng cách đi lên từ vị trí package tới `.git` (file hoặc
thư mục). Nếu không tìm thấy, `run` và `draft-gold` từ chối mọi output với
`UNSAFE_OUTPUT` (fail-closed), kể cả khi output ngoài thư mục package. Vì vậy bản
sao `git archive` không có `.git` không dùng để chạy hai lệnh ghi này. Test hàm lõi
truyền `repo_root` là repo giả trong thư mục tạm; test CLI patch hàm tìm repo.
CLI không cung cấp tùy chọn hay biến môi trường để đổi ranh giới repo.

```powershell
$env:PYTHONPATH='src'
py -3.14 -m cv_compass_llm run --input C:\cv-compass-synthetic\ocr --output C:\cv-compass-synthetic\run-01 --limit 10
py -3.14 -m cv_compass_llm draft-gold --results C:\cv-compass-synthetic\run-01 --output C:\cv-compass-synthetic\gold
py -3.14 -m cv_compass_llm compare --results C:\cv-compass-synthetic\run-01 --gold C:\cv-compass-synthetic\gold --json
```

`run` đọc `*.json` trực tiếp trong input theo thứ tự tên, không đi vào thư mục con.
`--limit` không bắt buộc; giá trị 0 tạo lượt rỗng. `--max-blocks` mặc định 20.
Mỗi file tạo `<stem>.result.json` (kết quả có evidence và `seconds`) hoặc
`<stem>.error.json` (chỉ mã lỗi đọc/parse OCR), rồi tiếp tục file kế tiếp.
`run-summary.json` chứa `files`, `ok`, lỗi và `issues` theo mã, `issues_by_field`
theo `code.field` (chỉ `code` khi field là null), `total_seconds`,
`average_seconds`, `provider`, `prompt_version`, `mode`, `whole_fallbacks`. Thời gian tổng/trung bình của
lượt batch gồm cả file lỗi và ghi artifact; `seconds` của result đo đọc/trích xuất
trước khi ghi file. Lỗi provider vẫn theo contract L1a: issue `PROVIDER_ERROR`,
không làm hỏng cả file. Lỗi HTTP cụ thể có mã cố định ở ranh giới provider.

Provider nhận `http://127.0.0.1`, `http://localhost`, `http://[::1]` với port tùy
chọn; không nhận credential, path tùy ý, dấu `?` hoặc `#` kể cả phần theo sau rỗng.
Timeout phải hữu hạn, dương và áp dụng cho từng thao tác socket, không phải hạn
chót của toàn bộ request/lượt batch. Response tối đa 4 MiB. CLI không có provider `fake`;
test gọi `run_batch`
với `FakeProvider`, HTTP test dùng server giả trong tiến trình và không cần Ollama.
stdout/stderr chỉ có số tổng hợp, nhãn cố định và mã lỗi; không in đường dẫn hay
giá trị trích xuất. Mã thoát 0 khi hoàn tất (kể cả từng file lỗi), 2 khi tham số,
an toàn đường dẫn hoặc I/O của lệnh không hợp lệ. Lỗi ngoài dự kiến chỉ in
`INTERNAL_ERROR`, không traceback hay message exception. Không ghi/xóa trong input.

## Gold v2 và so khớp

Gold v2 gồm đúng `schemaVersion: "experience-gold/2"`, `status`, `personal`,
`experiences`. Mỗi công ty chứa danh sách `positions` không rỗng, ví dụ tổng hợp:

```json
{
  "schemaVersion": "experience-gold/2",
  "status": "DRAFT",
  "personal": {"fullName": null, "gender": null, "location": null, "dateOfBirth": null},
  "experiences": [{
    "company": "Acme Synthetic", "companyLocation": null,
    "start": "2018", "end": "2024", "ongoing": false,
    "positions": [
      {"title": "MANAGER", "start": "2020", "end": "2024", "ongoing": false},
      {"title": "EXECUTIVE", "start": "2018", "end": "2020", "ongoing": false}
    ]
  }]
}
```

Các object phải đủ và đúng tập key; giá trị chuỗi có thể null, `ongoing` phải là
boolean. `company` và `title` không được cùng null trong một vị trí. Date kinh
nghiệm nhận `YYYY` hoặc `YYYY-MM` với tháng 01–12; `dateOfBirth` nhận thêm
`YYYY-MM-DD` hợp lệ lịch. `ongoing=true` buộc `end=null`. Gold không có evidence,
contacts, summary hay issue. Sai định dạng trả `INVALID_GOLD`.

Gold v1 không có `schemaVersion` vẫn đọc được: mỗi mục phẳng thành một vị trí,
giữ kiểm tra kiểu dữ liệu cũ để tương thích (kiểm nghiêm ở trên áp dụng cho v2),
gộp các mục **liền nhau** có company khác null và bằng nhau sau NFC, casefold,
gộp/strip khoảng trắng. Giữ thứ tự và chuỗi công ty đầu tiên; company null không
gộp, cùng công ty xuất hiện lại sau công ty khác vẫn là nhóm riêng. Quy tắc này
cũng áp dụng cho draft và kết quả trích xuất phẳng khi so khớp.

`draft-gold` chỉ tạo trạng thái `DRAFT`; con người kiểm tra, sửa và đổi thành
`CONFIRMED`. Nháp luôn là v2, `companyLocation=null`, kỳ công ty có
`start=null`, `end=null`, `ongoing=false` để người duyệt điền khoảng chung nếu CV
có ghi; vị trí giữ date của từng mục. Lệnh cho phép thư mục gold đã có nội dung,
bỏ qua mọi gold đã tồn tại và đếm `skipped_existing`; không ghi đè.
Output vẫn phải ngoài repo, tách khỏi
results. `draft-gold` không biết thư mục OCR input của lần `run` trước: không chọn
output trùng hoặc nằm trong thư mục OCR đó. File result không hợp lệ được đếm
trong `errors`.

`compare` ghép đúng stem, chỉ dùng gold `CONFIRMED`, đếm `skipped_draft`,
`missing_results`, `unpaired_results`, `invalid_gold`, `invalid_results` và
`compared`. `unpaired_results` đếm result không có file gold tương ứng; gold DRAFT
hoặc gold không hợp lệ vẫn là file gold tương ứng. `invalid_gold` đếm file gold
không hợp lệ; `invalid_results` chỉ kiểm/đếm result ghép với gold CONFIRMED hợp lệ.
File lỗi không đóng góp số liệu.
So sánh dùng NFC, casefold và gộp/strip khoảng trắng; null khác chuỗi rỗng.
Personal có `correct`, `wrong`, `missing`, `extra`; hai phía cùng null tính là
`correct`.

Kỳ công ty dùng nguyên bộ `(start, end, ongoing)` đã ghi trong gold nếu có start,
end hoặc ongoing; nếu chưa có thì suy từ vị trí: start sớm nhất, end muộn nhất,
ongoing nếu bất kỳ vị trí nào ongoing (khi đó end=null). Kết quả trích xuất luôn
suy kỳ công ty từ vị trí. Khi chọn biên, `YYYY` sớm là `YYYY-01`, muộn là
`YYYY-12`; giữ chuỗi gốc được chọn, không tự thêm tháng. Kỳ hiệu lực vị trí dùng
date riêng khi có start **và** (end hoặc ongoing), còn lại dùng kỳ công ty.
Hai phía áp dụng cùng quy tắc; các key date vẫn so khớp nguyên giá trị đã chọn.

Kinh nghiệm dùng bốn nhóm multiset trong từng CV:

| Nhóm | Key |
| --- | --- |
| `companies` | company |
| `companyPeriods` | (company, start, end, ongoing) của công ty |
| `titles` | (company, title) |
| `positions` | (company, title, kỳ hiệu lực vị trí) |

**`positions` là chỉ số bộ ba dùng cho ngưỡng 90%.** Mỗi nhóm có `matched`,
`predicted`, `gold`, `precision`, `recall`; multiset không cho dự đoán lặp khớp
vượt số lần xuất hiện trong gold. Key thiếu company bị loại; `titles` và
`positions` cần thêm title khác null. `companyPeriods` loại kỳ hoàn toàn chưa
biết `(null, null, false)`. Các key bị loại không đóng góp mẫu số.
`companyLocation` chưa chấm vì kết quả chưa có field này; output ghi
`"companyLocation": "NOT_SCORED"`.

Mẫu số 0 cho tỷ lệ 0. `average_seconds` chỉ tính các CV đã so khớp. Không dùng
thứ tự experience để ghép, không tìm gần đúng hay suy ra danh tính.
Không có `--json` thì in từng chỉ số; có cờ thì in cùng số liệu JSON.

Artifact result/gold chứa giá trị tổng hợp riêng tư của lượt chạy: giữ ngoài Git,
không đưa vào log hoặc artifact công khai. Giữ nguyên OCR đầu vào khi có lỗi.

## Cấu hình `run`

Sao chép `.env.example` thành `llm-worker/.env`, rồi điền tag model đã chuẩn bị
vào `CVC_LLM_MODEL`. Ví dụ `run` ở trên dùng provider/model từ file này.
Provider và model bắt buộc phải được đặt; không có model mặc định.
Bộ đọc chỉ dùng thư viện chuẩn, không thay đổi `os.environ`.

Mỗi khóa lấy giá trị theo thứ tự **CLI > biến môi trường > file `.env` > mặc định**.
Giá trị rỗng hoặc chỉ khoảng trắng được coi là chưa đặt. File mặc định được tìm
từ vị trí package, không phụ thuộc thư mục làm việc. Có thể chọn file khác bằng
`run --env-file PATH`. File mặc định không tồn tại được bỏ qua; file chọn tường minh
không tồn tại trả `ENV_FILE_NOT_FOUND`.

| Biến | Tham số CLI | Mặc định / giới hạn |
| --- | --- | --- |
| `CVC_LLM_PROVIDER` | `--provider` | Bắt buộc, chỉ `ollama` |
| `CVC_LLM_MODEL` | `--model` | Bắt buộc, tag model đã chuẩn bị |
| `CVC_LLM_BASE_URL` | `--base-url` | `http://127.0.0.1:11434`, chỉ HTTP loopback như trên |
| `CVC_LLM_TIMEOUT_S` | `--timeout` | 120, số hữu hạn lớn hơn 0 |
| `CVC_LLM_MODE` | `--mode` | `blocks`; chỉ nhận `blocks` hoặc `whole` |
| `CVC_LLM_WHOLE_TIMEOUT_S` | `--whole-timeout` | 900, số hữu hạn lớn hơn 0; chỉ dùng cho whole |
| `CVC_LLM_MAX_BLOCKS` | `--max-blocks` | 20, số nguyên từ 0 |
| `CVC_LLM_TEMPERATURE` | `--temperature` | 0, số hữu hạn từ 0 đến 2 |
| `CVC_LLM_THINK` | `--think {true,false}` | false; file/môi trường không phân biệt hoa thường |
| `CVC_LLM_NUM_CTX` | `--num-ctx` | 8192; số nguyên từ 1 đến 1048576 |

Với VRAM 4 GB, context mặc định 8192 có thể tràn sang CPU; giảm
`CVC_LLM_NUM_CTX` nếu xử lý chậm.

File `.env` phải là UTF-8 strict, tối đa 64 KiB. Dòng trống và dòng bắt đầu bằng
`#` được bỏ qua; dòng còn lại có dạng `KEY=VALUE`, bỏ khoảng trắng ở hai đầu.
Value có thể được bao bởi một cặp nháy đơn hoặc kép; không nội suy biến hay xử lý
escape/comment cuối dòng. Khóa không thuộc `CVC_LLM_*` được bỏ qua sau khi kiểm
cú pháp; khóa `CVC_LLM_*` không biết hoặc lặp bị từ chối để tránh cấu hình mơ hồ.
File sai cú pháp, UTF-8, kích thước hoặc không đọc được trả `INVALID_ENV_FILE`.
File vẫn được kiểm tra khi CLI đã cung cấp đủ giá trị. `.env` được Git bỏ qua;
chỉ `.env.example` được track, không ghi bí mật hoặc nội dung CV vào cấu hình.

Thiếu provider/model trả `MISSING_CONFIG`; provider không hợp lệ trả
`INVALID_PROVIDER`. Giá trị sai trả mã tương ứng `INVALID_TIMEOUT`,
`INVALID_MAX_BLOCKS`, `INVALID_TEMPERATURE`, `INVALID_THINK`, `INVALID_NUM_CTX`.
Mode và timeout whole sai trả `INVALID_MODE`, `INVALID_WHOLE_TIMEOUT` tương ứng.
Base URL không hợp lệ trả `OLLAMA_HOST_NOT_LOCAL`. Sai cú pháp CLI (kể cả
`--think` ngoài hai lựa chọn) trả `INVALID_ARGUMENTS`. Lỗi chỉ in mã cố định,
không in giá trị, tên khóa lạ, nội dung file hay đường dẫn; mã thoát là 2.
`draft-gold` và `compare` không đọc cấu hình này.

## E5 — Trích xuất toàn CV bằng mode whole

Mặc định vẫn là `blocks`. Bật `run --mode whole` hoặc đặt `CVC_LLM_MODE=whole`
trong môi trường tiến trình/`.env`; ưu tiên CLI > môi trường > `.env`. `pull`
chọn mode qua cấu hình môi trường/`.env`. Whole gửi toàn bộ text các trang `ok`
trong một lời gọi với schema gộp `{personal, experiences}` để lấy các vị trí công việc
ngoài heading kinh nghiệm. Prompt và schema của lời gọi whole giữ nguyên bản cũ;
pipeline bỏ giá trị và issue personal của lời gọi đó rồi gọi personal riêng trên header.
Contacts regex, kiểm evidence/ngày và khử kinh nghiệm trùng vẫn dùng quy tắc chung.
Evidence luôn thuộc một trang; whole thông thường báo tiến độ `1/2`, `2/2` và
không dùng `max_blocks`. Lời gọi personal dùng context/timeout thông thường.

Context được chọn tự động từ 8192, 16384, 24576, 32768 theo
`ceil(len(prompt) / 2.5) + 4096`, thay cho `CVC_LLM_NUM_CTX` ở lời gọi whole.
Timeout whole mặc định 900 giây, đổi bằng `--whole-timeout` hoặc
`CVC_LLM_WHOLE_TIMEOUT_S`; timeout này cũng áp dụng cho từng thao tác socket.
Nếu ước lượng vượt 32768, CV đó dùng lại `blocks` với context/timeout/giới hạn block
thông thường; issue nội bộ `WHOLE_TOO_LONG` được đếm trong `run-summary.json`
(`whole_fallbacks` là số CV fallback). Pull ghi `mode=whole fallback=blocks`
trong `extraction_done` và không gửi issue này cho backend.

`prompt_version` của kết quả/batch whole và `provenance.promptVersion` lúc claim
của pull whole luôn là `llm-extraction-prompt/whole-2`, kể cả CV fallback blocks,
để provenance lúc submit khớp claim. Mode blocks dùng `llm-extraction-prompt/3`.
Log `worker_start` và `extraction_done` ghi mode, không chứa nội dung CV.

Với VRAM 4 GB, các bậc 16k/24k có thể đẩy một phần xử lý sang CPU, làm tăng thời
gian chạy. Người dùng có thể tự đặt `OLLAMA_FLASH_ATTENTION=1` và
`OLLAMA_KV_CACHE_TYPE=q8_0` cho tiến trình Ollama; worker không đặt các biến này.
Whole ưu tiên độ đầy đủ; đo trên cùng bộ OCR tổng hợp và cùng gold đã xác nhận:

```powershell
py -3.14 -m cv_compass_llm run --input C:\cv-compass-synthetic\ocr --output C:\cv-compass-synthetic\run-blocks --mode blocks
py -3.14 -m cv_compass_llm run --input C:\cv-compass-synthetic\ocr --output C:\cv-compass-synthetic\run-whole --mode whole --whole-timeout 900
py -3.14 -m cv_compass_llm compare --results C:\cv-compass-synthetic\run-blocks --gold C:\cv-compass-synthetic\gold --json
py -3.14 -m cv_compass_llm compare --results C:\cv-compass-synthetic\run-whole --gold C:\cv-compass-synthetic\gold --json
```

So sánh metric từ `compare` cùng `total_seconds`, `average_seconds` và
`whole_fallbacks` trong summary; mỗi lượt cần thư mục output mới hoặc rỗng ngoài repo.

### E8 — Personal và summary

Cả hai mode chọn trang có dấu hiệu personal trong ba trang `ok` đầu tiên (E6),
mặc định trang `ok` đầu tiên, rồi thêm trang liền kế nếu trang đó có text `ok`.
Header tối đa 8000 ký tự kể cả dấu xuống dòng nối trang; offset vẫn tính theo
code point của từng trang nguồn.

Schema personal riêng nhận summary là `null` hoặc danh sách 1–8 đoạn
`{quoteStart, quoteEnd}`. Model chép khoảng 5–12 từ đầu/cuối mỗi đoạn Summary,
Profile, Career Objective, Tóm tắt, Định hướng nghề nghiệp hoặc Mục tiêu nghề nghiệp.
Validator lấy nguyên văn giữa hai mốc, nối các phần theo thứ tự đọc bằng `\n`,
tổng tối đa 6000 ký tự. Đoạn vắt trang tách thành evidence cho từng trang;
toàn summary tối đa 8 evidence, tăng theo `(page, start)` và không chồng nhau.
Mốc thiếu, ngược, chồng lấn hoặc kết quả quá dài bị bỏ kèm `QUOTE_NOT_FOUND.summary`.

Matcher chỉ dành cho personal chịu khác biệt nháy cong/thẳng, gạch `–—‐`/`-`,
khoảng trắng quanh dấu câu và NFD/NFC, giữ offset và giá trị nguyên văn nguồn.
Không sửa chữ/số hay bỏ dấu. Phone nhận cả ký tự OCR rời, dấu chấm và mã `(+84)`;
loại chuỗi ngày/năm, giữ nguyên văn số điện thoại. Số bắt đầu bằng `0` nhận 9–10 chữ số sau số 0
(di động và máy bàn như `028 3822 1234`).

`FieldValue.evidence` giữ phần đầu; `additional_evidence` chứa các phần tiếp theo
của summary, rỗng với field thông thường. Payload compact gửi summary evidence
là object khi một phần, mảng khi nhiều phần, theo contract E9.

## L5 — Worker kéo việc từ backend

Lệnh `pull` nhận từng job qua `/internal/mapping/v1`, tải OCR vào bộ nhớ, dùng
provider L1 đã cấu hình và gửi kết quả bản gọn `cv-compass.llm-mapping/1.0`.
Không truy cập DB, không ghi CV/prompt/output ra đĩa. Heartbeat chạy trong thread
riêng theo khoảng thời gian backend trả; trích xuất chạy ở thread chính.

Thiết lập `CVC_LLM_PROVIDER`, `CVC_LLM_MODEL` và các tùy chọn Ollama như L1b trong
`llm-worker/.env`. Thêm `CVC_LLM_BACKEND_URL` (chỉ origin, không dấu `/` cuối):
HTTP chỉ cho `127.0.0.1`, `localhost`, `[::1]`; máy khác bắt buộc HTTPS.
Không dùng proxy hệ thống và không theo redirect, kể cả redirect cùng host.

Token do chủ dự án cấp, chỉ truyền qua **biến môi trường tiến trình**
`CVC_LLM_WORKER_TOKEN`, gồm 43–256 ký tự `A-Z a-z 0-9 _ -`. Không có flag CLI
nhận token. Có key token trong `.env` là lỗi `TOKEN_IN_ENV_FILE`, kể cả khi môi
trường đã có token hợp lệ. Không commit `.env`; `.env.example` chỉ có giá trị giả.
Không ghi token thật vào lịch sử lệnh, log hay tài liệu.

Sau khi token đã được cấp vào môi trường tiến trình, chạy tại `llm-worker/`:

```powershell
$env:PYTHONPATH='src'
py -3.14 -m cv_compass_llm pull
# Xử lý tối đa một attempt (204 thì thoát thành công):
py -3.14 -m cv_compass_llm pull --once
# Chọn file cấu hình khác, cùng quy tắc parser L1:
py -3.14 -m cv_compass_llm pull --env-file .env --once
```

| Biến bổ sung | Mặc định | Giới hạn |
| --- | --- | --- |
| `CVC_LLM_BACKEND_URL` | bắt buộc | origin HTTPS hoặc HTTP loopback |
| `CVC_LLM_WORKER_TOKEN` | bắt buộc | chỉ môi trường tiến trình |
| `CVC_LLM_WORKER_VERSION` | `dev` | `[A-Za-z0-9._:/@+-]{1,128}` |
| `CVC_LLM_POLL_S` | `5` | số nguyên 1–300 |
| `CVC_LLM_HTTP_TIMEOUT_S` | `10` | số nguyên 1–120 |
| `CVC_LLM_MAX_HTTP_ATTEMPTS` | `3` | số nguyên 1–10 |
| `CVC_LLM_LOG_LEVEL` | `INFO` | `DEBUG`, `INFO`, `WARNING`, `ERROR` |

Model phải thỏa cùng regex provenance như worker version. Cấu hình vẫn ưu tiên
môi trường trên `.env`; `--log-level` ưu tiên trên môi trường và `.env`.
Giá trị mức log sai trả `CVC_LLM_LOG_LEVEL INVALID_LOG_LEVEL`, không in giá trị.

Mỗi request retry tối đa theo cấu hình, chỉ khi lỗi mạng/timeout/503, backoff
1, 2, 4 rồi tối đa 8 giây. Claim retry giữ nguyên body, Idempotency-Key và lease
token, kể cả khi poll tiếp sau một đợt retry chưa rõ kết quả. Result/failure
retry giữ nguyên byte; không đổi sang failure sau khi đã bắt đầu gửi result.
204 được poll lại bình thường; 503/lỗi mạng đã hết lượt retry thì chờ `POLL_S`.
Với `--once`, claim hết lượt retry thì thoát 2.

Heartbeat được gửi sau tối đa nửa TTL lease còn lại hoặc một heartbeat interval,
tùy khoảng nào ngắn hơn, để gia hạn cả lease ngắn hơn interval đã khai báo.
Heartbeat mất fencing/xác thực hoặc hết lượt retry thì bỏ outcome để backend
thu hồi lease. Worker không gửi outcome sau thời hạn lease đã biết. Khi còn
một khoảng heartbeat đến hạn chót, supervisor gửi `PROVIDER_TIMEOUT` nếu còn
lease và bỏ kết quả trả về muộn. Provider đang chạy có thể chỉ trả quyền điều
khiển khi hết timeout riêng của nó; worker đợi trước khi claim job tiếp theo.
Ctrl+C gửi `WORKER_SHUTDOWN` best effort nếu chưa gửi outcome, rồi thoát 130.

Kết quả có đúng 7 key personal; email/phone đầu tiên theo page/offset vào
personal, phần còn lại và URL vào `other`. Field tắt là null, experiences tắt
là `[]`, `other` tắt là `[]`; vượt `maxItems` thì giữ thứ tự và thêm
`OTHER_TRUNCATED`. Không cắt kết quả vượt 1 MiB. Issue của L1 dùng field con
kinh nghiệm được đổi thành `experiences`; chỉ mã issue do worker sở hữu được gửi.
Email/phone tắt cũng bị loại khỏi `other`. Evidence của field chuỗi, kể cả summary một phần, được thu hẹp
về đúng lát cắt của value trong quote, so theo Unicode whitespace của backend,
giữ offset code point và nguyên văn nguồn. Summary nhiều phần giữ từng evidence nguồn,
kiểm giá trị bằng các text nối theo thứ tự sau chuẩn hóa khoảng trắng; mọi summary
vẫn tối đa 6000 ký tự. Không tìm được thì bỏ field kèm
`QUOTE_NOT_FOUND`; gender, dateOfBirth và period giữ quote gốc. Ngày ngoài giới
hạn verifier hoặc period sai thứ tự/ongoing có end bị bỏ kèm `INVALID_DATE`;
period lỗi được xóa đồng thời start/end/ongoing và evidence period.
Payload nhận DOB `YYYY`, `YYYY-MM` hoặc `YYYY-MM-DD` hợp lệ lịch, năm 1900–2100;
period chỉ nhận `YYYY` hoặc `YYYY-MM`, năm 1950–2100. Parser nhận tháng tiếng Anh
nối năm bằng khoảng trắng hoặc `-`, `–`, `—` (ví dụ `Jan -2019`), và DOB có ngày
như `July 28th,1982`/`28th July 1982` mà không gắn `DOB_DAY_FIRST_ASSUMED`.
Parser DOB hiện chỉ nhận năm 1950–2100 theo giới hạn có sẵn trong `_parts`;
payload chấp nhận năm 1900–2100 như mô tả ở trên.

Stdout chỉ có số đếm `claimed=… accepted=… failed=…` và mã lỗi cố định kèm số
đếm; không có body, đường dẫn, token hoặc thông tin ứng viên. Mã lỗi backend
ngoài tập contract đã biết được báo chung `BACKEND_HTTP_ERROR` để tránh phản
chiếu dữ liệu tùy ý. Lỗi cấu hình chỉ nêu tên biến và mã cố định.

Test L5 dùng `ThreadingHTTPServer` loopback và provider giả trong tiến trình,
chạy cùng lệnh unittest ở trên; không cần backend/Ollama/CV thật.

### Log tiến trình và kiểm tra kết nối

`pull` ghi từng sự kiện qua `logging` ra stderr, flush mỗi dòng, giờ local không
mili giây: `YYYY-MM-DD HH:MM:SS INFO event=claimed attempt=a0000000 engine=llm-extraction/1.0`.
Các sự kiện gồm `worker_start`, `waiting`, `claimed`, `input_fetched`,
`extraction_block`, `extraction_done`, `submitting`, `accepted`, `failed`,
`heartbeat_lost`, `backend_error`, `provider_error`, `stopped`; `heartbeat` chỉ ở DEBUG.
`waiting` xuất hiện khi hết việc, 503 hoặc không kết nối được backend, tối đa một
lần mỗi 60 giây cho cùng mã trong đợt chờ. Khi backend trả lời thành công, bộ đếm
thời gian lỗi kết nối được reset; các poll hết việc liên tiếp vẫn không lặp log.
Tiến độ block tính cả lời gọi personal và các block kinh nghiệm thực
sự xử lý (sau giới hạn `max_blocks`); chỉ số bắt đầu từ 1.
Log chỉ có metadata: attempt rút còn 8 ký tự, số trang/ký tự, thời gian, tổng issue
theo mã và field cố định (ví dụ `issues=INVALID_DATE.start:1,QUOTE_NOT_FOUND.title:2`;
field null chỉ ghi mã, `issues=NONE:0` khi không có issue). Allowlist mã giữ nguyên.
Model dạng URL/đường dẫn
tuyệt đối được che bằng `REDACTED`. Origin chỉ hiện host:port; không ghi OCR, prompt, output, evidence,
giá trị ứng viên, token/hash, URL đầy đủ, đường dẫn hay message exception.
Logger riêng chỉ được cấu hình trong lúc chạy lệnh; stdout bộ đếm giữ nguyên.

```powershell
py -3.14 -m cv_compass_llm pull --log-level DEBUG
py -3.14 -m cv_compass_llm check --env-file .env
```

`check` chỉ đọc: kiểm cấu hình pull (token bắt buộc trong môi trường tiến trình),
gửi `GET /internal/mapping/v1/check` tới backend và `GET /api/tags` tới Ollama.
Không claim, tải input, gia hạn lease, gửi result/failure hay gọi model.
Filter `MappingWorkerAuthenticationFilter` và provider L4 chỉ kiểm cấu hình/token,
không ghi DB; security chain áp dụng cho toàn `/internal/mapping/**`.
Controller không khai báo route `check`. Chỉ 404 kèm envelope lỗi CV Compass hợp
lệ với `code=NOT_FOUND` được coi là token được chấp nhận. Tương tự, chỉ 401 kèm
`UNAUTHORIZED` được báo token không hợp lệ; 503 kèm `MAPPING_REMOTE_UNAVAILABLE`
và 403 kèm `TLS_REQUIRED` giữ mã cố định đó. 401 kèm `AUTH_REQUIRED`, body không
đúng envelope, hoặc cặp status/code khác trả `BACKEND_PROBE_REJECTED`, không kết
luận token đúng/sai (redirect là `BACKEND_REDIRECT`).
Không theo redirect/proxy; response backend giới hạn 64 KiB, Ollama 4 MiB và
timeout theo từng cấu hình.
Tên model phải trùng chính xác trong tags; cấu hình không có tag chấp nhận thêm
`:latest`. Không in danh sách model hoặc response.

Mỗi dòng stdout là `OK <tên>` hoặc `FAIL <tên> code=<MÃ>`; backend/Ollama chỉ
hiện host:port, model chỉ ghi tên check `model`. Thoát 0 khi mọi check OK, 2 khi
có lỗi. Lỗi kết nối là `BACKEND_UNREACHABLE`/`OLLAMA_UNREACHABLE`, model thiếu là
`MODEL_NOT_FOUND`, token thiếu là `FAIL config code=MISSING_CONFIG`.
