# Foundation configuration correction — local gate PASS

27/10/2026. Base/head PR #17: 2df987c3a9ea511ff9edb67fcadcf51d6154cb97;
main d0b7f81d02e2f5910aeea356c2322f91cca7e5bb. Branch remains
codex/m10-2-mapping-foundation, worktree
C:\Users\NguyenVanHoang\.codex\worktrees\m10-2-mapping-foundation\cv-compass.

This correction was implemented, reviewed and verified BEFORE M10.3 implementation.
Its separate commit SHA is recorded as the baseline of M10_3_REPORT.md and in the
final handoff/PR. Original checkout and unrelated changes are preserved.

See [MAPPING_OPERATIONS.md](MAPPING_OPERATIONS.md) for the before/after table,
defaults, supported limits, environment names and business-batch/page/concurrency
semantics. The only business file-count property is cv.batch.max-files (default 10,
1–1000); cv.import.batch-size is retired and rejected when importer is enabled.
No new mapping business batch is created. Multiple OCR revisions per source
document are separate mapping inputs and do not inflate import batch counters.

V8 adds a snapshotted per-job attempt budget and replaces V7's fixed attempt check.
V1–V7 are unchanged. Index predicates remain compatible (PENDING + due-time/ID).
Changing operational max-attempts affects new jobs; old budgets remain three.
Same active configuration bootstrap preserves cursor AND upper bound; changing
config starts a new sweep. Lease/deadline/backoff/page bounds now reach SQL/service
validation rather than only scheduler configuration. The bounded worker executor
defaults to one task; configured concurrency is independent of file count/page size.

Local environment: WSL CVCompass-Ubuntu-24.04 / Java 25.0.4.1 / Gradle 9.7.1 /
PostgreSQL 17.11 in a separate test container on loopback port 55432.
Existing test helper creates and drops per-class schemas/storage; no dev DB is used.
TEST_DB_* are supplied as process environment without committing credentials.
Working directory is this worktree's backend/.

Commands (local ignored init script only relocates build output/cache to Linux):

```sh
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessApply --rerun-tasks --console=plain
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache test --tests '*Mapping*Tests' --tests '*DiskImport*Tests' --tests '*CvApiConfigurationTests' --rerun-tasks --console=plain --warning-mode=all
bash gradlew -I build/m10-2/native-output.gradle --project-cache-dir /tmp/cv-compass-m10-2-01a17d65/cache spotlessCheck check --rerun-tasks --console=plain --warning-mode=all
```

Final full gate: **371 tests / 0 failures / 0 errors / 0 skipped**, 50 fresh suites,
7 actionable tasks/7 executed; **PASS**, 1m18s. Targeted gate also PASS.
Fresh evidence is ignored under backend/build/m10-3/:
foundation-check.log, foundation-junit/TEST-*.xml, foundation-targeted.log,
foundation-targeted-junit/ and foundation-source-manifest.txt.
Manifest SHA-256: 394bdbc4e9e4afb0511cb6ae3513b89dad112dd40bbc2211e89f3529fb9889da.
Full tests include batch 1/10/100/101 plus short final batch, config ceiling 1000/
invalid 1001, multipart byte limits, concurrency bound, bootstrap restart,
multiple OCR revisions of one business file, five attempts with changed
lease/deadline/backoff, reconciliation/idempotency, Modulith and migration checks.

Review cycle 1 of the current two-stage invocation inspected actual diff against
2df987c plus intended new files, schema/SQL, callers, tests, startup binding and
safe logs. No Critical/High/Medium finding remains. git diff --check passes.
No new compiler/deprecation warning; Gradle cache suggestion and existing Git
LF→CRLF advisory are informational. No formatter/dependency/exclusion change.
The excluded DiskImportIntegrationTests file has only its explicitly required
property-key change; no wholesale formatting or weakened assertion.

Quality gate for this correction: **PASS (local)**. CI for this separate commit is
reported after push, separately from local evidence. No merge is authorized.
M10.3 begins only after this gate and the separate foundation commit.
