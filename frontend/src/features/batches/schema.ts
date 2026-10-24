import { z } from "zod";
import { idSchema, countSchema, instantSchema } from "../../shared/schema";
export const batchSchema = z.object({
  id: idSchema,
  totalFiles: countSchema,
  successCount: countSchema,
  failedCount: countSchema,
  status: z.string(),
  createdAt: instantSchema,
  completedAt: instantSchema.nullable(),
});
export const itemSchema = z.object({
  fileOrder: countSchema,
  displayName: z.string(),
  documentId: idSchema.nullable(),
  errorCode: z.string().nullable(),
});
const page = <T extends z.ZodType>(item: T) =>
  z.object({
    content: z.array(item),
    page: countSchema,
    size: countSchema.refine((n) => n > 0),
    totalElements: countSchema,
    totalPages: countSchema,
  });
export const batchPageSchema = page(batchSchema);
export const itemPageSchema = page(itemSchema);
export const importSchema = z.object({
  batch: batchSchema,
  items: z.array(itemSchema),
});
export type Batch = z.infer<typeof batchSchema>;
export type BatchItem = z.infer<typeof itemSchema>;
