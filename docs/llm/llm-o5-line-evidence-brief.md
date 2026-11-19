# Brief O5 — Evidence theo số dòng: model chỉ chỉ dòng + giá trị ngắn, code lấy nguyên văn và tự đọc ngày

Mục tiêu/acceptance: cờ `CVC_LLM_EVIDENCE=quote|lines` (mặc định `quote` = hành vi hiện tại, sha256 prompt/schema giữ nguyên). Ở `lines`:
(1) Input: mọi dòng không rỗng của các trang `ok` được đánh số toàn cục `[L<n>] <text>`, có dòng phân cách trang `=== page <p> ===`; bảng ánh xạ L → (page, start, end) nguồn. Không đổi raw text.
(2) Schema kinh nghiệm mỗi mục: `title {line, text}`, `company {line, text}`, `periodLine`, `ongoing` (text/line có thể null). **Không còn quote, không còn start/end do model viết.**
(3) Code: `text` phải khớp trong dòng `line` (khớp hoa-thường/khoảng trắng như E2, cho phép dòng kế ±1 khi model lệch 1 dòng); giá trị lưu = đoạn nguyên văn nguồn; không khớp → `QUOTE_NOT_FOUND.<field>` như hiện tại.
    Kỳ: `dates.find_dates`/`has_present` trên dòng `periodLine` (và dòng kế tiếp nếu dòng đó chỉ có 1 ngày, ví dụ `August 2017 – November` / `2018`); 2 ngày → start/end, 1 ngày + present → ongoing; evidence = span ngày trên dòng.
(4) Schema cá nhân: `fullName {line,text}`, `location {line,text}`, `gender {line, value}` (giữ quy tắc E8: nhãn/giá trị đứng riêng), `dateOfBirthLine` (code đọc bằng `parse_birth_date`), `summary [{startLine, endLine}]` (code ghép nguyên văn các dòng, nối qua trang như E8/E9, tối đa 6000 ký tự).
(5) Áp cho cả `whole` và `blocks`; prompt version mới (vd `llm-extraction-prompt/lines-1`, `whole-lines-1`); payload gửi backend **không đổi định dạng** (evidence vẫn page/start/end/text) → backend không sửa.
Mục đích đo: câu trả lời ngắn hơn (ít token viết) và hết lỗi chép sai trích dẫn. Bật mặc định chỉ khi `bench` cho thấy độ chính xác ≥ `quote` (chủ dự án quyết).
Spec: file này. Chủ dự án duyệt hướng C (đổi cách trả lời) ngày 19/11/2026.
Mức/lý do: 3 — đổi prompt/schema/validate trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{config,prompts,schema,pipeline,validate,evidence,segmenter}.py`, file mới `llm-worker/src/cv_compass_llm/lines.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, `pull_payload.py` (chỉ đọc), `dates.py` (chỉ dùng), dependency.
Base: xếp chồng trên HEAD cuối của task trước (xem prompt).
Ngữ cảnh đã biết: `segmenter.split_lines` (Span có page/start/end/raw_text), `Block`; `validate.validate_period`/`supported`/`validate_personal`/`validate_whole`; `evidence.source_value`/matcher cá nhân của E8; `dates.find_dates`/`has_present`/`parse_birth_date`; `pull_payload.compact_result` (định dạng evidence backend).
Test tối thiểu (tổng hợp): dòng đánh số + ánh xạ offset đúng qua nhiều trang; title/company đúng dòng, lệch ±1 dòng, sai dòng → drop; kỳ trên 1 dòng, vắt 2 dòng, `– Present`, dòng không có ngày → null; DOB/summary từ dòng; payload backend giống hệt dạng hiện tại; `quote` giữ nguyên sha256.
Check affected: llm-worker unittest + `ruff check src tests` + `ruff format --check src tests`.
