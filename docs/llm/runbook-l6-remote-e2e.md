# L6 — REMOTE E2E local và quay lại 3.5

Chỉ chủ dự án tự chạy với **PDF tổng hợp**, không CV thật; agent không chạy E2E/Ollama thật.
REMOTE mặc định tắt; chỉ bật bằng cấu hình riêng bên dưới, không đổi `application.yaml`.

## 1. Điều kiện

- Backend, OCR worker và frontend chạy theo [backend/README.md](../../backend/README.md)
  (các hướng dẫn LOCAL_OCR/M11 được liên kết ở đó); giữ cấu hình OCR đã hoạt động.
- Ollama local và model đã kéo theo [runbook O1/O2](runbook-o1-o2.md), phần O1;
  L6 không cần bước O2 xuất CV thật. Kiểm model sẵn có trước khi chạy worker.
- Dùng cùng máy, backend `http://127.0.0.1:8080`; Ollama chỉ loopback.

## 2. Token và cấu hình backend

Trong **cửa sổ PowerShell sẽ chạy llm-worker**, chạy một dòng sau (không bật transcript).
Lệnh sinh 32 byte ngẫu nhiên → base64url không `=`, giữ token trong môi trường và **chỉ in SHA-256**:

```powershell
$rng = [Security.Cryptography.RandomNumberGenerator]::Create(); $bytes = New-Object byte[] 32; $rng.GetBytes($bytes); $rng.Dispose(); $env:CVC_LLM_WORKER_TOKEN = [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_'); $sha = [Security.Cryptography.SHA256]::Create(); ([BitConverter]::ToString($sha.ComputeHash([Text.Encoding]::UTF8.GetBytes($env:CVC_LLM_WORKER_TOKEN)))).Replace('-','').ToLowerInvariant(); $sha.Dispose(); $bytes = $null
```

Không dán token vào file/chat/commit hoặc gõ giá trị token vào lịch sử lệnh.
Chép khối [cấu hình mẫu](llm-remote-properties-example.md) vào `backend/.env.properties`:
thay `<repo>` bằng đường dẫn repo tuyệt đối dùng `/`, `<sha256 hex>` bằng hash vừa in.
Các cờ bật, revision và worker ID `local_llm` giữ như mẫu; không thêm token vào file nào.
Nếu đã có khóa tương ứng, sửa khóa đó, không tạo khóa trùng. Khởi động lại backend.
Dấu hiệu đúng: Flyway đã tới V13, backend khởi động thành công và mapping đăng ký/kích hoạt
revision `llm-extraction-v1-r1` (đối chiếu SQL bên dưới), engine `llm-extraction/1.0`.

## 3. Chạy worker và E2E

Từ `llm-worker/`, tạo `.env` từ `.env.example` nếu chưa có; theo
[README L5](../../llm-worker/README.md#l5--worker-kéo-việc-từ-backend).
Đặt `CVC_LLM_PROVIDER=ollama`, `CVC_LLM_MODEL=<tag model đã kéo>`,
`CVC_LLM_BASE_URL=http://127.0.0.1:11434`, `CVC_LLM_BACKEND_URL=http://127.0.0.1:8080`;
giữ các giới hạn còn lại từ mẫu. Không thêm khóa `CVC_LLM_WORKER_TOKEN` vào `.env`.
Trong cùng cửa sổ đã sinh token:

```powershell
$env:PYTHONPATH='src'
py -3.14 -m cv_compass_llm pull --once
py -3.14 -m cv_compass_llm pull
```

`--once` xử lý tối đa một attempt; không có việc (204) cũng thoát thành công.
Upload một PDF tổng hợp mới qua UI → OCR hoàn tất → job mapping `llm-extraction/1.0`
→ worker nộp kết quả → publication → mở hồ sơ ứng viên trên UI.
Đối chiếu dữ liệu tổng hợp với nguồn, không coi việc tạo hồ sơ là xác nhận độ chính xác LLM.

| Thấy gì là đúng / lỗi thường gặp | Cách đọc và xử lý |
| --- | --- |
| OCR hoàn tất, job `PENDING` → `PROCESSING` → `COMPLETED`, attempt `ACCEPTED` | Worker có số đếm `claimed`, `accepted`; publication tạo hồ sơ có dữ liệu đề xuất cần duyệt (`NEEDS_REVIEW`). |
| Job xong nhưng chưa thấy hồ sơ | Kiểm cờ publication và chờ scheduler, xem mã lỗi an toàn; không sửa DB. |
| 401 `UNAUTHORIZED` | Token môi trường không khớp hash backend hoặc sai cửa sổ; sinh lại cặp token/hash và khởi động lại backend. |
| 403 `TLS_REQUIRED` | HTTP từ địa chỉ không loopback; dùng loopback local, máy khác cần HTTPS được cấu hình riêng. |
| 503 `MAPPING_REMOTE_UNAVAILABLE` | REMOTE còn tắt; kiểm cấu hình mẫu và khởi động lại backend. |
| 422 `INVALID_EVIDENCE` | Backend từ chối cả kết quả vì evidence không khớp OCR; attempt `FAILED`, tính một lượt thử. Giữ nguyên nguồn. |
| Job `FAILED` sau hết lượt | Kiểm mã lỗi/attempt; không retry bằng SQL, không xóa PDF/OCR/job. |

## 4. SQL chỉ đọc

Chạy trong DB local bằng công cụ của chủ dự án; chỉ xem trạng thái/cấu hình, không UPDATE/DELETE:

```sql
SELECT c."REVISION", c."DEFINITION"->>'engineCompatibility' AS "ENGINE_VERSION",
       c."VALIDATION_STATE", (c."ID" = s."ACTIVE_CONFIG_ID") AS "IS_CURRENT"
FROM "CV_MAPPING_CONFIGS" c CROSS JOIN "CV_MAPPING_SETTINGS" s
WHERE s."ID" = 1 AND c."REVISION" = 'llm-extraction-v1-r1';
SELECT "ENGINE_VERSION", "STATUS", COUNT(*) AS "TOTAL"
FROM "CV_MAPPING_JOBS" GROUP BY "ENGINE_VERSION", "STATUS"
ORDER BY "ENGINE_VERSION", "STATUS";
SELECT "STATUS", COUNT(*) AS "TOTAL"
FROM "CV_MAPPING_ATTEMPTS" GROUP BY "STATUS" ORDER BY "STATUS";
```

## 5. Quay lại 3.5

Dừng `pull` bằng Ctrl+C. Trong `backend/.env.properties`, đặt lại:

```properties
cv.mapping.definition-file=<repo>/backend/src/main/resources/mapping/personal-info-config-v3.5.json
cv.mapping.revision=personal-info-grammar-v35-r1
CV_MAPPING_REMOTE_ENABLED=false
```

Revision trên lấy từ [definition 3.5](../../backend/src/main/resources/mapping/personal-info-config-v3.5.json).
Khởi động lại backend để kích hoạt 3.5; giữ mapping/publication bật nếu tiếp tục dùng 3.5.
Job LLM còn `PENDING` sẽ không được ai xử lý: worker 3.5 không nhận engine LLM,
REMOTE tắt nên pull worker không nhận việc. Không xóa hay đổi dữ liệu của các job đó.
Đóng cửa sổ worker để bỏ token môi trường; lần bật sau sinh cặp token/hash mới và **thay** dòng `credential-sha256[0]` cũ (không để lại hash cũ).

## Giới hạn

Description/aliases trong definition hiện chưa được worker dùng trong prompt (cố định `PROMPT_VERSION`); đưa vào prompt cần duyệt riêng.
