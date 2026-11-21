# Brief O11 — Cascade O8 không chạy model dự phòng khi lỗi là tràn context (M1 của PR #84)

Mục tiêu/acceptance: khi `CVC_LLM_CTX_GUARD=true` và `CVC_LLM_FALLBACK_MODEL` có giá trị: CV mà nguyên nhân thiếu field/mục là `CONTEXT_OVERFLOW` (hoặc `WHOLE_TOO_LONG`) KHÔNG kích hoạt chạy lại bằng model dự phòng ở cùng cỡ context (coi như lỗi hạ tầng, giống `PROVIDER_ERROR`); các CV khác giữ tiêu chí O8. `run --only-fallback-candidates` loại các CV này và đếm riêng `skipped_overflow` trong run-summary. Cờ tắt → hành vi y hệt.
Spec: file này + Open Question M1 PR #84 (chủ dự án 21/11/2026: làm task riêng trước khi bật đồng thời hai cờ).
Mức/lý do: 2 — điều kiện chọn CV dự phòng, không đổi prompt.
Path được sửa: `llm-worker/src/cv_compass_llm/{pipeline,cli,bench}.py` (+ file cascade O8 nếu tách riêng), `llm-worker/tests/**`, `llm-worker/README.md`.
Base: origin/develop SAU KHI #84 đã merge (cần `CONTEXT_OVERFLOW`). Branch: `agent/llm-o11-cascade-overflow`.
Test tối thiểu: overflow + fallback → không gọi model 2; lỗi thường → gọi như O8; cờ tắt → như develop.
Check affected: llm-worker unittest + ruff check + ruff format --check.
