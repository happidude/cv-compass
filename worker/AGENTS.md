<a id="worker-agent-rules"></a>

# Quy tắc agent cho worker

Kế thừa [quy tắc root](../AGENTS.md). Đọc [convention worker](../docs/conventions/worker.md),
[README](README.md) và [bản đồ contract](../docs/contracts/README.md) liên quan.

- Mục tiêu CPython 3.14.8, src layout, dependency khóa hash, unittest.
  Không dùng pytest, framework mới, LLM, candidate parser hoặc prompt nếu chưa duyệt riêng.
- Tự thực hiện phạm vi worker đã duyệt. Không sửa Java khi chưa được phép.
- PDF child offline: không PostgreSQL, HTTP, credential hoặc Docker socket.
  HTTP pull thuộc host supervisor; Spring kiểm soát retry và persistence.
- Ưu tiên native PDF text, chỉ OCR khi cần; giữ raw output, không bịa dữ liệu.
  Chuẩn hóa nghiệp vụ và liên kết danh tính thuộc Java.
- Giữ nguyên đường dẫn/byte của runtime schema/limits trong `design/` và build provenance;
  phân loại tài liệu không cấp quyền di chuyển runtime asset hoặc đổi wire contract.
- Public/boundary function mới cần parameter/return hints.
  Dùng `observability.event`, enum và context allowlist; không log dữ liệu cá nhân
  hoặc error message/đường dẫn tùy ý.
- Ruff single quotes/width 100. Từ worker: `ruff format src tests spikes run_worker.py run_pull_worker.py`
  rồi `ruff check src tests spikes run_worker.py run_pull_worker.py`.
  Không blanket fix, dùng formatter thứ hai hoặc cài global tool.
- unittest `test_*.py`, `*Tests(unittest.TestCase)`, `test_snake_case`;
  chỉ synthetic fixture.
- Full gate dùng Ubuntu/WSL CPython đã provision + Docker Linux/cgroup v2,
  baseline builder/verifier hiện có và report full `tests/run_checks.py --xml <fresh-path>` mới.
  Subset/syntax check không bao giờ chứng minh full Docker PASS.
  Theo [kiểm chứng](../docs/VERIFICATION.md) và giữ cơ chế cleanup theo ownership.
