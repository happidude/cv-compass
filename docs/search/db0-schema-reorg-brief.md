# Brief DB0 — Sắp xếp database theo schema module, tên VIẾT HOA gọn + tài liệu tbls

Mục tiêu/acceptance:
(1) Migration MỚI (không sửa migration đã áp dụng) `V14__db_reorganize_schemas.sql` (số kế tiếp thực tế): tạo schema `"INGESTION"`, `"MAPPING"`, `"CANDIDATE"`, `"IDENTITY"`; `ALTER TABLE … SET SCHEMA` + `RENAME TO` theo bảng:
  INGESTION: CV_DOCUMENTS→DOCUMENTS, CV_EXTRACTIONS→EXTRACTIONS, CV_IMPORT_BATCHES→IMPORT_BATCHES, CV_IMPORT_ITEMS→IMPORT_ITEMS, CV_DISK_IMPORT_FILES→DISK_IMPORT_FILES, CV_DISK_OBSERVATIONS→DISK_OBSERVATIONS, CV_PROCESSING_JOBS→PROCESSING_JOBS, CV_OCR_ATTEMPTS→OCR_ATTEMPTS.
  MAPPING: CV_MAPPING_CONFIGS→CONFIGS, CV_MAPPING_JOBS→JOBS, CV_MAPPING_ATTEMPTS→ATTEMPTS, CV_MAPPING_RESULTS→RESULTS, CV_MAPPING_SETTINGS→SETTINGS.
  CANDIDATE: CANDIDATES, CANDIDATE_PROFILES→PROFILES, CANDIDATE_EXPERIENCES→EXPERIENCES, CANDIDATE_HISTORY→HISTORY, CANDIDATE_PROPOSALS→PROPOSALS, CANDIDATE_PUBLICATIONS→PUBLICATIONS, CANDIDATE_PUBLICATION_SETTINGS→PUBLICATION_SETTINGS.
  IDENTITY: CV_USERS→USERS.
  Cột giữ nguyên (đã VIẾT HOA). Sequence/identity, index, constraint, FK, trigger, view (nếu có) phải đi theo bảng và còn hoạt động; đổi tên index/constraint có tiền tố cũ cho khớp (tùy chọn, ghi trong PR). `flyway_schema_history`, `event_publication` giữ `public`. Quyền (GRANT) của user ứng dụng/test trên schema mới phải đủ (USAGE + quyền bảng như cũ).
  Thêm `COMMENT ON TABLE` tiếng Việt cho cả 21 bảng (mô tả 1 dòng); cột: tối thiểu khóa chính/khóa ngoại và cột trạng thái.
(2) Sửa mọi tham chiếu trong code/test/script/tài liệu vận hành sang tên mới đầy đủ schema (`"MAPPING"."JOBS"`): SQL viết tay, `@Table`/entity (nếu có), test, `scripts/**` (vd test-db), `docs/llm/runbook-*.md` (lệnh psql xuất OCR → `"INGESTION"."EXTRACTIONS"`). Không dựa vào `search_path` để che tên.
(3) tbls: thêm `.tbls.yml` (DSN đọc từ biến môi trường, không ghi secret), sinh `docs/db/` (markdown + sơ đồ) từ DB test sau migration; thêm job/bước vào `.github/workflows/persistence-ci.yml`: sau khi migrate DB CI, chạy `tbls diff` (cài tbls bản ghim + kiểm checksum) → FAIL nếu `docs/db/` lệch schema. README ngắn cách tự sinh lại (`tbls doc --force`).
Spec: file này + ADR D0 (quy ước database). Chủ dự án duyệt 21/11/2026: phương án A, tên bảng + cột VIẾT HOA, dùng tbls.
Mức/lý do: 3 — migration + chạm toàn bộ truy cập DB + CI.
Path được sửa: `backend/src/main/resources/db/migration/V*__db_reorganize_schemas.sql`, `backend/src/**` (chỉ tham chiếu tên bảng), `backend/src/test/**`, `scripts/**` (tham chiếu tên bảng), `docs/llm/runbook-*.md`, `docs/db/**`, `.tbls.yml`, `.github/workflows/persistence-ci.yml`, `docs/VERIFICATION.md` (mục CI). Loại trừ: thay đổi logic nghiệp vụ, worker/llm-worker/frontend.
Base: xem prompt. Branch: `agent/db0-schema-reorg`.
Ngữ cảnh đã biết: 21 bảng ở `public`, tên HOA có ngoặc kép; module Spring Modulith: candidate, identity, ingestion, mapping, storage, web; DB test container cổng 5433 (`scripts/test-db.*`).
Check affected: backend `./gradlew check` ĐẦY ĐỦ (đổi tên chạm mọi module) trên DB test mới migrate từ đầu; thêm test migrate DB có dữ liệu mẫu bịa ở phiên bản cũ → V14 → đọc được qua repository mới. `tbls diff` sạch.
