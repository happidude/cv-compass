ALTER TABLE "CATALOG"."ALIASES"
    ALTER COLUMN "ENTRY_ID" DROP NOT NULL,
    ALTER COLUMN "ORIGIN" DROP NOT NULL,
    ADD "CONTEXT_COMPANY" text,
    ADD "STATE" varchar(16) NOT NULL DEFAULT 'DONE',
    ADD "ATTEMPTS" int NOT NULL DEFAULT 0,
    ADD "MAX_ATTEMPTS" int NOT NULL DEFAULT 3,
    ADD "TOKEN" uuid,
    ADD "LEASE_EXPIRES_AT" timestamptz,
    ADD "NEXT_ATTEMPT_AT" timestamptz NOT NULL DEFAULT now(),
    ADD "ERROR_CODE" varchar(40),
    ADD "COMPLETED_AT" timestamptz,
    ADD CONSTRAINT "CHK_ALIASES_STATE" CHECK ("STATE" IN ('PENDING', 'LEASED', 'DONE', 'FAILED')),
    ADD CONSTRAINT "CHK_ALIASES_ENTRY_STATE" CHECK (("ENTRY_ID" IS NOT NULL) = ("STATE" = 'DONE')),
    ADD CONSTRAINT "CHK_ALIASES_CONTEXT_COMPANY" CHECK ("KIND" = 'TITLE' OR "CONTEXT_COMPANY" IS NULL),
    ADD CONSTRAINT "CHK_ALIASES_LINK_ORIGIN" CHECK ("ENTRY_ID" IS NULL OR "ORIGIN" IS NOT NULL);

CREATE INDEX "IDX_ALIASES_STATE_NEXT_ATTEMPT_AT"
    ON "CATALOG"."ALIASES" ("STATE", "NEXT_ATTEMPT_AT")
    WHERE "STATE" IN ('PENDING', 'LEASED');

COMMENT ON TABLE "CATALOG"."ALIASES" IS 'Ánh xạ cách viết bí danh tới mục danh mục và hàng đợi chuẩn hóa; ENTRY_ID để trống khi bí danh đang chờ chuẩn hóa.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."ENTRY_ID" IS 'Khóa ngoại tới mục danh mục; để trống khi bí danh chưa được chuẩn hóa.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."ORIGIN" IS 'Nguồn tạo hoặc chỉnh sửa bí danh; bắt buộc khi đã liên kết mục danh mục.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."CONTEXT_COMPANY" IS 'Tên công ty cùng kinh nghiệm, chỉ dành cho chức danh.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."STATE" IS 'Trạng thái chờ, đang thuê, hoàn tất hoặc thất bại; hoàn tất khi đã liên kết mục danh mục.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."ATTEMPTS" IS 'Số lượt thuê chuẩn hóa bí danh đã bắt đầu.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."MAX_ATTEMPTS" IS 'Số lượt thử tối đa trước khi thất bại.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."TOKEN" IS 'Mã thuê dùng chung cho một lô bí danh.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."LEASE_EXPIRES_AT" IS 'Thời điểm hết hạn lượt thuê hiện tại.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."NEXT_ATTEMPT_AT" IS 'Thời điểm sớm nhất được thử lại chuẩn hóa bí danh.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."ERROR_CODE" IS 'Mã lỗi an toàn của lượt thử gần nhất.';
COMMENT ON COLUMN "CATALOG"."ALIASES"."COMPLETED_AT" IS 'Thời điểm hoàn tất hoặc thất bại vĩnh viễn của lượt chuẩn hóa.';
