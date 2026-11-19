# Brief E7 — Sửa mất ngày tháng: tháng dạng `Jan -2019`, DOB đầy đủ bị payload loại, DOB tiếng Anh có thứ tự ngày

Mục tiêu/acceptance:
(1) `dates.find_dates`/`parse_date` nhận tên tháng tiếng Anh nối năm bằng gạch/khoảng: `Jan-2019`, `Jan -2019`, `Mar- 2016`, `Feb – 2017`, `Apr - 2016`, `Sep-2011` → `YYYY-MM` (hiện chỉ ra năm nên start/end có tháng của model bị `QUOTE_NOT_FOUND`, mất cả period);
(2) `pull_payload.compact_result` gửi dateOfBirth `YYYY-MM-DD` hợp lệ lịch lên backend (backend nhận từ E3 #66); hiện `_date_valid` chỉ nhận `YYYY(-MM)` → DOB đầy đủ luôn bị bỏ với `INVALID_DATE`;
(3) DOB tiếng Anh dạng `July 28th,1982`, `28th July 1982`, `Jul 28, 1982`, `July 28 1982` → `1982-07-28` (tháng chữ nên không gắn `DOB_DAY_FIRST_ASSUMED`).
Không nới: năm 2 chữ số vẫn từ chối; khoảng `2016 - 2017` (năm-năm) không được hiểu thành tháng; số tiền/điện thoại/ISO vẫn không thành date (test E2 giữ PASS).
Spec: file này. Chủ dự án test mode whole 17/11/2026 (log: `QUOTE_NOT_FOUND.start:8` trên CV có `From: Jan -2019 To Now`, `INVALID_DATE.dateOfBirth`); Primary tái hiện bằng FakeProvider + `compact_result`.
Mức/lý do: 3 — logic dữ liệu cá nhân + payload contract (chỉ nới theo định dạng backend đã nhận).
Path được sửa: `llm-worker/src/cv_compass_llm/{dates,pull_payload}.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, segmenter, pipeline, prompts, dependency.
Base: `origin/develop` (≥ `eff02f1`); worktree `..\cv-compass-wt\llm-e7-dates`, branch `agent/llm-e7-dates`; Draft PR vào `develop`.
Ngữ cảnh đã biết: `dates.ENGLISH` = `(month)\.?[,]?\s+[0-9]{4}` (thiếu nối bằng `-–—`); `_parts`, `parse_birth_date`, `find_birth_dates`; `pull_payload._date_valid(value, minimum)` dùng cho DOB (min 1900) và period (min 1950); backend `MappingRemoteVerifier.birthDate()` nhận `YYYY|YYYY-MM|YYYY-MM-DD`, năm 1900–2100.
Test tối thiểu: bảng period ở (1) với `To Now`/`to Feb – 2017`; DOB (2) qua `compact_result` ra `{"value": "1975-08-14"}` và `1990-02-31`/`1899-01-01` vẫn bị loại; period vẫn từ chối `YYYY-MM-DD`; DOB (3); test cũ PASS.
Check affected: trong `llm-worker/`: unittest (`python3.14`/`py -3.14`) + `ruff check src tests`.
