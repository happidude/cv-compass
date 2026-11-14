import { z } from "zod";

// Require the absolute end of the string; `$` also allows a trailing newline.
const revisionSchema = z
  .string()
  .regex(/^[a-z0-9][a-z0-9._/-]{0,63}(?![\s\S])/);
const mappingMetadataSchema = z.object({
  mapping: z.object({
    engineVersion: revisionSchema,
    configRevision: revisionSchema.optional(),
  }),
});

const engineLabels: Record<string, string> = {
  "llm-extraction/1.0": "LLM",
  "personal-info-mapping/3.0": "quy tắc 3.0",
  "personal-info-mapping/3.1": "quy tắc 3.1",
  "personal-info-mapping/3.2": "quy tắc 3.2",
  "personal-info-mapping/3.3": "quy tắc 3.3",
  "personal-info-mapping/3.4": "quy tắc 3.4",
  "personal-info-mapping/3.5": "quy tắc 3.5",
  "experience-mapping/2.0": "quy tắc 2.0",
  "literal-mapping/1.0": "quy tắc 1.0",
};

export function mappingEngineLabel(extensions: unknown): string | undefined {
  const result = mappingMetadataSchema.safeParse(extensions);
  if (!result.success) return undefined;
  const { engineVersion, configRevision } = result.data.mapping;
  const label = Object.hasOwn(engineLabels, engineVersion)
    ? engineLabels[engineVersion]
    : undefined;
  const engine = label ? `${label} (${engineVersion})` : engineVersion;
  const config = configRevision ? ` · cấu hình ${configRevision}` : "";
  return `Trích xuất tự động: ${engine}${config}`;
}
