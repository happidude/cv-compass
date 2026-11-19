# Brief llm-l1-config — cấu hình không ghi cứng + sửa test aggregates (cho Codex)

Mục tiêu/acceptance: (a) sửa test hỏng `test_batch_gold_compare.py::CompareTests::test_cli_only_prints_aggregates`;
(b) bộ đọc cấu hình chỉ dùng thư viện chuẩn + `llm-worker/.env.example`; (c) test tổng hợp cho bộ đọc cấu hình.
Toàn bộ unittest `llm-worker` PASS, không còn FAIL.
Spec: file này; chủ dự án duyệt task `llm-l1-config` ngày 13/11/2026.
Mức/lý do: 2 — cấu hình CLI/provider và assertion privacy; không đổi pipeline/validate/evidence.
Path được sửa: `llm-worker/**` (trừ `llm-worker/ruff.toml`). Loại trừ: mọi path khác, mọi file dependency.
Base: HEAD worktree `cv-compass-wt/llm-l1-config` (branch `agent/llm-l1-config`, từ 36f55df).
Ngữ cảnh đã biết: `cli.py` (`parser()`, `main()`, `CLI_ERRORS`, `_Parser.error` in `INVALID_ARGUMENTS`),
`providers/ollama.py` (`OllamaProvider(model, *, base_url, timeout_s)`, kiểm host loopback, payload `think: False`,
`options: {temperature: 0}`). Lỗi là `ValueError('<MÃ>')`; `main` chỉ in mã có trong `CLI_ERRORS`, còn lại `INVALID_DATA`.
Check: trong `llm-worker/`: `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests -v`
(sandbox không thấy Python → ghi `checks_run` NOT_RUN + lý do, không cài gì).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn, ghi `open_questions`.

## SPEC

1. **Test hỏng (a).** Nguyên nhân: `collect_values` gom cả chuỗi là tên field schema (vd giá trị `"field": "fullName"` trong
   issue) rồi coi là giá trị riêng tư, trong khi output hợp lệ có `personal.fullName.correct=...`. Sửa trong test: loại khỏi
   `markers` **chỉ** tên khóa schema cố định (`fullName`, `gender`, `location`, `dateOfBirth`, `title`, `company`, `start`,
   `end`, `ongoing`, `personal`, `experiences`, `status`, mã trạng thái/issue cố định như `CONFIRMED`, `DRAFT`) — liệt kê
   tường minh, không lọc theo độ dài/regex. Mọi giá trị CV tổng hợp, tên file, stem, đường dẫn, evidence/quote vẫn phải
   assertNotIn. Không bỏ/yếu assertion nào; thêm một assert rằng tập marker sau lọc vẫn chứa ít nhất các giá trị tổng hợp
   của fixture (để test không thể rỗng một cách lặng lẽ).
2. **Bộ đọc cấu hình (b)** — module mới `src/cv_compass_llm/config.py`, chỉ stdlib:
   - Biến: `CVC_LLM_PROVIDER`, `CVC_LLM_MODEL`, `CVC_LLM_BASE_URL`, `CVC_LLM_TIMEOUT_S`, `CVC_LLM_MAX_BLOCKS`,
     `CVC_LLM_TEMPERATURE`, `CVC_LLM_THINK`, `CVC_LLM_NUM_CTX` (tùy chọn).
   - Ưu tiên mỗi khóa: tham số CLI > biến môi trường > file `.env`. Giá trị rỗng/chỉ khoảng trắng coi là chưa đặt.
   - Hàm thuần để test: `load_config(cli: Mapping[str, str | None], environ: Mapping[str, str], env_file: Path | None,
     *, env_file_explicit: bool) -> LlmConfig` (dataclass frozen). `main` truyền `os.environ`.
   - `.env`: mặc định `llm-worker/.env` (tính từ vị trí package, không từ cwd); chọn khác bằng `run --env-file PATH`.
     Mặc định không tồn tại → bỏ qua; `--env-file` không tồn tại → `ENV_FILE_NOT_FOUND`. Đọc UTF-8 strict, tối đa 64 KiB;
     dòng trống/`#` bỏ qua; dòng khác dạng `KEY=VALUE` (strip, cho phép một cặp nháy `"..."`/`'...'` bao toàn bộ value);
     sai dạng/UTF-8/quá lớn → `INVALID_ENV_FILE`. Chỉ đọc khóa `CVC_LLM_*`; khóa `CVC_LLM_*` không biết → `INVALID_ENV_FILE`.
     Không đặt vào `os.environ`.
   - Bắt buộc: provider (chỉ `ollama`, sai → `INVALID_PROVIDER`) và model; thiếu một trong hai → `MISSING_CONFIG`.
     **Không có tên model mặc định nào trong source/test/README** (test dùng tên tổng hợp như `synthetic-model`).
   - Mặc định khi không đặt: base URL `http://127.0.0.1:11434`, timeout 120, max blocks 20, temperature 0, think false,
     num_ctx không gửi. Kiểm: timeout float hữu hạn > 0 (`INVALID_TIMEOUT`); max blocks int ≥ 0 (`INVALID_MAX_BLOCKS`);
     temperature float hữu hạn 0..2 (`INVALID_TEMPERATURE`); think chỉ `true|false` không phân biệt hoa thường
     (`INVALID_THINK`); num_ctx int 1..1048576 (`INVALID_NUM_CTX`). Base URL vẫn do `OllamaProvider` kiểm (giữ nguyên
     kiểm tra chỉ `127.0.0.1`/`localhost`/`::1`, không nới).
   - Thông báo lỗi chỉ là mã cố định: không in giá trị, đường dẫn, tên khóa lạ hay nội dung `.env`. Thêm mã mới vào `CLI_ERRORS`.
3. **CLI/provider.** `run`: `--provider`, `--model`, `--base-url`, `--timeout`, `--max-blocks` thành tùy chọn (giá trị
   chuỗi, kiểm ở `config.py`), thêm `--temperature`, `--think {true,false}`, `--num-ctx`, `--env-file`. `--input`,
   `--output`, `--limit` giữ nguyên. `OllamaProvider` thêm tham số keyword `temperature=0.0`, `think=False`,
   `num_ctx=None` (tự kiểm như trên); payload giữ đúng như cũ khi dùng mặc định, `options.num_ctx` chỉ có khi đặt.
   `draft-gold`/`compare` không đổi.
4. **`.env.example`** (commit): mọi biến ở trên, comment tiếng Việt, `CVC_LLM_MODEL=` để trống ("điền tag model đã chuẩn bị"),
   không giá trị bí mật. `.env` đã bị `.gitignore` gốc bỏ qua — không sửa `.gitignore`.
5. **Test (c)** `tests/test_config.py`: ưu tiên CLI > env > file theo từng khóa; giá trị rỗng = chưa đặt; thiếu model/provider;
   từng mã sai kiểu/khoảng; `.env` mặc định vắng (OK) vs `--env-file` vắng (lỗi); dòng sai, UTF-8 sai, quá lớn, khóa lạ;
   output `main` khi lỗi không chứa giá trị/đường dẫn tổng hợp; payload Ollama (server giả có sẵn trong `test_ollama.py`)
   có `num_ctx`/`think`/`temperature` đúng. Cập nhật test CLI cũ nếu chữ ký đổi, không yếu assertion.
6. **README** `llm-worker/README.md`: mục cấu hình (biến, ưu tiên, `.env`, mã lỗi); ví dụ lệnh `run` không còn cần
   `--provider/--model` khi đã có trong `.env`. Tiếng Việt.
