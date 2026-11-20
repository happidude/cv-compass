# Brief O3 — Lệnh `bench`: so nhiều cấu hình trên cùng bộ OCR + gold, chỉ in số tổng hợp

Mục tiêu/acceptance: `python3.14 -m cv_compass_llm bench --input OCR_DIR --gold GOLD_DIR --output OUT_DIR --config NAME=ENV_FILE [--config …] [--repeat N] [--json]`
chạy lần lượt từng cấu hình (mỗi `ENV_FILE` là file `.env` ghi đè key `CVC_LLM_*`, ví dụ model/mode/flag tối ưu), mỗi cấu hình ghi kết quả vào `OUT_DIR/<NAME>/` như lệnh `run`,
rồi gọi `compare` và in **một bảng so sánh**: theo cấu hình — `positions`/`companies`/`titles`/`companyPeriods` (precision, recall), personal theo field (correct/wrong/missing/extra),
giây/CV (TB, trung vị, p90), token đọc/viết TB và token/giây (từ metrics E11 nếu có), số issue theo mã. Thêm cột chênh lệch so với cấu hình đầu tiên (baseline).
Chỉ in số và tên cấu hình; không in tên file CV, giá trị, prompt, output. `--repeat N` chạy N lần để thấy dao động thời gian (giá trị trích xuất phải giống nhau giữa các lần vì temperature 0 — báo số CV khác nhau nếu có).
Spec: file này. Chủ dự án yêu cầu 19/11/2026: tối ưu thời gian nhưng giữ nguyên độ chính xác → mọi tối ưu phải đo bằng gold trước khi bật mặc định.
Mức/lý do: 2 — công cụ đo offline, dùng lại `run_batch`/`compare`; không đổi trích xuất.
Path được sửa: `llm-worker/src/cv_compass_llm/{cli.py,config.py}`, file mới `llm-worker/src/cv_compass_llm/bench.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, prompt/schema/validate/pipeline, dependency.
Base: xếp chồng trên HEAD cuối của task trước trong hàng đợi (xem prompt); Draft PR base = branch task trước.
Ngữ cảnh đã biết: `cli.run_batch(input_dir, output_dir, provider, *, max_blocks, limit, repo_root)`; `compare.compare(results, gold)`; `config.load_config(cli, environ, env_file, env_file_explicit=…)`; quy tắc output ngoài repo/`OUTPUT_NOT_EMPTY` của `batch_io.prepare_output`; metrics token từ E11 (`provider.last_metrics`, `run-summary.json`).
Test tối thiểu: 2 cấu hình FakeProvider khác nhau trên dữ liệu tổng hợp → bảng đúng số + cột chênh lệch; ENV_FILE sai key → lỗi mã cố định; output không chứa tên file/giá trị; `--repeat 2` đếm đúng CV khác nhau.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
