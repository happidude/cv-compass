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
    countInTotal: z.boolean().optional(),
    descriptionRaw: z.string().nullable().optional(),
    descriptionEvidence: evidence,
    evidence,
    issues: z
      .array(z.object({ code: z.string(), field: z.string(), evidence }))
      .optional(),
  })
  .passthrough();
export type ExperienceData = z.infer<typeof experienceDataSchema>;
export const basicSchema = z.object({
  fullName: z.string().nullable().optional(),
  email: z.string().nullable().optional(),
  phone: z.string().nullable().optional(),
  location: z.string().nullable().optional(),
  summary: z.string().nullable().optional(),
  gender: z.string().nullable().optional(),
  dateOfBirth: z.string().nullable().optional(),
});
export const personalFieldSchema = z
  .object({
    enabled: z.boolean().optional(),
    raw: z.string().nullable().optional(),
    normalized: z.string().nullable().optional(),
    state: z.enum(["PROPOSED", "NOT_FOUND", "INVALID", "AMBIGUOUS"]).optional(),
    alternatives: z
      .array(
        z.object({
          raw: z.string(),
          normalized: z.string().nullable(),
          evidence: z.array(evidenceSchema),
        }),
      )
      .optional(),
    evidence: z.array(evidenceSchema).optional(),
    issues: z
      .array(
        z.object({
          code: z.string(),
          field: z.string(),
          evidence: z.array(evidenceSchema).optional(),
        }),
      )
      .optional(),
    documentId: idSchema.nullable().optional(),
    resultId: idSchema.nullable().optional(),
    configRevision: z.string().optional(),
    origin: z.string().optional(),
    reviewState: z.string().optional(),
  })
  .passthrough();
export const personalInfoSchema = z.object({
  schemaVersion: z.literal(1),
  fields: z.object({
    fullName: personalFieldSchema.optional(),
    email: personalFieldSchema.optional(),
    phone: personalFieldSchema.optional(),
    location: personalFieldSchema.optional(),
    summary: personalFieldSchema.optional(),
    gender: personalFieldSchema.optional(),
    dateOfBirth: personalFieldSchema.optional(),
  }),
});
export type PersonalInfo = z.infer<typeof personalInfoSchema>;
export type Basic = z.infer<typeof basicSchema>;
const experienceSummarySchema = z.object({
  totalMonths: countSchema,
  countedIds: z.array(z.uuid()),
  skipped: z.array(z.object({ id: z.uuid(), reason: z.string() })),
  ongoingReferenceDates: z.array(z.iso.date()),
  hasYearOnlyPeriods: z.boolean(),
});
export type ExperienceSummary = z.infer<typeof experienceSummarySchema>;
export const profileSchema = z.object({
  id: idSchema,
  version: countSchema,
  basic: basicSchema,
  basicState: z.string(),
  basicOrigin: z.string(),
  extensions: z
    .object({ personalInfo: personalInfoSchema.optional() })
    .catchall(z.unknown()),
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
  experienceSummary: experienceSummarySchema.optional(),
});
export const pageSchema = z.object({
  content: z.array(
    z.object({
      id: idSchema,
      version: countSchema,
      fullName: z.string().nullable(),
      basicState: z.string(),
      importedAt: z.string(),
      sourceFilename: z.string().nullable().optional(),
    }),
  ),
  hasNext: z.boolean(),
  nextBeforeId: idSchema.nullable(),
});
const proposalBase = z.object({
  id: z.uuid(),
  documentId: idSchema,
  resultId: idSchema,
});
export const proposalsSchema = z.array(
  z.union([
    proposalBase.extend({
      kind: z.literal("EXPERIENCE").default("EXPERIENCE"),
      data: experienceDataSchema,
    }),
    proposalBase.extend({
      kind: z.literal("PERSONAL_INFO"),
      data: z.object({ patch: basicSchema, metadata: personalInfoSchema }),
    }),
  ]),
);
export type Proposal = z.infer<typeof proposalsSchema>[number];
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
