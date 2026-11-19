# Brief E3 — Backend nhận dateOfBirth đầy đủ từ llm-worker

Mục tiêu/acceptance: `MappingRemoteVerifier` nhận `personal.dateOfBirth.value` dạng `YYYY-MM-DD` hợp lệ lịch (năm 1900–2100) → `PartialDate` precision `DAY`;
`YYYY`/`YYYY-MM` giữ như cũ; ngày sai lịch / sai định dạng / ngoài khoảng → 422 `INVALID_RESULT`; start/end kinh nghiệm vẫn chỉ `YYYY`/`YYYY-MM` (tối thiểu 1950).
Result `llm-extraction/1.0` có dateOfBirth DAY đi hết luồng verify → lưu result → publication mà không bị từ chối.
Spec: file này + `docs/llm/llm-e2-normalize-brief.md` mục 2. Chủ dự án duyệt hướng sửa backend (thay vì cắt ngày ở worker) ngày 16/11/2026; plan ngắn của Primary cần chủ dự án duyệt trước khi code.
Lý do: PR #65 làm llm-worker gửi `YYYY-MM-DD`; `MappingRemoteVerifier.java:123` gọi `date(raw, 1900)`, `date()` (:336) chỉ nhận `YYYY(-MM)` → từ chối cả kết quả.
Mức/lý do: 3 — contract API remote mapping + logic dữ liệu cá nhân.
Path được sửa: `backend/src/main/java/com/cvcompass/backend/mapping/service/MappingRemoteVerifier.java`, `backend/src/test/java/**` (test liên quan), `docs/contracts/**` nếu có mô tả định dạng dateOfBirth của remote result.
Loại trừ: `llm-worker/**`, frontend, worker OCR, migration, dependency. Cần sửa file Java khác (vd mapper/validator publication) → dừng, ghi Open Questions, chờ chủ dự án.
Base: `origin/develop`; worktree `..\cv-compass-wt\llm-e3-dob-day`, branch `agent/llm-e3-dob-day`; PR vào `develop`, merge trước hoặc cùng #65.
Ngữ cảnh đã biết: `ExperienceMappingResult.PartialDate(year, month, day, precision)` hỗ trợ `DAY`; `CandidateValidator` (:131-141) nhận precision YEAR/MONTH/DAY; `ExperienceDates`/`ExperienceGrammarDates` đã tạo `DAY`;
test thuần `MappingRemoteVerifierTests` (không DB), `LlmRemoteSerializationTests`, `MappingRemoteServiceTests`.
Test tối thiểu: `1987-02-01` → DAY; `1990-02-31`, `1987-13-01`, `1899-01-01`, `1987-2-1` → INVALID_RESULT; `1987`/`1987-02` không đổi; start/end `2020-05-15` vẫn INVALID_RESULT; 1 test luồng lưu/publication với DOB DAY nếu có test tích hợp phù hợp.
Check affected: `cd backend; .\gradlew.bat test --tests "*MappingRemoteVerifier*" --tests "*LlmRemote*" --tests "*MappingRemoteService*"` + spotlessCheck; full gate `scripts\full-gate.ps1` một lần trên source cuối (cần Postgres + Docker).
