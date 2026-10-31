# Detailed agent policy

Authority: [root rules](../AGENTS.md). This file retains repository-wide controls
from the former long root rules; component conventions retain implementation detail.
Explicit developer approval for the current spec authorizes only the listed scope.

## Level A — human-only / forbidden to agents

Never read, print, edit or commit secrets (`.env*`, keys, keystores, credentials,
private production configuration). Fake example files may be tracked but are not
a reason to inspect private configuration. Never commit real CVs/personal data or
send CV content to external AI/third parties or call services using real credentials.

Never touch production databases/servers/deployments, releases/tags, CI/CD pipelines,
branch protection/security hooks or GitHub settings. Never edit an applied Flyway
migration. Never merge, force push, push main, rewrite history, delete remote branches,
disable/bypass hooks, weaken checks, disable/skip tests, lower coverage, remove
assertions or add blanket suppressions to obtain green results.

No destructive filesystem/SQL/Git cleanup (recursive forced deletion, DROP/TRUNCATE,
reset-hard, git clean). No edits outside the repository, global tools or machine
configuration. No rule-file edits unless the task authorizes them.
Never automatically delete local branches/worktrees.

## Level B — explicit scope approval required

Before new schema/migrations/indexes/type changes; dependency/runtime changes;
formatter/linter/build/Docker/application config changes; contract/status/error
changes; security/auth/token/role/CORS/CSRF changes; personal-data logic changes;
transaction boundaries, async/scheduled jobs/caches/queues; shared modules,
module boundaries, architecture/restructuring, refactors over approximately ten
code files; deletions or mass moves/renames of files/classes/endpoints/fields/env
keys: check whether the approved spec already covers the action.

If covered, proceed without repeat permission. If outside scope or ambiguous,
record context/options/recommendation under PR Open Questions and do not perform
the dependent work. Continue independent work. New architecture needs a short ADR
after approval. Task-specific authorization can cover rule/docs reorganization,
as it does for the agent-workflow task; it does not authorize application changes.

## Level C — within approved scope

Implement scoped code/tests, run existing format/lint/test tools, update related
docs, create a dedicated branch and commit/push/open a PR when authorized.
Do not treat a milestone plan or existing implementation as permission.

## Architecture, data and privacy

Modular monolith + separate Python worker; preserve module boundaries. Spring Boot
owns DB writes, job lifecycle, validation, normalization and candidate association.
The host worker uses internal HTTP only; the PDF child has no DB/HTTP access.

Preserve original PDFs, raw OCR and structured results. Never overwrite sources,
invent fields, assume an old “Present” role is current or double-count overlapping
experience. Unknown values stay null/omitted. Candidate links may be nullable; no
name/email/phone uniqueness or automatic identity merging. Failed processing never
deletes originals. Upload success is independent of OCR success. Spring controls
retry and exclusive worker ownership. Validate file type/size and safe paths.

Use existing safe logging facilities and allowlists only. Never log full CVs,
request bodies, tokens, arbitrary paths or free-form exceptions. Real CV processing
and privacy logic need explicit approved scope. Use synthetic local tests and
isolated storage/schema. Preserve unresolved OCR production/accuracy gates;
technical PASS alone does not open them.

Database: PostgreSQL 17, Flyway, quoted UPPERCASE_SNAKE_CASE identifiers, BIGINT identity,
TIMESTAMPTZ, explicit PK/FK, JSONB where designed. No Hibernate auto schema update,
PDF binaries in DB or speculative search tables.

Search/ranking/embeddings/vector DB, matching/merging, taxonomies/normalization,
recruitment/partner workflows, reporting and distributed processing require
separate approval. Do not introduce Kafka, RabbitMQ, Redis or Kubernetes implicitly.

Secret/dependency scanning in CI is a target, not a claim of existing coverage.
Propose CI/security-hook changes in docs/PR for a human; do not edit those files.
Report any exposed secret immediately for human rotation, without reproducing it.
