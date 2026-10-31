# Superseded single-agent workflow record

Extracted authority record from the former docs/AI_DEVELOPMENT_WORKFLOW.md,
inspected on 2026-10-31; this is a concise record, not a verbatim copy.
The old path now redirects to [current workflow](../AGENT_WORKFLOW.md).

The superseded procedure used one Coding Agent alternating Developer and Reviewer:
planning, implementation, review, fixes/re-review, local quality gate, then PR.
Review meant a fresh pass by the same agent, not an external audit. Its invocation
example requested no commit/push until separately authorized and explicitly did
not authorize Milestone 3. Those task-specific statements are historical.

Retained requirements now live in [verification](../VERIFICATION.md) and the
[current workflow](../AGENT_WORKFLOW.md): final local Gradle check with rerun-tasks,
fresh XML/task/warning inspection, no CI substitution, complete diff including
untracked files, severity/evidence-based findings and at most three total reviews.
The old “FAILED after cycle 3” becomes BLOCKED with unresolved issues reported.
The former weaker exceptions suggesting approved force push or applied-migration
edits are superseded by the root human-only prohibitions.

Reference audit found prose references in conventions and historical worker design
reports, no runtime dependency on this document. Live convention/workflow links
were updated; compatibility entry preserves historical inbound references.
