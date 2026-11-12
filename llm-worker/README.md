# Lõi trích xuất CV bằng LLM — L1a

Package Python độc lập theo src layout, chỉ dùng thư viện chuẩn; không có mạng,
persistence, CLI, Ollama hay eval gold. Kiến trúc provider được chủ dự án duyệt
trong chat ngày 12/11/2026.

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
ra ngoài; FakeProvider giữ prompt trong bộ nhớ chỉ để test. Bên gọi chịu trách nhiệm
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
