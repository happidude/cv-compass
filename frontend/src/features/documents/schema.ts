import { z } from "zod";
import { countSchema, idSchema, instantSchema } from "../../shared/schema";
export const metadataSchema = z.object({
  id: idSchema,
  originalFilename: z.string(),
  sizeBytes: countSchema,
  mimeType: z.string(),
  sha256: z.string(),
  status: z.string(),
  createdAt: instantSchema,
});
export const processingSchema = z.object({
  jobId: idSchema.nullable(),
  status: z.string(),
  attemptsStarted: countSchema.nullable(),
  maxAttempts: countSchema.nullable(),
  nextAttemptAt: instantSchema.nullable(),
  startedAt: instantSchema.nullable(),
  completedAt: instantSchema.nullable(),
  endReason: z.string().nullable(),
  errorCode: z.string().nullable(),
  resultAvailability: z.string(),
});
export const overviewSchema = z.object({
  metadata: metadataSchema,
  batchId: idSchema.nullable(),
  ingestionStatus: z.string(),
  processing: processingSchema,
});
const issue = z.object({ code: z.string(), stage: z.string() });
export const ocrSchema = z
  .object({
    availability: z.string(),
    extractionId: idSchema.nullable(),
    status: z.string().nullable(),
    pageCount: countSchema.nullable(),
    pages: z.array(
      z.object({
        pageNumber: countSchema.refine((n) => n > 0),
        status: z.string(),
        method: z.string(),
        rawText: z.string(),
        warnings: z.array(z.string()),
        error: issue.nullable(),
      }),
    ),
    warnings: z.array(z.string()),
    errors: z.array(issue),
    legacyRawText: z.string().nullable(),
  })
  .superRefine((v, ctx) => {
    if (new Set(v.pages.map((p) => p.pageNumber)).size !== v.pages.length)
      ctx.addIssue({ code: "custom", message: "Duplicate page" });
  });
export const detailSchema = metadataSchema.extend({
  batchId: idSchema.nullable(),
  ingestionStatus: z.string(),
  processing: processingSchema,
  ocr: ocrSchema,
});
export const cursorSchema = z
  .object({
    content: z.array(overviewSchema),
    size: countSchema.refine((n) => n > 0),
    hasNext: z.boolean(),
    nextBeforeId: idSchema.nullable(),
  })
  .superRefine((v, ctx) => {
    if (
      v.hasNext &&
      (!v.nextBeforeId ||
        !v.content.length ||
        v.nextBeforeId !== v.content.at(-1)?.metadata.id)
    )
      ctx.addIssue({ code: "custom", message: "Invalid cursor" });
    if (!v.hasNext && v.nextBeforeId !== null)
      ctx.addIssue({ code: "custom", message: "Unexpected cursor" });
    if (
      v.content.some(
        (row, i) =>
          i > 0 &&
          Number(row.metadata.id) >= Number(v.content[i - 1].metadata.id),
      )
    )
      ctx.addIssue({ code: "custom", message: "Invalid order" });
  });
export type Overview = z.infer<typeof overviewSchema>;
export type DocumentDetail = z.infer<typeof detailSchema>;
