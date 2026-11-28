# Spec E13 — Độ đúng chức danh / công ty / thời gian cho search + chạy thật bằng API

Chủ dự án duyệt 29/11/2026. Base: `develop` @ `cecee94` (đã merge #105 L7) hoặc mới hơn.
Bối cảnh (số tổng hợp, không PII): bench 20 CV gold, chấm theo tên lõi — Luna none: bộ ba kinh nghiệm R 0,87 / P 0,93; Haiku 5.5: R 0,82 / P 0,91. Phân tích lỗi cho thấy phần lớn lỗi còn lại do code worker (parse ngày, so khớp trích dẫn) và prompt (chưa tách vai trò), không do model.
Mục tiêu đo sau E13: bộ ba kinh nghiệm (ngày đúng tháng, chấm tên lõi) ≥ 0,92 recall với Luna none trên bộ 20 CV.

## Nguyên tắc giữ nguyên
- Không bịa: mọi giá trị chức danh/công ty/ngày vẫn phải là chuỗi có nguyên văn trong text OCR (backend so `value` với `evidence.text` theo offset). Mọi nới lỏng chỉ ở cách TÌM chuỗi, không cho phép giá trị không có trong nguồn.
- Không đổi backend, frontend, worker OCR. Không thêm dependency (stdlib).
- Mặc định cũ giữ nguyên khi không bật cờ mới; test sha256 prompt cũ vẫn xanh.
- Agent không gọi API thật, không đọc key/`.env*` thật/CV/OCR/gold thật. Test bằng dữ liệu tổng hợp tự viết.

## A. Parse ngày chịu lỗi (dates.py, validate.py) — cờ `CVC_LLM_DATES=v3` (v2 giữ nguyên)
A1. Tháng tiếng Anh viết tắt/sai chính tả khi ĐỨNG NGAY TRƯỚC năm 4 chữ số:
   - tiền tố duy nhất ≥ 3 chữ của tên tháng ("Decem", "Septem", "Febr", "Novem") → tháng đó;
   - hoán vị đúng 3 chữ của tên viết tắt, chỉ khi duy nhất ("Arp" → Apr, "Mya" → May);
   - không áp dụng khi không có năm theo sau.
A2. Tiếng Việt / số có khoảng trắng quanh dấu phân cách: "Tháng 3/ 2020", "tháng 12 / 2019", "T3/ 2020", "03/ 2020", "3 - 2020" (chỉ trong evidence period, không áp cho DOB/điện thoại).
A3. Khoảng mở = đang làm: evidence period kết thúc bằng ngày bắt đầu + dấu "-", "–", "—", "~", "→" và không có ngày kết thúc phía sau (vd "08.2022 -", "03/2021 –") → được coi là `explicit_ongoing` nếu model trả `ongoing=true`, `end=null`.
A4. Không suy đoán tháng/năm thiếu; giữ quy tắc v2 hạ về năm khi token tháng thật sự không có.

## B. So khớp trích dẫn chịu lỗi (evidence.py) — cờ `CVC_LLM_QUOTE_MATCH=tolerant` (mặc định `strict` = hành vi cũ)
B1. Unicode: so khớp sau NFC trên cả hai phía (OCR tiếng Việt hay ở dạng dấu tách rời NFD), nhưng `Evidence.text`/offset trả về là CHUỖI GỐC trong raw (theo code point) để backend kiểm khớp.
B2. Dấu câu tương đương độ dài: ‘’→', “”→", – — ‐ → -, NBSP → space.
B3. Nếu quote của model không tìm thấy nhưng `value` (chức danh/công ty) có nguyên văn duy nhất trong block → dùng span của `value` làm evidence; nếu `value` xuất hiện nhiều lần → chọn lần gần nhất với evidence period của cùng item; không thấy → QUOTE_NOT_FOUND như cũ.
B4. Đếm số lần dùng B1/B2/B3 bằng issue thông tin (vd `QUOTE_MATCH_RELAXED`, không chứa nội dung) để bench thấy được.

## C. Prompt tách vai trò + tên lõi (prompts.py) — cờ `CVC_LLM_PROMPT_RULES=v3` (prompt version mới, sha mới; prompt cũ không đổi)
C1. Chức danh ghép "A, B" / "A | B" / "A / B" / "A cum B" / "A kiêm B" mà là HAI vai trò khác nhau → hai item kinh nghiệm riêng, cùng công ty, cùng period (period evidence dùng chung). Không tách khi phần sau là phạm vi/đơn vị/kênh của cùng vai trò ("Director, Creative Merchandising", "Area Sales Manager | Traditional Trade Channel", "Manager, Category Management – Home & Living", "EAF, LF, CCM supervisor").
C2. Nhắc model: công ty là tên tổ chức (không lấy dòng mô tả/địa điểm/kỹ năng làm công ty); vị trí "X at/of Y" → company = Y (vd "salesman of P&G at Mesa Distributor" → Mesa Distributor).
C3. Nhắc model: CV 2 cột / bảng tóm tắt + chi tiết — quét toàn bộ, không bỏ kinh nghiệm ở đầu trang; mỗi vị trí liệt kê đúng một lần.
C4. Giữ name_trim (tên lõi là đoạn nguyên văn liền mạch). Thêm quy tắc cắt title: bỏ hậu tố sau " – "/" - "/" | " khi hậu tố là tên công ty hoặc phạm vi (đã có một phần) — không đổi khi ra chuỗi rỗng.

## D. Đo lường & chi phí (compare.py, bench.py, providers)
D1. Đưa chấm "tên lõi" vào compare/bench: thêm nhóm metric `experiences.*.core` (companies, titles, positions theo tháng, positions theo năm) và personal core (họ tên bỏ danh xưng Mr/Mrs/Ms/Miss + bí danh trong ngoặc). Quy tắc lõi giống `score_core.py` đính kèm brief (công ty: bỏ phần trong ngoặc, phần sau " – "/" - "/"|", từ pháp lý Công ty/Cổ phần/TNHH/Co/Ltd/JSC/Corp/Corporation/Company/Group/Vietnam/Việt Nam/VN; chức danh: bỏ phần sau " – "/" - "/"|"/"(").
D2. Provider OpenAI/Anthropic ghi thêm vào metrics (chỉ số): `reasoning_tokens`, `cached_input_tokens`, `cache_write_tokens` (nếu API trả), và usage của response `incomplete`/`max_tokens`. Bench in tổng (`.total`) cho prompt/output/reasoning/cache.
D3. Giới hạn token ra mặc định cho provider API khi `CVC_LLM_MAX_OUTPUT_TOKENS` trống: 4000 cho extraction, 2000 cho normalize (tránh lời gọi "viết không dừng" bị tính tiền tới 128k token).
D4. Bench không đếm được token của lời gọi hết giờ — ghi rõ trong README; khuyến nghị đặt max output.

## E. Chạy thật bằng API (config.py, cli.py, pull.py, normalize.py) — cờ `CVC_LLM_ALLOW_API=true` (mặc định false)
E1. Khi cờ bật: `pull`, `normalize`, `bench-norm`, `check` chấp nhận `CVC_LLM_PROVIDER=openai|anthropic`; khi tắt giữ lỗi cũ.
E2. `check`: với provider API chỉ kiểm cấu hình + có key (không gọi mạng trừ khi đã có lệnh check mạng sẵn cho Ollama — nếu có thì gọi 1 request tối thiểu và chỉ in mã kết quả).
E3. normalize (norm-1) dùng provider API: prompt/schema norm giữ nguyên; nếu API từ chối từ khóa schema → bỏ khi gửi như extraction, vẫn validate đủ ở local.
E4. Không log nội dung CV, giá trị trích xuất hay key; lỗi chỉ mã.
E5. Backend KHÔNG đổi: `MappingRemoteVerifier` vẫn yêu cầu evidence khớp raw theo offset và value = evidence text — worker đảm bảo điều này (B1 trả chuỗi gốc).

## Chung cho mọi cờ mới
- Thêm vào `ENV_KEYS`, allowlist bench (`BENCH_ENV_KEYS`), `.env.example` (dòng comment), README; giá trị sai → lỗi cấu hình có mã như các cờ hiện có.

## Thứ tự PR đề xuất (Primary có thể đổi trong plan)
1. A (ngày) — 2. B (trích dẫn) — 3. C (prompt) — 4. D (đo lường/chi phí) — 5. E (pull/normalize API).

## Lệnh đo sau E13 (chủ dự án chạy)
Env thêm: `CVC_LLM_DATES=v3`, `CVC_LLM_QUOTE_MATCH=tolerant`, `CVC_LLM_PROMPT_RULES=v3`, `CVC_LLM_MAX_OUTPUT_TOKENS=4000`; bench luna none, luna low, haiku low trên 20 CV gold; đọc metric `*.core`.
