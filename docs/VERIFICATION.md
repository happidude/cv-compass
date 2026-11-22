# Kiểm chứng

Đọc [root](../AGENTS.md) về quyền/ownership; [workflow](AGENT_WORKFLOW.md) về mức/vòng.

## Lệnh theo phạm vi

| Phạm vi | Affected check mỗi vòng; full gate trên source cuối hoặc CI PR |
| --- | --- |
| Backend | Java 25/PostgreSQL 17; từ backend: `./gradlew test --tests <affected>` và `./gradlew spotlessApply`; full `./gradlew check --rerun-tasks` (Windows `.\gradlew.bat`); Modulith khi áp dụng |
| Worker | Từ worker: `ruff format src tests spikes run_worker.py run_pull_worker.py`, `ruff check src tests spikes run_worker.py run_pull_worker.py`; unittest affected; full builder/verifier dưới đây |
| Frontend | Từ frontend: `npm exec prettier -- --write src tests`, affected Vitest; full `npm run check` và `npm run test:browser` |
| Docs/rules/TOML | Toàn Markdown link/anchor, tham chiếu/phân loại khi di chuyển; JSON parse `.claude/settings.json`, frontmatter `.claude/agents/*.md` (name/description/model/tools); ignore/staging; diff frozen theo spec; `git diff --check`, `git diff --cached --check`; app suite N/A có lý do |

Full worker từ root trên Ubuntu/WSL CPython 3.14.8 đã provision + Docker Linux/cgroup v2:
`python3.14 worker/spikes/m6_2_hardening/build_baseline.py`, rồi
`python3.14 worker/spikes/m6_2_hardening/verify_baseline.py`.
Report full `tests/run_checks.py --xml <fresh-path>` mới trong verifier;
`--cleanup-only` theo ownership kể cả fail, sau khi lượt chạy dừng. Subset/syntax không chứng minh full Docker.
Prerequisite/resource theo README component; kiểm biến test có đặt, không in giá trị/đọc private env.
Dữ liệu test theo policy; thiếu prerequisite báo BLOCKED/check NOT VERIFIED và bước tiếp.

## Full gate và chuỗi PR

`scripts/full-gate.ps1` chạy trên HEAD của checkout hiện tại: backend `.\gradlew.bat check --rerun-tasks`,
frontend `npm run check` + `npm run test:browser`, worker builder/verifier/cleanup-only ở trên qua
`wsl -d <-WslDistro> --exec bash -l` (mặc định `Ubuntu-24.04`; CPython 3.14.8 ở `~/.local/bin` của user nên
cần login shell; clone riêng `~/cv-compass-gate` của script, đúng SHA, build dir mới mỗi lượt). Source (git bundle
của HEAD) và script được đẩy qua stdin vào `/tmp` của distro, nên không cần automount ổ đĩa (`/mnt/e`) hay `wslpath`.
`python3.14 --version` khác 3.14.8 thì worker BLOCKED kèm phiên bản thấy được.
`llm-worker/` (Python thuần thư viện chuẩn) chạy cùng distro trên bản sao `llm-worker/` của HEAD trong `/tmp`
(git archive qua stdin): `unittest` với CPython 3.14.8, cộng `ruff check` + `ruff format --check` nếu máy có ruff;
không có ruff thì có dòng riêng `llm-worker-ruff NOT_VERIFIED` và dòng tổng ghi `(NOT_VERIFIED: ...)` (không cài,
không tính FAIL). HEAD chưa có `llm-worker/` thì bước này là SKIP, không làm hỏng gate.
Log ở `build/gate/<sha>/<timestamp>/`, in ≤ 15 dòng; exit ≠ 0 khi có phần FAIL/BLOCKED hoặc tree bẩn
(kể cả file untracked chưa ignore). `-JavaHome` mặc định `$env:JAVA_HOME`, trống thì dùng JDK 25 của máy dev;
chỉ áp cho tiến trình Gradle con. `TEST_DB_URL`/`TEST_DB_USERNAME`/`TEST_DB_PASSWORD` chỉ đọc từ môi trường;
script chỉ kiểm có/không, không in giá trị. BLOCKED (không coi là PASS) khi thiếu một trong ba biến đó, JDK,
`node_modules`, Playwright browser, WSL distro, Docker hoặc đúng CPython 3.14.8 cho worker; backend thiếu DB/Docker lúc chạy là FAIL.

Chuỗi PR (PR sau dựa trên PR trước); agent chạy gate và báo kết quả, **con người merge**:

- PR giữa chuỗi chỉ chạy affected check theo bảng trên.
- Full gate **một lần** trên head của PR cuối chuỗi.
- Chuỗi được merge cùng lúc, theo đúng thứ tự.
- Muốn merge lẻ một PR giữa chuỗi thì phải chạy full gate trên head PR đó trước.

## Bằng chứng

CI là nguồn sự thật về gate CI; không viết lại report dài. Log ra file, đọc tóm tắt/đuôi,
XML/HTML/problems report mới, số tests/failures/errors/skips, task/report thiếu và warning.
UP-TO-DATE/NO-SOURCE/cache/report cũ không chứng minh chạy. Đánh giá WARN/ERROR dự kiến của negative test.
Giữ `-Xlint:deprecation`/formatter/linter; sửa warning mới trong phạm vi,
warning ngoài phạm vi/công cụ ghi nguồn/nguyên nhân/ảnh hưởng/lý do hoãn; không đổi build config để che.
Log dài không dán vào hội thoại.

Ghi source SHA hoặc base + fingerprint payload trước commit; commit chỉ bằng chứng có thể trỏ payload
đã review giống hệt, không hash tương lai tự tham chiếu. Local/CI báo riêng cùng source cuối;
PASS đã chạy thành công; FAIL thất bại chứng minh; NOT VERIFIED thiếu bằng chứng; N/A không liên quan có lý do.
CI (PR/push vào `main`, `develop`; action ghim SHA, `contents: read`, chỉ run PR chia nhóm theo số PR và bị hủy khi có run mới, run push không bị hủy):

| Workflow | Trigger | Lệnh chính |
|---|---|---|
| Persistence CI | job `scope` (không service) quyết định; `backend-check` chỉ chạy khi `run=true`: mọi PR/push main; develop chỉ khi diff chạm `backend/**`, `frontend/tests/fixtures/llm-remote`, `worker/design` (test backend đọc), workflow này, `scripts/ci/**`, `docs/db` hoặc `.tbls.yml` | `./gradlew check` (Postgres 17), rồi `tbls diff` (tbls v1.96.1 ghim sha256) |
| Flyway immutability | mọi PR, trong job `scope`; so với đầu `origin/<base>` hiện tại | `scripts/ci/check-flyway-immutable.sh BASE HEAD` + test `scripts/ci/tests/`; FAIL nếu sửa/xóa/đổi tên migration đã có, version không tăng hoặc trùng |
| LLM worker CI | `llm-worker/**` | `unittest discover` + `ruff check` + `ruff format --check` (ruff khóa hash `llm-worker/requirements-ci.lock`) |
| Python worker baseline | `worker/**` | build image + `verify_baseline.py` (full suite/cleanup/provenance) |
| Frontend CI | `frontend/**` | typecheck/lint/format, unit, build, browser |

Schema DB: `docs/db/` do tbls v1.96.1 sinh (cấu hình `.tbls.yml`, DSN từ `TBLS_DSN`), **không sửa tay**. Không đặt file khác vào `docs/db/`,
vì `tbls diff` sẽ coi là lệch. DB tài liệu được tạo bằng psql, áp lần lượt `V*__*.sql` (`sort -V`, `-1 -v ON_ERROR_STOP=1`) vào một DB tạm, không qua Flyway,
nên không có `flyway_schema_history`. `backend-check` làm đúng các bước này, cài tbls ghim (kiểm sha256) rồi chạy `tbls diff`; lệch thì FAIL.
Cách sinh lại sau khi đổi migration (DB tạm riêng, không bao giờ trỏ vào DB có dữ liệu thật):
`for f in $(ls backend/src/main/resources/db/migration | grep -E '^V[0-9]+__.*\.sql$' | sort -V); do psql -d tbls_doc -1 -v ON_ERROR_STOP=1 -f backend/src/main/resources/db/migration/$f; done`, sau đó
`docker run --rm --network container:<postgres-container> -v <repo>:/work -w /work -e TBLS_DSN ghcr.io/k1low/tbls@sha256:0d90f07aed0c423a6012d542358c10b13fcdf15a1074ceee3d73676c87cc2f79 doc --force -c .tbls.yml`
(`-c` là bắt buộc), cuối cùng chạy cùng image với `diff -c .tbls.yml`, kết quả phải sạch.
Run thất bại upload báo cáo 7 ngày.
Run thiếu/pending không PASS; chỉ báo CI PASS sau khi kiểm đúng head mới đã kết thúc.
Thử native dispatch/scanner có sẵn khi môi trường hỗ trợ; ghi lệnh/event/source/kết quả,
không có thì NOT VERIFIED + lý do/bước tiếp. Parser không thay dispatch; không mặc nhiên có secret scan.
Tách bằng chứng mới khỏi lịch sử/ngân sách cũ. Dùng [mẫu](templates/verification.md).
