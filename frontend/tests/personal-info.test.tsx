import { it, expect } from "vitest";
import {
  profileSchema,
  proposalsSchema,
} from "../src/features/candidates/schema";
import { personalProfile, personalProposal } from "./personal-info-fixtures";
it("preserves gender and versioned field evidence at the boundary", () => {
  const profile = profileSchema.parse(personalProfile);
  expect(profile.basic).toHaveProperty("gender", "MALE");
  expect(profile.extensions.personalInfo).toMatchObject({
    schemaVersion: 1,
    fields: {
      gender: { documentId: "12", evidence: [{ page: 2, extractionId: "31" }] },
    },
  });
});
it("decodes personal proposals without treating their patch as experience", () => {
  const rows = proposalsSchema.parse([personalProposal]);
  expect(rows[0]).toMatchObject({
    kind: "PERSONAL_INFO",
    data: { patch: { gender: "FEMALE" } },
  });
});
it("rejects malformed evidence and personal payloads instead of silently stripping them", () => {
  expect(
    profileSchema.safeParse({
      ...personalProfile,
      extensions: {
        personalInfo: {
          ...personalProfile.extensions.personalInfo,
          fields: { gender: { evidence: [{ page: -1 }] } },
        },
      },
    }).success,
  ).toBe(false);
  expect(
    proposalsSchema.safeParse([
      { ...personalProposal, data: { company: { raw: "Synthetic" } } },
    ]).success,
  ).toBe(false);
});
it("keeps legacy profiles and legacy experience proposals readable", () => {
  const legacy = profileSchema.parse({
    ...personalProfile,
    basic: { fullName: "Synthetic" },
    extensions: { legacy: { kept: true } },
  });
  expect(legacy.extensions).toEqual({ legacy: { kept: true } });
  const old = {
    id: personalProposal.id,
    documentId: 12,
    resultId: 21,
    data: {
      company: { raw: "Synthetic company" },
      title: { raw: null },
      period: { ongoingAsStated: false, state: "NOT_FOUND" },
    },
  };
  expect(proposalsSchema.parse([old])[0].kind).toBe("EXPERIENCE");
});
it("preserves manual provenance without manufacturing old parser evidence", () => {
  const profile = profileSchema.parse({
    ...personalProfile,
    extensions: {
      personalInfo: {
        schemaVersion: 1,
        fields: { gender: { origin: "USER", reviewState: "EDITED" } },
      },
    },
  });
  expect(profile.extensions.personalInfo?.fields.gender).toEqual({
    origin: "USER",
    reviewState: "EDITED",
  });
});
it("does not coerce unknown gender raw text or accept unknown proposal kinds", () => {
  expect(
    profileSchema.parse({ ...personalProfile, basic: { gender: "Other" } })
      .basic.gender,
  ).toBe("Other");
  expect(
    proposalsSchema.safeParse([{ ...personalProposal, kind: "OTHER" }]).success,
  ).toBe(false);
});
it("keeps the parsed birth date and falls back to the source file name for display", async () => {
  const { candidateTitle } = await import("../src/features/candidates/labels");
  const { pageSchema } = await import("../src/features/candidates/schema");
  const profile = profileSchema.parse({
    ...personalProfile,
    basic: { dateOfBirth: "1990-10-21", location: "Hồ Chí Minh" },
  });
  expect(profile.basic).toMatchObject({ dateOfBirth: "1990-10-21" });
  const page = pageSchema.parse({
    content: [
      {
        id: 7,
        version: 0,
        fullName: null,
        basicState: "UNVERIFIED",
        importedAt: "2026-11-01T00:00:00Z",
        sourceFilename: "synthetic-cv.pdf",
      },
    ],
    hasNext: false,
    nextBeforeId: null,
  });
  const row = page.content[0];
  expect(candidateTitle(row.id, row.fullName, row.sourceFilename)).toBe(
    "synthetic-cv.pdf",
  );
  expect(candidateTitle(row.id, "Synthetic Person", "x.pdf")).toBe(
    "Synthetic Person",
  );
  expect(candidateTitle(row.id, null, null)).toBe("Ứng viên #7");
});
