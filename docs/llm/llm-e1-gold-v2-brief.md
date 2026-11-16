# Brief E1 — Gold kinh nghiệm dạng công ty → vị trí, so khớp v2, đếm issue theo field (cho Codex)

Mục tiêu/acceptance: `llm-worker/` đọc được gold `experience-gold/2` (công ty → vị trí, có `companyLocation`), vẫn đọc gold cũ (v1 phẳng);
`draft-gold` sinh nháp v2; `compare` in chỉ số tổng hợp theo công ty/vị trí; `run-summary.json` và log `pull` đếm issue theo mã **và field**.
Toàn bộ unittest PASS (cũ + mới), chỉ dữ liệu tổng hợp.
Spec: file này + `claude/experience-extraction-plan-2026-11-15.md` (Project). Chủ dự án chốt định dạng gold 15/11/2026, giao bước 0 ngày 16/11/2026.
Mức/lý do: 2 — công cụ đo offline, chỉ in số tổng hợp; không đổi kết quả trích xuất, không chạm backend.
Path được sửa: `llm-worker/src/cv_compass_llm/{gold,compare,cli,pull}.py`, `llm-worker/tests/**`, `llm-worker/README.md`.
Loại trừ: mọi path khác (đặc biệt `validate.py`, `segmenter.py`, `evidence.py`, `prompts.py`, `config.py` — task E2 đang sửa song song), dependency, `ruff.toml`.
Base: `origin/develop` @ `31854e4`, worktree `cv-compass-wt/llm-e1-gold`, branch `agent/llm-e1-gold` (PR vào `develop`).
Ngữ cảnh đã biết: `gold.py` (`PERSONAL_FIELDS`, `EXPERIENCE_FIELDS`, `valid_values`, `load_gold`, `result_values`, `draft_gold`);
`compare.py` (`normalize` NFC+casefold+gộp khoảng trắng, `experience_keys` multiset, `compare`); `cli.run_batch` ghi `run-summary.json`
với `issues` theo mã; `pull.py` log `extraction_done issues=CODE:n,...`. `Issue(code, field)` trong `schema.py`, field là tên cố định.
Check: trong `llm-worker/`: `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests -v` (không thấy Python → NOT_RUN + lý do, không cài gì).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn và ghi `open_questions`.

## SPEC

1. **Gold v2** (`gold.py`): file `<stem>.gold.json`:
   ```json
   {"schemaVersion": "experience-gold/2", "status": "DRAFT|CONFIRMED",
    "personal": {"fullName": null, "gender": null, "location": null, "dateOfBirth": null},
    "experiences": [{"company": "Acme Corp", "companyLocation": null, "start": "2018", "end": "2024", "ongoing": false,
      "positions": [{"title": "MANAGER", "start": "2020", "end": "2024", "ongoing": false},
                    {"title": "EXECUTIVE", "start": "2018", "end": "2020", "ongoing": false}]}]}
   ```
   - Kiểm nghiêm: đúng tập key; chuỗi hoặc null; `ongoing` bool; `positions` không rỗng; `title`/`company` có thể null nhưng không cùng null
     trong một vị trí; date kinh nghiệm khớp `^\d{4}(-(0[1-9]|1[0-2]))?$`; `dateOfBirth` thêm dạng `YYYY-MM-DD` hợp lệ lịch;
     `ongoing=true` ⇒ `end=null`. Sai → `ValueError('INVALID_GOLD')`.
   - Gold v1 (không có `schemaVersion`) vẫn đọc được: mỗi item v1 → một vị trí; gộp thành công ty theo quy tắc gộp bên dưới.
   - `load_gold` trả dạng nội bộ thống nhất (v2) cho `compare`.
2. **Quy tắc gộp** (dùng chung cho chuyển v1, draft và kết quả trích xuất phẳng): các mục **liền nhau** có company chuẩn hóa
   (`compare.normalize`) bằng nhau và khác null → một công ty, giữ thứ tự; company null không bao giờ gộp. Không gộp mục không liền nhau.
3. **Kỳ công ty và kỳ hiệu lực của vị trí** (hàm thuần, có test):
   - Kỳ công ty = giá trị gold nếu có `start` hoặc `end`/`ongoing`; nếu không có (và luôn với kết quả trích xuất) = suy từ vị trí:
     start sớm nhất, end muộn nhất, `ongoing` nếu có vị trí ongoing (khi đó end=null). So sánh độ chính xác khác nhau theo khoảng:
     `YYYY` sớm = `YYYY-01`, muộn = `YYYY-12`; giữ nguyên chuỗi gốc của giá trị được chọn, không tự thêm tháng.
   - Kỳ hiệu lực vị trí = (start, end, ongoing) của chính vị trí nếu có start **và** (end hoặc ongoing); ngược lại = kỳ công ty.
     Áp dụng giống nhau cho gold và kết quả.
4. **`draft-gold`**: sinh v2 `DRAFT` từ `*.result.json` (kết quả hiện vẫn phẳng): gộp theo mục 2, `companyLocation=null`,
   kỳ công ty để null (để người duyệt điền nếu CV ghi khoảng chung), vị trí giữ date của từng mục. Không ghi đè gold có sẵn (như cũ).
5. **`compare` v2** (chỉ số tổng hợp, không in giá trị/tên file): ngoài phần personal (giữ nguyên), kinh nghiệm thay bằng 4 nhóm multiset
   `matched / predicted / gold / precision / recall`:
   - `companies`: key company; `companyPeriods`: (company, start, end, ongoing) của công ty;
   - `titles`: (company, title); `positions`: (company, title, kỳ hiệu lực) — **đây là chỉ số "bộ ba" dùng cho ngưỡng 90%**.
   - Key có thành phần chính null thì không tính khớp (như logic hiện tại). `companyLocation` chưa chấm (kết quả chưa có field này):
     ghi `"companyLocation": "NOT_SCORED"` trong output.
   - Kết quả phẳng gộp theo mục 2 trước khi so. Gold v1 và v2 cùng đi qua một đường so.
6. **Issue theo field**: `run-summary.json` thêm `issues_by_field` `{"QUOTE_NOT_FOUND.title": n, "INVALID_DATE.start": n, "BLOCK_LIMIT_REACHED": n}`
   (`code` hoặc `code.field` khi field khác null); giữ `issues` cũ. `pull.py` log `extraction_done issues=` đổi sang dạng `code.field:n`
   cùng allowlist mã hiện có (field là hằng trong code, không phải dữ liệu CV). Cập nhật test pull liên quan.
7. README: mục "Gold v2 và so khớp" (định dạng, quy tắc gộp, kỳ hiệu lực, chỉ số `positions` là ngưỡng), ví dụ chỉ dùng công ty giả.
8. Test tối thiểu: load v2 hợp lệ/không hợp lệ (từng quy tắc ở mục 1); v1 vẫn load và gộp đúng; gộp không liền nhau → tách;
   kỳ công ty suy ra với độ chính xác lẫn lộn; kỳ hiệu lực (vị trí thiếu start → dùng kỳ công ty); compare tính tay trên bộ nhỏ gồm:
   1 công ty 2 vị trí, 1 công ty xuất hiện 2 lần không liền nhau, 1 vị trí "A → B" với kỳ chung; output không chứa giá trị/tên file
   (giữ test privacy hiện có); draft v2 không ghi đè; `issues_by_field` và log pull đúng định dạng.
