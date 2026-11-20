# Brief O4 — Tối ưu nhanh sau cờ (mặc định tắt = hành vi hiện tại giữ nguyên từng byte)

Mục tiêu/acceptance: thêm các cờ cấu hình (CLI > env > .env), **mặc định giữ đúng hành vi develop hiện tại** (test khóa sha256 prompt/schema khi tắt cờ):
(1) `CVC_LLM_PROMPT_SCHEMA_TEXT=false` → bỏ đoạn `json.dumps(schema)` khỏi nội dung prompt (Ollama `format` vẫn ép schema); bỏ câu chỉ dẫn trùng 2 ngôn ngữ, giữ 1 bản (tiếng Anh) — đổi prompt version (vd `…/3-lean`, `whole-2-lean`).
(2) `CVC_LLM_WHOLE_PERSONAL=false` → lời gọi whole dùng schema **chỉ experiences** (bỏ phần personal mà E8 đang bỏ đi), prompt whole tương ứng; personal vẫn từ lời gọi header.
(3) `CVC_LLM_MAX_OUTPUT_TOKENS=N` → gửi `options.num_predict=N` (chặn sinh lặp/dài như CV 27k ký tự với 1.7b); vượt giới hạn → JSON cụt → `LLM_INVALID_JSON` như hiện tại, kèm issue nội bộ `OUTPUT_TRUNCATED` khi Ollama báo `done_reason=length` (chỉ log/run-summary).
(4) `CVC_LLM_KEEP_ALIVE=<duration|-1>` → gửi `keep_alive` trong request (giữ model nạp sẵn giữa CV).
(5) `CVC_LLM_CTX_CHARS_PER_TOKEN=<float>` (mặc định 2.5 như E5) để chỉnh bậc ngữ cảnh whole theo đo thực tế (`prompt_eval_count` của E11); thêm bậc `12288` khi cờ `CVC_LLM_CTX_FINE_TIERS=true`.
Provenance: `promptVersion` phản ánh tổ hợp cờ, cố định theo tiến trình (backend so provenance lúc claim và submit).
Spec: file này. Chủ dự án 19/11/2026: tối ưu thời gian, giữ nguyên độ chính xác; bật mặc định chỉ sau khi đo bằng `bench` (O3).
Mức/lý do: 3 — đổi prompt/schema trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{config,prompts,schema,pipeline,validate,pull,cli}.py`, `llm-worker/src/cv_compass_llm/providers/{base,ollama,fake}.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, `pull_payload.py`, `dates.py`, `evidence.py`, dependency.
Base: xếp chồng trên HEAD cuối của task trước (xem prompt).
Ngữ cảnh đã biết: `prompts.build_prompt`/`build_personal_prompt`/`build_whole_prompt`, `PROMPT_VERSION='llm-extraction-prompt/3'`, `WHOLE_PROMPT_VERSION='llm-extraction-prompt/whole-2'`; `schema.WHOLE_SCHEMA`/`WHOLE_PERSONAL_SCHEMA`/`EXPERIENCE_SCHEMA`/`PERSONAL_SCHEMA`; `pipeline.extract(mode)` (whole: 2 lời gọi); `OllamaProvider.generate(prompt, schema, *, num_ctx, timeout_s)`; E8 test khóa sha256 prompt kinh nghiệm.
Test tối thiểu: mọi cờ tắt → sha256 prompt/schema/payload Ollama bằng develop; từng cờ bật → request đúng (không có schema text / schema chỉ experiences / `num_predict` / `keep_alive`), version đổi, provenance cố định; `done_reason=length` → issue nội bộ.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
