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
prompt, model output, exception của provider hay đường dẫn nguồn. Không gửi dữ liệu
ra ngoài máy; Ollama chỉ dùng HTTP loopback, không proxy và không theo redirect.
FakeProvider giữ prompt trong bộ nhớ chỉ để test. Bên gọi chịu trách nhiệm
giới hạn vòng đời dữ liệu và không ghi/public hóa prompt đã ghi nhận. OCR gốc không
bao giờ bị sửa hoặc xóa, kể cả khi đọc/parse/trích xuất thất bại. Gender chỉ để hiển
thị, không dùng lọc hay xếp hạng.

## Quyết định thiết kế

- Evidence chỉ có một page: quote trải qua nhiều trang bị loại với QUOTE_NOT_FOUND;
  không nối trang để tạo evidence giả. Quote nhiều dòng cùng trang được hỗ trợ.
- Summary chỉ giữ giá trị nguyên văn có trong quote; không tạo bản tóm tắt.
  dateOfBirth chỉ nhận YYYY, YYYY-MM, M/YYYY hoặc MM/YYYY và chuẩn hóa như date kinh nghiệm.
  Dạng DD/MM/YYYY hiện bị bỏ; không đoán ngày sinh.
- Date khác độ chính xác được so theo khoảng có thể có; cùng năm không suy ra tháng.
  Khi period không hợp lệ, bỏ cả start/end/ongoing và evidence period, giữ role/company.
- Dòng đơn dài hơn 6000 ký tự không thể cắt ở ranh giới dòng: bỏ dòng đó và sinh
  BLOCK_LINE_TOO_LONG. Không ép cắt giữa dòng hoặc gửi khối vượt giới hạn.
- Gender chỉ được giữ khi quote có biên token và dòng OCR gốc là giá trị đứng riêng
  hoặc nhãn Giới tính/Gender với dấu hai chấm hoặc gạch nối; không suy từ tên/địa danh.

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
py -3.14 -m cv_compass_llm run --input C:\cv-compass-synthetic\ocr --output C:\cv-compass-synthetic\run-01 --provider ollama --model synthetic-model --base-url http://127.0.0.1:11434 --timeout 120 --max-blocks 20 --limit 10
py -3.14 -m cv_compass_llm draft-gold --results C:\cv-compass-synthetic\run-01 --output C:\cv-compass-synthetic\gold
py -3.14 -m cv_compass_llm compare --results C:\cv-compass-synthetic\run-01 --gold C:\cv-compass-synthetic\gold --json
```

`run` đọc `*.json` trực tiếp trong input theo thứ tự tên, không đi vào thư mục con.
`--limit` không bắt buộc; giá trị 0 tạo lượt rỗng. `--max-blocks` mặc định 20.
Mỗi file tạo `<stem>.result.json` (kết quả có evidence và `seconds`) hoặc
`<stem>.error.json` (chỉ mã lỗi đọc/parse OCR), rồi tiếp tục file kế tiếp.
`run-summary.json` chứa `files`, `ok`, lỗi và issue theo mã, `total_seconds`,
`average_seconds`, `provider`, `prompt_version`. Thời gian tổng/trung bình của
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

Gold gồm đúng `status`, `personal`, `experiences`. `personal` bắt buộc đủ bốn key
`fullName`, `gender`, `location`, `dateOfBirth`, giá trị chuỗi hoặc null.
Mỗi experience bắt buộc `title`, `company`, `start`, `end` (chuỗi hoặc null) và
`ongoing` (boolean). Gold không có evidence, contacts, summary hay issue.
`draft-gold` chỉ tạo trạng thái `DRAFT`; con người kiểm tra, sửa và đổi thành
`CONFIRMED`. Lệnh cho phép thư mục gold đã có nội dung, bỏ qua mọi gold đã tồn tại
và đếm `skipped_existing`; không ghi đè. Output vẫn phải ngoài repo, tách khỏi
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
`correct`. Experience khớp theo bộ `(title, company, (start, end, ongoing))` và
riêng từng thành phần, dùng multiset trong từng CV để không đếm trùng vượt gold.
Experience có title/company/start/end đều null và ongoing false bị loại khỏi cả
khớp exact lẫn các thành phần, không đóng góp mẫu số. Thành phần title/company
null và period hoàn toàn chưa biết không được tính là khớp riêng.
Mỗi nhóm có `matched`, `predicted`, `gold`, `precision`, `recall`;
mẫu số 0 cho tỷ lệ 0. `average_seconds` chỉ tính các CV đã so khớp. Không dùng
thứ tự experience để ghép, không tìm gần đúng hay suy ra danh tính.
Không có `--json` thì in từng chỉ số; có cờ thì in cùng số liệu JSON.

Artifact result/gold chứa giá trị tổng hợp riêng tư của lượt chạy: giữ ngoài Git,
không đưa vào log hoặc artifact công khai. Giữ nguyên OCR đầu vào khi có lỗi.
