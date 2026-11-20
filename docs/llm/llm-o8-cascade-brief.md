# Brief O8 — Model nhỏ trước, model lớn dự phòng (sau cờ)

Mục tiêu/acceptance: cờ `CVC_LLM_FALLBACK_MODEL=<tag>` (trống = tắt, hành vi hiện tại). Khi bật: chạy model chính (vd `qwen3:1.7b`) trước; nếu kết quả có dấu hiệu kém thì chạy lại **toàn bộ CV** bằng model dự phòng (vd `qwen3:4b`) và dùng kết quả dự phòng.
Dấu hiệu kém (cấu hình được, mặc định): `LLM_INVALID_JSON`; 0 kinh nghiệm hợp lệ khi text có heading kinh nghiệm; mất `fullName`; tỷ lệ mục kinh nghiệm bị loại (`QUOTE_NOT_FOUND`/`EXPERIENCE_WITHOUT_ROLE`) ≥ `CVC_LLM_FALLBACK_DROP_RATIO` (mặc định 0.3).
Provenance: `model` = chuỗi cố định theo tiến trình `"<chính>|fallback:<dự phòng>"` (backend so provenance lúc claim/submit nên không được đổi theo từng CV); log `extraction_done` thêm `fallback=yes|no` + lý do (mã).
Hai model không cùng nằm trong 4 GB VRAM → mỗi lần dự phòng Ollama nạp lại model; README ghi chi phí này và gợi ý chạy dự phòng theo lô (`bench`/`run` với danh sách CV đánh dấu) thay vì xen kẽ — thêm tùy chọn `run --only-fallback-candidates <run-dir>` đọc run trước, chỉ chạy lại CV bị đánh dấu.
Spec: file này. Chủ dự án 19/11/2026: chỉ bật nếu `bench` cho thấy độ chính xác giữ nguyên và nhanh hơn.
Mức/lý do: 3 — logic trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{config,pipeline,pull,cli,bench}.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, prompt/schema/validate, dependency.
Base: xếp chồng trên HEAD cuối của task trước (xem prompt).
Ngữ cảnh đã biết: `pipeline.extract` trả `ExtractionResult(fields, experiences, issues, …)`; `config.LlmConfig`; `OllamaProvider(model, …)`; provenance tạo lúc claim trong `pull.py`.
Test tối thiểu: FakeProvider chính trả kết quả kém theo từng tiêu chí → gọi dự phòng; kết quả tốt → không gọi; provenance cố định; `--only-fallback-candidates` chỉ chạy CV đánh dấu; cờ trống → hành vi cũ.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
