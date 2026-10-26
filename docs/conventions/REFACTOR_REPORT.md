# Báo cáo áp dụng Code Conventions

Ngày: 26/10/2026. Branch duy nhất: `docs/code-conventions`.
Điểm quay lại trước lượt áp dụng: `6ddbe40d249afb356e276f3426860bc2f0e384d9`.
Working tree sạch tại preflight; không tạo branch/worktree khác.

## Kết quả và giới hạn

**Đã áp dụng có giới hạn, chưa đạt full quality gate.** Frontend PASS. Backend và
worker full gate NOT VERIFIED do thiếu prerequisite, với các lỗi thực thi được ghi
nguyên trạng dưới đây. Không diễn giải test failure thành PASS vì AST giống nhau.
Các số liệu lượt đầu bên dưới ghi trạng thái tại `2e019b4`. Quyết định mới ngày
26/10/2026 cho phép riêng Java xóa unused imports và sắp xếp imports bằng formatter,
khi compile thành công và output `javap -c -p -constants` của toàn bộ main/test classes
giống hệt trước/sau (không `-l`). Không đổi string/text block; tắt reflowLongStrings.
Python và Frontend vẫn cấm xóa unused imports. Kết quả lượt Java tiếp theo được ghi bổ sung riêng.

Không đổi logic, API/JSON/DB/env/event contract, dependency hiện có hoặc lockfile.
Không rename, di chuyển package/file, tách hàm/class/component, thêm type hints,
sửa bug hay làm yếu assertions/tests. Java được phép xóa unused imports kèm javap;
Python/Frontend không được xóa import. Test code chỉ thay hình thức.

| Phần | Số file thay đổi so với điểm quay lại | Loại thay đổi |
| --- | ---: | --- |
| Chung | 1 | `.editorconfig`; không normalize EOL |
| Backend | 0 | Chỉ baseline và trial trong ignored build directory; Java/build.gradle giữ nguyên |
| Worker | 48 | 47 Python files format; trong đó 6 file có commit import riêng; thêm `ruff.toml` |
| Frontend | 3 | `.prettierrc.json`, `.prettierignore`, di chuyển 1 type import trong `documents/pages.tsx` |
| Docs | 5 | Bốn conventions và báo cáo này |
| Tổng | 57 | Không gồm logs, output, venv hoặc trial artifacts |

## Commit và checkpoint

| Hash | Message |
| --- | --- |
| `3a18fb8` | docs(conventions): đồng bộ rule format Java với google-java-format (AOSP) |
| `41ab396` | chore(conventions): thêm .editorconfig |
| `19bb413` | chore(worker): thêm cấu hình Ruff |
| `d453273` | style(worker): format Python bằng Ruff |
| `1f55cbf` | chore(frontend): explicit hóa cấu hình Prettier |
| `fe1d93e` | style(worker): sắp xếp import theo Ruff (isort) |
| `2964893` | style(frontend): sắp xếp import theo convention |
| `f516fb8` | docs(conventions): cập nhật trạng thái sau khi áp dụng |

Commit chứa chính báo cáo có message `docs(conventions): thêm báo cáo refactor`;
hash được cung cấp trong báo cáo cuối của chat và `git log`, không tự ghi hash
vào nội dung commit của chính nó. Không tạo empty commit cho frontend format vì
`prettier --write src tests` không tạo thay đổi. Không tạo commit backend chưa áp dụng.
Các checkpoint docs ban đầu, worker và frontend đã được push cùng nhánh; báo cáo này
được push ở checkpoint cuối. Không merge, PR, force push hoặc push main.

## Môi trường và baseline so với sau

Baseline source: Java/Python/TS ở điểm quay lại, trước mọi sửa source. Docs-only
commit `3a18fb8` không thay source. Final Java source vẫn byte-for-byte cùng version;
worker source tại `fe1d93e`, frontend source tại `2964893`; các commit sau chỉ docs.
CI của source cuối **NOT VERIFIED**; kết quả dưới đây là local, không lấy CI cũ làm bằng chứng.

- Windows 11: Microsoft JDK 25.0.4.1 đã có, Gradle wrapper 9.7.1; JAVA_HOME chỉ đặt
  trong command session. JVM Windows lỗi `Unable to establish loopback connection`
  / `Invalid argument: connect`, kể cả retry ưu tiên IPv4, chưa chạy được tests.
- Backend fallback: WSL Ubuntu 24.04, OpenJDK 25.0.4.1, Gradle 9.7.1. Không có
  TEST_DB_PASSWORD/URL/USERNAME trong shell; không đọc credential riêng, không trỏ
  test về dev schema/storage. PostgreSQL 17 và Flyway/JPA integration NOT VERIFIED.
- Worker: WSL Ubuntu 24.04, Python 3.14.8 ở runtime đã provision. Docker daemon
  không có kết nối; builder không tạo được runtime image. Không dùng CV thật.
- Frontend: Windows Node 24.22.0, npm 11.19.1. `npm ci --no-audit --no-fund` PASS,
  lockfile nguyên vẹn. Resolved: React 19.3.1, TS 5.9.3, Vite 7.3.7, ESLint 10.13.0,
  Prettier 3.6.2, Vitest 5.0.5, Playwright 1.64.1. Không đổi version đã khai báo/resolved.

| Phần / lệnh, working directory | Baseline | Sau cuối | Trạng thái |
| --- | --- | --- | --- |
| Backend Windows, `backend/`: `./gradlew.bat check --rerun-tasks --console=plain --warning-mode=all` | Lỗi JVM loopback trước test; không có fresh JUnit | Giữ nguyên Java, dùng WSL bên dưới | NOT VERIFIED trên Windows |
| Backend WSL, `backend/`: `./gradlew check --rerun-tasks --console=plain --warning-mode=all` | 331 tests / 112 failures / 0 errors / 0 skipped, 44 suites; 4 tasks executed; build FAILED | Cùng 331/112/0/0, cùng outcome mọi testcase; 4 tasks executed; build FAILED | Full gate NOT VERIFIED vì PostgreSQL credential thiếu; không che build failure |
| Worker WSL, root: `python worker/spikes/m6_2_hardening/build_baseline.py --output worker/build/conventions-baseline` | FAILED ở Docker build, daemon unavailable | Không build lại image khi prerequisite vẫn thiếu | Image/provision/full Docker gate NOT VERIFIED |
| Worker WSL, `worker/`: `python tests/run_checks.py --xml build/conventions/<baseline\|after-format\|after-imports>.xml` | unittest: 177 tests, 15 failures, 25 errors, 0 skipped | Sau format và imports: cùng summary; outcome mọi testcase JUnit không đổi | Full gate NOT VERIFIED; các lỗi môi trường/native/Docker đã có ở baseline |
| Frontend `frontend/`: `npm run check` | typecheck/lint/format/unit/build PASS; 22/0/0/0 unit tests, 5 files | Cùng PASS, 22/0/0/0 | PASS |
| Frontend `frontend/`: `npm run test:browser` | 30/0/0/0 | 30/0/0/0 | PASS, mock/synthetic Chromium suite; không phải live OCR E2E |
| Worker: `ruff format --check src tests spikes run_worker.py run_pull_worker.py` | Chưa có Ruff gate; trial báo file chưa format | PASS, không còn format diff | PASS trong target/excludes |
| Worker: `ruff check --select E4,E7,E9,F ...` | Chẩn đoán được ghi sau format, không coi là lint baseline trước sửa | 19 diagnostics: E402 ×1, E731 ×2, F401 ×16 | Lint FAIL; chỉ báo cáo, không sửa |
| Worker: `ruff check --select I ...` | Chỉ xem trước sửa import | Còn 49 import-block diagnostics trong các file cố ý giữ nguyên | Chưa đồng nhất toàn bộ imports; không suppress |

Custom worker JUnit writer chỉ lưu một outcome cho mỗi testcase: XML có
177 tests / 14 failures / 24 errors / 0 skipped / 139 passed. unittest console
đếm cả failing/error subtests nên summary là 15 failures / 25 errors. Không cộng
hai dạng evidence hoặc báo 139 passed là full suite PASS. Đã so sánh XML theo
`(classname, name, outcome)` giữa baseline, sau format và sau imports: không khác.

Fresh local evidence không commit: `backend/build/conventions/` chứa logs, baseline
JUnit copies, AST/EOL JSON, import diagnostics, frontend resolved versions và trial
project; `worker/build/conventions/` chứa ba fresh XML. Baseline Java log 4m49s;
final 2m48s. Worker ba lượt 35.559s / 29.354s / 33.318s. Frontend browser 1.8m / 1.4m.
Các output này bị ignore, không dùng làm source hoặc đưa log lớn vào Git.

### Warnings giữ nguyên

- Backend: không ghi nhận compiler/deprecation warnings mới; expected negative-test
  logs không đồng nghĩa compiler warning. PostgreSQL failures vẫn ngăn full gate.
- Spotless trial 1.37.0: `NoSuchMethodError` ở `JavaFormatterOptions.Style.valueOf`;
  đổi candidate tool mới sang 1.36.1 (default Spotless công bố), không đổi dependency
  hiện có. Trial diagnose có Gradle-10 deprecation notice; không suppress.
- Frontend: hai Rollup warnings về PURE annotations của Zod trong
  `node_modules/zod/v4/core/regexes.js` và `util.js`, có cả baseline và sau.
  Browser có NO_COLOR/FORCE_COLOR warning. Không chỉnh dependency hoặc env để che warning.
- Ruff install có pip update notice; không nâng pip. GCC/Tesseract production gate
  trong worker README là rủi ro lịch sử, không được coi đã resolve qua lượt này.
- Plain `git diff --check` trên worker báo CR cuối dòng là trailing whitespace ở
  file vốn lưu CRLF. Đã giữ EOL thay vì normalize. Kiểm tra bổ sung
  `git -c core.whitespace=cr-at-eol diff --check` PASS, vẫn phát hiện spaces/tabs thừa.
  Không đổi config Git repository hoặc tắt lint; ghi rõ giới hạn plain check này.
  Git add docs/.editorconfig có autocrlf advisory; không sửa EOL source vì advisory.

## Tool versions và quyết định backend

| Tool | Trạng thái / căn cứ |
| --- | --- |
| Spotless 8.10.4 | Stable theo [changelog chính thức](https://raw.githubusercontent.com/diffplug/spotless/main/plugin-gradle/CHANGES.md); trial project riêng chạy được trên Gradle 9.7.1/JDK 25; chưa thêm plugin backend |
| google-java-format 1.36.1 AOSP | Default của Spotless được changelog công bố; trial parse/format thành công; `reflowLongStrings(false)`, `formatJavadoc(false)`, `reorderImports(false)` |
| Ruff 0.17.2 | [Release stable](https://github.com/astral-sh/ruff/releases/tag/0.17.2); cài bằng pip trong `/tmp/cv-compass-conventions-tools`, Python 3.14.8; target py314 được tool chấp nhận; không Black, không requirements lock change |
| Prettier 3.6.2 | Package có sẵn; config explicit đúng defaults, không plugin mới |

Trial Java chỉ dùng synthetic `Trial.java` trong ignored build directory, có
`import java.util.Set` không dùng và `import java.util.List`. `spotlessApply` xóa
Set ngay cả khi không bật `removeUnusedImports` và `reorderImports(false)`.
[Adapter chính thức](https://raw.githubusercontent.com/diffplug/spotless/main/lib/src/googleJavaFormat/java/com/diffplug/spotless/glue/java/GoogleJavaFormatFormatterFunc.java)
gọi trực tiếp RemoveUnusedImports rồi ImportOrderer. Flag reorderImports(false)
không phải disable sorting toàn bộ; không có flag disable removal trong cấu hình
đã kiểm tra. Vì vậy không đưa một pipeline vi phạm yêu cầu vào build.gradle.
Chưa trial tất cả syntax/string/text block của source thật; không tuyên bố hỗ trợ
hoàn chỉnh codebase từ một sample. Không chạy formatter trên Java source.

Do chưa áp dụng backend: javap trước/sau **0 file, NOT APPLICABLE**, không có kết
luận bytecode-equivalence giả. Không đổi bất kỳ file Java nào (159 main/test files
giữ nguyên), không mở wildcard ở 29 production files và không bật spotlessCheck.
Không thay nguồn chuẩn AOSP sang formatter khác.

## Kiểm chứng tương đương và review

| Phạm vi | So sánh | Kết quả |
| --- | --- | --- |
| Worker format | `ast.dump(ast.parse(bytes, type_comments=True), include_attributes=False)` trước/sau từng file bằng cùng Python 3.14.8 | 47/47 giống hệt, kể cả string/docstring values; không dùng ngoại lệ docstring |
| Worker imports | Giữ multiset Import/ImportFrom với alias nguyên vẹn; AST toàn bộ phần ngoài import giống hệt; chỉ block đầu file stdlib | 6/6 chỉ hoán vị imports, không F401 deletion; test outcomes không đổi |
| Worker cuối so với original backups | AST chính xác + đối chiếu import permutation | 41 AST giống hệt + 6 import-only permutations; 47/47 giữ loại EOL gốc; không mixed/new CRCRLF |
| Frontend format | `prettier --check` rồi `--write src tests`, kiểm tra Git diff | Không có diff; không JSX text/className change, không empty commit |
| Frontend type import | TS 5.9.3 `transpileModule` với compiler options tsconfig, không source maps | Emitted JS trước/sau giống hệt; runtime import order giữ nguyên, EOL giữ nguyên |
| Frozen artifacts | Git diff so với điểm quay lại | Không đổi backend/src, backend/build.gradle, migration, worker/design, canonical OCR JSON, lockfiles, MSW generated file, .bat |

Số file đã áp dụng rồi hoàn nguyên: **0**. Một ứng viên frontend `button.tsx`
đổi sang `import type * as React` không đạt so sánh emitted output nên bị loại
**trước khi ghi file**, không tính là revert. Không có test regression mới từ thay đổi
đã giữ. Review actual diff theo từng đợt, kèm AST/EOL/emitted output và test outcomes;
không dựa riêng vào formatter exit code. Phần import ngoài phạm vi chứng minh được
được bỏ qua, không suppress hoặc xóa cảnh báo để tạo gate xanh.

## Bỏ qua có chủ đích

Frontend giữ runtime import order ở `main.tsx` (font/CSS/bootstrap/dynamic imports),
`app/router.tsx`, `features/batches/pages.tsx`, `features/batches/queries.ts`,
`features/documents/pages.tsx`, `features/documents/queries.ts`,
`features/documents/upload-page.tsx`, `shared/ui/common.tsx`.
Các runtime modules có config initialization, QueryClient, route/MSW setup hoặc
dependency chưa chứng minh thứ tự evaluation không quan trọng. Chỉ chuyển type
import DocumentDetail; không tuyên bố toàn bộ import grouping đã đồng nhất.
Giữ `shared/ui/button.tsx` như nêu trên; các file đã đúng grouping không cần sửa.
Không chạy lint autofix hoặc className sorting.

Worker: 38 file có import diagnostics được bỏ qua; danh sách theo nhóm dưới đây.
File format vẫn được giữ nếu AST giống hệt; “bỏ qua” ở đây chỉ là đợt sắp xếp import.

| Worker file (tương đối worker/) | Lý do bỏ qua import |
| --- | --- |
| `run_pull_worker.py` | Bootstrap hoặc code thực thi trước/giữa import |
| `run_worker.py` | Có import lồng/trì hoãn |
| `spikes/m6_1/stack_smoke.py` | Có import lồng/trì hoãn |
| `spikes/m6_2_hardening/download_locked.py` | Bootstrap hoặc code thực thi trước/giữa import |
| `spikes/m6_2_hardening/verify_baseline.py` | Bootstrap hoặc code thực thi trước/giữa import |
| `src/cv_compass_worker/child.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/docker_sandbox.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/ocr.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/pdf.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/pipeline.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/pull.py` | Có import lồng/trì hoãn |
| `src/cv_compass_worker/sandbox.py` | Có import lồng/trì hoãn |
| `tests/fixtures.py` | Có import lồng/trì hoãn |
| `tests/native_quality.py` | Có import lồng/trì hoãn |
| `tests/run_checks.py` | Có import lồng/trì hoãn |
| `tests/test_additional.py` | Có import lồng/trì hoãn |
| `tests/test_docker_enforcement.py` | Có import lồng/trì hoãn |
| `tests/test_halt_diagnostics.py` | Có import lồng/trì hoãn |
| `tests/test_native_quality.py` | Có import lồng/trì hoãn |
| `tests/test_observability.py` | Có import lồng/trì hoãn |
| `tests/test_pipeline.py` | Có import lồng/trì hoãn |
| `tests/test_pull.py` | Có import lồng/trì hoãn |
| `tests/test_revision.py` | Có import lồng/trì hoãn |
| `tests/test_verification_ownership.py` | Có import lồng/trì hoãn |
| `spikes/m6_1/test_stack_smoke.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `spikes/m6_2_hardening/build_baseline.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `spikes/m6_2_hardening/rebuild_worker.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `src/cv_compass_worker/__main__.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `src/cv_compass_worker/contracts.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `src/cv_compass_worker/observability.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `src/cv_compass_worker/pull_config.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `src/cv_compass_worker/pull_http.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/run_m7_independent_checks.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/run_unprivileged_checks.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/test_encryption.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/test_hardening_contract.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/test_hardening_review.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |
| `tests/test_review_regressions.py` | Chưa chứng minh import nội bộ/third-party không có side-effect |

### Wildcard Java chưa thay

29 production files dưới `backend/src/main/java/com/cvcompass/backend/`:

- `ingestion/config/DiskImportConfiguration.java`
- `ingestion/config/DiskImportProperties.java`
- `ingestion/config/OcrIntegrationConfiguration.java`
- `ingestion/config/OcrIntegrationProperties.java`
- `ingestion/config/OcrJobProperties.java`
- `ingestion/config/OcrSecurityConfiguration.java`
- `ingestion/config/OcrWorkerAuthenticationFilter.java`
- `ingestion/config/OcrWorkerAuthenticationProvider.java`
- `ingestion/controller/OcrWorkerController.java`
- `ingestion/entity/Candidate.java`
- `ingestion/entity/CvDocument.java`
- `ingestion/entity/CvExtraction.java`
- `ingestion/entity/CvImportBatch.java`
- `ingestion/entity/CvImportItem.java`
- `ingestion/entity/CvOcrAttempt.java`
- `ingestion/entity/CvProcessingJob.java`
- `ingestion/repository/CvImportItemRepository.java`
- `ingestion/repository/DiskImportRepository.java`
- `ingestion/repository/OcrStateRepository.java`
- `ingestion/service/CvBatchService.java`
- `ingestion/service/OcrEnqueueService.java`
- `ingestion/service/OcrJobService.java`
- `ingestion/service/OcrLeaseReaper.java`
- `ingestion/service/OcrResultValidator.java`
- `ingestion/service/OcrWorkerService.java`
- `ingestion/source/ImportSource.java`
- `ingestion/source/LocalDiskImportSource.java`
- `ingestion/upload/PdfUploadStream.java`
- `storage/local/LocalFileStorageService.java`

### Lint E4/E7/E9/F còn lại (không sửa)

| File:dòng | Code | Chẩn đoán |
| --- | --- | --- |
| `worker/spikes/m6_2_hardening/download_locked.py:11` | E402 | Module level import not at top of file |
| `worker/src/cv_compass_worker/pipeline.py:57` | E731 | Do not assign a `lambda` expression, use a `def` |
| `worker/src/cv_compass_worker/pull.py:22` | F401 | `.diagnostics.annotate` imported but unused |
| `worker/tests/fixtures.py:3` | F401 | `pathlib.Path` imported but unused |
| `worker/tests/fixtures.py:4` | F401 | `pypdf.PdfReader` imported but unused |
| `worker/tests/native_quality.py:193` | E731 | Do not assign a `lambda` expression, use a `def` |
| `worker/tests/run_unprivileged_checks.py:5` | F401 | `sys` imported but unused |
| `worker/tests/run_unprivileged_checks.py:7` | F401 | `run_checks.ROOT` imported but unused |
| `worker/tests/test_additional.py:2` | F401 | `os` imported but unused |
| `worker/tests/test_docker_enforcement.py:3` | F401 | `dataclasses.replace` imported but unused |
| `worker/tests/test_docker_enforcement.py:12` | F401 | `cv_compass_worker.config.Config` imported but unused |
| `worker/tests/test_docker_enforcement.py:17` | F401 | `cv_compass_worker.docker_sandbox.execute_scope` imported but unused |
| `worker/tests/test_docker_enforcement.py:19` | F401 | `cv_compass_worker.docker_sandbox.docker_env` imported but unused |
| `worker/tests/test_halt_diagnostics.py:7` | F401 | `pathlib.Path` imported but unused |
| `worker/tests/test_pipeline.py:2` | F401 | `dataclasses.replace` imported but unused |
| `worker/tests/test_pipeline.py:13` | F401 | `cv_compass_worker.contracts.page` imported but unused |
| `worker/tests/test_pipeline.py:14` | F401 | `cv_compass_worker.pipeline.empty_result` imported but unused |
| `worker/tests/test_pull.py:335` | F401 | `unittest.mock.Mock` imported but unused |
| `worker/tests/test_review_regressions.py:10` | F401 | `cv_compass_worker.contracts.validate_request` imported but unused |

## Rủi ro / code smell / tham chiếu chưa sửa

| Mức ưu tiên | File:dòng | Quan sát / giới hạn |
| --- | --- | --- |
| P1 | `AGENTS.md:524`, `AGENTS.md:584` | Roadmap vẫn coi M6 là next milestone; lệch source M7/M8/M9 và OCR v2; không chỉnh AGENTS |
| P1 | `AGENTS.md:367` | Tham chiếu `docs/POC_OCR_API_CONTRACT.md` không có trong checkout |
| P2 | `docs/conventions/README.md:59` | `docs/git.md` không tồn tại; dùng CV_COMPASS_GIT_WORKFLOW hiện có, không tạo tài liệu thay thế |
| P1 | `worker/README.md:6`, `worker/README.md:122` | GCC/Tesseract production blocker, OCR accuracy chưa đánh giá; không phải bug mới đã tái hiện |
| P1 | `backend/src/test/java/com/cvcompass/backend/IsolatedPostgresSchema.java:26` | Required TEST_DB_PASSWORD thiếu ở môi trường này; không sửa test guard hoặc dùng dev DB |
| P2 | `worker/tests/run_checks.py:42` | Custom XML runner lưu một outcome/testcase, khác số subtest failures/errors console; báo cả hai, không sửa runner |
| P2 | `frontend/src/features/documents/upload-page.tsx:31`, `worker/src/cv_compass_worker/pull.py:225` | File/lifecycle dài; không tách vì scope và nguy cơ hook/lock/cleanup order |

Các chẩn đoán lint chi tiết ở trên là code smell/tooling diagnostics, không tự
khẳng định bug runtime. Không có bug nghiệp vụ mới được kết luận bằng bằng chứng
trong đợt chuẩn hóa này. Thất bại baseline không được sửa tiện tay.

## Bước tiếp theo (tách khỏi nhiệm vụ này)

1. **P1:** Cung cấp PostgreSQL 17 test credentials trong shell và Docker/native
   prerequisites đúng README, chạy lại full local gates trên source cuối; không
   dùng CI thay bằng chứng local hoặc đổi tests để bỏ prerequisite.
2. **P1:** Tích hợp AOSP theo quyết định mới cho phép Java import cleanup, giữ
   compile/javap gates và công cụ đã chọn; không mở ngoại lệ sang Python/Frontend.
3. **P2:** Review riêng E402/E731/F401 và import side-effects. Chỉ xóa/di chuyển khi
   chứng minh an toàn hoặc có scope riêng; không bật unsafe fixes.
4. **P2:** Đồng bộ roadmap và các tham chiếu hợp đồng bằng nhiệm vụ docs riêng.
5. **P3:** Nếu developer duyệt scope riêng, đánh giá type hints cho Python cũ và
   tách file/component dài; đây là các mục đang hoãn, không được tự triển khai.

Dừng sau push nhánh `docs/code-conventions` để developer review. Không merge/PR.
