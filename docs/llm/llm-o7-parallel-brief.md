# Brief O7 — Chạy nhiều tiến trình llm-worker song song an toàn

Mục tiêu/acceptance: chủ dự án chạy được N tiến trình `python3.14 -m cv_compass_llm pull` cùng lúc (Ollama `OLLAMA_NUM_PARALLEL=N`) mà không lấy trùng job, không mất lease:
(1) `CVC_LLM_WORKER_NAME` (mặc định hostname-pid rút gọn, chỉ chữ/số/-) in ở mọi dòng log và `worker_start`; (2) test tích hợp backend: 2 claim đồng thời không trả cùng job (claim `FOR UPDATE SKIP LOCKED` đã có — viết test chứng minh);
(3) README runbook: cách bật `OLLAMA_NUM_PARALLEL`, cảnh báo VRAM (mỗi luồng nhân KV cache: model 4b + 2 luồng 16k thường không vừa 4 GB → dùng ngữ cảnh nhỏ hơn hoặc model nhỏ), cách đo bằng `bench`/`lifecycle-report`; (4) `bench` (O3) thêm `--parallel N` chạy N luồng trong 1 tiến trình (thread) để đo thông lượng.
Không đổi giao thức, lease (120 s + heartbeat) hay logic trích xuất.
Spec: file này. Chủ dự án 19/11/2026.
Mức/lý do: 2 — vận hành/đo; test concurrency backend chỉ thêm test.
Path được sửa: `llm-worker/src/cv_compass_llm/{config,pull,bench,cli}.py`, `llm-worker/tests/**`, `llm-worker/README.md`, test backend `backend/src/test/java/**` (chỉ thêm test). Loại trừ: code backend main, prompt/schema/validate, dependency.
Base: xếp chồng trên HEAD cuối của task trước (xem prompt).
Ngữ cảnh đã biết: `MappingRemoteRepository:50` claim `FOR UPDATE OF j SKIP LOCKED`; lease `cv.mapping.remote.lease-duration` 120 s, heartbeat trong `pull.py`; log `event(...)` của `pull.py`.
Test tối thiểu: worker name hợp lệ/không hợp lệ; log có `worker=`; bench `--parallel 2` với FakeProvider chạy đủ CV, không trùng; backend 2 claim đồng thời → 2 job khác nhau (hoặc 1 + NO_WORK).
Check affected: llm-worker unittest + ruff; `cd backend; .\gradlew.bat test --tests "*MappingRemote*"` + spotlessCheck.
