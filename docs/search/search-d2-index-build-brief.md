# Brief D2 — Chỉ số tính sẵn + dựng chỉ mục search

Mục tiêu/acceptance (module `candidate` + `search`, không gọi LLM):
(1) Tổng tháng kinh nghiệm: gộp các khoảng vị trí chồng nhau; vị trí "hiện tại" tính tới ngày dựng; năm không có tháng tính bảo thủ (bắt đầu = tháng 12, kết thúc = tháng 1 của năm đó; cùng năm → 1 tháng); bỏ vị trí thiếu ngày bắt đầu → ghi `PROFILES.TOTAL_EXPERIENCE_MONTHS`.
(2) Công ty/chức danh hiện tại: vị trí đang làm (ongoing), nếu không có thì kết thúc muộn nhất → CURRENT_COMPANY_ID/CURRENT_TITLE_ID (null nếu chưa liên kết danh mục).
(3) Tỉnh/thành: từ `PROFILES.LOCATION` theo danh mục tỉnh engine 3.3 (`PersonalInfoProvinces`, 63 tỉnh cũ) → LOCATION_PROVINCE; không chắc → null.
(4) `"SEARCH"."CANDIDATE_INDEX"`: SEARCH_TEXT = fold(họ tên gốc (gồm bí danh), chức danh gốc + tên danh mục, công ty gốc + tên chuẩn + tên tập đoàn mẹ, nơi ở, tóm tắt); các mảng mã từ liên kết danh mục; HAS_AI_NORMALIZATION.
(5) Thời điểm dựng: trong CÙNG giao dịch khi publish kết quả mapping và khi HR sửa hồ sơ; job đêm cập nhật vị trí đang làm; endpoint admin "dựng lại toàn bộ" (theo lô, không khóa bảng lâu).
Spec: file này + ADR D0. Mức/lý do: 3 — thay đổi giao dịch publish.
Path: `backend/src/main/java/**/{candidate,search}/**`, `backend/src/test/**`. Loại trừ: migration (dùng cột D1), llm-worker, frontend.
Branch: `agent/search-d2-index-build`. Test tối thiểu: khoảng chồng nhau, năm không tháng, ongoing, tỉnh mơ hồ, publish → index cập nhật cùng giao dịch (rollback thì index cũng rollback), sửa HR → index đổi.
Check affected: backend `./gradlew check`.
