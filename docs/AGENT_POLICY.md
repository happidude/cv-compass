# Detailed agent policy

Authority: [root rules](../AGENTS.md). This file retains repository-wide controls
from the former long root rules; component conventions retain implementation detail.
Explicit developer approval for the current spec authorizes only the listed scope.

## Level A — safety invariants

Secrets, private environment/production configuration and real CVs are confidential.
Never print, log or commit secret values or real CV/personal data. Fake examples
may be tracked but do not authorize reading private configuration. Ordinary
development does not access private environments, production config or real CVs.
Secret access/management requires a separately assigned, explicitly authorized
task and an approved secure mechanism; authorization never permits disclosure.
Never send CV content to external AI/third parties or call application services
using real credentials as part of ordinary development.

Never weaken assertions, skip checks/tests, lower coverage, remove assertions or
add blanket suppressions to obtain green results. Never bypass hooks, edit applied
Flyway migrations, force push, push main, rewrite history, delete remote branches
or run destructive filesystem/SQL/Git cleanup. No global tool installation,
machine configuration changes or edits outside the repository. Rule-file edits
require task authorization. Never automatically delete local branches/worktrees.

## CI and separately authorized sensitive operations

CI workflows and security hooks may be changed only when an approved task
explicitly covers those files and intended changes. Preserve required checks;
approval is never permission to weaken checks to conceal defects. Without that
scope, propose the change under PR Open Questions and continue independent work.

GitHub settings, rulesets, repository secrets, production databases/servers/
deployments, releases/tags and merge are outside the normal development workflow.
Perform them only when the developer separately assigns the operation and grants
explicit authorization for its target and scope. Existing implementation or
commit/push/PR permission is not that authorization. A clearly approved task is
actionable without asking again; do not interpret this policy as an every-task
ban. This agent-workflow follow-up changes rules only and performs none of these
sensitive operations or CI/security-hook edits.

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
Implementer may write accompanying tests, helpers and synthetic fixtures, preferring
test-first for behavior changes. Tester independently verifies acceptance criteria
and adds missing tests, never production code. Primary explicitly assigns the
repair owner and an exclusive write lease. No role may weaken assertions/checks,
skip verification or reduce coverage to conceal defects.
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
