import { z } from "zod";
import { idSchema, countSchema } from "../../shared/schema";
export const evidenceSchema = z.object({
  extractionId: idSchema,
  page: z.number().int().positive(),
  start: z.number().int().nonnegative(),
  end: z.number().int().positive(),
  text: z.string(),
});
const dateSchema = z.object({
  year: z.number().int(),
  month: z.number().int().nullable(),
  day: z.number().int().nullable(),
  precision: z.enum(["YEAR", "MONTH", "DAY"]),
});
const evidence = z.array(evidenceSchema).optional();
export const experienceDataSchema = z
  .object({
    company: z.object({
      raw: z.string().nullable(),
      normalized: z.string().nullable().optional(),
      industryCodes: z.array(z.string()).optional(),
      industryBasis: z.string().nullable().optional(),
      evidence,
    }),
    title: z.object({
      raw: z.string().nullable(),
      canonical: z.string().nullable().optional(),
      functionCodes: z.array(z.string()).optional(),
      seniorityCode: z.string().nullable().optional(),
      alternatives: z.array(z.string()).optional(),
      evidence,
    }),
    period: z.object({
      start: dateSchema.nullable().optional(),
      end: dateSchema.nullable().optional(),
      ongoingAsStated: z.boolean(),
      state: z.string(),
      alternatives: z.array(z.unknown()).optional(),
      evidence,
    }),
    descriptionRaw: z.string().optional(),
    descriptionEvidence: evidence,
    evidence,
    issues: z
      .array(z.object({ code: z.string(), field: z.string(), evidence }))
      .optional(),
  })
  .passthrough();
export type ExperienceData = z.infer<typeof experienceDataSchema>;
const basicSchema = z.object({
  fullName: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  phone: z.string().nullable().optional(),
  location: z.string().nullable().optional(),
  summary: z.string().nullable().optional(),
});
export const profileSchema = z.object({
  id: idSchema,
  version: countSchema,
  basic: basicSchema,
  basicState: z.string(),
  basicOrigin: z.string(),
  extensions: z.record(z.string(), z.unknown()),
  experiences: z.array(
    z.object({
      id: z.uuid(),
      state: z.string(),
      origin: z.string(),
      documentId: idSchema.nullable(),
      resultId: idSchema.nullable(),
      data: experienceDataSchema,
    }),
  ),
  documents: z.array(
    z.object({ id: idSchema, filename: z.string(), importedAt: z.string() }),
  ),
});
export const pageSchema = z.object({
  content: z.array(
    z.object({
      id: idSchema,
      version: countSchema,
      fullName: z.string().nullable(),
      basicState: z.string(),
      importedAt: z.string(),
    }),
  ),
  hasNext: z.boolean(),
  nextBeforeId: idSchema.nullable(),
});
export const proposalsSchema = z.array(
  z.object({
    id: z.uuid(),
    documentId: idSchema,
    resultId: idSchema,
    data: experienceDataSchema,
  }),
);
export const historySchema = z.array(
  z.object({
    id: idSchema,
    version: countSchema,
    actor: z.string(),
    action: z.string(),
    before: z.unknown(),
    after: z.unknown(),
    at: z.string(),
  }),
);
export type Profile = z.infer<typeof profileSchema>;
