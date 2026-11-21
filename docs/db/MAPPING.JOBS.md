# MAPPING.JOBS

## Description

Công việc ánh xạ kết quả trích xuất theo cấu hình và phiên bản bộ máy.

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| ID | bigint |  | false | [MAPPING.RESULTS](MAPPING.RESULTS.md) [MAPPING.ATTEMPTS](MAPPING.ATTEMPTS.md) |  | Khóa chính của công việc ánh xạ. |
| EXTRACTION_ID | bigint |  | false |  | [INGESTION.EXTRACTIONS](INGESTION.EXTRACTIONS.md) | Khóa ngoại tới kết quả trích xuất nguồn. |
| CONFIG_ID | bigint |  | false |  | [MAPPING.CONFIGS](MAPPING.CONFIGS.md) | Khóa ngoại tới phiên bản cấu hình ánh xạ. |
| ENGINE_VERSION | varchar(128) |  | false |  |  |  |
| STATUS | varchar(20) | 'PENDING'::character varying | false |  |  | Trạng thái công việc ánh xạ. |
| ATTEMPTS | integer | 0 | false |  |  |  |
| TOKEN | uuid |  | true |  |  |  |
| LEASE_EXPIRES_AT | timestamp with time zone |  | true |  |  |  |
| HARD_DEADLINE_AT | timestamp with time zone |  | true |  |  |  |
| INPUT_SHA256 | character(64) |  | true |  |  |  |
| NEXT_ATTEMPT_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |
| ERROR_CODE | varchar(40) |  | true |  |  |  |
| CREATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |
| COMPLETED_AT | timestamp with time zone |  | true |  |  |  |
| MAX_ATTEMPTS | integer | 3 | false |  |  |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| CK_MAPPING_ATTEMPTS | CHECK | CHECK ((("ATTEMPTS" >= 0) AND ("ATTEMPTS" <= "MAX_ATTEMPTS"))) |
| CK_MAPPING_COMPLETED_INPUT | CHECK | CHECK (((("STATUS")::text <> 'COMPLETED'::text) OR ("INPUT_SHA256" IS NOT NULL))) |
| CK_MAPPING_LEASE | CHECK | CHECK (((("STATUS")::text <> 'PROCESSING'::text) OR (("TOKEN" IS NOT NULL) AND ("LEASE_EXPIRES_AT" IS NOT NULL) AND ("ATTEMPTS" > 0) AND ("HARD_DEADLINE_AT" IS NOT NULL) AND ("LEASE_EXPIRES_AT" <= "HARD_DEADLINE_AT")))) |
| CK_MAPPING_MAX_ATTEMPTS | CHECK | CHECK ((("MAX_ATTEMPTS" >= 1) AND ("MAX_ATTEMPTS" <= 1000))) |
| CK_MAPPING_TERMINAL | CHECK | CHECK (((("STATUS")::text = ANY ((ARRAY['COMPLETED'::character varying, 'FAILED'::character varying])::text[])) = ("COMPLETED_AT" IS NOT NULL))) |
| CV_MAPPING_JOBS_INPUT_SHA256_check | CHECK | CHECK ((("INPUT_SHA256" IS NULL) OR (("INPUT_SHA256")::text ~ '^[0-9a-f]{64}$'::text))) |
| CV_MAPPING_JOBS_STATUS_check | CHECK | CHECK ((("STATUS")::text = ANY ((ARRAY['PENDING'::character varying, 'PROCESSING'::character varying, 'COMPLETED'::character varying, 'FAILED'::character varying])::text[]))) |
| CV_MAPPING_JOBS_EXTRACTION_ID_fkey | FOREIGN KEY | FOREIGN KEY ("EXTRACTION_ID") REFERENCES "INGESTION"."EXTRACTIONS"("ID") |
| CV_MAPPING_JOBS_CONFIG_ID_fkey | FOREIGN KEY | FOREIGN KEY ("CONFIG_ID") REFERENCES "MAPPING"."CONFIGS"("ID") |
| CV_MAPPING_JOBS_pkey | PRIMARY KEY | PRIMARY KEY ("ID") |
| UQ_MAPPING_INPUT | UNIQUE | UNIQUE ("EXTRACTION_ID", "CONFIG_ID", "ENGINE_VERSION") |

## Indexes

| Name | Definition |
| ---- | ---------- |
| CV_MAPPING_JOBS_pkey | CREATE UNIQUE INDEX "CV_MAPPING_JOBS_pkey" ON "MAPPING"."JOBS" USING btree ("ID") |
| UQ_MAPPING_INPUT | CREATE UNIQUE INDEX "UQ_MAPPING_INPUT" ON "MAPPING"."JOBS" USING btree ("EXTRACTION_ID", "CONFIG_ID", "ENGINE_VERSION") |
| IX_MAPPING_READY | CREATE INDEX "IX_MAPPING_READY" ON "MAPPING"."JOBS" USING btree ("NEXT_ATTEMPT_AT", "ID") WHERE (("STATUS")::text = 'PENDING'::text) |
| IX_MAPPING_EXPIRY | CREATE INDEX "IX_MAPPING_EXPIRY" ON "MAPPING"."JOBS" USING btree ("LEASE_EXPIRES_AT", "ID") WHERE (("STATUS")::text = 'PROCESSING'::text) |

## Relations

```mermaid
erDiagram

"MAPPING.RESULTS" |o--|| "MAPPING.JOBS" : "FOREIGN KEY (#quot;JOB_ID#quot;) REFERENCES #quot;MAPPING#quot;.#quot;JOBS#quot;(#quot;ID#quot;)"
"MAPPING.ATTEMPTS" }o--|| "MAPPING.JOBS" : "FOREIGN KEY (#quot;JOB_ID#quot;) REFERENCES #quot;MAPPING#quot;.#quot;JOBS#quot;(#quot;ID#quot;)"
"MAPPING.JOBS" }o--|| "INGESTION.EXTRACTIONS" : "FOREIGN KEY (#quot;EXTRACTION_ID#quot;) REFERENCES #quot;INGESTION#quot;.#quot;EXTRACTIONS#quot;(#quot;ID#quot;)"
"MAPPING.JOBS" }o--|| "MAPPING.CONFIGS" : "FOREIGN KEY (#quot;CONFIG_ID#quot;) REFERENCES #quot;MAPPING#quot;.#quot;CONFIGS#quot;(#quot;ID#quot;)"

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
}
"MAPPING.RESULTS" {
  bigint ID
  bigint JOB_ID FK
  integer SCHEMA_VERSION
  varchar_24_ QUALITY
  character_64_ DIGEST
  jsonb RESULT
  timestamp_with_time_zone CREATED_AT
}
"MAPPING.ATTEMPTS" {
  bigint ID
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
  jsonb PROVENANCE
  timestamp_with_time_zone CREATED_AT
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
"MAPPING.CONFIGS" {
  bigint ID
  varchar_128_ REVISION
  jsonb DEFINITION
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone ACTIVATED_AT
  character_64_ CONTENT_SHA256
  varchar_10_ VALIDATION_STATE
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
