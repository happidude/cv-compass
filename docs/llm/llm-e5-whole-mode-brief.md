# Brief E5 — Mode `whole`: một lần gọi LLM cho toàn bộ text OCR (personal + experiences)

Mục tiêu/acceptance: thêm mode `whole` bên cạnh `blocks` (mặc định vẫn `blocks`). `whole` gửi **toàn bộ text các trang `ok`** trong **một** lần gọi với schema gộp `{personal, experiences}`, không cắt heading/block, rồi kiểm evidence/date như hiện tại.
Chọn mode: `run --mode whole|blocks` và `CVC_LLM_MODE` (CLI > env > .env, như các key khác). Kết quả, contract backend (`llm-extraction/1.0`, payload `pull_payload`) **không đổi**.
Spec: file này (mục SPEC) + Project `claude/experience-extraction-plan-2026-11-15.md`. Chủ dự án duyệt 16/11/2026 23:22: 1 lần gọi gộp; thời gian chậm chấp nhận được, ưu tiên độ đầy đủ.
Mức/lý do: 3 — logic trích xuất dữ liệu cá nhân + giao tiếp provider.
Path được sửa: `llm-worker/src/cv_compass_llm/{pipeline,prompts,schema,validate,config,cli,pull}.py`, `llm-worker/src/cv_compass_llm/providers/{base,ollama,fake}.py`, `llm-worker/tests/**`, `llm-worker/README.md`.
Loại trừ: `segmenter.py` (E4/E6), `pull_payload.py`, backend, dependency, `ruff.toml`. Không thêm issue code mới vào `WORKER_ISSUES` (backend không nhận).
Base: xếp chồng trên branch `agent/llm-e4-heading` (HEAD cuối của E4); worktree `..\cv-compass-wt\llm-e5-whole`, branch `agent/llm-e5-whole`; Draft PR base = `agent/llm-e4-heading` (merge sau E4).
Ngữ cảnh đã biết: `pipeline.extract(document, provider, *, max_blocks, on_block)`; `segmenter.split_lines`/`Block`/`Span` (Block nhiều trang, evidence chỉ 1 trang); `schema.PERSONAL_SCHEMA`/`EXPERIENCE_SCHEMA`/`object_schema`; `validate.validate_personal`/`validate_experiences`/`parse_response`;
`providers.base.LlmProvider` (`name`, `generate(prompt, json_schema)`), `OllamaProvider(num_ctx, timeout_s)`; `config.LlmConfig` + `ENV_KEYS`; `pull._run_pull` tạo `provenance` (có `promptVersion`) **lúc claim**, backend so khớp provenance khi submit (`MappingRemoteVerifier:102`).
Check affected: unittest llm-worker (WSL Ubuntu-24.04 `python3.14` hoặc `py -3.14`) + `ruff check src tests`; test Ollama dùng HTTP server giả như test hiện có, không cần Ollama/CV thật.

## SPEC
1. Schema/prompt: `WHOLE_SCHEMA = object_schema({'personal': <object personal hiện có>, 'experiences': <array experiences hiện có>})`; `build_whole_prompt(text)` gộp chỉ dẫn personal + experiences hiện có (giữ nguyên quy tắc quote/date/gender/hoa-thường), thêm: CV có thể có thư xin việc/trang bìa, thông tin cá nhân có thể ở bất kỳ trang nào; liệt kê **mọi** vị trí công việc trong toàn CV. `WHOLE_PROMPT_VERSION = 'llm-extraction-prompt/whole-1'`.
2. Validate: parse một lần theo `WHOLE_SCHEMA`; tách hàm hiện có để nhận dữ liệu đã parse (không đổi hành vi `blocks`); evidence định vị trên một `Block` gồm mọi dòng của các trang `ok` (`split_lines`); JSON sai → `LLM_INVALID_JSON` như cũ.
3. Ngữ cảnh: ước token = `ceil(len(prompt) / 2.5)` + 4096 (dư cho output); chọn bậc nhỏ nhất trong `(8192, 16384, 24576, 32768)` ≥ ước lượng. Vượt 32768 → chạy `blocks` cho CV đó + issue nội bộ `WHOLE_TOO_LONG` (chỉ run-summary/log, không gửi backend).
4. Provider: `generate(prompt, json_schema, *, num_ctx: int | None = None, timeout_s: float | None = None)` (tham số tùy chọn, mặc định dùng giá trị khởi tạo); Ollama gửi `options.num_ctx` theo lời gọi; `FakeProvider` nhận và ghi lại để test.
5. Timeout: key mới `whole_timeout` (`CVC_LLM_WHOLE_TIMEOUT_S`, `--whole-timeout`), mặc định 900 giây, validate như timeout hiện có; chỉ dùng cho lời gọi whole.
6. Pipeline: `extract(..., mode='blocks'|'whole')`; contacts regex giữ nguyên trên mọi trang; dedupe experiences như cũ; `on_block` báo `1/1` ở whole. `ExtractionResult.prompt_version` = version của mode.
7. Pull: `provenance.promptVersion` lấy theo mode của tiến trình (whole → `WHOLE_PROMPT_VERSION`, kể cả CV fallback blocks — ghi chú README); log `worker_start` và `extraction_done` thêm `mode=<mode>`; nếu fallback thêm `fallback=blocks`. Không log nội dung.
8. CLI `run`: `--mode`, `--whole-timeout`; `run-summary.json` thêm `mode` và `whole_fallbacks` (số CV fallback).
9. README: cách bật `whole`, cảnh báo VRAM 4 GB (bậc 16k/24k sẽ đẩy một phần sang CPU; gợi ý biến môi trường Ollama `OLLAMA_FLASH_ATTENTION=1`, `OLLAMA_KV_CACHE_TYPE=q8_0` do người dùng tự đặt), cách so sánh 2 mode bằng `run` + `compare`.
10. Test tối thiểu (tổng hợp): whole 1 lần gọi với schema gộp, prompt chứa text mọi trang; personal ở trang 2 được nhận; evidence đúng trang; chọn bậc ngữ cảnh đúng ở biên (8192/16384/32768) và fallback khi quá dài; FakeProvider nhận `num_ctx`/`timeout_s`; Ollama gửi `options.num_ctx` theo lời gọi; config/CLI/env mode + whole_timeout (hợp lệ/không hợp lệ); pull: provenance promptVersion theo mode, log có `mode=`; `blocks` giữ nguyên hành vi (test cũ PASS).
