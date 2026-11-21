# Brief A2 — Đọc thêm kiểu ngày thường gặp trong CV (sau cờ)

Mục tiêu/acceptance: cờ `CVC_LLM_DATES=v1|v2` (mặc định `v1` = hành vi hiện tại y hệt). `v2` mở rộng `dates.py` cho thời gian kinh nghiệm (KHÔNG đổi ngày sinh), mọi giá trị vẫn `YYYY`/`YYYY-MM`:
 a. Tháng chữ + năm 2 chữ số: `May 21 – Jul 23` → `2021-05`..`2023-07`, `Aug 19` → `2019-08`. Chỉ khi có tên tháng đứng ngay trước; năm 2 số → 20xx nếu ≤ (năm hiện tại % 100) + 1, ngược lại 19xx.
 b. Tháng.năm có/không khoảng trắng: `08.2022`, `08. 2022`, `08 .2022` → `2022-08`; `2019 – 08. 2022` → `2019`..`2022-08`.
 c. Ký tự chen giữa tên tháng và năm: `Sep’- 2010`, `Sept'-2010`, `Apr - 2016`, `Feb – 2017` → tháng đúng; nhận `Sept`.
 d. Khoảng mở cuối không có từ "hiện tại": `08.2022 -` / `2022 –` → start có, end null, ongoing false (không đoán).
 e. Tháng viết sai chính tả cạnh năm (`Arp 2006`, `Decem 2006`, token chữ 3–8 ký tự không phải tháng) → CHỈ năm (`2006`); không sửa chính tả, không đoán tháng.
 f. Khi lấy thời gian cho một mục kinh nghiệm, không lấy ngày từ evidence của mục khác (thêm test chống gán nhầm nếu có đường code liên quan).
Backend: `MappingRemoteVerifier` chỉ kiểm `YYYY(-MM)?` và evidence tồn tại → không cần sửa (xác minh lại, ghi trong PR).
Spec: file này. Chủ dự án 21/11/2026: lỗi chính tả tháng → chỉ ghi năm. Số đo bench-v1: ~10 vị trí mất/sai thời gian vì các dạng trên.
Mức/lý do: 3 — đổi kết quả trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{dates,validate,config,pipeline,cli,bench}.py`, `llm-worker/tests/**`, `llm-worker/README.md`. Loại trừ: backend, prompt, schema, `pull_payload.py`.
Base: xếp chồng (xem prompt). Branch: `agent/llm-a2-dates`.
Ngữ cảnh đã biết: `dates.DATE_TOKEN` (VIETNAMESE|ENGLISH_MONTH_DAY|ENGLISH_DAY_MONTH|ENGLISH|NUMERIC), `find_dates`, `has_present`; E2/E7 đã thêm `Jan -2019`, ngày sinh đầy đủ; issue `INVALID_DATE` khi không đọc được.
Test tối thiểu: mỗi dạng a–f (dữ liệu bịa) cho đúng giá trị khi `v2`; `v1` giữ nguyên toàn bộ test cũ; không nhận nhầm số điện thoại/số trang/`Room 21`.
Check affected: llm-worker unittest + ruff check + ruff format --check.
