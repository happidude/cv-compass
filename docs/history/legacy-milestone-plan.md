# Legacy milestone/worktree plan

Historical plan extracted from the former Git workflow inspected on 2026-10-31.
No row establishes current approval, Active status, completion or merge status.
Current [Git procedure](../CV_COMPASS_GIT_WORKFLOW.md) excludes milestone plans;
[roadmap](../roadmap.md) leaves unconfirmed statuses explicit.

| Original item | Original branch | Original example checkout |
| --- | --- | --- |
| M1 Database Foundation | feature/database-foundation | E:/cv-compass-db |
| M2 Backend Foundation | feature/backend-foundation | E:/cv-compass-backend-base |
| M3 File Storage Foundation | feature/storage-foundation | E:/cv-compass-storage |
| M4 Single CV Upload, PR #5 | feature/cv-upload | E:/cv-compass-upload |
| M5 Batch Management, PR #6 | feature/batch-management | E:/cv-compass-batch |
| M6 OCR Worker Integration | TBD after approval | TBD after approval |
| Future POC evaluation | TBD | TBD |

The former document recorded a developer decision dated 2026-10-17 splitting the
original M4 into single upload M4 and batch M5, moving OCR integration to M6.
It described M5 starting after merged M4 and named an existing PR/worktree.
These old statements do not determine today's milestone status.

It also instructed keeping local docs/AGENTS ignored, and included old cleanup
examples. Shared rules/docs are now tracked by explicit developer instruction;
agents still never merge or automatically delete branches/worktrees.
No live code/build/CI consumes the old prose plan. References use the retained
Git procedure path or this explicit history entry.
