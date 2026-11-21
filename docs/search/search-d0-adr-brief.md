# Brief D0 — ADR: chuẩn hóa công ty/chức danh, quy ước database, Search v1

Mục tiêu/acceptance: chốt thiết kế đã duyệt thành tài liệu trong repo, làm căn cứ cho DB0–D7.
(1) `docs/adr/ADR-0xx-search-normalization.md` (số kế tiếp theo thư mục hiện có; chưa có thì tạo `docs/adr/` bắt đầu 0001), tiếng Việt, gồm:
  - Nguyên tắc: giá trị gốc + evidence giữ nguyên; chuẩn hóa là lớp tách riêng (liên kết danh mục), không sửa giá trị gốc.
  - Bảng mã `cv-taxonomy/0`: 19 nhóm nghề + OTHER, 7 cấp bậc (STAFF, SENIOR, SUPERVISOR, MANAGER, HEAD, DIRECTOR, EXECUTIVE), 16 ngành (gồm OTHER), scope (COMPANY/BUSINESS_UNIT/REGION). Không khớp → OTHER + `PROPOSED_LABEL`; LLM không tự tạo mã; admin thêm mã → tăng phiên bản. Một chức danh ≤ 2 nhóm nghề, đúng 1 cấp bậc; một công ty 1 ngành chính (danh sách mã: xem phụ lục, chép từ file taxonomy chủ dự án cung cấp trong brief D1).
  - Tên công ty chuẩn: giữ như CV (bỏ hậu tố pháp nhân/quốc gia/mô tả), không dịch/thêm dấu/suy luận; tập đoàn mẹ chỉ khi CV ghi, hiển thị `Tên (Tập đoàn mẹ)`, lưu liên kết parent riêng để search.
  - GM công ty/mảng: cùng EXECUTIVE, phân biệt bằng scope.
  - Đề xuất của LLM dùng ngay cho search, gắn nhãn "AI đề xuất" (ORIGIN=AI), sửa được sau; không có bước duyệt bắt buộc.
  - Search v1: lọc theo trường + từ khóa không dấu; xếp hạng: khớp công ty trực tiếp > khớp tập đoàn mẹ; khớp chức danh; độ liên quan văn bản. Không lọc theo giới tính, tuổi. Tỉnh/thành lưu theo 63 tỉnh cũ (bảng quy đổi tỉnh mới làm sau). Bí danh họ tên không tách trường, nằm trong văn bản search.
  - Dữ liệu cá nhân production: không có bước duyệt → chỉ từ chối khi chắc chắn sai định dạng; họ tên giữ quy tắc chặt.
  - Quy ước database (áp dụng từ DB0): mỗi module một schema PostgreSQL VIẾT HOA (`"INGESTION"`, `"MAPPING"`, `"CANDIDATE"`, `"IDENTITY"`, `"CATALOG"`, `"SEARCH"`); tên bảng/cột VIẾT HOA có ngoặc kép, snake_case, bỏ tiền tố thừa (`"CV_MAPPING_JOBS"` → `"MAPPING"."JOBS"`); mọi bảng/cột mới có `COMMENT ON` tiếng Việt; file migration `V<n>__<module>_<mo_ta>.sql`; `flyway_schema_history` và `event_publication` (framework) giữ ở `public`; tài liệu sơ đồ sinh bằng `tbls` vào `docs/db/`.
  - Ngoài phạm vi v1: học vấn/kỹ năng/ngoại ngữ/lương (D3), UI duyệt danh mục (D6), tìm theo ngữ nghĩa/xếp hạng theo JD.
(2) Cập nhật mục liên quan trong `docs/` hiện có (nếu có danh sách ADR/README docs) để trỏ tới ADR.
Spec: file này + Docs artifact phân tích phase (chủ dự án duyệt 21/11/2026).
Mức/lý do: 2 — chỉ tài liệu.
Path được sửa: `docs/adr/**`, `docs/README.md` hoặc mục lục docs nếu có. Loại trừ: code, migration.
Branch: `agent/search-d0-adr`. Check affected: không có (docs); kiểm link markdown nội bộ nếu repo có công cụ.
