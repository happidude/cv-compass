# Brief E10 — Họ tên có danh xưng / bí danh trong ngoặc được nhận (chỉ đường remote LLM)

Mục tiêu/acceptance: với result remote `llm-extraction/1.0`, `personal.fullName` dạng `Mr Cao`, `Mr. Cao`, `Ms Lan`, `Anh Tùng`, `NGUYỄN HOÀNG TÙNG (ETHAN)`, `Nguyen Van A (Andy) `
được nhận (hiện bị `INVALID_NAME`: danh xưng bị bỏ còn 1 từ < 2 từ; ngoặc không nằm trong ký tự cho phép).
Giá trị raw = nguyên văn CV (hiển thị như CV). Normalized: bỏ danh xưng đầu (`Mr|Ms|Mrs|Miss|Mx|Dr|Ông|Bà|Anh|Chị`, có/không dấu chấm), giữ bí danh trong ngoặc ở cuối như nguyên văn, gộp khoảng trắng.
Cho phép 1 từ **chỉ khi** raw có danh xưng đầu (`Mr Cao` → normalized `Cao`) hoặc có bí danh trong ngoặc. Vẫn từ chối: chức danh/tiêu đề (`ROLE_OR_DOCUMENT_HEADING`), chữ số, email/URL, ngoặc không đóng, > 1 cặp ngoặc.
Engine parser 3.x **không đổi hành vi** (`PersonalInfoValidation.check` dùng chung với `PersonalInfoEngine`/`PersonalInfoCandidates`): thêm hàm/nhánh riêng cho remote, gọi từ `MappingRemoteVerifier`.
Spec: file này. Chủ dự án yêu cầu 17/11/2026 22:39 ("Mr Cao", "NGUYỄN HOÀNG TÙNG (ETHAN)" đều là Họ tên).
Mức/lý do: 3 — logic dữ liệu cá nhân, backend.
Path được sửa: `backend/src/main/java/com/cvcompass/backend/mapping/service/{PersonalInfoValidation,MappingRemoteVerifier}.java`, test backend liên quan. Loại trừ: llm-worker, frontend, migration, dependency, hành vi engine 3.x.
Base: `origin/develop` (≥ `b4fcbc3`); worktree `..\cv-compass-wt\llm-e10-fullname`, branch `agent/llm-e10-fullname`; Draft PR vào `develop`.
Ngữ cảnh đã biết: `PersonalInfoValidation.name()` (bỏ `Mr|Ms|Mrs|Ông|Bà|Anh|Chị` đầu), `check("fullName")` (regex `[\p{L}\p{M}][\p{L}\p{M} .'-]*`, ≥ 2 từ, `ROLE_OR_DOCUMENT_HEADING`); `MappingRemoteVerifier:162` gọi `check(field, raw)`; publication dùng `normalized` làm điều kiện hợp lệ.
Test tối thiểu: 6 ví dụ ở trên PASS với normalized đúng; `Cao` (1 từ, không danh xưng), `Mr`, `Engineer Nguyen`, `Nguyen (A`, `A (B) (C)`, `Nguyen 123` → INVALID_NAME; test engine 3.x hiện có PASS không sửa; 1 test luồng remote lưu + publish `Mr Cao`.
Check affected: `cd backend; .\gradlew.bat test --tests "*PersonalInfo*" --tests "*MappingRemote*"` + spotlessCheck.
