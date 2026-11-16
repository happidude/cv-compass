# Brief E2 — Chuẩn hóa ngày, khớp không phân biệt hoa thường, nhận heading linh hoạt, num_ctx (cho Codex)

Mục tiêu/acceptance: giảm mất dữ liệu kinh nghiệm do kiểm tra quá cứng, **không nới quy tắc "không bịa"**: (1) ngày ở nhiều định dạng phổ biến
được chuẩn hóa bằng code; (2) giá trị/quote khác chữ hoa-thường vẫn được nhận, giá trị lưu lấy nguyên văn từ nguồn; (3) heading kinh nghiệm
có dấu `:`, số thứ tự, ký hiệu, heading ghép vẫn nhận; (4) `num_ctx` mặc định 8192. Unittest PASS (cũ + mới), dữ liệu tổng hợp.
Spec: file này + `claude/experience-extraction-plan-2026-11-15.md` mục B1 (Project). Chủ dự án duyệt 16/11/2026.
Mức/lý do: 3 — đổi logic trích xuất dữ liệu cá nhân (ngày sinh, kinh nghiệm). Không chạm backend/OCR worker/engine 3.5; engine `llm-extraction/1.0` giữ nguyên.
Path được sửa: `llm-worker/src/cv_compass_llm/{validate,evidence,segmenter,config,prompts}.py`, file mới `dates.py`, `llm-worker/tests/**`, `llm-worker/README.md`.
Loại trừ: mọi path khác (đặc biệt `gold.py`, `compare.py`, `cli.py`, `pull.py` — task E1 sửa song song), dependency, `ruff.toml`.
Base: `origin/develop` @ `31854e4`, worktree `cv-compass-wt/llm-e2-dates`, branch `agent/llm-e2-dates` (PR vào `develop`).
Ngữ cảnh đã biết: `validate.normalize_date`/`date_tokens`/`DATE_TOKEN`/`PRESENT(_TOKEN)`/`validate_period`/`validate_birth_date`/`supported`;
`evidence.locate` (exact → chỉ khác khoảng trắng); `segmenter.scan_headings` so nguyên dòng `strip().casefold()`, `EXPERIENCE_HEADINGS`/`OTHER_HEADINGS`;
`config.load_config` (`num_ctx` None nếu không đặt); `prompts.PROMPT_VERSION='llm-extraction-prompt/1'`.
Check: trong `llm-worker/`: `$env:PYTHONPATH='src'; py -3.14 -m unittest discover -s tests -v` (không thấy Python → NOT_RUN + lý do, không cài gì).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn và ghi `open_questions`.

## SPEC

1. **`dates.py`** (hàm thuần, thay phần date trong `validate.py`, `validate` import lại):
   - `parse_date(text) -> str | None` cho **một** token, trả `YYYY` hoặc `YYYY-MM`; `find_dates(quote) -> list[str]` theo thứ tự xuất hiện;
     `has_present(quote) -> bool`. Năm hợp lệ 1950–2100, tháng 1–12.
   - Hỗ trợ: `2020`; `5/2020`, `05/2020`, `05-2020`, `05.2020`; `2020-05`, `2020/05`, `2020.05`; `T5/2020`, `T05-2020`; `Tháng 5/2020`, `tháng 05 năm 2020`;
     tên tháng tiếng Anh đầy đủ/viết tắt có/không dấu chấm, hoa thường bất kỳ (`Jan 2021`, `JANUARY 2021`, `Sept. 2021`, `Jan, 2021`);
     ngày đầy đủ `15/05/2020`, `15-05-2020`, `15.05.2020`, `2020-05-15` → kinh nghiệm rút còn `YYYY-MM` (bỏ ngày, không suy diễn).
   - **Không** hỗ trợ năm 2 chữ số (`05/20`, `Jan '21`) — không đoán thế kỷ; trả None.
   - Present: `present, now, current, today, to date, hiện tại, hiện nay, nay, đến nay` (theo biên từ, không phân biệt hoa thường).
   - Tránh bắt nhầm: số trong `2,000+`, `USD 150M`, số điện thoại, `ISO/IEC 27001:2013` không được thành date (test riêng).
   - `validate_period`: start/end của model được chuẩn hóa bằng `parse_date` (model trả định dạng nào trong danh sách đều nhận), rồi phải
     nằm trong `find_dates(quote)` như quy tắc hiện tại. Lỗi định dạng vẫn là `INVALID_DATE` theo field, giữ thứ tự start ≤ end như cũ.
2. **Ngày sinh**: `validate_birth_date` nhận thêm ngày đầy đủ → `YYYY-MM-DD` (hợp lệ lịch). `D/M/YYYY`, `D-M-YYYY`, `D.M.YYYY` đọc **ngày trước**
   (quy ước 3.3); nếu cả ngày và tháng ≤ 12 thêm issue `DOB_DAY_FIRST_ASSUMED` (field `dateOfBirth`) nhưng vẫn giữ giá trị. `YYYY` / `YYYY-MM` như cũ.
   Giá trị phải khớp token trong quote như hiện tại. Cập nhật mục "Quyết định thiết kế" trong README (dòng "DD/MM/YYYY hiện bị bỏ").
3. **Khớp không phân biệt hoa thường** (title, company, location, fullName, summary — không áp cho gender, date đã có quy tắc riêng):
   - `evidence.locate`: thêm bước 3 sau exact và khoảng trắng: tìm theo casefold + gộp khoảng trắng; chỉ dùng khi `casefold`/`lower` giữ nguyên
     độ dài từng ký tự của đoạn so (nếu không, bỏ bước này). Evidence `text` = **đoạn nguồn nguyên văn**, offset theo nguồn.
   - `supported`: so value với evidence theo casefold + gộp khoảng trắng; giá trị trả về = **đoạn nguyên văn trong evidence** tương ứng value
     (vd model trả `Chief Operating Officer`, nguồn `CHIEF OPERATING OFFICER` → lưu `CHIEF OPERATING OFFICER`). Không định vị được → `QUOTE_NOT_FOUND` như cũ.
   - Không thêm so khớp mờ (fuzzy), không bỏ dấu tiếng Việt khi so. NFC: chỉ chuẩn hóa nếu giữ nguyên độ dài; khác thì ghi `open_questions`, không tự xử lý.
4. **Heading** (`segmenter`):
   - Chuẩn hóa dòng ứng viên heading: strip; bỏ tiền tố không phải chữ cái (số thứ tự, bullet, ký hiệu/icon) và số La Mã `I.`/`II)`/`IV -`;
     bỏ hậu tố `:`, `-`, `–`, `.`; gộp khoảng trắng; casefold. Dòng dài > 60 ký tự sau chuẩn hóa không phải heading.
   - Heading ghép: tách theo `&`, `/`, `,`, ` and `, ` và `; là heading nếu **mọi** phần là heading đã biết; loại theo phần đầu
     (`EDUCATION & CERTIFICATIONS` → khác; `WORK EXPERIENCE & PROJECTS` → kinh nghiệm). Không nhận theo tiền tố (dòng `Education supervise …` không phải heading).
   - Bổ sung kinh nghiệm: `work history`, `career history`, `employment`, `experiences`, `kinh nghiệm công tác`, `lịch sử làm việc`, `lịch sử công tác`.
     Bổ sung khác: `certificates`, `certificate`, `core skills`, `key skills`, `personal information`, `thông tin cá nhân`, `contact`, `liên hệ`,
     `objective`, `career objective`, `hobbies`, `activities`, `hoạt động`, `training`, `đào tạo`, `memberships`, `publications`.
   - `projects` / `dự án` gặp **khi đang trong mục kinh nghiệm** thì không dừng (dự án thường nằm trong từng công ty); ngoài mục kinh nghiệm vẫn là heading khác.
5. **`num_ctx`**: `config.load_config` mặc định `8192` khi không đặt (vẫn ghi đè được bằng CLI/env/.env, validate như cũ); README ghi chú VRAM 4 GB có thể
   tràn sang CPU, giảm qua `CVC_LLM_NUM_CTX` nếu chậm.
6. **Prompt**: `PROMPT_VERSION='llm-extraction-prompt/2'`. Thêm chỉ dẫn (EN + VI như hiện tại): chép title/company **đúng chữ hoa-thường như CV**;
   company chỉ là tên công ty, không kèm mô tả ngành/địa danh; mỗi vị trí một record; dự án, học vấn, chứng chỉ không phải công việc;
   quote period phải chứa nguyên văn token ngày. Không đổi schema JSON.
7. Test tối thiểu: bảng định dạng date (đủ dạng ở mục 1, cả dạng bị từ chối và các số không phải date); period với `Jan 2021 – Present`,
   `T5/2020 - nay`, `05.2019 – 10.2024`, `2025 – 2026`; DOB `01/02/1987` → `1987-02-01` + `DOB_DAY_FIRST_ASSUMED`, `31/12/1990` không có issue,
   `31/02/1990` → INVALID_DATE; locate/supported với quote và value đổi hoa thường → giá trị lưu nguyên văn nguồn; casefold đổi độ dài → không nhận;
   heading `KINH NGHIỆM LÀM VIỆC:`, `2. Work Experience`, `II. KINH NGHIỆM`, `EDUCATION & CERTIFICATIONS`, dòng `Education supervise sample…` (không heading),
   `Projects` trong/ngoài mục kinh nghiệm; config mặc định 8192; test cũ vẫn PASS (chỉ sửa test cũ khi hành vi được spec này đổi có chủ ý — ghi rõ trong notes).
