CREATE TABLE "CATALOG"."NORMALIZATION_TASKS" (
    "ID" bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    "KIND" varchar(8) NOT NULL CHECK ("KIND" IN ('COMPANY', 'TITLE')),
    "ALIAS_FOLDED" text NOT NULL,
    "ALIAS_RAW" text NOT NULL,
    "CONTEXT_COMPANY" text,
    "STATE" varchar(16) NOT NULL DEFAULT 'PENDING' CHECK ("STATE" IN ('PENDING', 'LEASED', 'DONE', 'FAILED')),
    "ATTEMPTS" int NOT NULL DEFAULT 0,
    "MAX_ATTEMPTS" int NOT NULL DEFAULT 3,
    "TOKEN" uuid,
    "LEASE_EXPIRES_AT" timestamptz,
    "NEXT_ATTEMPT_AT" timestamptz NOT NULL DEFAULT now(),
    "ERROR_CODE" varchar(40),
    "CREATED_AT" timestamptz NOT NULL DEFAULT now(),
    "COMPLETED_AT" timestamptz,
    UNIQUE ("KIND", "ALIAS_FOLDED"),
    CHECK ("KIND" = 'TITLE' OR "CONTEXT_COMPANY" IS NULL)
);
CREATE INDEX "IDX_NORMALIZATION_TASKS_STATE_NEXT_ATTEMPT_AT"
    ON "CATALOG"."NORMALIZATION_TASKS" ("STATE", "NEXT_ATTEMPT_AT");

COMMENT ON TABLE "CATALOG"."NORMALIZATION_TASKS" IS 'Hàng đợi chuẩn hóa bí danh công ty và chức danh.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."ID" IS 'Khóa chính của tác vụ chuẩn hóa.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."KIND" IS 'Loại bí danh công ty hoặc chức danh.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."ALIAS_FOLDED" IS 'Bí danh đã bỏ dấu dùng để chống trùng theo loại.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."ALIAS_RAW" IS 'Tên gốc cần chuẩn hóa, không chứa các trường CV khác.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."CONTEXT_COMPANY" IS 'Tên công ty cùng kinh nghiệm, chỉ dành cho chức danh.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."STATE" IS 'Trạng thái chờ, đang thuê, hoàn tất hoặc thất bại.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."ATTEMPTS" IS 'Số lượt thuê tác vụ đã bắt đầu.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."MAX_ATTEMPTS" IS 'Số lượt thử tối đa trước khi thất bại.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."TOKEN" IS 'Mã thuê dùng chung cho một lô tác vụ.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."LEASE_EXPIRES_AT" IS 'Thời điểm hết hạn lượt thuê hiện tại.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."NEXT_ATTEMPT_AT" IS 'Thời điểm sớm nhất được thử lại tác vụ.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."ERROR_CODE" IS 'Mã lỗi an toàn của lượt thử gần nhất.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."CREATED_AT" IS 'Thời điểm tạo tác vụ chuẩn hóa.';
COMMENT ON COLUMN "CATALOG"."NORMALIZATION_TASKS"."COMPLETED_AT" IS 'Thời điểm hoàn tất hoặc thất bại vĩnh viễn.';
