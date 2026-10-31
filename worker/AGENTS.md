# Quy tắc worker

Kế thừa [root](../AGENTS.md). Sửa Python: đọc [convention](../docs/conventions/worker.md);
chạy worker: [README](README.md); đụng wire/runtime: [contract](../docs/contracts/README.md)
và policy theo trigger root. Lệnh/gate/cleanup tại [kiểm chứng](../docs/VERIFICATION.md).

- CPython 3.14.8, src layout, dependency hash-lock, unittest; không pytest/framework mới
  nếu chưa duyệt riêng. Ranh giới PDF child/host/Java và runtime asset theo policy.
- Ưu tiên native PDF text, chỉ OCR khi cần; dữ liệu/log theo policy.
- Public/boundary function mới cần parameter/return hints.
  Log dùng `observability.event`, enum và context allowlist.
- Ruff single quotes/width 100; không blanket fix/formatter thứ hai.
- unittest `test_*.py`, `*Tests(unittest.TestCase)`, `test_snake_case`.
