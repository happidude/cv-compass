# Local verification procedure

Applies to final source, alongside component README/conventions and the approved
spec. Historical evidence is classified in [docs index](README.md).
Use the [evidence template](templates/verification.md).

## Prepare and execute

Inspect directory, branch, status and prerequisites. Preserve developer changes.
Check test-variable presence without printing values; never read private env files
or redirect tests to development/production data. Use isolated synthetic schema,
storage and resources. Missing prerequisite: BLOCKED / NOT VERIFIED with next action.

| Affected scope | Required local checks |
| --- | --- |
| Backend code/tests | Affected tests, configured formatter, then full `./gradlew check --rerun-tasks` from backend (PowerShell `.\gradlew.bat check --rerun-tasks`), Java 25/PostgreSQL 17; Modulith when applicable |
| Worker code/tests | Ruff format/check paths in worker rules; provisioned Ubuntu/WSL CPython 3.14.8, Docker Linux/cgroup v2, existing baseline builder then verifier; fresh full `tests/run_checks.py --xml <fresh-path>` evidence inside verifier |
| Frontend code/tests | Existing Prettier, `npm run check` and `npm run test:browser` from frontend |
| Docs/rules/agent TOML only | Relative links/anchors, classification/reference audit, TOML fields and role boundaries, Git ignore/staging checks, frozen CI/application diff and `git diff --check`; application suites N/A with rationale |

Worker full gate, from root on the provisioned Linux host:
`python3.14 worker/spikes/m6_2_hardening/build_baseline.py`, then
`python3.14 worker/spikes/m6_2_hardening/verify_baseline.py`. Use fresh output per
README; run the matching ownership-aware `--cleanup-only` even on failure, only
after the run stops. Never relabel a subset as full Docker verification.
Run existing tools, never install global tools or change application dependencies.

## Inspect evidence and warnings

Read terminal logs and fresh JUnit/XML/HTML/problems reports where generated.
Record tests/failures/errors/skips, executed tasks and missing expected reports.
UP-TO-DATE, NO-SOURCE, caches and old reports do not demonstrate execution; N/A
requires a concrete reason. Expected negative-test WARN/ERROR lines need assessment.

Keep existing `-Xlint:deprecation` and formatter/linter gates enabled. Fix new
in-scope warnings; record source/cause/impact and deferral reason for out-of-scope
or tooling warnings. Do not edit build config, suppress warnings or remove assertions
to hide failures. Recheck final source after fixes, within the three-cycle budget.

Record exact source commit or base plus file fingerprints before commit; a
documentation-only evidence commit may refer to the identical reviewed payload.
Do not insert the future commit hash into itself. CI and local source must be
identifiable separately. PASS means executed successfully; FAIL means demonstrated
failure; NOT VERIFIED means missing evidence; N/A means irrelevant with reason.

## Existing CI — preserve unchanged

- Persistence CI: all PRs/pushes to main; Java 25/PostgreSQL 17, Gradle check.
- Worker CI: worker/workflow path filters; provisioned Python/Docker baseline build,
  full verifier, ownership cleanup and provenance artifacts.
- Frontend CI: frontend/workflow path filters; Node 24, locked install, format/lint/
  typecheck/unit/build and Chromium browser checks.

Do not narrow CI, edit its pipelines, switch unittest to pytest or add unnecessary
tools for this workflow. Rule files under component paths may trigger component
CI; docs/root changes alone may not trigger path-filtered jobs. A missing or
pending run is not PASS. Secret/dependency scan automation is not assumed present.
