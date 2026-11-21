# Brief A3 — Chấm điểm gold công bằng hơn (compare/bench): đồng nhất ký tự, báo cả strict lẫn chuẩn hóa

Mục tiêu/acceptance: `compare` và `bench` chấm thêm một mức **chuẩn hóa** cạnh mức hiện tại (strict, giữ nguyên để so lịch sử):
(1) Hàm `normalize_loose(value)` (gold.py): NFC + casefold + gộp khoảng trắng (như `normalize`) + đổi mọi gạch `‐-‒–—−` thành `-`, nháy `‘’‚‛′` thành `'`, `“”„″` thành `"`, bỏ dấu câu cuối/đầu `.,;:` và khoảng trắng quanh `-`, `|`, `/`. Không đoán nghĩa, không bỏ chữ.
(2) `compare`/`bench` xuất song song `experiences.<kind>.precision/recall` (strict) và `experiences.<kind>.loose.precision/recall`; personal thêm `personal.<field>.loose.correct/wrong/missing/extra`. Bảng bench in đủ cả hai; JSON có cả hai.
(3) KHÔNG nới kiểu "chuỗi con" — tên dài hơn gold vẫn tính sai (quy ước 21/11: hệ thống phải xuất tên gọn).
Không đổi: pipeline trích xuất, prompt, payload backend, định dạng file gold/result, các metric strict hiện có (giá trị y hệt).
Spec: file này. Chủ dự án 21/11/2026: tên công ty/chức danh phải gọn; lỗi chính tả tháng trong CV → gold chỉ ghi năm.
Mức/lý do: 2 — công cụ đo offline, không chạm đường production.
Path được sửa: `llm-worker/src/cv_compass_llm/{gold,compare,bench}.py`, `llm-worker/tests/**`, `llm-worker/README.md`.
Base: origin/develop mới nhất. Branch: `agent/llm-a3-scoring`.
Ngữ cảnh đã biết: `gold.normalize` (NFC+casefold+space); `compare.experience_keys(items, kind)` dùng multiset; bench gộp metric từ compare; số đo bench-v1 base: strict positions 43%, ~1/3 lệch còn lại do `-`/`–`, dấu chấm cuối.
Test tối thiểu: metric strict giữ nguyên trên fixture cũ; loose khớp `-`/`–`, `‘`/`'`, chấm cuối; tên dài hơn gold vẫn sai ở loose; bench in cột loose.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
