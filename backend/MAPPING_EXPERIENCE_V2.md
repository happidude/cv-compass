# M10.3 — Experience mapping v2

This is a bounded rule-based parser, not NER/LLM/embedding or a general CV
understanding system. Original PDFs, OCR results and candidate/profile data remain
unchanged. Operational properties and batch semantics are in
[MAPPING_OPERATIONS.md](MAPPING_OPERATIONS.md).

## Version and lifecycle contract

- Existing config schema 1 / literal-mapping/1.0 / result schema 1 stay readable and
  processable, including pending jobs and accepted replay.
- New config schema 2 declares engineCompatibility=experience-mapping/2.0, explicit
  rulesRevision and taxonomyRevision. An incompatible engine/config is rejected.
- New result schema 2 carries the existing source/config/engine/input-fingerprint
  envelope plus experiences, issues and the two rule/taxonomy revisions.
- MappingConfig/MappingOutcome are internal Java contracts. MappingVersions
  selects concrete immutable records by schema version; it never reads a v1 row
  as v2. No new HTTP endpoint is introduced.
- V9 permits exactly result schemas 1 and 2; V1–V8 are unchanged. Config/result
  rows remain immutable and job completion/result insertion remain atomic.
- Active config selects the engine for NEW jobs. Workers continue both supported
  engines, using each job's pinned config/engine/input rather than the current default.
- V1 config and result canonical bytes were independently reproduced by compiling
  the DTOs/mapper from foundation commit 0a0a733; golden tests preserve them.
  The published Jackson record creator/declaration ordering is retained. Do not
  change mapper settings/order for an existing engine version.

To run v2, supply cv.mapping.definition-file and cv.mapping.revision, enable
cv.mapping.enabled, and restart. A synthetic example is
src/test/resources/mapping/experience-config-v2.json; its invented taxonomy is
test data, not a production company catalog. Restarting with the same active
revision preserves scan cursor/upper bound. New revision activation rescans without
changing results or candidates created earlier.

## Supported grammar

Literal configurable headings select experience/summary/stop sections in English
and Vietnamese. Supported block anchors include:

- Employer/Company/Công ty/Nhà tuyển dụng: value, with configurable literal labels.
- Worked at / Have been working at / Làm việc tại employer from/từ period.
- period | employer | title within a recognized experience section.
- Configurable Position/Title/Chức danh/Vị trí and Dates/Period/Thời gian labels.

A position/application heading without an employer block does not create employment.
Client/supplier/partner names in description text do not become employers.
Parenthetical employer names remain one source value. Different returns to the same
company and distinct dated roles retain separate experiences.

Explicit continuation markers such as "(continued)" on the same employer or a
configured verified alias can link adjacent ok pages. Adjacency alone is insufficient.
Failed/not_processed/blank intervening pages are never treated as verified continuity.
Orphan continuation becomes a source issue instead of a fabricated new employment.

Summary/detail dedup uses normalized/explicitly aliased employer and compatible
title plus matching start intervals. An undated summary can link only a unique
matching titled detail. Multiple possible details yield an unresolved summary issue;
dated returns to a company remain separate. Conflicting/coarse date proposals remain
alternatives with AMBIGUOUS state, rather than silently choosing one.
Repeated spelling/case or declared bilingual title aliases retain raw alternatives
and evidence without manufacturing another position.

## Dates, taxonomy and quality

Supported dates: YEAR, MM/YYYY, ISO YYYY-MM or YYYY-MM-DD, DD/MM/YYYY or DD.MM.YYYY,
and English month-name/year or day/month-name/year. Vietnamese tháng MM/YYYY
is supported. Numeric day dates use DMY grammar; arbitrary US numeric layouts,
misspelled month names or free-form dates are not repaired/inferred.
Calendar-invalid periods retain raw proposals with INVALID state.
Ranges may use dash variants, to/until/đến and explicit now/present/current/nay/
hiện tại. ongoingAsStated means exactly a supported statement in the source; it
does not establish current employment or use today's date. False under a missing/
invalid field is not an assertion that the person is unemployed.

Industry comes only from an explicitly configured employer alias with a nonempty
basis; it is not inferred from the title. Function and seniority come from declared
title aliases. Unmapped values retain raw evidence and NOT_FOUND taxonomy state.
Canonical identities/aliases cannot conflict within a configuration.
No group-company graph, employer fuzzy merge, candidate merge or team-size inference
is implemented. Company headcount and management numbers stay in description text.

Experiences have result-local IDs, company raw/normalized/industry fields, title
raw/canonical/function/seniority fields, typed partial dates, description raw segments,
evidence and quality issues. Raw field states include PROPOSED, NOT_FOUND, AMBIGUOUS
and INVALID. All usable results remain NEEDS_REVIEW; no broad auto-accept or
publication is enabled. Consumers must honor field state rather than filtering
INVALID/AMBIGUOUS values as accepted data. NOT_FOUND does not mean lack of a skill.
No score is interpreted as a probability.

Period overlap is POSSIBLE_PERIOD_OVERLAP using date precision bounds, never an
automatic correction. Unknown/ongoing end dates are not fabricated for comparisons.
Reading order is always marked unverified because source OCR supplies no layout
coordinates; arbitrary multi-column reading-order repair is not claimed.

## Raw source mapping and bounds

Derived lines use NFC and collapsed Unicode whitespace. Every normalized UTF-16
unit maps to its complete original grapheme. Field captures become verified raw
spans; evidence stores [start,end) Unicode code points in original page raw_text.
Emoji, combining characters and whitespace are preserved in quotes.
Description is an LF-joined sequence of original raw line segments, with separate
source spans across lines/pages; the OCR source itself is never rewritten.
Multiple spans are supported, and there is no PDF bbox/highlight claim.

Current invariants: original OCR admission limits, <=10000 nonempty lines,
<=100 parser blocks before dedup, <=4096 code points per captured value,
<=16384 description code points per block, <=1 MiB serialized result and <=64 KiB
configuration. Rules/taxonomy lists are bounded; only literal labels/aliases are
operator-configurable. There is no operator-provided regex/script/SQL execution.

Known unsupported layouts include unlabeled employer/title blocks, arbitrary
narrative employment and unmarked page continuation. These produce missing/unlinked
issues rather than guessed employers. A representative real corpus and separate
accuracy review are still required. See [M10_3_REPORT.md](M10_3_REPORT.md) for actual
evaluation numbers, precision/recall definitions and limitations.
