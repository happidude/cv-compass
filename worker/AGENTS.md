# Worker agent rules

Inherits [root rules](../AGENTS.md). Read [worker conventions](../docs/conventions/worker.md),
[README](README.md) and the relevant [contract map](../docs/contracts/README.md).

- CPython 3.14.8 target, src layout, hash-locked dependencies, unittest.
  No pytest, new framework, LLM, candidate parser or prompt without separate approval.
- Implement approved worker scope autonomously. Do not edit Java without permission.
- PDF child is offline: no PostgreSQL, HTTP, credentials or Docker socket. HTTP pull
  belongs to the host supervisor; Spring controls retry and persistence.
- Native PDF text first, OCR only when needed; preserve raw output, never invent data.
  Keep business normalization and identity association in Java.
- Keep `design/` runtime schema/limits and build provenance paths/bytes intact;
  taxonomy is not authorization to move runtime assets or change the wire contract.
- Public/boundary functions in new code need parameter/return hints.
  Use `observability.event`, enums and context allowlists; no personal data or
  arbitrary error messages/paths in logs.
- Ruff single quotes/width 100. From worker: `ruff format src tests spikes run_worker.py run_pull_worker.py`
  then `ruff check src tests spikes run_worker.py run_pull_worker.py`.
  No blanket fix, second formatter or global tool installation.
- unittest `test_*.py`, `*Tests(unittest.TestCase)`, `test_snake_case`;
  synthetic fixtures only.
- Full gate uses provisioned Ubuntu/WSL CPython + Docker Linux/cgroup v2, existing
  baseline build/verifier and fresh `tests/run_checks.py --xml <fresh-path>`
  reports. Subset/syntax checks never establish full Docker PASS.
  Follow [verification](../docs/VERIFICATION.md) and preserve cleanup ownership.

- Implementer may write accompanying tests, helpers and synthetic fixtures; prefer
  test-first for behavior changes. Tester independently verifies acceptance criteria
  and adds missing tests, never production code. Primary assigns each repair with
  one writer at a time; no role may weaken assertions/checks or reduce coverage.
