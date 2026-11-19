# Brief E4 — Sửa regression heading của E2 (dòng tiếp nối câu bị coi là heading)

Mục tiêu/acceptance: dòng kết thúc bằng `.` hoặc có chữ cái đầu (sau khi bỏ số thứ tự/ký hiệu) là chữ thường **không** phải heading; `activities.`, `projects.`, `education.` nằm giữa mục kinh nghiệm không dừng mục.
Heading hợp lệ của E2 vẫn nhận: `KINH NGHIỆM LÀM VIỆC:`, `2. Work Experience`, `II. KINH NGHIỆM`, `EXPERIENCES:`, `EDUCATION & CERTIFICATIONS`, `Work History`; `C. Training` vẫn không phải heading.
Spec: file này + `claude/experience-extraction-plan-2026-11-15.md` (Project, mục "Test local 16/11"). Chủ dự án duyệt E4/E5/E6 ngày 16/11/2026 23:22.
Lý do: CV thật 10 trang có dòng `activities.` (câu xuống dòng) ở trang 3 → khớp stop-heading `activities` (E2 bỏ `.` cuối dòng) → mất trang 3–10 của mục kinh nghiệm.
Mức/lý do: 3 — logic trích xuất dữ liệu cá nhân (nhỏ, 1 hàm).
Path được sửa: `llm-worker/src/cv_compass_llm/segmenter.py`, `llm-worker/tests/**`. Loại trừ: mọi path khác, dependency, `ruff.toml`.
Base: `origin/develop` (≥ `d6a986e`); worktree `..\cv-compass-wt\llm-e4-heading`, branch `agent/llm-e4-heading`; PR vào `develop`.
Ngữ cảnh đã biết: `segmenter.normalize_heading` (bỏ tiền tố không phải chữ cái, số La Mã I–XX, hậu tố `: - – .`) và `scan_headings` (tách heading ghép theo `& / , and và`, giới hạn 60 ký tự, `projects`/`dự án` không dừng khi đang trong mục kinh nghiệm).
Cách sửa đề xuất: kiểm "có phải heading" trên dòng gốc trước khi chuẩn hóa — dòng strip kết thúc bằng `.` → không phải heading; chữ cái đầu tiên (sau tiền tố số/ký hiệu/La Mã) phải `isupper()` → ngược lại không phải heading. Chỉ bỏ hậu tố `:`, `-`, `–`.
Test tối thiểu: fixture tổng hợp mục kinh nghiệm có các dòng `activities.`, `projects.`, `education.`, `skills` (chữ thường) → mục không bị cắt; các heading hợp lệ ở trên vẫn nhận; test cũ của E2 vẫn PASS (chỉ sửa test cũ nếu nó kỳ vọng `X.` cuối dòng là heading — ghi rõ trong PR).
Check affected: trong `llm-worker/`: `PYTHONPATH=src python3.14 -m unittest discover -s tests` (WSL Ubuntu-24.04) hoặc `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests`; `ruff check src tests`.
Không dùng CV thật: chủ dự án tự kiểm lại trên CV 10 trang sau khi merge (upload lại, xem log `extraction_block`).
