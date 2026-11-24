# CANDIDATE.PROFILES

## Description

Hồ sơ ứng viên có phiên bản và trạng thái xác nhận thông tin.

## Columns

| Name | Type | Default | Nullable | Extra Definition | Children | Parents | Comment |
| ---- | ---- | ------- | -------- | ---------------- | -------- | ------- | ------- |
| CANDIDATE_ID | bigint |  | false |  | [CANDIDATE.EXPERIENCES](CANDIDATE.EXPERIENCES.md) [CANDIDATE.PUBLICATIONS](CANDIDATE.PUBLICATIONS.md) [CANDIDATE.PROPOSALS](CANDIDATE.PROPOSALS.md) [CANDIDATE.HISTORY](CANDIDATE.HISTORY.md) | [CANDIDATE.CANDIDATES](CANDIDATE.CANDIDATES.md) | Khóa chính hồ sơ và khóa ngoại tới định danh ứng viên. |
| VERSION | bigint | 0 | false |  |  |  |  |
| FULL_NAME | varchar(255) |  | true |  |  |  |  |
| EMAIL | varchar(255) |  | true |  |  |  |  |
| PHONE | varchar(50) |  | true |  |  |  |  |
| LOCATION | varchar(255) |  | true |  |  |  |  |
| SUMMARY | text |  | true |  |  |  |  |
| BASIC | jsonb | '{}'::jsonb | false |  |  |  |  |
| BASIC_STATE | varchar(16) | 'UNVERIFIED'::character varying | false |  |  |  | Trạng thái xác nhận thông tin cơ bản của ứng viên. |
| BASIC_ORIGIN | varchar(8) | 'MAPPING'::character varying | false |  |  |  |  |
| EXTENSIONS | jsonb | '{"issues": [], "schemaVersion": 1}'::jsonb | false |  |  |  |  |
| CREATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |  |
| UPDATED_AT | timestamp with time zone | CURRENT_TIMESTAMP | false |  |  |  |  |
| LOCATION_PROVINCE | varchar(64) |  | true |  |  |  | Tên tỉnh/thành chuẩn theo danh sách 63 tỉnh cũ |
| TOTAL_EXPERIENCE_MONTHS | integer |  | true |  |  |  | Tổng số tháng kinh nghiệm không âm khi đã tính được. |
| CURRENT_COMPANY_ID | bigint |  | true |  |  | [CATALOG.ENTRIES](CATALOG.ENTRIES.md) | Khóa ngoại tới công ty hiện tại khi đã xác định. |
| CURRENT_COMPANY_KIND | varchar(8) |  | true | GENERATED ALWAYS AS <br />CASE<br />    WHEN ("CURRENT_COMPANY_ID" IS NULL) THEN NULL::text<br />    ELSE 'COMPANY'::text<br />END STORED |  | [CATALOG.ENTRIES](CATALOG.ENTRIES.md) | Loại công ty tự sinh để ràng buộc liên kết công ty hiện tại. |
| CURRENT_TITLE_ID | bigint |  | true |  |  | [CATALOG.ENTRIES](CATALOG.ENTRIES.md) | Khóa ngoại tới chức danh hiện tại khi đã xác định. |
| CURRENT_TITLE_KIND | varchar(8) |  | true | GENERATED ALWAYS AS <br />CASE<br />    WHEN ("CURRENT_TITLE_ID" IS NULL) THEN NULL::text<br />    ELSE 'TITLE'::text<br />END STORED |  | [CATALOG.ENTRIES](CATALOG.ENTRIES.md) | Loại chức danh tự sinh để ràng buộc liên kết chức danh hiện tại. |

## Constraints

| Name | Type | Definition |
| ---- | ---- | ---------- |
| CANDIDATE_PROFILES_BASIC_ORIGIN_check | CHECK | CHECK ((("BASIC_ORIGIN")::text = ANY ((ARRAY['MAPPING'::character varying, 'USER'::character varying])::text[]))) |
| CANDIDATE_PROFILES_BASIC_STATE_check | CHECK | CHECK ((("BASIC_STATE")::text = ANY ((ARRAY['UNVERIFIED'::character varying, 'CONFIRMED'::character varying, 'EDITED'::character varying])::text[]))) |
| CANDIDATE_PROFILES_BASIC_check | CHECK | CHECK ((jsonb_typeof("BASIC") = 'object'::text)) |
| CANDIDATE_PROFILES_VERSION_check | CHECK | CHECK (("VERSION" >= 0)) |
| PROFILES_TOTAL_EXPERIENCE_MONTHS_check | CHECK | CHECK (("TOTAL_EXPERIENCE_MONTHS" >= 0)) |
| CANDIDATE_PROFILES_CANDIDATE_ID_fkey | FOREIGN KEY | FOREIGN KEY ("CANDIDATE_ID") REFERENCES "CANDIDATE"."CANDIDATES"("ID") |
| CANDIDATE_PROFILES_pkey | PRIMARY KEY | PRIMARY KEY ("CANDIDATE_ID") |
| PROFILES_CURRENT_COMPANY_ID_CURRENT_COMPANY_KIND_fkey | FOREIGN KEY | FOREIGN KEY ("CURRENT_COMPANY_ID", "CURRENT_COMPANY_KIND") REFERENCES "CATALOG"."ENTRIES"("ID", "KIND") |
| PROFILES_CURRENT_TITLE_ID_CURRENT_TITLE_KIND_fkey | FOREIGN KEY | FOREIGN KEY ("CURRENT_TITLE_ID", "CURRENT_TITLE_KIND") REFERENCES "CATALOG"."ENTRIES"("ID", "KIND") |

## Indexes

| Name | Definition |
| ---- | ---------- |
| CANDIDATE_PROFILES_pkey | CREATE UNIQUE INDEX "CANDIDATE_PROFILES_pkey" ON "CANDIDATE"."PROFILES" USING btree ("CANDIDATE_ID") |

## Relations

```mermaid
erDiagram

"CANDIDATE.PROFILES" |o--|| "CANDIDATE.CANDIDATES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;CANDIDATES#quot;(#quot;ID#quot;)"
"CANDIDATE.EXPERIENCES" }o--|| "CANDIDATE.PROFILES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;PROFILES#quot;(#quot;CANDIDATE_ID#quot;)"
"CANDIDATE.PUBLICATIONS" }o--o| "CANDIDATE.PROFILES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;PROFILES#quot;(#quot;CANDIDATE_ID#quot;)"
"CANDIDATE.PROPOSALS" }o--|| "CANDIDATE.PROFILES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;PROFILES#quot;(#quot;CANDIDATE_ID#quot;)"
"CANDIDATE.HISTORY" }o--|| "CANDIDATE.PROFILES" : "FOREIGN KEY (#quot;CANDIDATE_ID#quot;) REFERENCES #quot;CANDIDATE#quot;.#quot;PROFILES#quot;(#quot;CANDIDATE_ID#quot;)"
"CANDIDATE.PROFILES" }o--o| "CATALOG.ENTRIES" : "FOREIGN KEY (#quot;CURRENT_COMPANY_ID#quot;, #quot;CURRENT_COMPANY_KIND#quot;) REFERENCES #quot;CATALOG#quot;.#quot;ENTRIES#quot;(#quot;ID#quot;, #quot;KIND#quot;)"
"CANDIDATE.PROFILES" }o--o| "CATALOG.ENTRIES" : "FOREIGN KEY (#quot;CURRENT_TITLE_ID#quot;, #quot;CURRENT_TITLE_KIND#quot;) REFERENCES #quot;CATALOG#quot;.#quot;ENTRIES#quot;(#quot;ID#quot;, #quot;KIND#quot;)"

"CANDIDATE.PROFILES" {
  bigint CANDIDATE_ID FK
  bigint VERSION
  varchar_255_ FULL_NAME
  varchar_255_ EMAIL
  varchar_50_ PHONE
  varchar_255_ LOCATION
  text SUMMARY
  jsonb BASIC
  varchar_16_ BASIC_STATE
  varchar_8_ BASIC_ORIGIN
  jsonb EXTENSIONS
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone UPDATED_AT
  varchar_64_ LOCATION_PROVINCE
  integer TOTAL_EXPERIENCE_MONTHS
  bigint CURRENT_COMPANY_ID FK
  varchar_8_ CURRENT_COMPANY_KIND FK
  bigint CURRENT_TITLE_ID FK
  varchar_8_ CURRENT_TITLE_KIND FK
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
  varchar_8_ COMPANY_KIND FK
  bigint TITLE_ID FK
  varchar_8_ TITLE_KIND FK
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
"CANDIDATE.HISTORY" {
  bigint ID
  bigint CANDIDATE_ID FK
  bigint VERSION
  varchar_100_ ACTOR
  varchar_40_ ACTION
  jsonb BEFORE
  jsonb AFTER
  timestamp_with_time_zone CREATED_AT
}
"CATALOG.ENTRIES" {
  bigint ID
  varchar_8_ KIND
  text NAME
  text NAME_FOLDED
  bigint PARENT_ID FK
  varchar_8_ PARENT_KIND FK
  varchar_64_ INDUSTRY_CODE
  text__ FUNCTION_CODES
  varchar_64_ SENIORITY_CODE
  varchar_64_ SCOPE_CODE
  text PROPOSED_LABEL
  varchar_8_ ORIGIN
  timestamp_with_time_zone CREATED_AT
  timestamp_with_time_zone UPDATED_AT
}
```

---

> Generated by [tbls](https://github.com/k1LoW/tbls)
