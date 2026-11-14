# Mẫu cấu hình bật REMOTE (L6)

Chỉ chủ dự án thêm khối này vào `backend/.env.properties` (file đó không commit) để bật REMOTE local.
Mặc định REMOTE **tắt**; repo không đổi `application.yaml`. Hướng dẫn đầy đủ: [runbook L6](runbook-l6-remote-e2e.md).

```properties
# Chỉ chủ dự án thêm khối này vào backend/.env.properties để bật REMOTE local.
# Thay <repo> bằng đường dẫn tuyệt đối, dùng dấu / trên Windows.
# Thay <sha256 hex> bằng hash token; không đặt token vào file.
CV_MAPPING_ENABLED=true
CV_CANDIDATE_PUBLICATION_ENABLED=true
cv.mapping.definition-file=<repo>/backend/src/main/resources/mapping/llm-extraction-config-v1.json
cv.mapping.revision=llm-extraction-v1-r1
CV_MAPPING_REMOTE_ENABLED=true
cv.mapping.remote.workers.local_llm.credential-sha256[0]=<sha256 hex>
```
