# Detailed agent policy

Authority: [root rules](../AGENTS.md). This file retains repository-wide controls
from the former long root rules; component conventions retain implementation detail.
Explicit developer approval for the current spec authorizes only the listed scope.

## Level A — human-only operations and safety invariants

Secrets, private environment/production configuration and real CVs are confidential.
Never print, log or commit secret values or real CV/personal data. Fake examples
may be tracked but do not authorize reading private configuration. Ordinary
development does not access private environments, production config or real CVs.
Never disclose or inspect secret values. Repository secret creation/editing is
human-only, as are merge, GitHub settings/branch protection/rulesets, releases/tags
and production database/server/deployment operations. Agents never perform these
operations, regardless of task authorization.
Never send CV content to external AI or third-party services. Do not call
application services using real credentials as part of ordinary development.

Never weaken assertions, skip checks/tests, lower coverage, remove assertions or
add blanket suppressions to obtain green results. Never bypass hooks, edit applied
Flyway migrations, force push, push main, rewrite history, delete remote branches
or run destructive filesystem/SQL/Git cleanup. No global tool installation,
machine configuration changes or edits outside the repository. Rule-file edits
require task authorization. Never automatically delete local branches/worktrees.

## CI and security-hook scope

CI workflows and security hooks may be changed only when an approved spec lists
the exact paths and intended changes. Preserve required checks; never weaken,
skip or suppress checks to conceal defects. Without that scope, record the
proposal under PR Open Questions and continue independent work.
Level A human-only operations remain forbidden to agents in every task.
This agent-workflow revision changes rules only and performs none of those
operations or CI/security-hook edits.

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
Test ownership and repair handoffs are defined only by
[root Execution and delivery](../AGENTS.md#execution-and-delivery).
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
CI/security-hook edits require the explicit approved scope described above;
otherwise record the proposal in docs/PR without performing it.
Report any exposed secret immediately for human rotation, without reproducing it.
