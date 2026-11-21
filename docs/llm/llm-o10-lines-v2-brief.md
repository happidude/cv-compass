# Brief O10 — Sửa EVIDENCE=lines: model trả {line, value}, value là chuỗi con của dòng

Mục tiêu/acceptance: thay hợp đồng `lines` (O5) — vẫn sau cờ `CVC_LLM_EVIDENCE=lines`, `quote` (mặc định) giữ nguyên từng byte.
(1) Schema lines: `title`/`company` = `{line: int, value: string}`; `period` = `{line: int, text: string}` (text = đoạn ngày nguyên văn trong dòng, có thể nối dòng kế); personal `fullName/location/dateOfBirth/gender` = `{line, value}`; `location` cho phép `endLine` khi địa chỉ xuống dòng; summary giữ `[{startLine,endLine}]`.
(2) Code: `value` phải là chuỗi con (không phân biệt hoa thường, tolerant như personal hiện có) của dòng `line` (hoặc dải line..endLine); không khớp → issue `QUOTE_NOT_FOUND.<field>`, bỏ field. Evidence = vị trí của value trong dòng (không phải cả dòng). Bỏ nhãn (`Address:`, `Position:`) là việc của model; code không tự cắt.
(3) Hướng dẫn tên gọn của A1 (`NAME_STYLE=clean`) áp dụng cho lines.
(4) Payload backend không đổi định dạng.
Spec: file này. Số đo bench-v1: lines nhanh −50% nhưng positions 1/72 vì lấy cả dòng (vd chức danh = `Worked at X from July 2022 to Oct 2023.`), location dính nhãn/số điện thoại.
Mức/lý do: 3 — đổi prompt/schema (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{lines,prompts,schema,validate,pipeline}.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, `pull_payload.py`, dependency.
Base: xếp chồng (xem prompt). Branch: `agent/llm-o10-lines-v2`.
Ngữ cảnh đã biết: O5 `lines.py` đánh số `[L<n>]`, `validate_line_experiences(response, table)`, `LINES_EXPERIENCE_SCHEMA`/`LINES_PERSONAL_SCHEMA`, `build_lines_prompt`.
Test tối thiểu: value chuỗi con → evidence đúng vị trí; value không có trong dòng → QUOTE_NOT_FOUND; địa chỉ 2 dòng; period nối dòng; `quote` sha256 giữ nguyên; promptVersion lines đổi (`…lines-2`).
Check affected: llm-worker unittest + ruff check + ruff format --check.
