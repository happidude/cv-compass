# OCR result v2 — raw_text only

This is a breaking successor to the frozen `cv-compass.ocr/1.0` request/result contract. The version syntax is `cv-compass.ocr/<major>.<minor>`; removing the required `text` page member changes the major version to `cv-compass.ocr/2.0`. The job transport protocol remains `cv-compass.ocr-job/1.0`.

- `ocr-v2.schema.json` and `ocr-v2.limits.json` are the new request/result authority. They keep the page, document and byte ceilings at or below v1. `examples-v2/` contains new examples while `examples/` remains frozen v1. `verify_contract.py` checks examples, semantics and Unicode code point boundaries.
- Each v2 page has `raw_text` only. Native output is the initial pdfplumber extraction. Scan output is Tesseract's `.txt` file. Neither is normalized, reordered, joined or truncated. `ok` requires usable nonblank raw text. Failed pages may retain bounded raw text as in v1; blank pages are empty.
- Unknown glyphs remain visible. `READING_ORDER_UNCERTAIN` can describe OCR layout uncertainty; removing the native geometry algorithm does not prove native order correct.
- v1 schemas/limits are retained unchanged for old submissions and exact replay. The backend uses the immutable admission snapshot's `result_version` for claims. A snapshot without the field means v1. New v2 profiles must be explicitly registered and pinned to a rebuilt image.
- Stored v1 JSONB remains byte-for-byte unchanged. UI projection reads only `raw_text`. It never substitutes v1 `text` if raw text is absent.

No mapping, data migration, backfill or old-spool byte conversion is part of v2. See `backend/RAW_TEXT_V2_LOCAL.md` for transition and local commands.
