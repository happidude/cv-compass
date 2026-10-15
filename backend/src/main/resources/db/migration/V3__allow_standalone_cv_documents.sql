-- Single-document upload does not create batches. Existing batch links and FK remain valid.
ALTER TABLE "CV_DOCUMENTS" ALTER COLUMN "BATCH_ID" DROP NOT NULL;
