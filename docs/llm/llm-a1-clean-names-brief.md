# Brief A1 — Tên công ty/chức danh gọn + tách vị trí + không mất thông tin cá nhân âm thầm (sau cờ)

Mục tiêu/acceptance:
(1) Cờ `CVC_LLM_NAME_STYLE=raw|clean` (mặc định `raw` = prompt/schema/request giữ nguyên từng byte, test sha256). `clean` → prompt kinh nghiệm (whole + blocks, cả `EVIDENCE=lines` nếu đã có) thêm hướng dẫn, giữ yêu cầu trích dẫn nguyên văn (giá trị vẫn PHẢI là chuỗi con của CV):
   - `company` = tên pháp nhân/thương hiệu GỌN: bỏ khẩu hiệu/mô tả sau `|`, bỏ ngoặc mô tả kiểu `(Incl: …)`/`(2 PLANTS)`, bỏ địa điểm/tập đoàn mẹ đứng sau dấu phẩy hoặc gạch (`ORVEN VIETNAM COMPANY LTD, PHALANX MERIDO AG., GERMANY` → `ORVEN VIETNAM COMPANY LTD`; `USA, SILVERTON HOME FURNISHINGS` → `SILVERTON HOME FURNISHINGS`); GIỮ tập đoàn mẹ nếu CV viết trong ngoặc ngay sau tên (`TKG CO., LTD (TAN KHOI GEMSTONE JOINT STOCK COMPANY)`); bỏ tiền tố nhãn `Company:`.
   - `title` = chức danh GỌN: bỏ nhãn `Position:`, bỏ `Work as … in <nơi>` → phần chức danh, bỏ mô tả sau `for …`/`consults for …`, bỏ ngoặc mô tả dài (`(Managing apps & website's performance)`) nhưng GIỮ viết tắt/kênh ngắn trong ngoặc (`(CGOO)`, `(CE)`, `(MT channel)`); không dính tên công ty vào cuối.
   - `A → B` (thăng tiến trong cùng công ty) → 2 mục riêng; các vị trí con có năm riêng trong cùng công ty → mỗi vị trí một mục với thời gian của nó.
   - Ví dụ trong prompt chỉ dùng dữ liệu bịa (không lấy từ CV thật).
   `promptVersion` đổi khi `clean` (vd `…/whole-2-clean`), cố định theo tiến trình.
(2) Lời gọi personal (header) trả về KHÔNG có field nào trong khi header có chữ → issue nội bộ `PERSONAL_EMPTY` (log/run-summary/bench `issues.*`), áp dụng mọi cấu hình. Không đổi giá trị gửi backend.
Spec: file này. Chủ dự án 21/11/2026 duyệt quy ước tên gọn để chuẩn hóa dữ liệu cho search. Số đo bench-v1: ~23/72 vị trí lệch vì tên kèm mô tả; `lean` mất thông tin cá nhân 4/10 CV không issue.
Mức/lý do: 3 — đổi prompt trích xuất (sau cờ).
Path được sửa: `llm-worker/src/cv_compass_llm/{config,prompts,pipeline,pull,cli,bench}.py`, `llm-worker/tests/**`, `llm-worker/README.md`, `llm-worker/.env.example` (nếu đã có từ O9). Loại trừ: backend, `schema.py` (trừ khi bắt buộc — ghi lý do), `pull_payload.py`, `evidence.py`, dependency.
Base: xếp chồng (xem prompt). Branch: `agent/llm-a1-clean-names`.
Ngữ cảnh đã biết: `prompts.build_prompt/build_whole_prompt/build_experience_prompt/build_lines_prompt`, `prompt_version(mode, config)`; validate yêu cầu quote nằm trong nguồn (title/company so không phân biệt hoa thường); E8/O4 có test khóa sha256 prompt.
Test tối thiểu: `raw` → sha256 giống develop; `clean` → prompt chứa hướng dẫn, version đổi; validate vẫn từ chối giá trị không phải chuỗi con; FakeProvider header trả rỗng → `PERSONAL_EMPTY`; header không chữ → không issue.
Check affected: llm-worker unittest + ruff check + ruff format --check.
