# Brief D1 — Schema CATALOG + SEARCH, cột chuẩn hóa, nạp bảng mã + danh mục khởi đầu

Mục tiêu/acceptance (theo quy ước DB0: schema/bảng/cột VIẾT HOA, `COMMENT ON` tiếng Việt):
(1) Migration mới: bật extension `unaccent`, `pg_trgm` (schema `public`); schema `"CATALOG"`, `"SEARCH"`:
  - `"CATALOG"."TAXONOMY_CODES"`: KIND (FUNCTION/SENIORITY/INDUSTRY/SCOPE), CODE, LABEL_VI, TAXONOMY_VERSION, ACTIVE; PK (KIND, CODE).
  - `"CATALOG"."COMPANIES"`: ID, NAME (tên chuẩn hiển thị, vd `ZE-TOP (Voran Group)`), NAME_FOLDED, PARENT_ID (FK tự tham chiếu, null), INDUSTRY_CODE (null cho entry chỉ là tập đoàn mẹ), PROPOSED_LABEL, ORIGIN (SEED/AI/USER), CREATED_AT, UPDATED_AT.
  - `"CATALOG"."COMPANY_ALIASES"`: ALIAS_FOLDED UNIQUE → COMPANY_ID, ALIAS_RAW, ORIGIN.
  - `"CATALOG"."TITLES"`: ID, NAME, NAME_FOLDED UNIQUE, FUNCTION_CODES (mảng ≤ 2), SENIORITY_CODE, SCOPE_CODE (null), PROPOSED_LABEL, ORIGIN, timestamps.
  - `"CATALOG"."TITLE_ALIASES"`: ALIAS_FOLDED UNIQUE → TITLE_ID, ALIAS_RAW, ORIGIN.
  - `"CANDIDATE"."EXPERIENCES"` thêm COMPANY_ID, TITLE_ID (FK null), SCOPE_CODE, NORMALIZATION_ORIGIN (AI/USER); dùng lại cột có sẵn COMPANY_NORMALIZED/FUNCTION_CODES/INDUSTRY_CODES/SENIORITY_CODE làm bản sao phi chuẩn cho search.
  - `"CANDIDATE"."PROFILES"` thêm LOCATION_PROVINCE (mã 63 tỉnh cũ), TOTAL_EXPERIENCE_MONTHS, CURRENT_COMPANY_ID, CURRENT_TITLE_ID.
  - `"SEARCH"."CANDIDATE_INDEX"`: CANDIDATE_ID PK, SEARCH_TEXT (đã bỏ dấu + chữ thường), SEARCH_TSV (tsvector), COMPANY_IDS, PARENT_COMPANY_IDS, TITLE_IDS, FUNCTION_CODES, SENIORITY_CODES, INDUSTRY_CODES, SCOPE_CODES (mảng), LOCATION_PROVINCE, TOTAL_EXPERIENCE_MONTHS, HAS_AI_NORMALIZATION, UPDATED_AT; index GIN (tsv, mảng), GIN trgm trên SEARCH_TEXT.
(2) Bảng mã: file `backend/src/main/resources/catalog/taxonomy-v0.json` (chép nguyên file taxonomy chủ dự án đưa trong `_briefs/attachments/taxonomy.json`), nạp vào TAXONOMY_CODES khi khởi động/migrate (idempotent, theo phiên bản).
(3) Danh mục khởi đầu: lệnh/endpoint admin `catalog import <file.json>` (định dạng `catalog-seed/0`: `companies[] {name, parent, industry, aliases[]}`, `titles[] {name, functions[], seniority, scope, aliases[]}`) — idempotent, ORIGIN=SEED, kiểm mã thuộc taxonomy, gộp theo NAME_FOLDED/ALIAS_FOLDED. File seed thật nằm NGOÀI repo (chủ dự án chạy); test dùng seed bịa.
(4) Hàm fold dùng chung (Java): NFC → bỏ dấu (gồm đ→d) → chữ thường → gộp khoảng trắng; dùng cho mọi *_FOLDED và SEARCH_TEXT; có test tiếng Việt.
Spec: file này + ADR D0. Mức/lý do: 3 — migration + contract dữ liệu mới.
Path được sửa: `backend/src/main/resources/db/migration/V*__catalog_*.sql`, `backend/src/main/resources/catalog/**`, `backend/src/main/java/**/catalog/**` (module mới `catalog`), `backend/src/main/java/**/candidate/**` (chỉ map cột mới), `backend/src/test/**`, `docs/db/**` (tbls sinh lại). Loại trừ: llm-worker, frontend, logic publish hiện có.
Branch: `agent/search-d1-catalog-schema`. Check affected: backend `./gradlew check`; `tbls diff` sạch; Spring Modulith verify module `catalog` không vi phạm ranh giới.
