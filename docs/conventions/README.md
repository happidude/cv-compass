# CV Compass conventions

Approved component conventions remain the detailed implementation reference:
[backend](backend.md), [worker](worker.md), [frontend](frontend.md).
Read the convention for every component touched. New code must follow it;
phrases about “this pass/phase” refer to the historical formatting exercise,
not permission to ignore rules.

## Shared requirements

Preserve runtime contracts, SQL, serialized names, side-effect/lazy import order,
transaction/retry behavior and literal values. Follow existing component structure;
do not introduce a global layer layout or mass-rename APIs for stylistic consistency.
Comments explain non-obvious reasons and preserve tool directives. Synthetic data
only; never log CV text, tokens, request bodies or arbitrary private paths.
Do not infer unused/dead code from text search alone.

Existing formatters are authoritative:
- Java: Spotless/google-java-format AOSP, 4 spaces/width 100.
- Python: Ruff formatter/linter, single quotes/width 100, public/boundary hints for new code.
- Frontend: Prettier, 2 spaces/double quotes/semicolons/width 80 and existing ESLint.

Do not change tool configuration, dependencies or excluded/canonical artifacts
without explicit scope approval. Never blanket-fix lint or normalize all repo files.
Keep formatting-only and behavior changes separate. Root .editorconfig and scoped
.gitattributes apply; canonical OCR artifacts preserve byte/hash identity.

## Workflow and provenance

Current [agent workflow](../AGENT_WORKFLOW.md), [Git procedure](../CV_COMPASS_GIT_WORKFLOW.md)
and [verification](../VERIFICATION.md) govern new tasks. The former survey, baseline
inventory, per-pass exclusions, historical results and authorization statements were
split into [history](../history/conventions-2026-10-26.md). Original application
evidence remains in [REFACTOR_REPORT.md](REFACTOR_REPORT.md); it is not current PASS.
Component files retain contextual examples and historical sections for interpretation.
