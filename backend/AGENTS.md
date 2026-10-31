# Backend agent rules

Inherits [root rules](../AGENTS.md). Read [backend conventions](../docs/conventions/backend.md),
[README](README.md), relevant contracts and the approved spec before edits.

- Java 25, Spring Boot, Gradle wrapper, PostgreSQL 17, JUnit; keep current versions.
- Developer-owned business logic: implement only explicit approved Java scope.
- Feature first under `com.cvcompass.backend`; controller → service → repository.
  Thin controllers, API DTOs, constructor injection, explicit transactions.
- Respect Modulith public interfaces; no circular/module-boundary changes without approval.
- Spring owns database writes. Quoted uppercase identifiers, BIGINT identity,
  TIMESTAMPTZ, JSONB; Flyway only. Never edit applied migrations; new schema needs approval.
- Use `web/OperationalLog` and safe context allowlists. No CV text, bodies, tokens,
  arbitrary paths or free-form exception messages in logs.
- API/security/storage/personal-data/transaction changes require explicit approved scope.
- Spotless/google-java-format AOSP is authoritative; do not change its configuration
  or excluded files. From backend: `./gradlew spotlessApply`, then
  `./gradlew check --rerun-tasks` (Windows: `.\gradlew.bat`).
- JUnit `XTests`/`XIntegrationTests`, camelCase methods; same package for internal
  tests. Keep root `ModulithArchitectureTests`.
- Backend source/test changes require affected tests then the full final local
  check with isolated PostgreSQL schema/storage. Follow [verification](../docs/VERIFICATION.md);
  inspect fresh XML counts and warnings. Never use development/runtime data.

- Implementer may write accompanying tests, helpers and synthetic fixtures; prefer
  test-first for behavior changes. Tester independently verifies acceptance criteria
  and adds missing tests, never production code. Primary assigns each repair with
  one writer at a time; no role may weaken assertions/checks or reduce coverage.
