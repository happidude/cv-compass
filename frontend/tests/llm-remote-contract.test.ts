import { expect, it } from "vitest";
import {
  profileSchema,
  proposalsSchema,
  historySchema,
  experienceDataSchema,
} from "../src/features/candidates/schema";
import initialProfile from "./fixtures/llm-remote/initial-profile.json";
import initialProposals from "./fixtures/llm-remote/initial-proposals.json";
import initialHistory from "./fixtures/llm-remote/initial-history.json";
import subsequentProfile from "./fixtures/llm-remote/subsequent-profile.json";
import subsequentProposals from "./fixtures/llm-remote/subsequent-proposals.json";
import subsequentHistory from "./fixtures/llm-remote/subsequent-history.json";
import beforeFixProfile from "./fixtures/llm-remote/before-fix-profile.json";

it.each([
  ["initial", initialProposals],
  ["subsequent", subsequentProposals],
])("parses all proposal branches for %s publication", (_, proposals) => {
  expect(() => proposalsSchema.parse(proposals)).not.toThrow();
});

it.each([
  ["initial", initialProfile, initialProposals, initialHistory],
  ["subsequent", subsequentProfile, subsequentProposals, subsequentHistory],
])(
  "parses backend serialization for %s publication",
  (_, profile, proposals, history) => {
    const parsedProfile = profileSchema.parse(profile);
    const parsedProposals = proposalsSchema.parse(proposals);
    expect(historySchema.parse(history)).toHaveLength(_ === "initial" ? 1 : 2);
    expect(parsedProfile.experiences).toHaveLength(5);
    const experiences = parsedProfile.experiences.map(({ data }) => data);
    expect(experiences.map(({ descriptionRaw }) => descriptionRaw)).toEqual(
      Array(5).fill(""),
    );
    expect(experiences[1].period).toMatchObject({
      end: null,
      ongoingAsStated: true,
    });
    expect(experiences[2].period).toMatchObject({
      end: null,
      ongoingAsStated: false,
    });
    expect(experiences[3]).toMatchObject({
      company: { raw: null },
      title: { raw: "Engineer" },
      period: { start: null, end: null, state: "NOT_FOUND", evidence: [] },
    });
    expect(experiences[4]).toMatchObject({
      evidence: [],
      company: { raw: null },
      title: { raw: null },
    });
    expect(parsedProfile.extensions.mapping).toMatchObject({
      values: [{ field: "other:synthetic", value: "é" }],
      issues: [{ code: "SYNTHETIC_WARNING", field: null, evidence: [] }],
    });
    expect(parsedProposals).toHaveLength(0);
    expect(parsedProfile.extensions.personalInfo?.fields.email).toMatchObject({
      enabled: false,
      state: "NOT_FOUND",
      issues: [{ code: "FIELD_DISABLED", field: "email", evidence: [] }],
    });
    expect(parsedProfile.extensions.personalInfo?.fields.phone?.state).toBe(
      "NOT_FOUND",
    );
    expect(parsedProfile.extensions.personalInfo?.fields.gender).toMatchObject({
      raw: "OTHER",
      normalized: null,
      issues: [{ code: "GENDER_UNSUPPORTED", field: "gender" }],
    });
  },
);

it("reads persisted null descriptions as valid empty descriptions in legacy profiles and proposals", () => {
  // Earlier verifier output is already stored locally; read compatibility must not require a migration.
  const profile = profileSchema.parse(beforeFixProfile);
  expect(profile.experiences.map(({ data }) => data.descriptionRaw)).toEqual(
    Array(5).fill(null),
  );
  const proposals = beforeFixProfile.experiences.map(
    ({ id, documentId, resultId, data }) => ({
      id,
      documentId,
      resultId,
      data,
      kind: "EXPERIENCE",
    }),
  );
  expect(
    proposalsSchema
      .parse(proposals)
      .map((proposal) =>
        proposal.kind === "EXPERIENCE"
          ? proposal.data.descriptionRaw
          : undefined,
      ),
  ).toEqual(Array(5).fill(null));
});

it.each([
  ["initial profile", initialProfile],
  ["initial proposals", initialProposals],
  ["initial history", initialHistory],
  ["subsequent profile", subsequentProfile],
  ["subsequent proposals", subsequentProposals],
  ["subsequent history", subsequentHistory],
])("keeps new backend output free of null descriptions in %s", (_, fixture) => {
  expect(JSON.stringify(fixture)).not.toContain('"descriptionRaw":null');
});

it("accepts only string, null or omitted descriptions without widening other fields", () => {
  const data = beforeFixProfile.experiences[0].data;
  for (const descriptionRaw of ["", "Synthetic description", null, undefined]) {
    expect(
      experienceDataSchema.parse({ ...data, descriptionRaw }).descriptionRaw,
    ).toBe(descriptionRaw);
  }
  expect(
    experienceDataSchema.safeParse({ ...data, descriptionRaw: 42 }).success,
  ).toBe(false);
  expect(
    experienceDataSchema.safeParse({ ...data, descriptionEvidence: null })
      .success,
  ).toBe(false);
});
