# MAPPING.ATTEMPTS

## Description

Lần thực hiện ánh xạ từ xa cùng thời hạn thuê và kết quả kết thúc.

## Columns

| Name | Type | Default | Nullable | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | -------- | ------- | ------- |
| ID | bigint |  | false |  |  | Khóa chính của lần thực hiện ánh xạ từ xa. |
| PUBLIC_ID | uuid |  | false |  |  |  |
| JOB_ID | bigint |  | false |  | [MAPPING.JOBS](MAPPING.JOBS.md) | Khóa ngoại tới công việc ánh xạ. |
| ATTEMPT_NO | integer |  | false |  |  |  |
| WORKER_ID | varchar(128) |  | false |  |  |  |
| CLAIM_REQUEST_ID | uuid |  | false |  |  |  |
| CLAIM_BODY_SHA256 | character(64) |  | false |  |  |  |
| LEASE_TOKEN_HASH | character(64) |  | false |  |  |  |
| LEASE_EXPIRES_AT | timestamp with time zone |  | false |  |  |  |
| HARD_DEADLINE_AT | timestamp with time zone |  | false |  |  |  |
| RECLAIM_AFTER | timestamp with time zone |  | false |  |  |  |
| STATUS | varchar(20) |  | false |  |  | Trạng thái lần thực hiện ánh xạ từ xa. |
| STARTED_AT | timestamp with time zone |  | false |  |  |  |
| FINISHED_AT | timestamp with time zone |  | true |  |  |  |
| END_REASON | varchar(40) |  | true |  |  | Lý do kết thúc lần thực hiện ánh xạ từ xa. |
| ERROR_CODE | varchar(60) |  | true |  |  |  |
| OUTCOME_SHA256 | character(64) |  | true |  |  |  |
| PROVENANCE | jsonb |  | false |  |  |  |
| CREATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| CK_MAPPING_ATTEMPT_END | CHECK | CHECK ((((("STATUS")::text = 'ACTIVE'::text) AND ("FINISHED_AT" IS NULL) AND ("END_REASON" IS NULL) AND ("ERROR_CODE" IS NULL) AND ("OUTCOME_SHA256" IS NULL)) OR ((("STATUS")::text = 'ACCEPTED'::text) AND ("FINISHED_AT" IS NOT NULL) AND ("END_REASON" IS NOT NULL) AND (("END_REASON")::text = 'RESULT_ACCEPTED'::text) AND ("ERROR_CODE" IS NULL) AND ("OUTCOME_SHA256" IS NOT NULL)) OR ((("STATUS")::text = 'FAILED'::text) AND ("FINISHED_AT" IS NOT NULL) AND ("END_REASON" IS NOT NULL) AND ("ERROR_CODE" IS NOT NULL)) OR ((("STATUS")::text = 'EXPIRED'::text) AND ("FINISHED_AT" IS NOT NULL) AND ("END_REASON" IS NOT NULL) AND (("END_REASON")::text = 'LEASE_EXPIRED'::text) AND ("ERROR_CODE" IS NOT NULL) AND (("ERROR_CODE")::text = 'LEASE_EXPIRED'::text) AND ("OUTCOME_SHA256" IS NULL)))) |
| CK_MAPPING_ATTEMPT_HASHES | CHECK | CHECK (((("CLAIM_BODY_SHA256")::text ~ '^[0-9a-f]{64}$'::text) AND (("LEASE_TOKEN_HASH")::text ~ '^[0-9a-f]{64}$'::text) AND (("OUTCOME_SHA256" IS NULL) OR (("OUTCOME_SHA256")::text ~ '^[0-9a-f]{64}$'::text)))) |
| CK_MAPPING_ATTEMPT_NO | CHECK | CHECK (("ATTEMPT_NO" >= 1)) |
| CK_MAPPING_ATTEMPT_PROVENANCE | CHECK | CHECK ((jsonb_typeof("PROVENANCE") = 'object'::text)) |
| CK_MAPPING_ATTEMPT_STATUS | CHECK | CHECK ((("STATUS")::text = ANY ((ARRAY['ACTIVE'::character varying, 'EXPIRED'::character varying, 'FAILED'::character varying, 'ACCEPTED'::character varying])::text[]))) |
| CK_MAPPING_ATTEMPT_TIMES | CHECK | CHECK ((("STARTED_AT" < "LEASE_EXPIRES_AT") AND ("LEASE_EXPIRES_AT" <= "HARD_DEADLINE_AT") AND ("HARD_DEADLINE_AT" < "RECLAIM_AFTER") AND (("FINISHED_AT" IS NULL) OR ("FINISHED_AT" >= "STARTED_AT")))) |
| CK_MAPPING_ATTEMPT_WORKER | CHECK | CHECK ((("WORKER_ID")::text ~ '^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$'::text)) |
| FK_MAPPING_ATTEMPT_JOB | FOREIGN KEY | FOREIGN KEY ("JOB_ID") REFERENCES "MAPPING"."JOBS"("ID") |
| PK_CV_MAPPING_ATTEMPTS | PRIMARY KEY | PRIMARY KEY ("ID") |
| UQ_MAPPING_ATTEMPT_PUBLIC | UNIQUE | UNIQUE ("PUBLIC_ID") |
| UQ_MAPPING_ATTEMPT_NUMBER | UNIQUE | UNIQUE ("JOB_ID", "ATTEMPT_NO") |
| UQ_MAPPING_CLAIM | UNIQUE | UNIQUE ("WORKER_ID", "CLAIM_REQUEST_ID") |
| UQ_MAPPING_ATTEMPT_JOB | UNIQUE | UNIQUE ("ID", "JOB_ID") |

## Indexes

| Name | Definition |
| ---- | ---------- |
| PK_CV_MAPPING_ATTEMPTS | CREATE UNIQUE INDEX "PK_CV_MAPPING_ATTEMPTS" ON "MAPPING"."ATTEMPTS" USING btree ("ID") |
| UQ_MAPPING_ATTEMPT_PUBLIC | CREATE UNIQUE INDEX "UQ_MAPPING_ATTEMPT_PUBLIC" ON "MAPPING"."ATTEMPTS" USING btree ("PUBLIC_ID") |
| UQ_MAPPING_ATTEMPT_NUMBER | CREATE UNIQUE INDEX "UQ_MAPPING_ATTEMPT_NUMBER" ON "MAPPING"."ATTEMPTS" USING btree ("JOB_ID", "ATTEMPT_NO") |
| UQ_MAPPING_CLAIM | CREATE UNIQUE INDEX "UQ_MAPPING_CLAIM" ON "MAPPING"."ATTEMPTS" USING btree ("WORKER_ID", "CLAIM_REQUEST_ID") |
| UQ_MAPPING_ATTEMPT_JOB | CREATE UNIQUE INDEX "UQ_MAPPING_ATTEMPT_JOB" ON "MAPPING"."ATTEMPTS" USING btree ("ID", "JOB_ID") |
| UQ_MAPPING_ACTIVE_ATTEMPT | CREATE UNIQUE INDEX "UQ_MAPPING_ACTIVE_ATTEMPT" ON "MAPPING"."ATTEMPTS" USING btree ("JOB_ID") WHERE (("STATUS")::text = 'ACTIVE'::text) |
| IX_MAPPING_ATTEMPT_EXPIRY | CREATE INDEX "IX_MAPPING_ATTEMPT_EXPIRY" ON "MAPPING"."ATTEMPTS" USING btree ("LEASE_EXPIRES_AT", "JOB_ID") WHERE (("STATUS")::text = 'ACTIVE'::text) |

## Relations

```mermaid
erDiagram

"MAPPING.ATTEMPTS" }o--|| "MAPPING.JOBS" : "FOREIGN KEY (#quot;JOB_ID#quot;) REFERENCES #quot;MAPPING#quot;.#quot;JOBS#quot;(#quot;ID#quot;)"

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
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
