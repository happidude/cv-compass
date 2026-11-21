# ADR-0001 — Chuẩn hóa công ty/chức danh, quy ước database và Search v1

Trạng thái: Đã duyệt (chủ dự án, 21/11/2026)

## Bối cảnh

Cần chốt thiết kế chuẩn hóa công ty/chức danh, bảng mã, quy ước database và Search v1
đã được chủ dự án duyệt ngày 21/11/2026, làm căn cứ cho DB0–D7.

## Quyết định

### 1. Nguyên tắc giá trị gốc và lớp chuẩn hóa

Giữ nguyên giá trị gốc và evidence. Chuẩn hóa là lớp tách riêng, liên kết danh mục;
không sửa giá trị gốc.

### 2. Bảng mã `cv-taxonomy/0`

Bảng mã gồm 19 nhóm nghề và `OTHER`, 7 cấp bậc (`STAFF`, `SENIOR`, `SUPERVISOR`,
`MANAGER`, `HEAD`, `DIRECTOR`, `EXECUTIVE`), 16 ngành (gồm `OTHER`) và scope
(`COMPANY`, `BUSINESS_UNIT`, `REGION`). Danh sách mã và mô tả ở Phụ lục A.

- Một chức danh có tối đa 2 nhóm nghề, đúng 1 cấp bậc; scope tùy chọn (`null` khi không xác định
  hoặc không áp dụng).
- Một công ty có 1 ngành chính.
- Không khớp mã nào thì dùng `OTHER` và `PROPOSED_LABEL` (nhãn đề xuất).
- LLM không tự tạo mã. Admin định kỳ xem nhóm `OTHER` để bổ sung mã; thêm mã phải tăng phiên bản taxonomy.

### 3. Tên công ty chuẩn và tập đoàn mẹ

Tên công ty chuẩn giữ như CV, bỏ hậu tố pháp nhân/quốc gia/mô tả; không dịch, thêm dấu hoặc suy luận.
Chỉ ghi tập đoàn mẹ khi CV có nêu. Hiển thị `Tên (Tập đoàn mẹ)` và lưu liên kết parent riêng để search.

### 4. GM và scope

GM công ty và GM mảng cùng cấp bậc `EXECUTIVE`, phân biệt bằng scope.

### 5. Đề xuất của LLM (`ORIGIN=AI`)

Đề xuất của LLM dùng ngay cho search, gắn nhãn "AI đề xuất" (`ORIGIN=AI`) và có thể sửa sau.
Không có bước duyệt bắt buộc.

### 6. Search v1

Lọc theo trường và từ khóa không dấu. Thứ tự xếp hạng:

1. Khớp công ty trực tiếp.
2. Khớp tập đoàn mẹ.
3. Khớp chức danh.
4. Độ liên quan văn bản.

Không lọc theo giới tính hoặc tuổi. Tỉnh/thành lưu theo 63 tỉnh cũ; bảng quy đổi tỉnh mới làm sau.
Bí danh họ tên không tách trường, chỉ nằm trong văn bản search.

### 7. Dữ liệu cá nhân production

Không có bước duyệt nên chỉ từ chối khi chắc chắn sai định dạng; họ tên giữ quy tắc chặt.

### 8. Quy ước database

Áp dụng từ DB0:

- Mỗi module có một schema PostgreSQL VIẾT HOA: `"INGESTION"`, `"MAPPING"`, `"CANDIDATE"`,
  `"IDENTITY"`, `"CATALOG"`, `"SEARCH"`.
- Tên bảng/cột VIẾT HOA, có ngoặc kép, theo dạng `UPPERCASE_SNAKE_CASE`; bỏ tiền tố thừa.
  Ví dụ: `"CV_MAPPING_JOBS"` → `"MAPPING"."JOBS"`.
- Mọi bảng/cột mới có `COMMENT ON` tiếng Việt.
- Tên file migration: `V<n>__<module>_<mo_ta>.sql`.
- `flyway_schema_history` và `event_publication` (framework) giữ ở `public`.
- Tài liệu sơ đồ sinh bằng `tbls` vào `docs/db/`.

## Ngoài phạm vi v1

- Học vấn/kỹ năng/ngoại ngữ/lương (D3).
- UI duyệt danh mục (D6).
- Tìm theo ngữ nghĩa/xếp hạng theo JD.

## Hệ quả

DB0–D7 tuân theo ADR này về chuẩn hóa công ty/chức danh, bảng mã, Search v1, dữ liệu cá nhân
production và quy ước database.

## Phụ lục A — Bảng mã `cv-taxonomy/0`

Nguồn `taxonomy.json` mang nhãn `cv-taxonomy/0-draft`; chủ dự án đã duyệt thành phiên bản 0 ngày 21/11/2026.
Các mô tả dưới đây được giữ nguyên từ file nguồn.

### Nhóm nghề (19 nhóm và `OTHER`)

| Mã | Mô tả |
| --- | --- |
| GENERAL_MANAGEMENT | Điều hành / quản lý chung (CEO, GM, COO, Phó TGĐ) |
| STRATEGY_BD | Chiến lược / phát triển kinh doanh / đối tác |
| FINANCE_ACCOUNTING | Tài chính – kế toán – kiểm soát |
| AUDIT_RISK | Kiểm toán / rủi ro / kiểm soát nội bộ |
| INVESTMENT | Đầu tư / quản lý quỹ |
| HR | Nhân sự / đào tạo |
| SALES | Kinh doanh / bán hàng / key account |
| MARKETING | Marketing / thương hiệu / truyền thông / trade marketing |
| ECOMMERCE | Thương mại điện tử / thương mại số |
| MERCHANDISING | Ngành hàng / category / merchandise |
| RETAIL_OPERATIONS | Vận hành cửa hàng / chuỗi bán lẻ |
| SUPPLY_CHAIN | Chuỗi cung ứng / kế hoạch / mua hàng / logistics / kho |
| PRODUCTION | Sản xuất / vận hành nhà máy / bảo trì |
| ENGINEERING | Kỹ thuật / R&D / cải tiến (lean, industrial) |
| QUALITY | Chất lượng (QA/QC) |
| IT | Công nghệ thông tin |
| PROJECT_MANAGEMENT | Quản lý dự án / chương trình |
| ADMIN_LEGAL | Hành chính / pháp chế / lễ tân |
| CONSULTING | Tư vấn / cố vấn |
| OTHER | Khác — kèm proposedLabel (nhãn đề xuất) để admin xem xét bổ sung mã mới |

### Cấp bậc (7 mã)

| Mã | Mô tả |
| --- | --- |
| STAFF | Nhân viên / chuyên viên / kỹ sư / trợ lý / điều phối |
| SENIOR | Chuyên viên cao cấp (Senior …, Audit Senior) |
| SUPERVISOR | Giám sát / trưởng nhóm / trưởng ca / trưởng bộ phận nhỏ |
| MANAGER | Quản lý / trưởng phòng / phó phòng (Manager, Assistant Manager) |
| HEAD | Trưởng khối / Senior Manager / Head of … / Financial Controller |
| DIRECTOR | Giám đốc chức năng / GĐ khu vực / Phó GĐ (Director, AGM, Vice Director) |
| EXECUTIVE | Cấp điều hành công ty (CEO, COO, CFO, CMO, GM công ty, Phó TGĐ, HĐQT, Founder) |

### Ngành (16 mã, gồm `OTHER`)

| Mã | Mô tả |
| --- | --- |
| FMCG | Hàng tiêu dùng nhanh (kể cả thuốc lá) |
| FOOD_BEVERAGE | Thực phẩm – đồ uống (sản xuất / chuỗi F&B) |
| RETAIL | Bán lẻ (chuỗi, siêu thị, mẹ & bé) |
| FASHION_LUXURY | Thời trang / trang sức / hàng cao cấp / mỹ phẩm |
| ECOMMERCE_TECH | Thương mại điện tử / công nghệ / nền tảng số |
| HEALTHCARE_PHARMA | Y tế / dược / nhà thuốc / thẩm mỹ |
| MANUFACTURING | Sản xuất công nghiệp (điện tử, ô tô, thép, nội thất, bao bì, vật liệu) |
| CONSTRUCTION_REAL_ESTATE | Xây dựng / bất động sản |
| FINANCE_INVESTMENT | Ngân hàng / quản lý quỹ / đầu tư / bảo hiểm |
| PROFESSIONAL_SERVICES | Kiểm toán / tư vấn / dịch vụ doanh nghiệp |
| MEDIA_ADVERTISING | Truyền thông / quảng cáo / agency |
| LOGISTICS | Logistics / vận tải / phân phối |
| HOSPITALITY | Khách sạn / du lịch |
| AGRICULTURE | Nông nghiệp |
| CHEMICALS_ENERGY | Hóa chất / năng lượng |
| OTHER | Khác — kèm proposedLabel (nhãn đề xuất) để admin xem xét bổ sung mã mới |

### Scope (`COMPANY`/`BUSINESS_UNIT`/`REGION` và `null`)

| Mã | Mô tả |
| --- | --- |
| COMPANY | Phạm vi toàn công ty/tập đoàn |
| BUSINESS_UNIT | Một mảng/khối/ngành hàng/division |
| REGION | Một vùng/khu vực |
| null | Không xác định hoặc không áp dụng |
