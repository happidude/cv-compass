import { render, screen } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { ExperienceSummaryNotice } from "../src/features/candidates/experience-summary";
import {
  experienceSkipReasonLabel,
  formatExperienceMonths,
} from "../src/features/candidates/labels";
import {
  experienceDataSchema,
  profileSchema,
} from "../src/features/candidates/schema";

const id = "11111111-1111-4111-8111-111111111111";
const oldProfile = {
  id: 1,
  version: 2,
  basic: {},
  basicState: "UNVERIFIED",
  basicOrigin: "MAPPING",
  extensions: {},
  experiences: [],
  documents: [],
};

describe("experience summary", () => {
  it.each([
    [0, "Chưa tính được"],
    [8, "0 năm 8 tháng"],
    [12, "1 năm 0 tháng"],
    [47, "3 năm 11 tháng"],
  ])("formats %s months", (months, text) => {
    expect(formatExperienceMonths(months)).toBe(text);
  });

  it.each([
    ["MISSING_START", "Thiếu ngày bắt đầu"],
    ["MISSING_END", "Thiếu ngày kết thúc"],
    ["PERIOD_UNRESOLVED", "Kỳ kinh nghiệm chưa xác định"],
    ["EXCLUDED_BY_USER", "Đã bỏ khỏi tổng"],
    ["NO_REFERENCE_DATE", "Chưa có ngày nhập CV để tham chiếu"],
    ["START_AFTER_REFERENCE", "Ngày bắt đầu sau ngày nhập CV"],
    ["FUTURE_REASON", "Lý do chưa xác định"],
  ])("labels %s in Vietnamese", (reason, text) => {
    expect(experienceSkipReasonLabel(reason)).toBe(text);
  });

  it("decodes an optional summary and rejects invalid counts and dates", () => {
    expect(profileSchema.parse(oldProfile).experienceSummary).toBeUndefined();
    const experienceSummary = {
      totalMonths: 13,
      countedIds: [id],
      skipped: [],
      ongoingReferenceDates: ["2026-07-12"],
      hasYearOnlyPeriods: true,
    };
    expect(
      profileSchema.parse({ ...oldProfile, experienceSummary })
        .experienceSummary,
    ).toEqual(experienceSummary);
    for (const invalid of [
      { ...experienceSummary, totalMonths: -1 },
      { ...experienceSummary, countedIds: ["not-a-uuid"] },
      { ...experienceSummary, ongoingReferenceDates: ["2026-02-30"] },
    ]) {
      expect(
        profileSchema.safeParse({ ...oldProfile, experienceSummary: invalid })
          .success,
      ).toBe(false);
    }
  });

  it("explains both a partial month and a skipped experience when the total is zero", () => {
    render(
      <ExperienceSummaryNotice
        summary={{
          totalMonths: 0,
          countedIds: [id],
          skipped: [
            {
              id: "22222222-2222-4222-8222-222222222222",
              reason: "MISSING_END",
            },
          ],
          ongoingReferenceDates: [],
          hasYearOnlyPeriods: false,
        }}
        experiences={[]}
      />,
    );
    expect(screen.getByText("Tổng kinh nghiệm: Chưa tính được")).toBeVisible();
    expect(
      screen.getByText("Các kỳ được tính chưa đủ một tháng."),
    ).toBeVisible();
    expect(screen.getByText(/Thiếu ngày kết thúc/)).toBeVisible();
  });

  it("keeps an optional boolean selection on experience data", () => {
    const data = {
      company: { raw: "Synthetic Office" },
      title: { raw: null },
      period: { state: "NOT_FOUND", ongoingAsStated: false },
    };
    expect(experienceDataSchema.parse(data).countInTotal).toBeUndefined();
    expect(
      experienceDataSchema.parse({ ...data, countInTotal: false }).countInTotal,
    ).toBe(false);
    expect(
      experienceDataSchema.safeParse({ ...data, countInTotal: "false" })
        .success,
    ).toBe(false);
  });
});
