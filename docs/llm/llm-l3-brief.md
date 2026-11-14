# Brief L3 — Publication nhận kết quả theo schema + version (cho Codex)

Mục tiêu/acceptance: bỏ các danh sách `engineVersion` ghi cứng ở phía `candidate` (PublicationService, PersonalInfoMapper).
Quyết định "kết quả mapping này có publish được không, thuộc loại nào, giá trị personal nào dùng dạng chuẩn hóa" chuyển về
**một nguồn duy nhất trong module `mapping`**, dựa trên parse nghiêm theo record (`PersonalInfoMappingResult` /
`ExperienceMappingResult`) + version. Hành vi publish **không đổi** với mọi version hiện có (2.0, 3.0–3.5); toàn bộ test backend PASS.
Lý do: thêm engine mới (vd LLM qua REMOTE ở L4/L5) hiện phải sửa 3 chỗ copy-paste ở 2 module; quên một chỗ thì kết quả bị
SKIPPED hoặc bị chuẩn hóa sai mà không test nào báo.
Spec: file này. Chủ dự án duyệt phạm vi Java nghiệp vụ cho L3 ngày 13/11/2026 (không merge main).
Base: `3d6bf1f` (`agent/llm-backend-wip`, đã có L2), branch `agent/llm-l3-wip`. L3 không dùng code L2.
Mức/lý do: 3 — chạm ranh giới module (Modulith named interface `mapping::results`), logic dữ liệu cá nhân, đường publish có transaction.
Path được sửa (chỉ những file này):
- `backend/src/main/java/com/cvcompass/backend/mapping/api/**` (thêm type/hàm mới trong named interface `results`)
- `backend/src/main/java/com/cvcompass/backend/mapping/dto/PersonalInfoDefinition.java` (chỉ khi cần thêm hàm tĩnh tra cứu, không đổi hằng số/validate)
- `backend/src/main/java/com/cvcompass/backend/candidate/service/PublicationService.java`
- `backend/src/main/java/com/cvcompass/backend/candidate/mapping/PersonalInfoMapper.java`
- test mới/sửa dưới `backend/src/test/java/com/cvcompass/backend/**` (chỉ thêm test; không xóa/làm yếu assertion cũ)
Loại trừ: migration (`db/migration/**`), `MappingRepository` (SQL claim cũng ghi cứng version — để L4), engine/grammar,
`package-info.java` của module (không mở thêm named interface/allowedDependencies), frontend, worker, llm-worker, file build/dependency.
Check: từ `backend/`: `.\gradlew.bat spotlessApply`, rồi `.\gradlew.bat test --tests "*Publication*" --tests "*Modulith*" --tests "*Mapping*"`
(cần DB test; sandbox không có DB → ghi `checks_run` NOT_RUN + lý do, không cài gì). Full gate do Primary/verifier chạy.
Spotless: chạy được không cần DB (`.\gradlew.bat spotlessApply` nếu sandbox cho phép, nếu không ghi NOT_RUN).
Không thêm dependency; không commit/push; không CV thật/PII/secret. Spec mơ hồ → chọn cách an toàn hơn và ghi `open_questions`.

## Ngữ cảnh đã biết (đọc từ code, nhánh `agent/llm-phase-integration` @ `fe4208f`)

- `candidate` chỉ được phụ thuộc `ingestion::candidate-documents`, `mapping::results` (= package `mapping.api`), `web`.
  → `candidate` **không import được** `mapping.dto.*` (kể cả `PersonalInfoMappingResult`, `PersonalInfoDefinition`). `ModulithArchitectureTests` sẽ đỏ nếu vi phạm.
- `CompletedMappings` (`mapping.api`, `@Service`): `read(id)` → `Optional<Result>`; `Result(id, schemaVersion, quality, body, extractionId, configId, documentId)`, `body` là JSON text.
- `MappingVersions.result(body, json)` (`mapping.dto`) đã parse theo `schemaVersion` 1/2/3 sang record. Constructor
  `PersonalInfoMappingResult` đã kiểm: `schemaVersion==3`, `PersonalInfoDefinition.supportsEngine(engineVersion)`,
  bộ key `personalInfo` == `PersonalInfoDefinition.fieldNames(engineVersion)`, gender chỉ MALE/FEMALE, state hợp lệ. Lỗi → `IllegalArgumentException`
  (hoặc lỗi Jackson khi kiểu sai — Jackson 3: `tools.jackson.core.JacksonException`, unchecked; kiểm đúng tên lớp trên classpath trước khi dùng).
- Chỗ ghi cứng cần bỏ:
  1. `PublicationService.publish`: biến `v3` so `engineVersion` với 6 chuỗi `personal-info-mapping/3.0`…`3.5`; `usable` so `experience-mapping/2.0`.
  2. `PublicationService.skipReason`: so lại `experience-mapping/2.0`.
  3. `PersonalInfoMapper.patch`: `v33 = Set.of("…/3.3","…/3.4","…/3.5").contains(version)` — quyết định `location`/`dateOfBirth` lấy `normalized` thay `raw`.
- Mã lý do SKIPPED bị CHECK ở V12, **chỉ** được dùng: `UNSUPPORTED_MAPPING_VERSION`, `UNSUPPORTED_MAPPING_QUALITY`,
  `MAPPING_SOURCE_MISMATCH`, `INVALID_MAPPING_ENVELOPE`, `NO_USABLE_FIELDS`. Mã mới = migration → **dừng, ghi open_questions**.
- `MappingProviderRegistry` (L2, package-private trong `mapping.service`) liệt kê version **chạy được**, không phải version **publish được**
  (1.0 chạy được nhưng không publish; REMOTE sau này publish được nhưng không có engine trong backend). **Không** dùng registry làm nguồn cho L3.

## SPEC

1. Thêm vào `mapping.api` một mô tả schema công khai, ví dụ (tên có thể đổi nếu lý do rõ):
   ```java
   public record PublishableMapping(
           int schemaVersion,           // 2 hoặc 3
           String engineVersion,
           boolean personalInfo,        // true với envelope v3
           boolean canonicalPersonalValues) {}  // true khi field set có dateOfBirth (3.3+)
   ```
   và trên `CompletedMappings` một hàm `Optional<PublishableMapping> publishable(Result result)` (hoặc lớp `@Component` riêng trong `mapping.api`):
   - parse `result.body()` bằng `MappingVersions.result(...)`; bắt `IllegalArgumentException` và `JacksonException` → `Optional.empty()`; không log body.
   - `ExperienceMappingResult` với `engineVersion` = `ExperienceEngine.VERSION` (`experience-mapping/2.0`) → `(2, v, false, false)`.
   - `PersonalInfoMappingResult` → `(3, v, true, PersonalInfoDefinition.fieldNames(v).contains("dateOfBirth"))`.
   - schema 1 / engine khác → empty.
   - `result.schemaVersion()` (cột DB) phải bằng `schemaVersion` trong body, khác → empty.
   Lý do đặt ở `mapping.api`: không mở rộng ranh giới module; L4 verifier dùng lại đúng parse này ("khớp L3" trong plan L4).
2. `PublicationService.publish`: thay biến `v3` và điều kiện version/schema trong `usable` bằng kết quả bước 1.
   Giữ nguyên **thứ tự** và **ngữ nghĩa** các kiểm còn lại (quality `NEEDS_REVIEW`, khớp `documentId`/`extractionId`, `experiences` là mảng,
   rỗng chỉ cho phép khi v3 có personal patch), khóa (`documents.valid`, `documents.lock`, `lockAndResolve`), ledger, revision.
   Không đổi chữ ký public `publish(long)`.
3. `skipReason`: dùng cùng kết quả. Quy tắc:
   - body có `schemaVersion`/`engineVersion` không được hỗ trợ (hoặc lệch cột DB) → `UNSUPPORTED_MAPPING_VERSION` (như cũ);
   - version được hỗ trợ nhưng parse nghiêm thất bại → `INVALID_MAPPING_ENVELOPE` (**mới cho trường hợp này**, mã đã có trong CHECK);
   - các nhánh còn lại giữ nguyên.
   Không bao giờ để exception parse thoát khỏi `publish` (sẽ rollback và sweep lặp lại vô hạn trên cùng một row).
4. `PersonalInfoMapper.patch`: không tự tra version nữa. Thêm tham số (vd `boolean canonicalValues`) hoặc nhận `PublishableMapping`;
   gọi từ `PublicationService` với giá trị bước 1. Giữ overload cũ chỉ nếu còn caller khác (hiện không có ngoài PublicationService) — nếu bỏ thì bỏ hẳn.
   Lời gọi `patch(body, empty)` đầu hàm hiện chạy với cả body không hợp lệ — chuyển sau khi có kết quả bước 1.
5. Không đổi hành vi cho dữ liệu đã có: row đã có ledger (`publications.exists`) không bị đánh giá lại; định dạng `extensions.mapping` không đổi.

## Test tối thiểu

- Unit cho `publishable(...)` (không cần DB, body tổng hợp): 2.0 → personalInfo=false; 3.0/3.1/3.2 → canonical=false; 3.3/3.4/3.5 → canonical=true;
  schema 1 → empty; engine lạ (`personal-info-mapping/9.9`) → empty; cột DB ≠ body → empty; body v3 thiếu key `personalInfo`
  hoặc gender `UNKNOWN` → empty, không throw.
- Test "một nguồn": với **mọi** version trong `PersonalInfoDefinition.supportsEngine` (lặp hằng số), `publishable` trả non-empty —
  thêm engine mà quên publication sẽ đỏ test.
- Integration (mở rộng `PersonalInfoPublicationIntegrationTests`, dùng helper `result(...)` có sẵn): body v3 hợp lệ về version nhưng sai kiểu
  (chèn trực tiếp vào `CV_MAPPING_RESULTS` qua jdbc, synthetic) → `publish` trả true, ledger `SKIPPED` + `INVALID_MAPPING_ENVELOPE`,
  không tạo candidate; gọi lại → false.
- Mọi test hiện có (`v34PublicationPublishesProposesAndKeepsHrEdits`, `v35…`, `v33PublicationStoresProvinceAndIsoBirthDate…`,
  `skippedPublicationPersistsFiniteReasonWithoutAProfile`, …) PASS **không sửa assertion**.
- `ModulithArchitectureTests` PASS.

## Rủi ro

| Rủi ro | Mức | Xử lý |
| --- | --- | --- |
| Parse nghiêm làm row trước đây publish được nay thành SKIPPED (body cũ khác record hiện tại) | Medium | Kết quả do chính backend ghi qua record → hiếm. Chỉ ảnh hưởng row chưa có ledger. Ghi số row SKIPPED mới vào báo cáo nếu chạy trên DB test. Chủ dự án kiểm backlog trên môi trường thật (agent không chạm). |
| Jackson 3 có bỏ qua field thừa hay không (`FAIL_ON_UNKNOWN_PROPERTIES`) khác kỳ vọng "nghiêm" | Low | Không tự đổi cấu hình ObjectMapper toàn cục. Chỉ ghi nhận hành vi thực tế trong `open_questions`; L4 verifier sẽ tự kiểm key thừa. |
| Exception parse thoát ra → rollback, sweep lặp | High nếu xảy ra | Bắt trong `publishable`, test bắt buộc (mục Integration). |
| Đổi chuẩn hóa `location`/`dateOfBirth` sai version | Medium | Test 3.0–3.2 vs 3.3–3.5 ở unit + integration 3.3 có sẵn. |
| Vi phạm ranh giới module | Medium | Chỉ dùng `mapping.api`; Modulith test. |

## Không migration

L3 không cần migration. Nếu phát hiện cần mã lý do mới, cột mới hoặc đổi CHECK → **dừng phần đó**, ghi `open_questions`, làm tiếp phần độc lập.

## Quyết định đã chốt (13/11/2026)

1. Body hợp lệ về version nhưng hỏng cấu trúc → `INVALID_MAPPING_ENVELOPE` (mặc định theo khuyến nghị; mã có sẵn).
2. Không sửa SQL claim trong `MappingRepository` (để L4).
3. Engine LLM chưa có; L3 chỉ tra qua `PersonalInfoDefinition.fieldNames`.
4. Chủ dự án đã giao phạm vi Java nghiệp vụ cho L3.
