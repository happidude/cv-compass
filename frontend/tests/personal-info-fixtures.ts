export const personalSpan = {
  extractionId: 31,
  page: 2,
  start: 0,
  end: 4,
  text: "Nam",
};
export const personalMetadata = {
  schemaVersion: 1,
  fields: {
    gender: {
      enabled: true,
      raw: "Nam",
      normalized: "MALE",
      state: "PROPOSED",
      alternatives: [
        { raw: "Nam", normalized: "MALE", evidence: [personalSpan] },
      ],
      issues: [],
      evidence: [personalSpan],
      documentId: 12,
      resultId: 21,
      configRevision: "synthetic-r1",
    },
  },
};
export const personalProfile = {
  id: 1,
  version: 1,
  basic: { fullName: "Synthetic candidate", gender: "MALE" },
  basicState: "UNVERIFIED",
  basicOrigin: "MAPPING",
  extensions: { personalInfo: personalMetadata },
  experiences: [],
  documents: [
    { id: 12, filename: "synthetic.pdf", importedAt: "2026-11-01T00:00:00Z" },
  ],
};
export const personalProposal = {
  id: "a0000000-0000-4000-8000-000000000001",
  documentId: 12,
  resultId: 21,
  kind: "PERSONAL_INFO",
  data: { patch: { gender: "FEMALE" }, metadata: personalMetadata },
};
