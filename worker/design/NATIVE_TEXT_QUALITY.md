# Native extraction quality evaluation

Scope: native page text only. No candidate/field mapping, OCR engine change,
result schema change, migration, backfill or runtime storage scan.

## Diagnosis

The inspected baseline at `f2b7dd8c559bd5319870fc5c0de81ee94440b158`:

* Used pdfplumber's fixed 3pt word gap. Separately positioned words with a smaller
  physical gap could become `AsaSoftware...` even when selectable text existed.
* Chose a gutter at page width/2, with fixed 4pt row grouping. A sidebar/main column
  layout can have a different gutter; different heading/date font sizes have
  different top coordinates even on the same baseline. Spanning headings could
  be cut into bands. PDF stream order is not guaranteed to be logical reading order.
* Normalized only NFC and CR/LF; it did not distinguish discretionary word breaks
  from literal hyphens. Unknown font encodings remained `(cid:...)` without an
  explicit uncertainty warning on a one-column page.

These are reproduced source/fixture hazards. The previously mentioned personal PDF
was not opened or located for this task; no claim is made that its exact glyph
positions or font mapping were diagnosed. The organization heading mentioned in
the report is not special-cased in the algorithm or fixtures.

## Change and limits

`layout.native_order` uses font-relative character gaps, vertical overlap/baseline
alignment, repeated whitespace gutters and x projections. It preserves spanning
headings as band boundaries, reads column blocks in order and treats repeated
numeric/date annotations conservatively as row context. It checks each word object
is present exactly once before publishing the reordered text. No keyword, name or
fixed CV coordinate is used to correct one document.

Horizontal whitespace is normalized in `text`; a line-ending discretionary U+00AD
between letters can be joined within a block. Literal `-` is retained, including
ambiguous `cross-\nplatform`, C++/C#/.NET/scikit-learn/UTF-8/ISO-9001. No language
dictionary guesses missing characters. A column/heading boundary prevents even
discretionary hyphen joining. Unknown CID/replacement/private-use glyphs remain
visible and trigger the existing `READING_ORDER_UNCERTAIN` warning. Detected columns
still carry that warning: geometry is a heuristic, not proof of semantic order.

`child.py` still extracts `raw_text` with the original pdfplumber call. Native
normalization never overwrites that value. OCR selection/blank handling, process
isolation, cancellation, watchdogs, file/page/text/output caps and non-truncating
success/partial/failed classification remain in place. Native engine errors still
have their existing warning; PDF-child library diagnostic messages are sanitized
without changing their WARNING/ERROR level or the result warning/error contract.

Multi-column documents with tables, rotated text, sparse/nonoverlapping bands or
complex sidebars remain uncertain. A tightly kerned single word may still be
ambiguous; an absent ToUnicode mapping cannot be repaired by spacing/layout logic.
Hard-hyphen joining intentionally remains conservative. No real-CV accuracy or
production readiness is claimed.

## Corpus and metrics

`tests/native_quality.py` generates eight PDFs plus explicit expected text. Its PDF
content stream order is reversed to separate object order from reading order.
Coverage: single column, tight positioned word gaps, equal/unequal columns,
spanning heading, mixed heading/date font sizes, bullets/soft and hard hyphens,
Vietnamese/English, unknown CID glyphs. Extra tests transform all cases at 0.75×,
1× and 1.2× scale/translated coordinates, check staggered columns, preserve raw
extraction, preserve technical tokens and prevent cross-column joining.

Metrics are deliberately separate:

* Content: non-whitespace character multiset F1; catches loss/duplication/substitution
  while ignoring whitespace and order. It does not prove order.
* Whitespace: positive whitespace-boundary F1 after compact character alignment;
  line/space boundaries count as whitespace. It does not prove exact line layout.
* Reading order: SequenceMatcher ratio on non-whitespace character sequences with
  autojunk disabled. Content errors also affect this score; it is not a semantic
  comprehension metric. Exact full text comparison additionally checks newlines.

Same eight fixtures, same Python3.14.8/pdfplumber0.11.10 environment:

| Fixture | Content before → after | Whitespace before → after | Order before → after | Exact before → after |
|---|---|---|---|---|
| Single column | 1 → 1 | 1 → 1 | 1 → 1 | yes → yes |
| Tight word gaps | 1 → 1 | 0.125 → 1 | 1 → 1 | no → yes |
| Equal columns | 1 → 1 | 1 → 1 | 1 → 1 | yes → yes |
| Unequal columns + heading | 1 → 1 | 0.882353 → 1 | 0.918182 → 1 | no → yes |
| Heading + date | 1 → 1 | 1 → 1 | 1 → 1 | no → yes |
| Bullets + hyphens | 0.997067 → 1 | 0.981132 → 1 | 0.997067 → 1 | no → yes |
| Vietnamese + English | 1 → 1 | 1 → 1 | 1 → 1 | yes → yes |
| Unknown font icon | 1 → 1 | 1 → 1 | 1 → 1 | yes → yes |

Exact matches improve 4/8 → 8/8, with no metric regression in this corpus. New
warnings for unequal columns and unknown glyphs are intentional; warnings on the
existing equal-column case remain. The corpus is small and synthetic: 8/8 is not
representative accuracy on 100 CVs. No personal PDF or extracted PII is committed.

Run from worker/ on the provisioned runtime:

```sh
PYTHONPATH=src:tests <python> -m unittest test_native_quality test_observability test_pull -v
PYTHONPATH=src <python> tests/native_quality.py --output build/quality-after.json
```

For baseline reproduction, obtain the trusted baseline's `worker/src/cv_compass_worker/layout.py`
with `git show <baseline-commit>:worker/src/cv_compass_worker/layout.py`, load it as a
module from a temporary/build directory, and call `native_quality.evaluate(module.native_order)`
with the same current fixture generator. Do not check out/reset the working tree or
feed personal PDFs into this evaluator. Actual before/after JSON is in this task's
ignored worker/build directory; the table records those measured results.

See [logging convention and restart/provenance steps](../../backend/LOGGING.md).
Live UI → worker → DB → UI verification was explicitly waived by the developer;
it is NOT VERIFIED, and no runtime extraction/profile/storage was altered. The
GCC/Tesseract production gate remains BLOCKED regardless of automated test results.
