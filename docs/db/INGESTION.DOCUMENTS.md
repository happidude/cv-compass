# INGESTION.DOCUMENTS

## Description

Tài liệu CV gốc cùng thông tin lưu trữ và trạng thái xử lý.

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| ID | bigint |  | false | [INGESTION.PROCESSING_JOBS](INGESTION.PROCESSING_JOBS.md) [INGESTION.EXTRACTIONS](INGESTION.EXTRACTIONS.md) [INGESTION.IMPORT_ITEMS](INGESTION.IMPORT_ITEMS.md) [INGESTION.DISK_IMPORT_FILES](INGESTION.DISK_IMPORT_FILES.md) [CANDIDATE.EXPERIENCES](CANDIDATE.EXPERIENCES.md) [CANDIDATE.PUBLICATIONS](CANDIDATE.PUBLICATIONS.md) [CANDIDATE.PROPOSALS](CANDIDATE.PROPOSALS.md) |  | Khóa chính của tài liệu CV. |
| BATCH_ID | bigint |  | true |  | [INGESTION.IMPORT_BATCHES](INGESTION.IMPORT_BATCHES.md) | Khóa ngoại tới đợt nhập CV, có thể để trống khi tải riêng lẻ. |
| CANDIDATE_ID | bigint |  | true |  | [CANDIDATE.CANDIDATES](CANDIDATE.CANDIDATES.md) | Khóa ngoại tới ứng viên khi đã có liên kết. |
| ORIGINAL_FILENAME | varchar(255) |  | false |  |  |  |
| STORAGE_PATH | text |  | false |  |  |  |
| FILE_SIZE | bigint |  | false |  |  |  |
| MIME_TYPE | varchar(100) |  | false |  |  |  |
| SHA256 | character(64) |  | false |  |  |  |
| STATUS | varchar(20) |  | false |  |  | Trạng thái xử lý tài liệu CV. |
| CREATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| CK_DOCUMENT_FILE_SIZE | CHECK | CHECK (("FILE_SIZE" >= 0)) |
| CK_DOCUMENT_SHA256 | CHECK | CHECK ((("SHA256")::text ~ '^[0-9a-f]{64}$'::text)) |
| CK_DOCUMENT_STATUS | CHECK | CHECK ((("STATUS")::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'PARTIAL'::character varying, 'FAILED'::character varying])::text[]))) |
| FK_DOCUMENT_BATCH | FOREIGN KEY | FOREIGN KEY ("BATCH_ID") REFERENCES "INGESTION"."IMPORT_BATCHES"("ID") |
| FK_DOCUMENT_CANDIDATE | FOREIGN KEY | FOREIGN KEY ("CANDIDATE_ID") REFERENCES "CANDIDATE"."CANDIDATES"("ID") |
| PK_CV_DOCUMENTS | PRIMARY KEY | PRIMARY KEY ("ID") |

## Indexes

| Name | Definition |
| ---- | ---------- |
| PK_CV_DOCUMENTS | CREATE UNIQUE INDEX "PK_CV_DOCUMENTS" ON "INGESTION"."DOCUMENTS" USING btree ("ID") |
| IX_DOCUMENT_BATCH | CREATE INDEX "IX_DOCUMENT_BATCH" ON "INGESTION"."DOCUMENTS" USING btree ("BATCH_ID") |
| IX_DOCUMENT_CANDIDATE | CREATE INDEX "IX_DOCUMENT_CANDIDATE" ON "INGESTION"."DOCUMENTS" USING btree ("CANDIDATE_ID") |
| IX_DOCUMENT_SHA256 | CREATE INDEX "IX_DOCUMENT_SHA256" ON "INGESTION"."DOCUMENTS" USING btree ("SHA256") |

## Relations

```mermaid
erDiagram

"INGESTION.PROCESSING_JOBS" }o--|| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;CV_DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"INGESTION.EXTRACTIONS" }o--|| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;CV_DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"INGESTION.IMPORT_ITEMS" }o--o| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"INGESTION.DISK_IMPORT_FILES" }o--o| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"CANDIDATE.EXPERIENCES" }o--o| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"CANDIDATE.PUBLICATIONS" }o--|| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"CANDIDATE.PROPOSALS" }o--|| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"INGESTION.DOCUMENTS" }o--o| "INGESTION.IMPORT_BATCHES" : "FOREIGN KEY (#quot;BATCH_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;IMPORT_BATCHES#quot;(#quot;ID#quot;)"
"INGESTION.DOCUMENTS" }o--o| "CANDIDATE.CANDIDATES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;CANDIDATES#quot;(#quot;ID#quot;)"

"INGESTION.DOCUMENTS" {
  bigint ID
  bigint BATCH_ID FK
  bigint CANDIDATE_ID FK
  varchar_255_ ORIGINAL_FILENAME
  text STORAGE_PATH
  bigint FILE_SIZE
  varchar_100_ MIME_TYPE
  character_64_ SHA256
  varchar_20_ STATUS
  timestamp_with_time_zone CREATED_AT
}
"INGESTION.PROCESSING_JOBS" {
  bigint ID FK
  bigint CV_DOCUMENT_ID FK
  varchar_20_ STATUS
  integer RETRY_COUNT
  text ERROR_MESSAGE
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone STARTED_AT
  timestamp_with_time_zone COMPLETED_AT
  varchar_128_ PROFILE_REVISION
  integer GENERATION
  integer MAX_ATTEMPTS
  integer ATTEMPTS_STARTED
  timestamp_with_time_zone NEXT_ATTEMPT_AT
  bigint CURRENT_ATTEMPT_ID FK
  jsonb ADMISSION_SNAPSHOT
  varchar_40_ END_REASON
  varchar_60_ ERROR_CODE
}
"INGESTION.EXTRACTIONS" {
  bigint ID
  bigint CV_DOCUMENT_ID FK
  bigint PROCESSING_JOB_ID FK
  text RAW_TEXT
  jsonb STRUCTURED_DATA
  varchar_30_ EXTRACTION_METHOD
  timestamp_with_time_zone CREATED_AT
  bigint ATTEMPT_ID FK
  jsonb OCR_RESULT
  varchar_20_ OCR_STATUS
  varchar_128_ PROFILE_REVISION
  jsonb PROVENANCE
}
"INGESTION.IMPORT_ITEMS" {
  bigint ID
  timestamp_with_time_zone CREATED_AT
  bigint BATCH_ID FK
  integer FILE_ORDER
  varchar_255_ DISPLAY_NAME
  bigint DOCUMENT_ID FK
  varchar_40_ ERROR_CODE
}
"INGESTION.DISK_IMPORT_FILES" {
  bigint ID
  character_64_ SOURCE_KEY
  text FILE_NAME
  character_64_ SHA256
  bigint SIZE_BYTES
  varchar_40_ MODIFIED
  text FILE_KEY
  varchar_30_ STATE
  integer ATTEMPTS
  integer READ_FAILURES_APPLIED
  integer MOVE_ATTEMPTS
  timestamp_with_time_zone NEXT_ATTEMPT_AT
  timestamp_with_time_zone NEXT_MOVE_AT
  bigint BATCH_ID FK
  bigint DOCUMENT_ID FK
  varchar_40_ ERROR_CODE
  varchar_40_ MOVE_ERROR_CODE
  varchar_36_ TARGET_NAME
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone UPDATED_AT
}
"CANDIDATE.EXPERIENCES" {
  uuid ID
  bigint CANDIDATE_ID FK
  text COMPANY_RAW
  text COMPANY_NORMALIZED
  text TITLE_RAW
  date START_DATE
  varchar_5_ START_PRECISION
  date END_DATE
  varchar_5_ END_PRECISION
  boolean ONGOING_AS_STATED
  jsonb FUNCTION_CODES
  jsonb INDUSTRY_CODES
  varchar_64_ SENIORITY_CODE
  text DESCRIPTION_RAW
  jsonb DATA
  integer DATA_SCHEMA_VERSION
  varchar_16_ STATE
  varchar_8_ ORIGIN
  bigint DOCUMENT_ID FK
  bigint RESULT_ID FK
  boolean DELETED
  bigint COMPANY_ID FK
  bigint TITLE_ID FK
  varchar_64_ SCOPE_CODE
  varchar_8_ NORMALIZATION_ORIGIN
}
"CANDIDATE.PUBLICATIONS" {
  bigint RESULT_ID FK
  bigint CANDIDATE_ID FK
  bigint DOCUMENT_ID FK
  bigint EXTRACTION_ID
  bigint CONFIG_ID
  varchar_20_ STATUS
  timestamp_with_time_zone CREATED_AT
  varchar_40_ REASON_CODE
}
"CANDIDATE.PROPOSALS" {
  uuid ID
  bigint CANDIDATE_ID FK
  bigint RESULT_ID FK
  bigint DOCUMENT_ID FK
  jsonb DATA
  varchar_10_ STATUS
  varchar_100_ DECIDED_BY
  timestamp_with_time_zone DECIDED_AT
  varchar_20_ KIND
}
"INGESTION.IMPORT_BATCHES" {
  bigint ID
  integer TOTAL_FILES
  integer SUCCESS_COUNT
  integer FAILED_COUNT
  varchar_20_ STATUS
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone COMPLETED_AT
}
"CANDIDATE.CANDIDATES" {
  bigint ID
  varchar_255_ FULL_NAME
  varchar_255_ EMAIL
  varchar_50_ PHONE
  varchar_255_ LOCATION
  text SUMMARY
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone UPDATED_AT
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
