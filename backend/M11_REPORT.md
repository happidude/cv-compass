# M11 — Báo cáo triển khai và quality gate

Ngày bàn giao 29/10/2026 (bắt đầu 28/10), Asia/Saigon.
Baseline đã fetch/đối chiếu: origin/main
67c56c7baeef88eaf651dd066c9659ef2ba337aa, PR #17 đã merge.
Branch codex/m11-candidate-review; worktree
C:\Users\NguyenVanHoang\.codex\worktrees\m11-candidate-review\cv-compass.
Checkout gốc và worktree M10.3 được bảo toàn. Không merge hoặc tự xóa worktree.

M11 được ủy quyền triển khai Java/Frontend, migration, kiểm thử và commit/push/PR.
Đã đọc workflow, conventions, M10.1 local ignored, MAPPING/OPERATIONS/EXPERIENCE_V2/
M10_3_REPORT và implementation thật. AGENTS không có trong worktree mới; bản local
E:\cv-compass\AGENTS.md đã được đọc, roadmap cũ không thay phạm vi M11 được giao.
Không dùng subagent; một agent chuyển tuần tự Developer/Reviewer.

## Luồng và kiến trúc

Mapping completed v2 usable → publication ledger unique → profile UNVERIFIED →
đăng nhập → hồ sơ cạnh PDF → chỉnh sửa/xác nhận từng phần → history.
Không chờ review toàn CV mới cho hồ sơ xuất hiện. Name/email/phone thiếu vẫn truy cập
bằng candidate ID và document; không tạo giả các giá trị đó hoặc tự gộp ứng viên.

Ingestion giữ duy nhất JPA Candidate cho identity CANDIDATES. Named interface
ingestion::candidate-documents và service bridge lock/CAS association; candidate
không truy cập entity/repository nội bộ. Mapping::results cung cấp scan metadata
bounded và read completed result; mapping không phụ thuộc candidate.
Candidate sở hữu profile/experience/publication/proposal/history. Identity sở hữu
users/session. Modulith verify và package/encapsulation rules vẫn chạy, thêm hai
module candidate/identity và named interface có kiểm soát.

V10 additive thêm 6 bảng candidate và CV_USERS; V1–V9 giữ nguyên. Core basic fields
và experience dùng cột chuẩn; basic/DATA JSONB và EXTENSIONS version 1 giữ phần mở
rộng, v2 payload, alternatives/issues/evidence. Ledger, association, revision,
profile/proposals/history commit cùng transaction. UUID experience do candidate tạo,
không dùng localId của mapping làm khóa ổn định.

Version ordering theo (extractionId,configId,resultId) trong từng document; late old
publication ghi SUPERSEDED, v1/unsupported/empty v2 ghi SKIPPED. Lần đầu tạo dữ liệu;
remap tiếp theo luôn tạo proposal để người dùng chọn ADD/target UUID, không tự ghép
và không overwrite USER/CONFIRMED/tombstone. Cùng result poll lặp không tạo trùng.
Sweep có cursor/upper bound durable và reconciliation hữu hạn; crash sau reserve
được thử lại ở sweep sau. Concurrency/page/delay được validated, không đổi batch
nghiệp vụ cv.batch.max-files. Không có retry nóng hoặc attempt limit tự bỏ dữ liệu.

Basic/experience có UNVERIFIED/CONFIRMED/EDITED và MAPPING/USER. Mutation yêu cầu
expected version; lock/version chặn lost update và decision race. USER edits bỏ PDF
evidence hiện hành, giữ history/evidence cũ. Title chưa biết được giữ null/NOT_FOUND,
không bắt điền đoán. Delete giữ tombstone. Actor lấy từ session, không từ request body.

Chi tiết migration, API, configuration, provisioning và lệnh local:
[M11.md](M11.md). Không có user/password production mặc định; provision bằng bcrypt
hash file riêng tư và cấu hình operator, chỉ INSERT, không public registration.
USER/ADMIN dùng chung kho; session HttpOnly/Secure/SameSite Strict, CSRF cho mutation.
HTTP loopback dev/test phải bật rõ secure=false; production dùng HTTPS/proxy cùng origin.
OCR bearer chain không dùng session và user chain không fallback sang OCR provider.

PDF API authenticated stream 64 KiB, single/open/suffix Range và 416, safe generic
filename, private/no-store; phân biệt missing document/file/denied/unavailable.
Không trả storage path/key hay worker token. PDF.js 6.4.299 dynamic import + worker/
font/CMap/WASM/ICC cùng origin render canvas, chuyển page/document theo evidence.
Không có CDN hoặc dịch vụ nhận CV; không XFA/annotation HTML. Raw/OCR/user text đều
render như text. Giới hạn canvas 1600×2400; có cancel/cleanup và tải PDF gốc.
Không sửa PDF, không bbox/highlight giả, không export.

## Gates local trên source cuối

| Gate | Kết quả | Evidence |
| --- | --- | --- |
| Backend spotlessCheck + check --rerun-tasks | PASS — 408 tests, 54 suites, 0 failure/error/skipped | build/m11/final-check.log; final-junit/TEST-*.xml |
| Modulith, package rules, migration/constraints | PASS trong full gate | Fresh JUnit của full run |
| Frontend check | PASS — typecheck/lint/Prettier, 22 tests/5 files, build | frontend/.m11-check.log |
| Full browser suite | PASS — 40/40 | frontend/.m11-browser.log; Playwright report |
| Live UI/API/DB/storage | PASS — 1/1, 15.1s | frontend/.m11-live.log; backend/build/m11/final-live-server.log |
| Python OCR engine / production accuracy | NOT VERIFIED, không nằm trong thay đổi M11 | Worker/parser/OCR gate không đổi |
| CI final commit | Theo dõi trên PR sau push; local evidence không dựa CI cũ | SHA/run links trong PR và bàn giao |

Backend: WSL CVCompass-Ubuntu-24.04, Java 25.0.4.1, Gradle 9.7.1; PostgreSQL 17.11
container test riêng trên loopback 55432. IsolatedPostgresSchema tạo/drop schema và
storage riêng; không dùng DB dev. TEST_DB_* chỉ cấp qua process environment.
Full backend: BUILD SUCCESSFUL 1m28s, 7 actionable tasks/7 executed. Đã đọc log và
fresh XML, không coi UP-TO-DATE là full execution.
Frontend: Windows Node 24.22.0 / npm 11.19.1; resolved React 19.3.1, TypeScript
5.9.3, Vite 7.3.7; chỉ thêm pdfjs-dist 6.4.299 và transitive lock entries, không nâng
các dependency cũ. Full browser khoảng 1m30s, không skip.

Backend src manifest SHA-256:
18d0922ecbf360a950dd1a65d84e0c2ad0ee39a5db45e86f6b5b24037ebc278f.
Frontend source/tests/config/lock manifest SHA-256:
711b39016cdd86c0cabcb1189eed5a66a20aff01ac777941e6526b154895aa7f.
Manifests ignored trong build/m11; source được đối chiếu lại trước commit.
Docs hoàn thiện sau gate; không thay runtime source sau verification.

Live test dùng PDF tổng hợp hợp lệ → OCR envelope qua validator/OcrJobService →
mapping engine thật → publication thật. Browser thật đăng nhập/CSRF, đọc đúng PDF
(Range bytes 0–3 = %PDF), assert canvas có pixel mực và text từ PDF, chọn evidence,
sửa basic USER, xác nhận experience, đọc actor/history, remap tạo proposal và giữ
USER/CONFIRMED, reject và logout. Screenshot đã kiểm tra trực quan:
frontend/.ci-reports/m11-live/profile-source.png. Không dùng CV thật, không gọi Python
OCR engine; đây không phải benchmark OCR. Test renderer độc lập native PDF plugin.

## Lệnh tái lập

Từ backend, với TEST_DB_* trỏ PostgreSQL **disposable**, Java 25:

```sh
bash gradlew spotlessApply
bash gradlew spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
```

Trên môi trường Windows này, Gradle chạy trong WSL với init script ignored chuyển
build/cache ra native Linux để tránh lỗi/độ chậm /mnt/c; không đổi task/test exclusions:

```groovy
allprojects {
    layout.buildDirectory.set(file('/tmp/cv-compass-m11/build'))
    afterEvaluate {
        tasks.register('m11Smoke', JavaExec) {
            dependsOn testClasses
            classpath = sourceSets.test.runtimeClasspath
            mainClass = 'com.cvcompass.backend.M11SmokeServer'
        }
    }
}
```

```sh
bash gradlew -I build/m11/native-output.gradle --project-cache-dir /tmp/cv-compass-m11/cache spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
# Explicit opt-in test main, sourceSets.test only:
M11_RUN_SMOKE=1 bash gradlew -I build/m11/native-output.gradle --project-cache-dir /tmp/cv-compass-m11/cache m11Smoke --console=plain
# Từ frontend sau khi ready.json xuất hiện:
npm run check
npm run test:browser
npm exec playwright -- test --config playwright.m11-live.config.ts
```

Live artifacts có outputDir riêng để không đụng thư mục full-browser. Server tự
cleanup sau marker stop/deadline; schemas/storage do helper sở hữu. Không đưa init
script/logs/credentials/runtime data vào commit. Native PostgreSQL test schema và
container được kiểm tra/dừng sau verification; giữ worktree để review.

Warnings còn lại: Rollup bỏ hai comment @__PURE__ có vị trí không phù hợp trong
dependency Zod hiện có; Playwright runner có FORCE_COLOR/NO_COLOR advisory.
Không đổi dependency đó, suppress/filter warning hoặc test exclusions. Java không
còn deprecation/compiler warning mới; Jackson API thay thế được xác minh từ jar
resolved. Git LF/CRLF advisory theo cấu hình hiện hữu, diff --check phải sạch.
Các failure phát triển được sửa theo nguyên nhân: wiring/proxy filter, fixture
accessor, auth setup cũ, package responsibility và locator. Không nới assertion.
Thử full Chromium native gặp spawn UNKNOWN; giải pháp cuối PDF.js dùng browser suite
chuẩn, không dùng lỗi môi trường đó làm bằng chứng viewer PASS.

## Review đúng ba vòng

Review base 67c56c7; đọc diff tracked/staged, status và intended new files. Không có
reviewer bên ngoài. Vòng 1 và 2 tìm/sửa các mục dưới; vòng 3 kiểm toàn bộ source
cuối, fixes, staged scope và evidence. Không còn Critical/High/Medium hoặc Low mở.

| ID | Mức | Vị trí fix trên source cuối | Finding, sửa và xác minh |
| --- | --- | --- | --- |
| M11-R1 | Medium | candidate/service/CandidateService.java:474 | USER correction giữ mapping title/taxonomy state cũ; tái hiện trước fix, reset state theo dữ liệu USER; regression/full PASS |
| M11-R2 | Medium | candidate/controller/CandidateController.java:35 | Command thiếu operation/version trả 500/conflict không đúng; tái hiện trước fix, @Valid + required/version/enum constraints; HTTP 400/409 regression PASS |
| M11-R3 | Medium | V10__candidate_profiles_publication_and_users.sql:5; CandidateService.java:227 | Core basic chỉ JSONB chưa đúng hybrid; thêm cột chuẩn, cập nhật cùng transaction, list metadata không đọc body; DB/list assertions PASS |
| M11-R4 | Medium | frontend/src/features/candidates/pdf-canvas.tsx:55 | Chỉ assert URL chưa chứng minh PDF hiện cạnh hồ sơ; ảnh headless trắng; thay PDF.js, assert pixel/text và kiểm tra screenshot thật PASS |
| M11-R5 | Medium | candidate/service/CandidateService.java:520 | Year JSON quá lớn bị asInt truncate thành năm khác; tái hiện 4294969296 trước fix; canConvertToInt cho year/month/day và full regression PASS |
| M11-R6 | Medium | CandidateService.java:466; frontend/src/features/candidates/experience-form.tsx:66 | Không sửa employer khi title chưa biết; tái hiện trước fix; giữ null/NOT_FOUND, không bắt title; backend/browser regression PASS |
| M11-R7 | Medium | frontend/src/shared/session.ts:51; frontend/src/features/candidates/pages.tsx:314 | Mọi 409 bị đổi thành VERSION_CONFLICT, gây hiểu sai association/proposal conflict; giữ ApiError code an toàn, labels riêng, browser regression PASS |

Giả thuyết thiếu precision gây NPE bị **bác bỏ bằng execution**: validator hiện có đã
trả VALIDATION_ERROR; không ghi thành bug đã sửa. Regression vẫn giữ cùng ca invalid/
oversized để bảo vệ boundary. Vòng review không bị reset bởi các lượt chạy test.

## Quyết định và giới hạn

- Bridge giữ identity/entity legacy trong ingestion, profile mới là dữ liệu nghiệp vụ
  authoritative; không chuyển toàn repo hay tạo JPA entity trùng CANDIDATES.
  Legacy identity columns còn nguyên, không tự đoán provenance/backfill dữ liệu cũ.
- Remap luôn proposal thay vì tự matching: bảo vệ sửa tay và tránh duplicate hiện
  hành; tốn review hơn. Có thể chọn ADD/UUID để xử lý có chủ đích.
- Ordering dùng ID tuple, không calendar CV hay activation epoch. Muốn rollback
  rule và coi là version mới cần tạo config/revision mới; re-activate ID cũ không
  làm tuple mới hơn. Nhiều document cùng candidate vẫn cần người dùng đối chiếu.
- Basic review theo phần, experience theo record; chưa có workflow từng field riêng,
  auto-accept hay merge nghi trùng. State quality mapping gốc giữ riêng với state review.
- Snapshot before/after giữ evidence tốt, tăng dung lượng; lịch sử có pagination,
  throughput/dung lượng kho lớn và corpus CV thực chưa benchmark.
- PDF.js thêm worker/assets và nghĩa vụ cập nhật dependency, đổi lại renderer offline
  kiểm chứng được. Không full annotation/forms/XFA editor, không xuất PDF/ZIP.
- Không có ngày nhận CV/ngày nội dung CV đáng tin từ nguồn hiện tại; UI ghi chưa
  xác định, không lấy filesystem mtime. Title/basic thiếu không bị bịa.
- Session một instance/in-memory, restart phải đăng nhập lại. Provision/rotation
  qua vận hành, không public registration/user-management UI.
- Search/ranking M12, export M13, AI/NER/embedding, group-company và automatic
  candidate merge đều ngoài phạm vi. Production OCR/accuracy/triển khai chưa được
  chứng nhận; PASS engineering gates M11 không đồng nghĩa production-ready.

Commit được tách persistence/publication, auth/API/PDF, frontend và test/docs.
SHA từng commit, head cuối, PR và CI exact-head được ghi trong PR/bàn giao sau push;
không tự tham chiếu SHA của chính tài liệu này.
