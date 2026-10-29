# Báo cáo refactor M11 — candidate / identity

Ngày kiểm chứng: 29/10/2026. Phạm vi: refactor PR #18 và finding end-only được
yêu cầu riêng; không triển khai milestone tiếp theo.

## Branch và commit

- Tiếp tục worktree `C:/Users/NguyenVanHoang/.codex/worktrees/m11-candidate-review/cv-compass`.
- PR #18 open/unmerged; head ban đầu `a7d1b1d3ccc93e75afbc4d46a4ebfaa303ffb0e9`;
  working tree sạch trước sửa. Base main `67c56c7baeef88eaf651dd066c9659ef2ba337aa`.
- Đổi tên local branch thành `chore/m11-layer-refactor`, giữ lineage của PR #18.
  Workflow repo dùng `feature/`, `fix/`, `chore/`, `docs/`.
- Theo [GitHub branch renaming](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-branches-in-your-repository/renaming-a-branch),
  rename head branch đóng PR đang mở; đổi base không đổi được head. Vì vậy push
  branch mới/tạo PR thay thế vào main và dẫn liên kết từ
  [PR #18](https://github.com/hoangnguyen229/cv-compass/pull/18). Giữ PR #18 và remote
  `codex/m11-candidate-review` để bảo toàn review history. Không force-push, merge,
  đóng PR, xóa branch hoặc worktree.

| Commit | Nội dung | Loại diff |
| --- | --- | --- |
| `d6f3d487aba573cbb9afc568bf4ebacacab3add6` | Tách layer, DTO, validator/mapper và service; thêm architecture/transaction/identity tests | Refactor, giữ hành vi hợp lệ và SQL |
| `7ee6a41427d02365c4a27c1af991b6a67887c8c2` | Lưu start/end riêng; 12 regression cases | Fix nghiệp vụ riêng |
| Commit tài liệu chứa báo cáo này | Báo cáo và README | Không thay executable source |

So với head ban đầu, không sửa frontend, dependencies, formatter/exclusions,
migration, worker hoặc OCR canonical contract. Báo cáo M11 trước là bằng chứng
lịch sử cho `a7d1b1d`, không dùng thay kiểm chứng refactor.

## Cây thư mục và trách nhiệm

Các đường dẫn dưới tương đối với `src/main/java/com/cvcompass/backend/`.

```text
candidate/
  controller/ CandidateController
  service/    CandidateQueryService, CandidateService, CandidateReviewService
              CandidateRevisionService, CandidateExperienceService
              CandidateValidator
              PublicationService, PublicationSweep
  mapper/     CandidateValueMapper
  repository/ CandidateProfileRepository, PublicationRepository
  dto/        Profile, Experience, Proposal, Summary, Page, History
              BasicEdit, ExperienceEdit, Decision, DocumentLink
              ProfileCore, BasicValues, ExperienceValues, PublicationCursor
  config/     PublicationProperties, PublicationScheduler
  exception/  CandidateFailure, CandidateErrors
  package-info.java
identity/
  controller/ AuthController
  service/    UserLookupService, UserProvisioningService
  repository/ UserRepository
  dto/        CsrfResponse, CurrentUser, UserAccount
  config/     UserSecurity, UserProvisioning, UserProvisioningProperties
              PrivateApiHeaders
  package-info.java
ingestion/
  candidateapi/ CandidateDocuments, package-info.java (public contract giữ nguyên)
  service/      CandidateDocumentBridge, OriginalPdfService
  repository/   CandidateDocumentRepository, OriginalPdfRepository
  dto/          DocumentCandidate, OriginalPdfMetadata
mapping/
  api/          CompletedMappings, package-info.java (named interface results)
  repository/   CompletedMappingRepository
```

| Class/trách nhiệm cũ | Class/package mới và trách nhiệm |
| --- | --- |
| `CandidateService.list/detail/proposals` | `CandidateQueryService`: pagination/compose profile; `CandidateProfileRepository`: SQL/row mapping |
| `CandidateService.basic/editExperience/attach` | `CandidateService`: entry point transaction edit/xác nhận/link, gọi validator/writer/revision |
| `CandidateService.decide` | `CandidateReviewService`: version/proposal lock, quyết định/target và audit |
| `CandidateService.lock/expected/revision/history` | `CandidateRevisionService`: version/snapshot/audit/history; SQL ở repository |
| `CandidateService.insert/updateExperience` | `CandidateExperienceService`: giới hạn 1.000 kể cả tombstone, UUID và writer dùng chung |
| JSON validation/normalization/projection trong service lớn | `service.CandidateValidator`: USER/date/code/evidence validation; `mapper.CandidateValueMapper`: `BasicValues`/`ExperienceValues` |
| Nested records service/controller | `candidate.dto`: giữ JSON fields/constraints/nullability; thêm typed internal read models |
| `CandidateFailure`, `CandidateErrors` | `candidate.exception`, giữ error/status/scope advice |
| SQL trong `PublicationService/PublicationSweep` | `PublicationRepository`: ledger/order/proposal/extensions/cursor; profile/count dùng repository chung |
| `identity.AuthController` | `identity.controller.AuthController`; DTO ở `identity.dto` |
| `identity.UserSecurity` và private JDBC lookup | `identity.config.UserSecurity` + `UserLookupService` + `UserRepository` |
| `UserProvisioning.Properties` | `config.UserProvisioningProperties`, prefix/default/validation không đổi |
| Runner/file handling/SQL provisioning cùng một class | Runner `config.UserProvisioning` nối properties với `UserProvisioningService`; repository giữ INSERT idempotent |
| SQL trong `CandidateDocumentBridge` | `ingestion.repository.CandidateDocumentRepository`; bridge giữ identity/association decisions và MANDATORY transaction |
| SQL trong `OriginalPdfService` | `ingestion.repository.OriginalPdfRepository`; HTTP vẫn đi qua service |
| SQL trong `mapping.api.CompletedMappings` | `mapping.repository.CompletedMappingRepository`; facade/Result vẫn ở named interface |

Query không ghi; edit và review là transaction entry points; revision thống nhất
version/audit. Experience writer dùng chung bởi edit, review và publication để
giữ limit/tombstone/projection. Publication và cursor reservation vẫn là hai
transaction riêng. Validator/mapper là component nội bộ, không thêm transaction.

Mặt tốt: controller không phụ thuộc nested DTO implementation, SQL tập trung,
trách nhiệm và đường đi dependency dễ kiểm tra. Đánh đổi: nhiều file DTO/constructor
hơn. Chọn hai repository candidate theo profile/review và publication, không tạo
interface/implementation rỗng cho từng bảng. Facade ingestion/mapping giữ boundary
hiện hữu. Không tạo entity/ORM, MapStruct, enums hoặc generic framework mới; không
mở rộng refactor OCR/mapping ngoài query M11 được giao.

## Boundary, transaction và security

- Candidate chỉ gọi `ingestion::candidate-documents`, `mapping::results`, `web`.
  Giữ `CandidateDocuments.Document` và `CompletedMappings.Result` ở API vì là
  public module contract. Modulith metadata không đổi.
- Architecture tests mới kiểm tra candidate/identity: placement/records trong DTO;
  service/controller không JDBC/JPA; controller không repository/entity; repository
  và DTO không phụ thuộc service/controller/config; package không cycle; không
  truy cập repository implementation của ingestion/mapping. Ba bridge M11 có rule
  không JDBC riêng. `package-info` chỉ được chấp nhận như `@ApplicationModule`
  metadata. Giữ nguyên `BackendPackageStructureTests` và `modules.verify()`.
- Đối chiếu 40 SQL call sites cũ: có 39 câu khác nhau và cả 39 đều giữ trong
  repository. Hai call sites đếm experience dùng chung một method. Kiểm tra binding,
  predicates, sort, limit, `FOR UPDATE`; cursor/document lock trả typed read model
  thay Map/Number. Không đổi migration.
- Không thêm readOnly/propagation/isolation trong commit refactor. Public
  `attach/basic/editExperience`, `review.decide`, `publish`, `reserve` gọi qua Spring
  bean/proxy. Writer/revision/repository tham gia transaction entry point, không
  service locator hoặc self-invocation thay entry-point proxy.
- Publication: document lock → ledger/order → resolve association → ensure profile
  → profile lock → ledger/profile/proposals/extensions/history. Attach khóa document
  trước profile; review khóa profile/version trước proposal. Giữ UUID/identity,
  tuple order, replay/idempotency, upper-bound cursor, USER/CONFIRMED và tombstone.
- Thêm test ép history INSERT thất bại khi gọi trực tiếp Spring service proxy,
  không outer transaction trong test: publication rollback association/profile/
  experience/ledger; edit/review rollback data/version/proposal/history. Các test
  rollback/retry, concurrent publish, review race và association race cũ vẫn đạt.
- Giữ bean names `userSecurity`, `userApiSecurityChain`, `userPasswordEncoder`,
  `userProvisioning`, `privateApiHeaders` và controller/advice/service hiện hữu.
  JDBC bean mới `candidateProfileRepository` tránh trùng legacy JPA repository.
  Properties inject theo type, prefix/default không đổi.
- Giữ chain order 2, matcher `/api/**`, `/error`, provider manager riêng, BCrypt 12,
  CSRF repository/handler, USER/ADMIN, login/logout handlers/cookies, no-store và
  session config. Lookup không đăng ký global UserDetailsService. OCR bearer chain
  order 1 giữ độc lập: test xác nhận session không thay bearer và worker bearer
  không cấp quyền user.
- Provisioning giữ absolute private file, cap 100 byte, bcrypt regex, INSERT ON
  CONFLICT DO NOTHING và lỗi không chứa cause/hash. `UserAccount` là read model
  nội bộ, toString redacted, không trả từ HTTP.
- PDF streaming/range controller không đổi. Live browser kiểm tra 206 `%PDF`, canvas
  có ink/text PDF thật, evidence quote, edit/history, remap protection/proposal,
  reject/logout. Đã xem screenshot tổng thể sau test.

## Finding và các vòng review

Một agent chuyển vai Developer/Reviewer; ba vòng của yêu cầu này độc lập với lần
triển khai M11 trước.

| Vòng | Finding/kết quả | Trạng thái |
| --- | --- | --- |
| 1 | `M11-RF-01` Medium: pure-refactor `CandidateValidator.java:97` chỉ nhìn start; `CandidateValueMapper.java:39–47` bỏ normalized dates khi NOT_FOUND, dù JSON còn end | Regression trước fix tái hiện; sửa riêng `7ee6a41` |
| 2 | Rà fix, transaction/security/boundary; 49 targeted, 431 full backend, 22 unit/40 browser frontend, live smoke 1/1 đạt | Fixed and verified, không finding Critical/High/Medium mới |
| 3 | Rà diff cuối/staged/untracked files, tài liệu, scope và evidence trước push | Không còn finding Critical/High/Medium được xác nhận trong phạm vi |

12 tổ hợp: start-only/end-only × YEAR/MONTH/DAY × SAVE/proposal EDIT. Trước fix:
6 start-only đạt, 6 end-only lỗi cột ngày null. Sau fix: 12/12 đạt, kiểm tra JSON,
normalized date/precision, mốc thiếu vẫn null và history/version. Từng endpoint
được validate trước so thứ tự khi có đủ hai mốc; không tự tạo start. Không backfill
dữ liệu cũ: bản ghi cũ được chuẩn hóa lại khi người dùng lưu chỉnh sửa. Mapping
AMBIGUOUS/INVALID vẫn không được coi là PROPOSED.

Quan sát baseline `BASE-01`: full run đầu ở `a7d1b1d` có 1/408 test lỗi
`MappingIntegrationTests.experienceV2PersistsThroughWorkerWhileV1PayloadDigestAndReplayRemainUnchanged`
(`NoSuchElementException`, dòng 881). Test chạy riêng đạt; full baseline retry
408/408 đạt. Không tái hiện trong full refactor/final-code checks. Chưa xác định
nguyên nhân lần lỗi đơn lẻ; giữ assertion và theo dõi độ ổn định, không gán nguyên
nhân cho refactor hoặc sửa module ngoài phạm vi.

## Bằng chứng local và CI

Java 25.0.4.1 / WSL `CVCompass-Ubuntu-24.04`, Gradle wrapper 9.7.1, PostgreSQL
17.11 trong container test `cv-compass-m10-test`, loopback 55432. Mỗi integration
class tạo/drop schema UUID và temp storage riêng. Không dùng DB dev. Frontend
Node 24.22.0/npm 11.19.1/Chromium. Chỉ synthetic PDF/OCR envelope.

Executable source của gate trước follow-up chuyển mapper: `7ee6a41427d02365c4a27c1af991b6a67887c8c2`.
Tree `backend/src`: `052b119401a6edc2a3b223bb1665ea9d98fe6406`;
tree frontend: `896c5f2aa75cf178cc2540cb2c77207c16e10c47`. Commit tài liệu không
đổi các tree này. Head cuối và CI run URLs được ghi trong mô tả PR thay thế/handoff;
không lấy checks cũ của PR #18 làm bằng chứng mới.

Từ backend trong WSL, prefix Gradle local:

```sh
bash gradlew -I build/m11/native-output.gradle \
  --project-cache-dir /tmp/cv-compass-m11/cache
```

Init script ignored chỉ chuyển output sang `/tmp/cv-compass-m11/build` để giảm
chi phí filesystem Windows và đăng ký JavaExec smoke test-only. Không sửa
build.gradle/dependencies. TEST_DB_URL/USERNAME/PASSWORD cấp từ môi trường test.

| Lệnh/arguments sau prefix | Kết quả log/fresh JUnit XML |
| --- | --- |
| Baseline `spotlessCheck check --rerun-tasks --console=plain --warning-mode=all` | Đầu: 408 tests/54 suites, 1 failure; retry cùng source: 408/54, 0 failure/error/skipped |
| Pure refactor targeted M11/properties/architecture/package/Modulith | 30 tests/5 suites, 0 failure/error/skipped |
| Pure refactor `spotlessApply spotlessCheck check --rerun-tasks` | 419 tests/57 suites, 0 failure/error/skipped; test thực thi; Spotless check UP-TO-DATE sau apply nên không dùng riêng bước này làm fresh formatter gate cuối |
| Regression trước fix, `test --tests '*M11IntegrationTests.singleDateEndpointsRetainTheirOwnPrecisionInJsonColumnsAndHistory' --rerun-tasks` | 12 cases, 6 end-only failures như finding |
| Fix targeted: M11/properties/architecture/package/Modulith/lookup/provisioning | 49 tests/7 suites, 0 failure/error/skipped |
| Code cuối `spotlessCheck check --rerun-tasks --console=plain --warning-mode=all` | **431 tests/57 suites, 0 failure/error/skipped; 7/7 actionable tasks executed**, không UP-TO-DATE/NO-SOURCE |
| Frontend `npm run check` | Typecheck/lint/format, 22 tests/5 files và production build PASS |
| Frontend `npm run test:browser` | 40/40 PASS |
| Backend opt-in `m11Smoke`; frontend `npm exec playwright -- test --config playwright.m11-live.config.ts` | Live smoke 1/1 PASS; server shutdown/cleanup thành công |

Targeted classes: `M11IntegrationTests`, `M11PropertiesTests`,
`CandidateIdentityArchitectureTests`, `BackendPackageStructureTests`,
`ModulithArchitectureTests`, `UserLookupServiceTests`, `UserProvisioningServiceTests`.
Full suite còn gồm migration/persistence/OCR/mapping/HTTP tests hiện có.

Evidence ignored, không commit generated output:

- `backend/build/m11-refactor/`: `baseline.log`, `baseline-retry.log`, `pure-full.log`,
  `code-full.log` và các thư mục `*-junit` copy fresh XML ngay sau run.
- `date-red.log`/`date-red-junit/`, `fix-targeted.log`/`fix-targeted-junit/`.
- `frontend-check.log`, `frontend-browser.log`, `live-browser.log`, `live-server.log`;
  screenshot `frontend/.ci-reports/m11-live/profile-source.png` đã xem.
- Final-head replay: `final-head.txt`, `final-full.log`, `final-full-junit/`; đối
  chiếu HEAD/source tree trước và sau, không gán kết quả commit cũ cho head mới.

Không có compiler/deprecation warning mới trong code thay đổi. Advisories hiện hữu:
Rollup bỏ hai Zod comment annotation không hiểu; Vite/browser báo NO_COLOR và
FORCE_COLOR cùng được đặt. Không đổi dependency/suppress/exclusions để làm xanh.
Git báo LF normalize CRLF theo cấu hình hiện hữu; `git diff --check` đạt.

Local code gate **PASS**. CI phải đối chiếu head SHA sau push; trạng thái/run URL
chính xác nằm trong mô tả PR và handoff cuối. CI không thay local execution.
Không còn finding Critical/High/Medium được xác nhận trong phạm vi; theo dõi
quan sát baseline `BASE-01` và giới hạn không backfill end-only cũ.

Python OCR engine, accuracy corpus thật, deployment/production readiness vẫn
**NOT VERIFIED**, ngoài refactor này. Giữ các đánh đổi M11: remap bảo thủ cần
review, session in-memory một instance, provisioning do operator quản lý.
Không merge PR tự động.

## Follow-up theo yêu cầu: package mapper

`CandidateValueMapper` chuyển từ `candidate.service` sang `candidate.mapper`;
đã sửa imports của edit service, experience writer và validator. Bean name
`candidateValueMapper` giữ nguyên. Helper `date` trở thành public static để
validator ở package khác dùng chung, vẫn là implementation nội bộ module.
Thân mapping, SQL, transaction và JSON contract không đổi.

Architecture rule buộc class có hậu tố `Mapper` ở package `mapper`, cấm JDBC/JPA
và dependency ngược từ mapper đến service/repository/controller/config. Các rule
package cycle và Modulith verification vẫn giữ nguyên. Một vòng Reviewer cho
follow-up này xác nhận chỉ thay package/import/helper visibility và tăng rule.

Fresh local `spotlessCheck check --rerun-tasks --console=plain --warning-mode=all`
đạt 431 tests/57 suites, 0 failure/error/skipped; 7/7 actionable tasks executed.
Evidence riêng: `backend/build/m11-mapper-followup/full.log`, `junit/`.
PostgreSQL/schema/storage tiếp tục cô lập, cleanup xong và container dừng.
Frontend không thay source; không chạy lại local frontend cho lần di chuyển
class Java này. Head/CI của follow-up được cập nhật trong PR #19.
