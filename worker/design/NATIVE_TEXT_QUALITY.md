# Native PDF raw text in OCR v2

The v1 layout experiment is historical. Its geometry reconstruction (`native_order`), discretionary hyphen joining and whitespace normalization are no longer on the worker path. The v1 schema and previously stored results remain unchanged for exact replay and read compatibility.

For v2, the child takes `raw_text` directly from `pdfplumber`'s first `page.extract_text(layout=False)` call. A scanned page uses the unmodified text file emitted by Tesseract. There is no output `text` field. The worker still evaluates native usability, image coverage, blank pages and OCR confidence to decide native-first versus OCR fallback. The `READING_ORDER_UNCERTAIN` warning remains possible for Tesseract's layout proposal; no native geometry warning is claimed from the removed algorithm. Neither engine guarantees semantic reading order, especially with columns, tables, font encoding errors or rotated text.

The 50,000 Unicode code point per-page cap is non-truncating and operator config can only lower it. Existing page/document/byte limits, isolation, timeout, cancellation, retry and cleanup behavior remain in force. No personal PDF, OCR output or PII is committed. `tests/native_quality.py` still generates synthetic layout fixtures and can report content, whitespace and order observations for the raw extractor; those scores are not correction targets or proof that reading order is right.

From `worker/` in the provisioned environment:

```sh
<python> design/verify_contract.py
PYTHONPATH=src:tests <python> -m unittest test_pipeline.PipelineTests.test_native_real_engine test_pipeline.PipelineTests.test_mixed_native_scan_blank_real_engines test_native_quality -v
```

See `backend/RAW_TEXT_V2_LOCAL.md` for contract version, old data, profile transition and provenance. The GCC/Tesseract production gate remains blocked.
