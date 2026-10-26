# Convention Worker Python — quy ước chính thức

Trạng thái: **Đã duyệt ngày 26/10/2026**; phạm vi tại [README](README.md), chưa áp dụng. Worker hiện tại xử lý PDF/page text,
chưa phải candidate extraction service đầy đủ. Không mở scope M6/M7 hoặc thêm LLM.

## Khảo sát và điểm chưa thống nhất

Runtime mục tiêu theo README/CI là CPython 3.14.8, Ubuntu/WSL + Docker cho full gate.
Package `src/cv_compass_worker`; dependency runtime/test được hash-lock riêng.
Không có pyproject, Ruff, Black hoặc type checker trong inventory cấu hình.

| ID | File thực tế | Quan sát | Chuẩn chính thức / xử lý |
| --- | --- | --- | --- |
| W01 | `worker/src/cv_compass_worker/pull_http.py` | `primary=sys.exception()`, `failure=None` xen code có spaces | 4 spaces, spacing PEP 8, single quotes đang phổ biến |
| W02 | `worker/src/cv_compass_worker/config.py`, `pull_http.py` | stdlib `Path`/`logging`/`urlsplit` xen vị trí khác nhau, relative import chưa nhóm đều | stdlib → third party → relative, nhưng giữ nguyên import có side-effect/lazy |
| W03 | `worker/src/cv_compass_worker/pdf.py` so với `pipeline.py`, `pull_config.py` | `preflight(path: Path, max_pages: int) -> int` đầy đủ; `snapshot(...)`, `PullConfig.parse(...)` thiếu hint | Hint ở boundary chỉ cho code mới; Giai đoạn 2 không bổ sung hint vào code cũ |
| W04 | `worker/tests/test_pull.py`, `tests/run_checks.py` | Test config dài một dòng, spacing `ROOT/'src'`, nhiều đối số dính nhau | Formatter giới hạn dòng; giữ literal/path/result và test behavior |
| W05 | `worker/run_worker.py` và `run_pull_worker.py` | Một bootstrap thực thi trực tiếp, một dùng `if __name__ == '__main__'` | Không đồng nhất guard: khác import behavior hiện tại |
| W06 | `worker/src/cv_compass_worker/config.py`, `contracts.py`; `worker/README.md` | `design/` nhìn như tài liệu nhưng có schema/limits runtime dependency; full runner import cả spikes | Giữ nguyên paths; không chuyển design/spikes vào package chỉ vì taxonomy |

Đã có module docstring và tên snake_case/PascalCase tương đối nhất quán; không tạo
finding giả cho những phần đã đồng nhất. Chỉ thấy ba top-level function signature
có return annotation trong `worker/src` qua tìm kiếm; đây không phải AST count mọi method.

## Cấu trúc và naming

| Rule | Đúng / ví dụ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ src layout, module theo trách nhiệm hiện có | `pull.py` supervisor, `pull_http.py` client, `pipeline.py` orchestration, `pdf.py`/`ocr.py` engines | Tạo `consumer/handler/service/client/` rỗng rồi chuyển tất cả | Worker dùng HTTP pull, không message broker consumer; flat package đang đủ rõ |
| Entrypoint không chứa nghiệp vụ mới | `run_worker.py` bootstrap, `__main__.py` CLI | Import engine/native library ở bootstrap sớm hơn | Lazy import và child isolation là một phần lifecycle |
| Function/module/variable snake_case; class PascalCase; constant UPPER_SNAKE_CASE | `real_path`, `PullConfig`, `BASE`, `PROTOCOL` | `realPath`, `pullConfig`, rename wire `profile_revision` sang camelCase | PEP 8 và pattern hiện có; không rename config/JSON/env key |
| Private marker chỉ cho code mới hoặc rename đã chứng minh không external reference | Giữ `real_path` hiện tại | Đổi thành `_real_path` vì nghĩ “helper = private” | Tests/tooling có thể import; không suy luận API chỉ từ ý nghĩa tên |
| Không di chuyển runtime asset, package hoặc builder paths | `DESIGN = Path(__file__).resolve().parents[2] / 'design'` | Đổi `design/` thành `contracts/` mà chỉ update Python import | Docker context, manifests, schemas và provenance còn tham chiếu đường dẫn |

## Type hint, docstring và format

| Rule | Đúng | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Code mới: hint parameters + return ở public/boundary function; helper khi kiểu rõ | `preflight(path: Path, max_pages: int) -> int` từ `pdf.py` | `dict[str, str]` cho payload chứa object/list hoặc `Any` để lách checker (minh họa) | Hint phải phản ánh dữ liệu thật, không tạo cảm giác type safety giả |
| Type hints chỉ áp dụng code mới; Giai đoạn 2 không bổ sung vào code cũ | Giữ signature code cũ | Thêm `from __future__ import annotations` toàn package | Annotation/type alias/import có thể ảnh hưởng runtime; không nằm trong auto-format |
| Docstring ngắn triple double quotes; mở rộng Args/Returns/Raises khi cần | Module `"""Strict local configuration..."""` và giải thích failure contract | Docstring chỉ nhắc lại tên hoặc ghi retry không khớp code | Style đang dùng summary ngắn; section mở rộng dùng cho phần cần giải thích, không mass rewrite |
| Format 4 spaces, single quote cho string thông thường, hai dòng trống giữa top-level definitions | `mode = 'production'` trong `Config` | Ép đổi mọi string kể cả prompt/schema hoặc literal nhạy whitespace | Single quote phổ biến; docstring vẫn double; không đổi string value |
| Width mục tiêu 100, wrap expression giữ thứ tự | Wrap đối số `PullConfig.parse(...)` | Rút gọn boolean guard hoặc đổi tuple thành set | 100 là mục tiêu đã duyệt, không khẳng định chuẩn đa số hiện tại; không sửa expression |
| Nhóm import theo PEP 8 chỉ khi thứ tự an toàn | stdlib trước relative trong module thuần | Hoist import pdfminer trong `preflight` ra module top | Engine import đang được cố ý trì hoãn vào supervised child |

Dùng **Ruff formatter + Ruff linter**, đã duyệt về nguyên tắc, không cài Black song song; single
quotes và line length 100. Lint khởi đầu chỉ chẩn đoán `E4/E7/E9/F/I`; không bật
rewrite `UP/SIM/B` hoặc unsafe fixes. Một chẩn đoán lint không cấp phép sửa logic.
Đợt format phải dùng formatter riêng; import cleanup đợt sau phải review thủ công,
không blanket `ruff check --fix`. `F401` cũng không chứng minh import không có side-effect.

Ruff đã duyệt về nguyên tắc, chưa cài đặt hoặc cấu hình; chờ chốt version và kiểm
chứng Python 3.14.8 ở Giai đoạn 2. Không sửa requirements lock hiện có.
mypy/pyright **không bật bắt buộc trong phạm vi đã duyệt**:
repo chưa có checker và annotations chưa đầy đủ; không cài để rồi thêm ignores/casts
che vấn đề. File generated/output/lock, design JSON và migration ngoài target.

## Config, logging, lỗi và retry

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Load TOML qua config hiện có; credentials lấy từ env được config chỉ định | `Config`, `PullConfig.load/parse`, `credential_env` | Thêm dotenv/framework config hoặc đổi default `enabled` | Có strict validation và operational opt-in hiện tại |
| Log bằng cơ chế safe observability | `observability.event(Event..., error_code=...)`, context allowlist | Log PDF text/request body, repr exception/token/path tùy ý | Formatter cố ý không format `msg/args/exc_info`; giữ nguyên level/event/key |
| Giữ lỗi theo diagnostics/contracts hiện tại | `WorkerFailure`, `Reason`, `failure_scope`, protocol error constants | Đổi mọi `ValueError` thành `WorkerFailure` vì “nhất quán” | Có thể đổi CLI receipt, exit, error classification và retry |
| Không đổi retry/heartbeat/cleanup order | HTTP retry dùng bytes gốc; supervisor quản lý lease/recovery | Thêm retry trong OCR child hoặc đổi sleep/backoff | Request có side-effect, timeout và idempotency là behavior; Spring Boot vẫn quản lý job lifecycle |
| Giữ isolation và import location | Offline PDF child, network-none, không DB | Gọi PostgreSQL hoặc HTTP từ PDF child | Architecture hiện tại và AGENTS.md; refactor không thay service boundary |

## Parsing, AI/LLM, constants và prompt

| Rule | Đúng / ví dụ | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Dữ liệu text không phải chỗ chuẩn hóa style | Giữ `raw_text` v2 và schema/limits hiện có | Thêm strip/normalize/rewrite text khi refactor | Giá trị text là output, không phải code formatting |
| Constants gần module sở hữu; chỉ shared khi đã được dùng chung | `BASE`, `PROTOCOL` trong `pull_http.py`, limits đọc canonical manifest | Copy limit sang config mới hoặc đổi protocol literal | Tránh hai source of truth và drift |
| Không thêm AI/LLM/candidate parser/prompt trong nhiệm vụ này | Giữ local PDF/OCR pipeline; không có pattern LLM/prompt hiện hữu để chọn | Tạo `prompts/`, thêm external AI call để “chuẩn bị” | Chưa có triển khai/ủy quyền, không bịa convention là pattern đa số |
| Nếu feature AI được duyệt riêng: đặt prompt cạnh parser sở hữu, bảo toàn template bytes | Quy ước tương lai: `parser_module` sở hữu template khi thật sự cần (minh họa) | Global prompt registry hoặc gửi CV ra ngoài khi chưa duyệt | Chỉ policy tương lai; cần duyệt privacy/contract riêng; không tạo file bây giờ |

## Test và phần cố ý giữ nguyên

| Rule | Đúng / căn cứ repo | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Giữ unittest, `test_*.py`, `*Tests(unittest.TestCase)`, `test_snake_case` | `ConfigTests.test_limits_can_only_reduce` trong `test_pull.py` | Chuyển sang pytest hoặc rename `setUp` thành `set_up` | Framework/discovery/lifecycle đang có |
| Dùng synthetic fixtures, cleanup theo ownership | `tests/fixtures.py`, `resource_helper.py`, ownership verification | CV thật hoặc cleanup mọi container theo prefix chung | Không đụng tài nguyên khác hoặc dữ liệu cá nhân |
| Full gate theo README, subset ghi đúng phạm vi | `tests/run_checks.py --xml <fresh-path>` trong verifier đã provision | Gọi subset unprivileged rồi tuyên bố Docker full suite PASS | Native/PDF/OCR/resource tests cần môi trường thực |

Không tách `pull.py` (425 dòng), `docker_sandbox.py` (408 dòng), `pipeline.py` (263
dòng) chỉ theo số dòng: cleanup, lock, exception chaining, atomic publication,
deadline và import timing đan xen. Chưa kết luận bug mới; README đã ghi GCC/Tesseract
production blocker và OCR accuracy chưa đánh giá. Đó là rủi ro đã ghi nhận từ trước,
không sửa hoặc tuyên bố đã xác minh lại trong khảo sát này.

Căn cứ chính thức: [PEP 8](https://peps.python.org/pep-0008/),
[Ruff formatter](https://docs.astral.sh/ruff/formatter/),
[Ruff linter và fix safety](https://docs.astral.sh/ruff/linter/).
