# Convention CV Compass — quy ước chính thức

Trạng thái: **Đã duyệt ngày 26/10/2026**. Đã áp dụng có giới hạn; kết quả tại
[REFACTOR_REPORT.md](REFACTOR_REPORT.md).

## Phạm vi và căn cứ khảo sát

Khảo sát checkout `E:\cv-compass`, branch `main`, HEAD
`be7fb4388c18c100dfb34f06ebbd594b4734d65b`. Có thay đổi có sẵn tại
`backend/src/main/resources/application-dev.yaml`; không sửa và không chép giá trị
riêng tư của file này vào tài liệu. Đây là khảo sát tĩnh, không phải chứng nhận toàn
bộ logic không có bug hoặc báo cáo baseline build/test.

Đã kiểm kê toàn bộ danh sách source, cấu hình và test của ba phần; đọc các điểm vào,
module, controller/service/repository/DTO/entity, config, logging, HTTP, OCR,
supervisor, hooks, pages, schema và các nhóm test tiêu biểu. Không đọc dữ liệu CV,
credentials hoặc `.env.local`. Trong lượt khảo sát ban đầu không chạy formatter, cài công cụ, sửa source,
commit/push hay chạy baseline Giai đoạn 2.

| Phần | Hiện trạng khi khảo sát | Công cụ hiện có |
| --- | --- | --- |
| Backend | Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, Gradle wrapper 9.7.1; feature `ingestion`, infrastructure `storage`, HTTP foundation `web` | JUnit Platform, AssertJ, Mockito, kiểm tra Modulith; compiler `-Xlint:deprecation`; chưa có Spotless/Checkstyle |
| Worker | `src/cv_compass_worker`, CLI offline và pull supervisor riêng; runtime mục tiêu CPython 3.14.8 theo README/CI; lockfile có hash | `unittest`, custom JUnit runner, compileall/pip check và Docker verification; chưa có Ruff/Black/mypy/pyright |
| Frontend | React `^19.2.0`, TypeScript `~5.9.3`, Vite `^7.1.0`, Router `^7.9.0`, TanStack Query `^5.90.0`, Zod `^4.1.0`, Tailwind `^4.1.0` | ESLint `^10.13.0`, Prettier `^3.6.2`, Vitest `^5.0.5`, Playwright `^1.56.0`; strict TS |

Các version có `^`/`~` ở trên là **khai báo package.json**, không phải khẳng định
version thực tế đang cài. Giai đoạn 2 phải ghi version resolved từ môi trường/lockfile;
không chỉnh lockfile hoặc dependency version.

Kiểm kê: 159 file Java trong `backend/src`; 45 file Python trong `worker/src`,
`worker/tests`, `worker/spikes`; 37 file TS/TSX trong `frontend/src`, `frontend/tests`.
Không có dòng bắt đầu bằng tab rồi ký tự không phải whitespace trong các tập đó.
Java/Python chủ đạo indent 4 spaces; frontend 2 spaces. Các số này không tính
generated, build output, venv, node_modules. Chi tiết và ví dụ:

- [Backend](backend.md)
- [Worker](worker.md)
- [Frontend](frontend.md)

## Nguyên tắc chung

Trong bảng dưới và các tài liệu thành phần, “Sai” là ví dụ cần tránh theo convention
chính thức, **không mặc nhiên là bug hiện tại**. Ví dụ lấy từ repo được dẫn path;
ví dụ dựng để minh họa được ghi rõ. Rule tương lai không cấp phép triển khai feature.

| Rule | Đúng | Sai / cần tránh | Lý do, căn cứ |
| --- | --- | --- | --- |
| Chỉ sửa hình thức, đóng băng runtime contract | Giữ nguyên `/api/documents`, field `raw_text`, mã lỗi và thứ tự gọi hiện tại | Đổi wrapper thành `{data: ...}` đồng loạt hoặc đổi retry “cho đồng bộ” (minh họa) | Controller, worker contracts và frontend schema đang phụ thuộc các chi tiết này |
| Giữ cấu trúc theo từng hệ sinh thái | `ingestion/service`, `cv_compass_worker/pull_http.py`, `features/documents/api.ts` | Tạo chung `services/` rồi chuyển mọi file vào đó (minh họa) | Đây là các pattern đang có; giảm đổi đường dẫn và rủi ro runtime |
| Comment giải thích vì sao, giữ comment có tác động công cụ | Comment bảo toàn metadata top-level ở `DocumentDetails.java`; giữ `@vitest-environment node` trong `tests/http.test.ts` | `// increment counter` hoặc xóa directive vì tưởng comment thừa (minh họa) | Ý đồ thiết kế hữu ích hơn diễn giải câu lệnh; directive ảnh hưởng execution |
| Không đổi dữ liệu nhạy cảm thành log/debug fixture | Dùng synthetic PDF, log ID đã kiểm soát | Log raw OCR, tên/email, token hoặc commit CV thật (minh họa) | AGENTS.md; có cơ chế safe operational logging sẵn |
| Không suy luận unused từ tìm kiếm text đơn thuần | Kiểm tra reflection, bean name, serialize, import side-effect và tooling trước khi xóa | Xóa `ApiResponse` vì endpoint upload không dùng wrapper (minh họa) | OCR controller vẫn dùng wrapper; tên/đường dẫn có thể được tra động |
| Không format artifact bị đóng băng | Bỏ qua migration, canonical OCR JSON, lockfile, generated MSW | Prettier toàn repo hoặc normalize mọi JSON (minh họa) | Contract có byte/hash identity; migration có checksum |

## Branch, commit và review

Nguồn quy trình là [CV_COMPASS_GIT_WORKFLOW.md](../CV_COMPASS_GIT_WORKFLOW.md)
và [AI_DEVELOPMENT_WORKFLOW.md](../AI_DEVELOPMENT_WORKFLOW.md).
**Không có `docs/git.md` trong checkout khảo sát**; không tạo tài liệu thay thế hoặc
tự đổi quy trình Git. Đợt ban hành tài liệu dùng nhánh docs/code-conventions từ
main theo ủy quyền riêng của developer, bảo toàn thay đổi chưa commit. Branch/worktree
cho refactor Giai đoạn 2 chỉ tạo khi được yêu cầu; không stash/reset thay đổi của developer.

| Rule | Đúng | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| Branch ngắn hạn theo Git workflow | `chore/code-conventions` cho việc bảo trì; `docs/code-conventions` cho đợt ban hành tài liệu | Branch vĩnh viễn `backend` hoặc sửa implementation trực tiếp trên `main` | Git workflow §4; không dùng milestone branch chưa được duyệt |
| Conventional Commits: `type(scope): mô tả` | `style(backend): chuẩn hóa khoảng trắng Java`, `refactor(worker): đổi tên biến nội bộ`, `docs(conventions): ghi quy ước` | `feat: format code`, `update everything` (minh họa) | Git log đã có `test:`, `feat(ocr):`, `fix(logging):`; type phản ánh đúng thay đổi |
| Mỗi commit chỉ một loại thay đổi và một phần | Commit format backend riêng với commit import backend | Trộn Java format, Python rename và TS component extraction | Dễ so sánh hành vi/revert; chỉ commit/push khi có ủy quyền tương ứng |
| Review diff thật, tối đa ba vòng trong một đợt review | Review cả tracked diff và intended new files | Dựa riêng vào formatter exit 0 hoặc CI cũ | Workflow local evidence và giới hạn review vẫn áp dụng; bug ngoài scope chỉ báo cáo |

Không dùng quy tắc “sửa mọi Medium finding” để vượt ràng buộc của nhiệm vụ này:
finding đòi thay hành vi phải ghi báo cáo, không sửa. Nếu cản kiểm chứng, báo BLOCKED /
NOT VERIFIED và xin quyết định; không tuyên bố refactor hoàn tất.

## Quy ước `.editorconfig` (đã tạo)

| Rule | Đúng | Sai / cần tránh | Lý do |
| --- | --- | --- | --- |
| UTF-8, spaces, newline cuối file cho code thông thường | Java/Python 4; TS/TSX/JS/JSON/CSS/YAML 2 | Tab trộn spaces, ép Java về 2 spaces (minh họa) | Chọn indent chủ đạo thực tế |
| LF là đích cho code thông thường; ngoại lệ theo `.gitattributes` | Giữ `.bat` CRLF; canonical JSON giữ nguyên byte; Markdown giữ hard-break spaces | Chuyển CRLF → LF trong `ocr-v1.schema.json` hoặc xóa trailing spaces mọi Markdown | Root `.gitattributes` đã nêu byte identity; backend `.gitattributes` có `.bat` CRLF |
| Không áp dụng cleanup whitespace toàn repo | Exclude generated, lockfile, migration, contract, output; preview diff theo đợt | `git add --renormalize .` (minh họa) | Không phát sinh diff ngoài phạm vi hoặc thay hash |

Cấu hình đã tạo ở repository root: `root=true`, `charset=utf-8`, `indent_style=space`, mặc định
`indent_size=4`, `end_of_line=lf`, `insert_final_newline=true`; override frontend và
JSON/YAML/CSS thành 2, `.bat` thành CRLF. Không bật trim cho Markdown; loại canonical
artifacts khỏi mọi formatter/cleanup. Không normalize EOL checkout hiện tại.

## Quyết định đã chốt

1. Bộ README, Backend, Worker và Frontend là Code Conventions chính thức, đã duyệt
   ngày 26/10/2026. Java dùng **Spotless + google-java-format, style AOSP**. Spotless tích hợp formatter vào Gradle và
   kiểm tra format; google-java-format là formatter chuẩn cho Java: indent 4 spaces, continuation 8 spaces, line width 100 cố định. Import theo output formatter, static trước non-static; không chia nhóm thủ công. Formatter là nguồn chuẩn cho whitespace, wrap và thứ tự import; rule viết tay không được mâu thuẫn output formatter. Ruff
   formatter/linter 0.17.2 đã cài trong tool venv riêng ngoài repo, có `worker/ruff.toml`;
   đã format và sắp xếp imports trong phạm vi kiểm chứng được. Không cài Black.
   Trial Spotless 8.10.4 + google-java-format 1.36.1 chạy được trên JDK 25/Gradle 9.7.1,
   quyết định mới cho phép riêng Java xóa unused imports khi compile thành công và
   toàn bộ output `javap -c -p -constants` giống hệt trước/sau. Python/Frontend vẫn
   cấm xóa unused imports; không đổi giá trị string/text block hoặc dependency hiện có.
   Frontend giữ Prettier 3.6.2 đã có và thêm config explicit, không đổi dependency/lockfile.
2. Python type hints chỉ áp dụng cho code mới. Giai đoạn 2 không bổ sung type hints
   vào code Python cũ; annotation có thể được inspect/runtime evaluate.
3. Không di chuyển package/file, không tách `UploadPage` hoặc `pull.py`; giữ các
   quyết định không thực hiện đã thống nhất, không mở rộng phạm vi.
4. Giữ nguyên `OcrProtocol` trong package `enums` dù là final constants class.
   Không tự thêm `constants/` hoặc thay đổi class này.
5. Ghi nhận roadmap/tham chiếu M6/M7/M8/M9 trong `AGENTS.md` đã lỗi thời so với
   source hiện có và OCR v2; thiếu `docs/POC_OCR_API_CONTRACT.md` trong checkout.
   Tham chiếu runtime ở `worker/design/` và code/tests hiện tại. Không chỉnh sửa
   `AGENTS.md`, roadmap hoặc hợp đồng trong đợt này.

## Kế hoạch kiểm chứng Giai đoạn 2

Baseline và kiểm chứng sau đã chạy. Frontend PASS; backend/worker full gate
**NOT VERIFIED** do thiếu prerequisite, kèm failure counts và bằng chứng tương đương
tại [REFACTOR_REPORT.md](REFACTOR_REPORT.md). Không tuyên bố toàn bộ refactor READY.

- Backend: kiểm tra JDK 25, PostgreSQL 17, schema/storage test cô lập theo README;
  build bằng wrapper và full `./gradlew.bat check --rerun-tasks`. Đọc JUnit XML mới,
  task status, warnings; hiện không có formatter/linter gate ngoài compiler.
- Worker: Ubuntu/WSL, Python 3.14.8, Docker Linux cgroup v2 và prerequisites theo
  `worker/README.md`; dùng `build_baseline.py`, `verify_baseline.py` và cleanup có
  ownership. Không coi syntax compileall là lint hoặc unit subset là full gate.
- Frontend: giữ install/lockfile; `npm run check` bao gồm typecheck, lint,
  format:check, unit tests và build; `npm run test:browser` cho browser suite.
  Live/local smoke cần môi trường cô lập riêng, không nhắm runtime data hiện có.
- Sau mỗi đợt so baseline tương ứng; test mới fail thì hoàn nguyên **chỉ diff do
  agent tạo của đợt đó**, không restore đè công việc developer, rồi điều tra.
- Ghi source/command/environment, counts, warnings và local/CI riêng. Bug đã có
  không sửa; check thiếu prerequisite phải ghi NOT VERIFIED. Không thay test để pass.

Báo cáo áp dụng: [REFACTOR_REPORT.md](REFACTOR_REPORT.md), gồm số file, baseline/sau,
bỏ qua có chủ đích, rủi ro kèm dòng và ưu tiên tiếp theo. Dừng sau lượt này để developer
review; không merge hoặc tự mở rộng phần chưa áp dụng.
