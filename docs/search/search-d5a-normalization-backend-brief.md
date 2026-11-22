# Brief D5a — Backend: hàng đợi chuẩn hóa tên công ty/chức danh + liên kết kinh nghiệm

Mục tiêu/acceptance:
(1) Sau publish (và job bù), với mỗi `"CANDIDATE"."EXPERIENCES"` chưa có COMPANY_ID/TITLE_ID: khớp alias đã có (ALIAS_FOLDED) → liên kết ngay, không cần LLM. Không khớp → đưa chuỗi (chỉ chuỗi tên đã fold + raw, KHÔNG gửi nội dung CV khác) vào hàng đợi `"CATALOG"."NORMALIZATION_TASKS"` (migration mới: ID, KIND company/title, ALIAS_FOLDED unique theo KIND, ALIAS_RAW, CONTEXT_COMPANY (cho title: tên công ty cùng vị trí, giúp đoán ngành/scope), STATE, lease như mapping jobs).
(2) API nội bộ `/internal/normalization/v1/claim|submit` (token worker như mapping): claim theo lô ≤ 50 chuỗi; submit kết quả: company → {name, parent?, industry, proposedLabel?}; title → {functions[≤2], seniority, scope?, proposedLabel?}. Backend kiểm: mã thuộc taxonomy hiện hành; OTHER bắt buộc kèm proposedLabel; name không rỗng; parent tạo/tìm entry theo NAME_FOLDED. Ghi entry + alias ORIGIN=AI; liên kết mọi EXPERIENCES khớp alias; điền cột phi chuẩn; dựng lại index các ứng viên bị ảnh hưởng (D2).
(3) Không ghi đè liên kết ORIGIN=USER.
Spec: file này + ADR D0. Mức/lý do: 3 — API nội bộ mới + migration.
Path: `backend/src/main/resources/db/migration/V*__catalog_normalization_tasks.sql`, `backend/src/main/java/**/catalog/**`, `backend/src/test/**`, `docs/db/**`. Loại trừ: llm-worker, frontend.
Branch: `agent/search-d5a-normalization-backend`. Check affected: backend `./gradlew check`; `tbls diff` sạch.
