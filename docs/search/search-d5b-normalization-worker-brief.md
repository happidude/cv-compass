# Brief D5b — llm-worker: lệnh `normalize` + đo bằng gold chuẩn hóa

Mục tiêu/acceptance:
(1) Lệnh `normalize` (pull như `pull`): claim lô chuỗi từ D5a, gọi Ollama 1 lần/lô với prompt chứa bảng mã (mã + nhãn), quy tắc ADR D0 (tên chuẩn giữ như chuỗi gốc, bỏ hậu tố pháp nhân/quốc gia/mô tả, KHÔNG dịch/thêm dấu/suy luận; tập đoàn mẹ chỉ khi có trong chuỗi; không khớp → OTHER + proposedLabel; ≤ 2 nhóm nghề; 1 cấp bậc; scope khi chức danh nói rõ mảng/vùng), schema JSON chặt; worker kiểm mã hợp lệ trước khi submit; lỗi → issue, không bịa.
(2) Lệnh offline `bench-norm --gold <dir> [--config …]`: đọc `*.norm.json` (định dạng `normalization-gold/0-draft`, chủ dự án cung cấp ngoài repo), gom chuỗi company/title gốc, chạy chuẩn hóa, in số tổng hợp: canonicalCompany đúng (strict + loose), parent đúng, industry đúng, functions (precision/recall theo mã), seniority đúng, scope đúng; không in chuỗi gốc. Có `--keep-raw` như D1.
(3) Không có nội dung CV trong prompt/log; log chỉ ID/mã/số.
Spec: file này + ADR D0. Mức/lý do: 3 — prompt LLM mới.
Path: `llm-worker/src/cv_compass_llm/**` (module mới `normalize.py`, `norm_bench.py`, cập nhật cli/config/backend_client), `llm-worker/tests/**`, `llm-worker/README.md`, `llm-worker/.env.example`. Loại trừ: backend.
Branch: `agent/search-d5b-normalization-worker`. Check affected: llm-worker unittest + ruff check + ruff format --check.
