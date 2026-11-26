# INGESTION.EXTRACTIONS

## Description

Kết quả trích xuất văn bản và OCR được giữ nguyên từ tài liệu.

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| ID | bigint |  | false | [MAPPING.JOBS](MAPPING.JOBS.md) |  | Khóa chính của kết quả trích xuất. |
| CV_DOCUMENT_ID | bigint |  | false |  | [INGESTION.DOCUMENTS](INGESTION.DOCUMENTS.md) [INGESTION.PROCESSING_JOBS](INGESTION.PROCESSING_JOBS.md) | Khóa ngoại tới tài liệu nguồn. |
| PROCESSING_JOB_ID | bigint |  | false |  | [INGESTION.PROCESSING_JOBS](INGESTION.PROCESSING_JOBS.md) [INGESTION.OCR_ATTEMPTS](INGESTION.OCR_ATTEMPTS.md) | Khóa ngoại tới công việc xử lý cùng tài liệu nguồn. |
| RAW_TEXT | text |  | true |  |  |  |
| STRUCTURED_DATA | jsonb |  | true |  |  |  |
| EXTRACTION_METHOD | varchar(30) |  | false |  |  |  |
| CREATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |
| ATTEMPT_ID | bigint |  | true |  | [INGESTION.OCR_ATTEMPTS](INGESTION.OCR_ATTEMPTS.md) | Khóa ngoại tới lần thực hiện OCR cùng công việc xử lý. |
| OCR_RESULT | jsonb |  | true |  |  |  |
| OCR_STATUS | varchar(20) |  | true |  |  | Trạng thái kết quả OCR do worker cung cấp. |
| PROFILE_REVISION | varchar(128) |  | true |  |  |  |
| PROVENANCE | jsonb |  | true |  |  |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| CK_EXTRACTION_METHOD | CHECK | CHECK ((("EXTRACTION_METHOD")::text = ANY ((ARRAY['TEXT_EXTRACTION'::character varying, 'OCR'::character varying, 'MIXED'::character varying, 'NONE'::character varying])::text[]))) |
| CK_OCR_EXTRACTION_FIELDS | CHECK | CHECK (((("OCR_RESULT" IS NULL) AND ("ATTEMPT_ID" IS NULL) AND ("OCR_STATUS" IS NULL) AND ("PROFILE_REVISION" IS NULL) AND ("PROVENANCE" IS NULL)) OR (("OCR_RESULT" IS NOT NULL) AND ("ATTEMPT_ID" IS NOT NULL) AND ("OCR_STATUS" IS NOT NULL) AND ("PROFILE_REVISION" IS NOT NULL) AND ("PROVENANCE" IS NOT NULL) AND (jsonb_typeof("OCR_RESULT") = 'object'::text) AND ("OCR_RESULT" ?& ARRAY['pages'::text, 'status'::text]) AND (jsonb_typeof(("OCR_RESULT" -> 'pages'::text)) = 'array'::text) AND (jsonb_typeof(("OCR_RESULT" -> 'status'::text)) = 'string'::text) AND (jsonb_typeof("PROVENANCE") = 'object'::text) AND (("OCR_STATUS")::text = ANY ((ARRAY['success'::character varying, 'partial'::character varying, 'failed'::character varying])::text[])) AND (("OCR_RESULT" ->> 'status'::text) = ("OCR_STATUS")::text) AND ("STRUCTURED_DATA" IS NULL) AND ("RAW_TEXT" IS NULL)))) |
| FK_EXTRACTION_DOCUMENT | FOREIGN KEY | FOREIGN KEY ("CV_DOCUMENT_ID") REFERENCES "INGESTION"."DOCUMENTS"("ID") |
| FK_EXTRACTION_JOB_DOCUMENT | FOREIGN KEY | FOREIGN KEY ("PROCESSING_JOB_ID", "CV_DOCUMENT_ID") REFERENCES "INGESTION"."PROCESSING_JOBS"("ID", "CV_DOCUMENT_ID") |
| PK_CV_EXTRACTIONS | PRIMARY KEY | PRIMARY KEY ("ID") |
| FK_EXTRACTION_ATTEMPT_JOB | FOREIGN KEY | FOREIGN KEY ("ATTEMPT_ID", "PROCESSING_JOB_ID") REFERENCES "INGESTION"."OCR_ATTEMPTS"("ID", "JOB_ID") |
| UQ_EXTRACTION_ATTEMPT | UNIQUE | UNIQUE ("ATTEMPT_ID") |

## Indexes

| Name | Definition |
| ---- | ---------- |
| PK_CV_EXTRACTIONS | CREATE UNIQUE INDEX "PK_CV_EXTRACTIONS" ON "INGESTION"."EXTRACTIONS" USING btree ("ID") |
| IX_EXTRACTION_DOCUMENT | CREATE INDEX "IX_EXTRACTION_DOCUMENT" ON "INGESTION"."EXTRACTIONS" USING btree ("CV_DOCUMENT_ID") |
| IX_EXTRACTION_JOB | CREATE INDEX "IX_EXTRACTION_JOB" ON "INGESTION"."EXTRACTIONS" USING btree ("PROCESSING_JOB_ID") |
| UQ_EXTRACTION_ATTEMPT | CREATE UNIQUE INDEX "UQ_EXTRACTION_ATTEMPT" ON "INGESTION"."EXTRACTIONS" USING btree ("ATTEMPT_ID") |
| UQ_OCR_EXTRACTION_JOB | CREATE UNIQUE INDEX "UQ_OCR_EXTRACTION_JOB" ON "INGESTION"."EXTRACTIONS" USING btree ("PROCESSING_JOB_ID") WHERE ("OCR_RESULT" IS NOT NULL) |

## Relations

```mermaid
erDiagram

"MAPPING.JOBS" }o--|| "INGESTION.EXTRACTIONS" : "FOREIGN KEY (#quot;EXTRACTION_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;EXTRACTIONS#quot;(#quot;ID#quot;)"
"INGESTION.EXTRACTIONS" }o--|| "INGESTION.DOCUMENTS" : "FOREIGN KEY (#quot;CV_DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;DOCUMENTS#quot;(#quot;ID#quot;)"
"INGESTION.EXTRACTIONS" }o--|| "INGESTION.PROCESSING_JOBS" : "FOREIGN KEY (#quot;PROCESSING_JOB_ID#quot;, #quot;CV_DOCUMENT_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;PROCESSING_JOBS#quot;(#quot;ID#quot;, #quot;CV_DOCUMENT_ID#quot;)"
"INGESTION.EXTRACTIONS" }o--|| "INGESTION.OCR_ATTEMPTS" : "FOREIGN KEY (#quot;ATTEMPT_ID#quot;, #quot;PROCESSING_JOB_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;OCR_ATTEMPTS#quot;(#quot;ID#quot;, #quot;JOB_ID#quot;)"

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
"MAPPING.JOBS" {
  bigint ID
  bigint EXTRACTION_ID FK
  bigint CONFIG_ID FK
  varchar_128_ ENGINE_VERSION
  varchar_20_ STATUS
  integer ATTEMPTS
  uuid TOKEN
  timestamp_with_time_zone LEASE_EXPIRES_AT
  timestamp_with_time_zone HARD_DEADLINE_AT
  character_64_ INPUT_SHA256
  timestamp_with_time_zone NEXT_ATTEMPT_AT
  varchar_40_ ERROR_CODE
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone COMPLETED_AT
  integer MAX_ATTEMPTS
  smallint PRIORITY
  bigint REMAP_RUN_ID FK
}
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
"INGESTION.OCR_ATTEMPTS" {
  bigint ID
  timestamp_with_time_zone CREATED_AT
  uuid PUBLIC_ID
  bigint JOB_ID FK
  integer ATTEMPT_NO
  varchar_128_ WORKER_ID
  uuid CLAIM_REQUEST_ID
  character_64_ CLAIM_BODY_SHA256
  character_64_ LEASE_TOKEN_HASH
  timestamp_with_time_zone LEASE_EXPIRES_AT
  timestamp_with_time_zone HARD_DEADLINE_AT
  timestamp_with_time_zone RECLAIM_AFTER
  varchar_20_ STATUS
  timestamp_with_time_zone STARTED_AT
  timestamp_with_time_zone FINISHED_AT
  varchar_40_ END_REASON
  varchar_60_ ERROR_CODE
  character_64_ OUTCOME_SHA256
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
