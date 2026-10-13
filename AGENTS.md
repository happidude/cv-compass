# CV Compass — Agent Guidelines

## 1. Project Overview

**Project:** CV Compass

CV Compass is a CV management system designed to upload, process, extract, store, and eventually search candidate CVs.

The system is being developed incrementally, starting with a Proof of Concept (POC).

### Current Stage: POC — CV Ingestion & OCR

Objectives:

- Process approximately 100 PDF CVs.
- Support uploading up to 10 files per batch.
- Store original PDF files.
- Extract text from digital PDFs.
- Perform OCR on scanned PDFs.
- Extract basic candidate information.
- Store raw text and structured extraction results.
- Track batch and processing job statuses.
- Establish a foundation for future expansion to approximately 100,000 CVs.

Search, ranking, and advanced candidate matching are outside the current POC scope.

---

## 2. Technology Stack

### Backend

- Java 25
- Spring Boot 4.1.1
- Gradle
- Spring Modulith
- Spring Data JPA
- Flyway
- PostgreSQL 17

### Python Worker

- Python 3
- PDF text extraction libraries
- OCR libraries selected through POC testing
- HTTP client for communicating with Spring Boot

### Infrastructure

- Docker Compose
- PostgreSQL
- Local file storage during POC
- Linux deployment compatibility

Do not introduce Kafka, RabbitMQ, Redis, Kubernetes, or other infrastructure unless explicitly requested.

---

## 3. Project Structure

```text
cv-compass/
├── AGENTS.md
├── backend/
├── frontend/
├── worker/
├── infra/
├── docs/
└── scripts/
```

Responsibilities:

- `backend/`: Spring Boot application.
- `worker/`: Python OCR and extraction worker.
- `frontend/`: Web application.
- `infra/`: Infrastructure configuration.
- `docs/`: Requirements, architecture, database design, and API contracts.
- `scripts/`: Development and operational scripts.

Do not restructure the repository without explicit approval.

---

## 4. Architecture Principles

The system follows a **Modular Monolith + Separate Python Worker** architecture.

### Spring Boot — Orchestrator

Spring Boot is responsible for:

- REST APIs.
- CV upload and validation.
- File storage management.
- Import batch management.
- Document metadata management.
- Processing job lifecycle.
- Candidate management.
- Database transactions.
- Worker coordination.
- Business validation and normalization.

### Python — Document Processor

Python is responsible for:

- Reading PDF documents.
- Extracting embedded PDF text.
- Performing OCR when necessary.
- Cleaning extracted text.
- Parsing candidate information.
- Returning structured extraction results.
- Reporting processing errors.

### Integration Rules

- Spring Boot owns all database writes.
- Python must not directly access PostgreSQL.
- Python communicates with Spring Boot through agreed internal HTTP APIs.
- Shared API contracts must be documented under `docs/`.
- Original PDF files must be preserved.
- OCR results must not overwrite original source data.

---

## 5. Code Ownership & Agent Permissions

### 5.1 Java Backend — Developer-Owned Business Logic

Directory: `backend/`

The developer primarily writes and maintains Java business logic.

#### Agent is allowed to:

- Set up Spring Boot project foundations.
- Configure Gradle dependencies.
- Configure PostgreSQL and Flyway.
- Create database migrations when assigned.
- Create JPA Entities and Repositories.
- Establish package/module structure.
- Set up global exception handling.
- Configure logging.
- Set up validation infrastructure.
- Create reusable base components when justified.
- Configure testing infrastructure.
- Review Java code.
- Suggest refactoring and performance improvements.
- Assist with debugging.

#### Agent must NOT:

- Implement Java business features without explicit approval.
- Modify existing business logic without permission.
- Refactor unrelated Java code.
- Introduce unnecessary abstractions or generic frameworks.
- Change established architectural decisions without discussion.
- Automatically proceed to the next milestone.

**Important:** For foundation tasks, the Agent may write Java code. For business features, the Agent should primarily guide, explain, review, and debug unless implementation is explicitly requested.

### 5.2 Python Worker — Agent-Implemented

Directory: `worker/`

The developer uses AI-assisted vibe coding for the Python Worker.

#### Agent is allowed to:

- Set up the Python project.
- Select and configure appropriate PDF/OCR libraries.
- Implement PDF text extraction.
- Implement OCR processing.
- Implement text preprocessing.
- Implement structured CV parsing.
- Implement communication with Spring Boot.
- Add error handling and logging.
- Add unit tests.
- Optimize OCR performance.
- Refactor Python code when needed within the assigned task.

#### Agent must:

- Keep Python code modular and maintainable.
- Explain major technical decisions.
- Use type hints where practical.
- Prefer simple, well-supported libraries.
- Avoid unnecessary frameworks.
- Preserve extracted raw text.
- Never fabricate missing CV information.
- Respect the agreed API contract.
- Keep business normalization and candidate matching in Java.

The Agent may implement Python features autonomously within the assigned task, but must not modify the Java backend without explicit permission.

### 5.3 Frontend

Directory: `frontend/`

Do not modify frontend code unless explicitly requested.

### 5.4 Infrastructure

Directory: `infra/`

The Agent may modify infrastructure configuration only when required by the assigned task.

Do not introduce new services without explaining the necessity and receiving approval.

---

## 6. Backend Development Standards

### Java

- Follow existing project conventions.
- Prefer constructor injection.
- Keep controllers thin.
- Put business logic in application/domain services.
- Use DTOs for API requests and responses.
- Do not expose JPA Entities directly through public APIs.
- Use appropriate transaction boundaries.
- Handle errors consistently.
- Use meaningful class, method, and variable names.
- Avoid premature optimization.

### Spring Modulith

- Respect module boundaries.
- Avoid unnecessary cross-module dependencies.
- Do not introduce circular dependencies.
- Do not create excessive modules for simple functionality.

### Package Structure

Backend base package:

`com.cvcompass.backend`

Use feature-oriented packages where appropriate.

Do not create additional layers or packages without a concrete need.

---

## 7. Database Guidelines

Database: PostgreSQL 17.

Schema changes are managed through Flyway.

### Naming Conventions

- Table names: UPPERCASE_SNAKE_CASE.
- Column names: UPPERCASE_SNAKE_CASE.
- Primary keys: BIGINT identity.
- Timestamps: TIMESTAMPTZ.
- Use explicit primary keys and foreign keys.
- Add indexes based on actual access patterns.

PostgreSQL requires quoted identifiers to preserve uppercase names. Ensure JPA mappings and SQL queries follow this convention consistently.

### POC Core Tables

1. `CV_IMPORT_BATCHES`
2. `CV_DOCUMENTS`
3. `CV_PROCESSING_JOBS`
4. `CV_EXTRACTIONS`
5. `CANDIDATES`

Refer to:

`docs/POC_CV_INGESTION_DATABASE_FOUNDATION.md`

### Database Rules

- Flyway is the source of truth for schema changes.
- Never modify an already applied migration.
- Create a new migration for schema changes.
- Do not use Hibernate automatic schema updates.
- Do not store PDF binary data in PostgreSQL.
- Preserve raw OCR text and structured extraction data.
- Use JSONB for flexible structured extraction during POC.
- Do not enforce uniqueness on candidate names, emails, or phone numbers.
- Do not automatically merge candidates.
- Keep CV-to-Candidate association nullable when identity is uncertain.

Do not create Search-specific tables during the POC unless explicitly requested.

---

## 8. CV Ingestion Workflow

The expected processing flow is:

1. Client uploads a batch of PDF files to Spring Boot.
2. Spring Boot validates the uploaded files.
3. Spring Boot stores the original PDF files.
4. Spring Boot creates batch and document records.
5. Spring Boot creates processing jobs.
6. Python Worker obtains pending jobs through an internal API.
7. Python extracts text or performs OCR.
8. Python parses available candidate information.
9. Python sends results to Spring Boot.
10. Spring Boot persists extraction results.
11. Spring Boot manages Candidate association according to the agreed POC rules.
12. Spring Boot updates job and batch statuses.

### Important Rules

- Upload success does not imply OCR success.
- OCR must run asynchronously from the upload request.
- Failed processing must not delete the original PDF.
- Retry behavior must be controlled by Spring Boot.
- The same job must not be processed concurrently by multiple workers.
- Job failures must be traceable.
- Candidate identity must not be inferred solely from matching names.

---

## 9. OCR & Extraction Guidelines

### PDF Processing Strategy

1. Attempt native PDF text extraction first.
2. Evaluate whether the extracted text is usable.
3. Apply OCR to scanned or unreadable pages when necessary.
4. Preserve raw extracted text.
5. Return structured extraction results.

### Initial Extraction Fields

- Full name
- Email
- Phone number
- Location
- Profile summary, if available

Additional fields may be extracted into JSONB when supported:

- Work experience
- Job titles
- Companies
- Skills
- Education
- Certifications
- Languages

Do not create dedicated relational tables for these additional fields until the corresponding requirements are approved.

### Data Quality

- Do not invent missing information.
- Return null or omit uncertain values.
- Preserve source information when practical.
- Avoid assuming that an old CV's "Present" employment is still current.
- Avoid double-counting overlapping work experience.
- Keep extraction logic separate from business normalization.

---

## 10. API Contract Rules

Shared contracts are maintained in:

`docs/POC_OCR_API_CONTRACT.md`

Before implementing Java–Python integration:

1. Review the current API contract.
2. Confirm request and response structures.
3. Confirm error handling behavior.
4. Confirm job status transitions.
5. Confirm authentication requirements.
6. Identify missing or ambiguous fields.

Do not independently change the contract.

Any proposed contract change must be documented and reviewed before implementation.

Python must not directly update database records.

---

## 11. Security & Privacy

CV files contain confidential personal information.

The Agent must:

- Avoid logging complete CV contents.
- Avoid logging sensitive personal information unnecessarily.
- Never commit actual CV files to Git.
- Never commit credentials or secrets.
- Use environment variables for sensitive configuration.
- Validate file types and upload limits.
- Prevent unsafe file paths and path traversal.
- Keep internal Worker APIs protected.
- Avoid sending CV contents to external AI APIs or third-party services without explicit approval.

Use local processing wherever practical during the POC.

---

## 12. Testing Requirements

### Java Backend

- Add unit tests for meaningful business logic when assigned.
- Add integration tests for database-related functionality when practical.
- Verify Flyway migrations.
- Verify JPA mappings.
- Test error handling and validation.

### Python Worker

- Test digital PDFs containing selectable text.
- Test scanned PDFs.
- Test multi-page PDFs.
- Test Vietnamese and English CVs.
- Test empty or unreadable PDFs.
- Test malformed files.
- Test extraction failures.

### POC Validation

- Approximately 100 CVs.
- Maximum 10 files per batch.
- Track processing time.
- Track success/failure rates.
- Evaluate extraction accuracy.
- Record resource usage where practical.

Never report tests as passed unless they were actually executed successfully.

---

## 13. Agent Development Workflow

For every assigned task:

### Step 1 — Understand

- Read `AGENTS.md`.
- Read relevant documents under `docs/`.
- Inspect existing implementation.
- Identify the task scope and affected modules.

### Step 2 — Plan

Before major implementation:

- Explain the proposed approach.
- List expected files to create or modify.
- Identify dependencies and architectural impacts.
- Raise important questions if requirements are ambiguous.

### Step 3 — Implement

- Stay within the assigned scope.
- Follow project conventions.
- Keep changes focused.
- Avoid unrelated refactoring.
- Do not modify developer-owned business logic without approval.

### Step 4 — Verify

- Run relevant tests.
- Run build checks where applicable.
- Verify database migrations when possible.
- Check for unintended changes.

### Step 5 — Report

After implementation, provide:

- Summary of completed work.
- Files created or modified.
- Important design decisions.
- Tests executed and results.
- Known limitations.
- Suggested next step.

Stop after completing the assigned task.

Do not automatically implement subsequent milestones.

---

## 14. Current POC Milestones

### Milestone 1 — Database Foundation

Agent-assisted.

- Implement five core tables.
- Configure Flyway migrations.
- Verify PostgreSQL schema.
- Review constraints and indexes.

### Milestone 2 — Backend Foundation

Agent-assisted.

- Create JPA Entities.
- Create Repositories.
- Set up exception handling.
- Set up logging and validation.
- Establish package structure.

Do not implement business features yet.

### Milestone 3 — Upload & Batch Management

Developer-owned Java implementation.

- Upload API.
- File validation.
- Local file storage.
- Batch creation.
- Document metadata persistence.
- Processing job creation.

Agent provides guidance and code review unless explicitly asked to implement.

### Milestone 4 — Python OCR Worker

Agent-implemented.

- PDF text extraction.
- OCR.
- Text preprocessing.
- Structured CV parsing.
- Unit tests.

### Milestone 5 — Java–Python Integration

Shared responsibility.

- Developer implements Java Worker APIs.
- Agent implements Python HTTP integration.
- Follow the agreed API contract.
- Test job lifecycle and result persistence.

### Milestone 6 — POC Evaluation

- Process sample batches.
- Evaluate OCR quality.
- Measure processing time.
- Identify bottlenecks.
- Review database design.
- Plan production improvements.

---

## 15. Out of Scope

Do not implement unless explicitly requested:

- Full-text candidate search.
- Semantic search.
- Candidate ranking.
- Embedding generation.
- Vector databases.
- Advanced candidate matching.
- Automatic candidate merging.
- Job title taxonomy.
- Industry normalization.
- Recruitment workflow management.
- Partner access.
- Excel reporting.
- Large-scale distributed processing.****

These features belong to future phases.

---

## 16. General Engineering Principles

1. Prefer simplicity over unnecessary complexity.
2. Keep the architecture extensible without overengineering.
3. Preserve original source data.
4. Maintain clear Java–Python responsibilities.
5. Use explicit contracts between components.
6. Respect developer code ownership.
7. Make database changes through Flyway.
8. Implement one milestone at a time.
9. Explain foundational code so the developer can maintain it.
10. Never assume an unapproved requirement is finalized.

**The Agent's role is to accelerate development while keeping the developer in control of architecture and Java business logic.**

---

## 17. Git Workflow

All Coding Agents must read and follow `docs/CV_COMPASS_GIT_WORKFLOW.md` before starting any milestone.

- Use one GitHub monorepo.
- Create a dedicated feature branch and Git worktree for each milestone.
- Never implement directly on `main`.
- Respect milestone scope and developer code ownership.
- Do not commit, push, merge, or delete branches/worktrees without explicit approval.
- Do not automatically proceed to the next milestone.