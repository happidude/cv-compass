# Frontend agent rules

Inherits [root rules](../AGENTS.md). Edit frontend only within explicit task scope.
Read [frontend conventions](../docs/conventions/frontend.md) and [README](README.md).

- Keep React function components/hooks, strict TypeScript, Router, TanStack Query,
  Zod and existing semantic CSS/Tailwind. Do not add dependencies or change tool config.
- Preserve app/shared/features boundaries, public feature exports and hook order.
  Server state uses Query; UI state stays local. Keep side-effect import order.
- Validate HTTP data at boundaries; preserve wire fields, IDs, retry/cancellation
  and upload unknown-outcome behavior. Never render OCR as HTML or store it locally.
- No CV/token/body logging, real CV fixtures or production credentials in VITE bundles.
- Prettier: 2 spaces, double quotes, semicolons, width 80; existing ESLint rules.
  `npm exec prettier -- --write src tests`; no mass cleanup of runtime/generated assets.
- Vitest `*.test.ts(x)`, Playwright `*.spec.ts`, MSW/Testing Library, synthetic data.
  From frontend: `npm run check` and `npm run test:browser`.
- Inspect actual reports/counts and warning output on final source. Existing CI
  remains intact. See [verification](../docs/VERIFICATION.md); do not substitute
  old verification evidence for a fresh check.

- Implementer may write accompanying tests, helpers and synthetic fixtures; prefer
  test-first for behavior changes. Tester independently verifies acceptance criteria
  and adds missing tests, never production code. Primary assigns each repair with
  one writer at a time; no role may weaken assertions/checks or reduce coverage.
