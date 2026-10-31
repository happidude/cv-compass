# Contract map

This index locates existing contracts; it does not change or approve their content.

| Surface | Existing sources | Caution |
| --- | --- | --- |
| OCR HTTP coordination | [M7 integration](../../worker/design/M7_INTEGRATION.md), backend OCR DTO/controller/tests | Contains v1 history; read raw-text extension too |
| OCR result v1/v2 | [Raw-text v2](../../worker/design/OCR_V2_RAW_TEXT.md), worker/design/ocr-v1.schema.json, ocr-v1.limits.json, ocr-v2.schema.json, ocr-v2.limits.json | Canonical runtime assets; preserve paths and byte parity with backend copies |
| Mapping | [Mapping](../../backend/MAPPING.md), [experience v2](../../backend/MAPPING_EXPERIENCE_V2.md) | Mixed implementation/spec; not automatic milestone acceptance |
| Candidate/auth/public API | [M11](../../backend/M11.md), [backend guide](../../backend/README.md), frontend boundary schemas/tests | Earlier public API examples predate session/CSRF requirements |

The old referenced `docs/POC_OCR_API_CONTRACT.md` is absent from this checkout.
Do not recreate an approved contract from assumptions. Before integration changes,
reconcile the existing sources, actual shapes/status/auth/tests and developer
approval; record disagreements in PR Open Questions. Historical references to the
absent file remain evidence of the old gap, not active dependencies to read.
