# Brief O6 — Gọn đầu vào cho model (bớt token đọc), không đổi raw text

Mục tiêu/acceptance: cờ `CVC_LLM_TRIM_INPUT=false|true` (mặc định false = giữ nguyên). Khi bật, **chỉ văn bản gửi model** được rút gọn, evidence vẫn tính trên raw text gốc (ánh xạ offset giữ nguyên):
(1) bỏ dòng lặp lại ≥ 2 trang ở đầu/cuối trang (header/footer), dòng chỉ là số trang (`Page 2 of 3`, `2/5`), dòng chỉ ký hiệu/khung; danh sách cụm cố định cấu hình được (`© example.vn`, `Private and Confidential`).
(2) gộp chuỗi khoảng trắng/tab dài trong dòng thành 1 khoảng; bỏ dòng trống liên tiếp.
(3) Lời gọi kinh nghiệm (whole): bỏ dòng thuộc mục rõ ràng không phải kinh nghiệm **chỉ khi** heading nhận chắc chắn (`Skills`, `Languages`, `Hobbies`, `References`… theo `segmenter`) — mặc định tắt bằng cờ con `CVC_LLM_TRIM_SECTIONS=false` vì rủi ro CV 2 cột.
Báo số ký tự trước/sau trong log (chỉ số). Hoạt động với cả `quote` và `lines` (O5); với `lines` giữ số dòng gốc để evidence đúng.
Spec: file này. Chủ dự án 19/11/2026: tối ưu thời gian giữ nguyên độ chính xác; bật sau khi đo bằng `bench`.
Mức/lý do: 3 — đổi đầu vào trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{config,pipeline,segmenter,prompts}.py`, file mới `llm-worker/src/cv_compass_llm/trim.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, OCR worker, raw text lưu trữ, dependency.
Base: xếp chồng trên HEAD cuối của task trước (xem prompt).
Ngữ cảnh đã biết: `segmenter.split_lines`/`scan_headings`/`OTHER_HEADINGS`; `prompts` builders; `lines.py` (O5) nếu đã có.
Test tối thiểu: header/footer lặp 3 trang bị bỏ, dòng nội dung giống nhau chỉ 1 lần không bị bỏ; offset evidence sau trim vẫn trỏ đúng raw; cờ tắt → sha256 prompt giữ nguyên; log có chars_before/after.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
