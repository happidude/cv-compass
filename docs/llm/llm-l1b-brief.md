# Brief L1b — OllamaProvider, CLI batch, gold và so khớp (cho Codex)

Mục tiêu/acceptance: thêm vào `llm-worker/` (chỉ thư viện chuẩn) provider Ollama local, CLI chạy cả thư mục OCR JSON,
định dạng gold DRAFT/CONFIRMED + chế độ tạo gold nháp, và lệnh so khớp **chỉ in số tổng hợp**. Toàn bộ unittest PASS
(cũ 47 + mới), test Ollama bằng HTTP server giả trong tiến trình; không cần Ollama/CV thật.
Spec: file này; nền là code L1a đã có trong nhánh (`llm-worker/src/cv_compass_llm`). Chủ dự án giao 12/11/2026 (task L1b).
Mức/lý do: 3 — xử lý dữ liệu cá nhân + gọi HTTP (chỉ loopback). Không chạm backend/frontend/worker OCR/engine 3.5.
Path được sửa: `llm-worker/**`. Loại trừ: mọi path khác, mọi file dependency (requirements*, pyproject.toml...), `llm-worker/ruff.toml`.
Base: HEAD của worktree `cv-compass-wt/llm-integration` (branch `agent/llm-phase-integration`).
Ngữ cảnh đã biết (L1a): `parse_document(text)`/`load_document(path)` → `Document`; `extract(document, provider, *, max_blocks=20)`
→ `ExtractionResult` (`to_dict()`); `LlmProvider` Protocol (`name: str`, `generate(prompt, json_schema) -> str`), `ProviderError`;
`FakeProvider`; lỗi đầu vào là `ValueError` mã cố định (vd `INVALID_OCR`). Phong cách: src layout, unittest, ruff.toml sẵn có,
dataclass frozen, type hint đủ, hàm ngắn có docstring 1 dòng tiếng Anh, không print/log nội dung CV/prompt/output model.
Check: trong `llm-worker/`: `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests -v`
(sandbox không thấy Python → ghi `checks_run` NOT_RUN + lý do, không cài gì).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn và ghi `open_questions`.

## SPEC

1. `providers/ollama.py` — `OllamaProvider(model: str, *, base_url='http://127.0.0.1:11434', timeout_s: float = 120.0)`:
   - `name = 'ollama:<model>'`. Chỉ chấp nhận host loopback (`127.0.0.1`, `localhost`, `::1`), scheme `http`; khác → `ValueError('OLLAMA_HOST_NOT_LOCAL')`
     ngay khi khởi tạo (không gửi CV ra ngoài máy).
   - `generate(prompt, json_schema)`: POST `/api/chat` JSON `{"model", "messages":[{"role":"user","content":prompt}],
     "format": json_schema, "stream": false, "think": false, "options": {"temperature": 0}}`; trả `message.content` (str).
   - Dùng `urllib.request` với opener **không proxy** (`ProxyHandler({})`) để biến môi trường proxy không đẩy request ra ngoài.
   - Lỗi → `ProviderError` với mã cố định, không chứa body/prompt: `OLLAMA_TIMEOUT`, `OLLAMA_HTTP_<status>`, `OLLAMA_UNREACHABLE`,
     `OLLAMA_BAD_RESPONSE` (không phải JSON / thiếu `message.content` kiểu str). Giới hạn đọc response (vd 4 MiB) → vượt thì BAD_RESPONSE.
2. `cli.py` + `__main__.py` (`python -m cv_compass_llm <lệnh>`, argparse):
   - `run --input DIR --output DIR --provider ollama --model NAME [--base-url URL] [--timeout S] [--max-blocks N] [--limit N]`:
     đọc mọi `*.json` trong `--input` (không đệ quy, sắp xếp tên), `load_document` → `extract` → ghi `<stem>.result.json`
     (`to_dict()` + `seconds`) vào `--output`; file lỗi đầu vào → ghi `<stem>.error.json` `{"error": "<mã>"}` và đi tiếp.
     Cuối cùng ghi `run-summary.json`: số file, ok, lỗi theo mã, tổng/ trung bình giây mỗi CV, issue theo mã, provider, PROMPT_VERSION.
   - An toàn đầu ra: `--output` phải **nằm ngoài** git repo chứa package (đi lên từ vị trí package tìm `.git`) và khác/không nằm trong
     `--input`; thư mục output đã tồn tại và không rỗng → từ chối (`OUTPUT_NOT_EMPTY`). Không bao giờ ghi/xóa trong `--input`.
   - stdout/stderr chỉ in số đếm và mã lỗi (vd `processed=12 ok=11 errors=1`), không in tên người, text CV, prompt, output model.
     Exit code: 0 xong (kể cả có file lỗi), 2 lỗi tham số/an toàn đường dẫn.
   - Provider `fake` không có trong CLI (test gọi hàm lõi `run_batch(...)` với FakeProvider).
3. `gold.py` — định dạng gold mỗi CV `<stem>.gold.json`:
   `{"status": "DRAFT"|"CONFIRMED", "personal": {fullName, gender, location, dateOfBirth} (giá trị str|null),
   "experiences": [{"title","company","start","end","ongoing"}]}`; `load_gold(path)` kiểm nghiêm (sai → `ValueError('INVALID_GOLD')`).
   - Lệnh `draft-gold --results DIR --output DIR`: từ `*.result.json` tạo gold `status: DRAFT` (chỉ giá trị, bỏ evidence/issue),
     để con người sửa rồi đổi sang CONFIRMED. **Không ghi đè** gold đã tồn tại (bỏ qua và đếm `skipped_existing`); cùng quy tắc output ngoài repo.
4. `compare.py` — lệnh `compare --results DIR --gold DIR [--json]`: chỉ dùng gold CONFIRMED (DRAFT đếm `skipped_draft`).
   Chuẩn hóa so sánh: NFC, casefold, gộp khoảng trắng, strip. In **chỉ số tổng hợp**:
   - mỗi field personal: correct / wrong / missing (gold có, kết quả không) / extra (gold null, kết quả có);
   - kinh nghiệm: bộ ba (title, company, period=start+end+ongoing) khớp chính xác — matched, precision, recall;
     thêm khớp riêng title, company, period;
   - số CV so khớp, giây/CV trung bình (từ `seconds` trong result).
   Không in giá trị, tên file hay nội dung. `--json` in cùng số liệu dạng JSON.
5. README `llm-worker/`: thêm phần L1b (cách chạy run/draft-gold/compare với đường dẫn ví dụ ngoài repo, quy tắc privacy,
   gold DRAFT/CONFIRMED). Không nhắc dữ liệu thật.
6. Test tối thiểu (dữ liệu tổng hợp, thư mục tạm `tempfile`):
   - OllamaProvider với `http.server.ThreadingHTTPServer` trên `127.0.0.1:0` trong thread: request body đúng (model, format=schema,
     stream false, think false, temperature 0, prompt trong messages); trả content; HTTP 500 → `OLLAMA_HTTP_500`; server chậm → `OLLAMA_TIMEOUT`;
     body không phải JSON / thiếu content → `OLLAMA_BAD_RESPONSE`; host không phải loopback → `OLLAMA_HOST_NOT_LOCAL`;
     đặt biến `HTTP_PROXY`/`http_proxy` trỏ địa chỉ không tồn tại vẫn gọi được server giả (không dùng proxy); message lỗi không chứa prompt.
   - run_batch với FakeProvider: ghi result/summary đúng; file OCR hỏng → `.error.json` và tiếp tục; output trong repo → từ chối;
     output không rỗng → từ chối; input không bị thay đổi (so sánh nội dung trước/sau).
   - draft-gold: tạo DRAFT, không ghi đè gold có sẵn. load_gold: hợp lệ/không hợp lệ.
   - compare: số liệu đúng trên bộ gold/result tổng hợp nhỏ (tính tay trong test), bỏ DRAFT, chuẩn hóa casefold/khoảng trắng;
     stdout không chứa giá trị nào của gold/result (kiểm bằng chuỗi đánh dấu).
