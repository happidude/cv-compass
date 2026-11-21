# Brief D1 — Tùy chọn giữ câu trả lời thô của model khi chạy offline (để chẩn đoán)

Mục tiêu/acceptance: thêm cờ CLI `--keep-raw` cho `run` và `bench` (KHÔNG có cho `pull`, không có biến env). Khi bật: với mỗi CV ghi `<stem>.raw.json` cạnh `<stem>.result.json` trong thư mục output riêng tư, gồm danh sách lời gọi theo thứ tự: `call` (whole/header/block-N/fallback), `num_ctx`, `prompt_version`, `response` (chuỗi JSON thô model trả), `done_reason`. Không ghi prompt, không ghi văn bản CV ngoài những gì có trong response. Mặc định tắt → output y hệt hiện tại. `compare`/`bench` bỏ qua file `.raw.json`. README ghi rõ: file chứa dữ liệu CV, chỉ dùng trên máy chủ dự án, xóa sau khi chẩn đoán; không log nội dung.
Lý do: bench-v2 có 1 CV mất cả 6 khoảng thời gian (`QUOTE_NOT_FOUND.period` ×6) chỉ ở cấu hình `WHOLE_PERSONAL=false`; không có câu trả lời thô thì không biết model trích dẫn sai thế nào.
Mức/lý do: 2 — công cụ offline, không chạm `pull`/backend/prompt.
Path được sửa: `llm-worker/src/cv_compass_llm/{cli,bench,batch_io,pipeline}.py`, `llm-worker/src/cv_compass_llm/providers/{base,ollama,fake}.py` (nếu cần lấy response thô), `llm-worker/tests/**`, `llm-worker/README.md`.
Base: xem prompt. Branch: `agent/llm-d1-keep-raw`.
Test tối thiểu: không cờ → không có file raw, result giống hệt; có cờ → raw đúng số lời gọi, không chứa prompt; `pull` không nhận cờ; compare bỏ qua raw.
Check affected: llm-worker unittest + ruff check + ruff format --check.
