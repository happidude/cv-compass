# Runbook O1 + O2 — chuẩn bị Ollama và xuất OCR để đo LLM (cho chủ dự án)

> **CẢNH BÁO:** bước O2 chạm **CV thật** (dữ liệu cá nhân). **Chỉ chủ dự án chạy**, trên máy của mình.
> Agent không chạy runbook này. Không đưa file xuất ra vào Git, chat, issue, PR hay dịch vụ bên ngoài.
> Xong việc thì tự xóa thư mục tạm chứa dữ liệu thật.

Mục đích: chuẩn bị để chạy L1b (`python -m cv_compass_llm run ...`) trên OCR thật và so với gold.
Thời gian ước tính: O1 khoảng 20–40 phút (phần lớn là tải model), O2 khoảng 10 phút.

Hiện trạng máy (agent kiểm chỉ đọc lúc 12/11/2026 đêm):
- WSL `Ubuntu-24.04` **thấy GPU** (`NVIDIA GeForce RTX 3050 Laptop GPU, 4096 MiB`);
- **chưa có** `ollama` và `psql`;
- Ollama bản Windows đã cài (có trong PATH) nhưng **không chạy**: cổng 11434 không trả lời.

## O1 — Cài Ollama trong WSL Ubuntu-24.04, chỉ nghe 127.0.0.1

1. Mở terminal WSL: `wsl -d Ubuntu-24.04`.
2. Kiểm GPU: `nvidia-smi`. Phải thấy RTX 3050 4 GB. Không thấy thì cập nhật driver NVIDIA trên Windows trước, rồi làm tiếp.
3. **Tránh trùng cổng với Ollama bản Windows.** Ollama Windows và Ollama WSL cùng dùng cổng 11434.
   Nếu Ollama Windows đang chạy (icon ở khay hệ thống), hãy Quit nó trước khi cài/chạy bản WSL.
   Kiểm cổng còn trống: `curl -s -m 3 http://127.0.0.1:11434/api/version` → không có kết quả là được.
4. Cài theo hướng dẫn chính thức tại https://ollama.com/download/linux. Lệnh cài một dòng ở trang đó cần `sudo` và có mạng.
   Nên đọc script trước khi chạy.
5. **Chỉ nghe 127.0.0.1.** Mặc định Ollama nghe `127.0.0.1:11434`. Nếu bản cài dùng systemd:
   - mở cấu hình: `sudo systemctl edit ollama`;
   - thêm 2 dòng: `[Service]` và `Environment="OLLAMA_HOST=127.0.0.1:11434"`;
   - chạy lại: `sudo systemctl restart ollama`.

   **Không** đặt `0.0.0.0`. Kiểm: `ss -ltnp | grep 11434` → địa chỉ phải là `127.0.0.1:11434`.
6. Kiểm chạy: `curl -s http://127.0.0.1:11434/api/version` → ra số phiên bản.

### Tải model

Tag cần tải: `qwen3:4b`, `qwen3.5:4b`, `qwen3.5:2b`.
**Agent không chắc các tag `qwen3.5:*` có tồn tại đúng tên này**, vì không tra được trong đêm và không đoán. Cách tra:
- Xem https://ollama.com/library/qwen3 và https://ollama.com/library/qwen3.5, tab "Tags".
- Hoặc chạy thử `ollama pull <tag>`. Báo `file does not exist` / `not found` thì tag sai.
  **Đừng tự thay bằng tag khác**; ghi lại để chốt sau.

```bash
ollama pull qwen3:4b
ollama pull qwen3.5:4b   # tag chưa xác nhận
ollama pull qwen3.5:2b   # tag chưa xác nhận
ollama list              # ghi lại tên + dung lượng
```

Kiểm nhanh bằng câu hỏi **không chứa CV**:
`ollama run qwen3:4b "Trả lời một từ: xin chào"`.
Trong lúc chạy, mở terminal khác chạy `nvidia-smi` để xem VRAM có được dùng không.
Model 4B ở 4 GB VRAM có thể bị đẩy một phần sang CPU, chậm hơn; đây là điều bình thường, cần ghi lại.

## O2 — Xuất OCR_RESULT ra thư mục NGOÀI repo

Nguồn dữ liệu, theo Flyway `V6__create_ocr_job_state_machine.sql`:
- bảng `"CV_EXTRACTIONS"`, cột `"OCR_RESULT"` (JSONB, dạng `{"pages":[{page_number,status,raw_text}...],"status":...}`);
- khóa `"CV_DOCUMENT_ID"`, `"CREATED_AT"`, `"ID"`.

L1a đọc thẳng dạng JSON này (khóa `status` ở gốc được bỏ qua).

1. Tạo thư mục ngoài repo, ví dụ `mkdir -p ~/cvc-llm-eval/ocr` (trong WSL), hoặc `D:\cvc-llm-eval\ocr`.
   **Không** dùng thư mục nằm trong `E:\cv-compass*` (CLI L1b cũng từ chối ghi vào repo).
2. Cần `psql`. Chọn một trong hai cách:
   - **Cách a:** cài client trong WSL: `sudo apt install postgresql-client`.
   - **Cách b:** dùng `psql` có sẵn trong container Postgres. `docker ps` để xem tên container,
     rồi chạy `docker exec -i <tên> psql ...` thay cho `psql`.
3. Dùng thông tin kết nối DB local của bạn (host/port/db/user giống cấu hình backend local).
   **Nhập mật khẩu khi được hỏi**; không ghi mật khẩu vào lệnh hay file.
4. Xuất mỗi tài liệu một file. Chỉ lấy bản OCR mới nhất của mỗi tài liệu; tên file dùng ID số, **không dùng tên file gốc**
   vì tên file gốc có thể chứa tên người:

```bash
OUT=~/cvc-llm-eval/ocr
psql -h <host> -p <port> -U <user> -d <db> -At -F $'\t' -c '
SELECT DISTINCT ON ("CV_DOCUMENT_ID") "CV_DOCUMENT_ID", "OCR_RESULT"::text
FROM "CV_EXTRACTIONS"
WHERE "OCR_RESULT" IS NOT NULL
ORDER BY "CV_DOCUMENT_ID", "CREATED_AT" DESC, "ID" DESC' |
while IFS=$'\t' read -r id json; do printf '%s' "$json" > "$OUT/doc-$id.json"; done
ls "$OUT" | wc -l      # chỉ xem số file, không mở nội dung trong chat
```

Ghi chú:
- Câu lệnh chỉ **đọc** (`SELECT`), không sửa DB.
- `jsonb::text` không chứa tab/xuống dòng thô, nên tách theo tab là an toàn.

5. Kiểm một file có đọc được không, chỉ in mã lỗi hoặc `ok`, không in nội dung:

```bash
cd <repo>/llm-worker && PYTHONPATH=src python3.14 -c "import sys; from cv_compass_llm import load_document; load_document(sys.argv[1]); print('ok')" ~/cvc-llm-eval/ocr/doc-<id>.json
```

## Sau O1/O2 (chuẩn bị cho L1b, khi đã merge/checkout nhánh có L1b)

```bash
cd <repo>/llm-worker
PYTHONPATH=src python3.14 -m cv_compass_llm run --input ~/cvc-llm-eval/ocr --output ~/cvc-llm-eval/run-qwen3-4b --provider ollama --model qwen3:4b
PYTHONPATH=src python3.14 -m cv_compass_llm draft-gold --results ~/cvc-llm-eval/run-qwen3-4b --output ~/cvc-llm-eval/gold
# Sửa từng file gold bằng tay theo CV gốc, đổi "status" thành "CONFIRMED" khi đã chắc.
PYTHONPATH=src python3.14 -m cv_compass_llm compare --results ~/cvc-llm-eval/run-qwen3-4b --gold ~/cvc-llm-eval/gold
```

`compare` chỉ in số tổng hợp. Ngưỡng bật LLM đã chốt:
- bộ ba (chức danh, công ty, thời gian) đúng ≥ 90%;
- thông tin cá nhân không kém engine 3.5.

Dọn dẹp: thư mục `~/cvc-llm-eval` chứa dữ liệu thật; xóa khi không cần nữa.
