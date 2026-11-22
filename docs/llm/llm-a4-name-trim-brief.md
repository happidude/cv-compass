# Brief A4 — Cắt tên công ty/chức danh gọn bằng quy tắc trong code (sau cờ), kết quả vẫn là chuỗi con của CV

Mục tiêu/acceptance: bench-v3 cho thấy hướng dẫn prompt `NAME_STYLE=clean` (A1) gần như KHÔNG được qwen3:4b làm theo: model vẫn chép `X | khẩu hiệu`, `X, TẬP ĐOÀN, QUỐC GIA`, `X (Incl: …)`, `Work as Y in Z`, `Y for product ranges: …`. Thêm bước cắt xác định trong code, cờ `CVC_LLM_NAME_TRIM=off|on` (mặc định `off` = y hệt). Khi `on`, sau khi giá trị đã được xác thực là chuỗi con của nguồn, cắt thành TIỀN TỐ/ĐOẠN CON liên tục của chính giá trị đó (evidence thu hẹp đúng vị trí; không ghép đoạn rời, không thêm chữ):
 Công ty (theo thứ tự):
  1. Bỏ nhãn đầu `Company:`, `Công ty:` (chỉ nhãn có dấu hai chấm).
  2. Cắt tại ` | ` đầu tiên.
  3. Bỏ ngoặc mô tả ở cuối nếu bắt đầu bằng `Incl`, `including`, `bao gồm`, hoặc dài > 6 từ; GIỮ ngoặc ngắn ngay sau tên (`(QUELBRO)`, `(VIETNAM)`, `(TAN KHOI GEMSTONE JOINT STOCK COMPANY)`, `(ZORANA VIETNAM)`).
  4. Bỏ tiền tố quốc gia `<Quốc gia>, ` ở đầu (danh sách quốc gia/viết tắt cố định trong code: USA, US, UK, Vietnam/Viet Nam, Japan, Korea, Singapore, Germany, Norway, Denmark, Australia, Canada, France, China…).
  5. Sau hậu tố pháp nhân cuối cùng hợp lệ (`CO., LTD`, `CO.,LTD`, `LTD`, `LTD.`, `CO. LTD`, `JSC`, `CORPORATION`, `CORP.`, `COMPANY`, `GROUP`, `INC.`, `LLC`, `PLC`, `AG`, `GMBH`, `Công ty TNHH/Cổ phần …` giữ nguyên toàn bộ tên tiếng Việt), nếu ngay sau là (ngoặc ngắn ở bước 3 được giữ) rồi `,` / ` -` / `–` / `- ` / khoảng trắng + chữ HOA khác → cắt phần sau. Không cắt nếu phần sau chỉ là dấu chấm.
 Chức danh:
  6. Bỏ nhãn `Position:`, `Chức vụ:`, `Vị trí:`.
  7. `^Work(ed)? as (.+?) (in|at) ` → nhóm 2 (không phân biệt hoa thường).
  8. Cắt trước ` for ` / ` consults for ` / ` cho ` khi phần sau có `:` hoặc > 5 từ.
  9. Bỏ ngoặc ở cuối dài > 3 từ; GIỮ ngoặc ngắn (`(CGOO)`, `(CE)`, `(MT channel)`, `(cum Chief Accountant)`).
  10. Bỏ đuôi trùng tên công ty của cùng mục nếu chức danh kết thúc bằng tên công ty đó.
 Bổ sung sau bench-v4 (19 CV, 21/11/2026) — áp dụng sau các quy tắc trên:
  11. Chức danh: cắt tại ` / ` hoặc ` | ` đầu tiên, giữ phần trái (`Area Sales Executive | Management Trainee` → `Area Sales Executive`; `Chief Executive Officer / ZE-TOP - VORAN GROUP` → `Chief Executive Officer`).
  12. Chức danh: bỏ đuôi `<sep> <tên công ty của cùng mục>` với sep ∈ ` / `, ` – `, ` - `, ` | `, ` at ` (`Sales Manager – Saturnia Foods Vietnam (Kelvra Group)` → `Sales Manager`).
  13. Chức danh: cắt trước ` at ` khi phần sau ≥ 2 từ (`Portfolio Manager at Leading Fund Management Company` → `Portfolio Manager`; KHÔNG cắt `Head of Sales at` cuối chuỗi).
  14. Chức danh: bỏ đuôi `, <… Department/Phòng …>` (`Senior Investment Officer, Investment Department` → `Senior Investment Officer`).
  15. Công ty: bỏ `(belong to …)` và đuôi ` belong to …`; cắt tại ` – `/` - ` khi phần sau có từ viết thường ≥ 3 chữ cái (câu mô tả: `Quorra Holding – Asset holding of a pioneer …` → `Quorra Holding`), KHÔNG cắt khi phần sau toàn chữ HOA/tên riêng (`ZEFO - QRT CEMENT JOINT STOCK COMPANY`, `QMX – Northgate Marketing Group VN`).
 Ước lượng trên bench-v4 (mô phỏng ngoài code): positions strict 48% → ~71% (batch 3: 50% → 78%; batch 4: 45% → 63%).
 Gộp xuống dòng trong giá trị thành khoảng trắng chỉ khi so sánh; giá trị gửi đi vẫn là chuỗi con nguyên văn (giữ `\n` nếu có).
 Không áp dụng cho personal. promptVersion KHÔNG đổi (prompt không đổi); thêm field nội bộ `name_trim` vào run-summary/log (on/off) để phân biệt lượt đo.
Spec: file này. Chủ dự án 21/11/2026 duyệt quy ước tên gọn. Số đo bench-v3 (cleandates): positions strict 50%, chấm nới ~85% → chênh chủ yếu do tên không gọn.
Mức/lý do: 3 — đổi giá trị trích xuất gửi backend (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{names (mới),validate,pipeline,config,cli,bench,pull}.py`, `llm-worker/tests/**`, `llm-worker/README.md`, `llm-worker/.env.example`. Loại trừ: backend, prompt, schema, `pull_payload.py`.
Base: origin/develop mới nhất. Branch: `agent/llm-a4-name-trim`.
Ngữ cảnh đã biết: backend `MappingRemoteVerifier` yêu cầu evidence tồn tại trong nguồn và giá trị khớp evidence → giá trị cắt phải trùng đúng đoạn evidence mới (cập nhật start/end/text evidence). `validate.supported(...)` trả (value, evidence) cho title/company.
Test tối thiểu (dữ liệu bịa, mỗi quy tắc 1–10 ít nhất 1 ca dương + 1 ca không được cắt): `NORTHWIND TRADING CO., LTD, HELIX GROUP AG, SWITZERLAND` → `NORTHWIND TRADING CO., LTD`; `ACME JSC | Cloud tools for retail` → `ACME JSC`; `Work as shift lead in Plant 2, Hai Phong` → `shift lead`; không cắt `Head of Sales for Asia`, `Brant & Moyle Vietnam Limited (BM)`, `Công ty Cổ phần Cơ điện và Xây dựng Vertaxa`; evidence sau cắt nằm trong nguồn và khớp text; cờ off → kết quả y hệt develop.
Check affected: llm-worker unittest + ruff check + ruff format --check.
