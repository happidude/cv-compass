import { describe, expect, it } from "vitest";
import {
  dateLabel,
  firstFilename,
  periodStateLabel,
  stateLabel,
  stateTone,
} from "../src/features/candidates/labels";

describe("dateLabel", () => {
  it("labels a missing date", () => {
    expect(dateLabel(null)).toBe("Chưa tìm thấy");
  });

  it("formats a year without a month or day", () => {
    expect(
      dateLabel({ year: 2024, month: null, day: null, precision: "YEAR" }),
    ).toBe("2024 (năm)");
  });

  it.each([
    [3, "03/2024 (tháng)"],
    [11, "11/2024 (tháng)"],
  ])("formats month %s with two digits", (month, expected) => {
    expect(
      dateLabel({ year: 2024, month, day: null, precision: "MONTH" }),
    ).toBe(expected);
  });

  it.each([
    [5, 3, "05/03/2024 (ngày)"],
    [15, 11, "15/11/2024 (ngày)"],
  ])("formats day %s and month %s with two digits", (day, month, expected) => {
    expect(dateLabel({ year: 2024, month, day, precision: "DAY" })).toBe(
      expected,
    );
  });
});

describe("periodStateLabel", () => {
  it.each([
    ["PROPOSED", "Đề xuất"],
    ["AMBIGUOUS", "Ngày mâu thuẫn"],
    ["INVALID", "Ngày không hợp lệ"],
    ["NOT_FOUND", "Chưa tìm thấy ngày"],
    ["FUTURE_STATE", "Chưa tìm thấy ngày"],
  ])("labels %s in Vietnamese", (state, expected) => {
    expect(periodStateLabel(state)).toBe(expected);
  });
});

describe("review state labels and tones", () => {
  it.each([
    ["CONFIRMED", "Đã xác nhận", "good"],
    ["EDITED", "Đã chỉnh sửa", "info"],
    ["UNVERIFIED", "Chưa xác minh", "warn"],
    ["FUTURE_STATE", "Chưa xác minh", "warn"],
  ])("labels and styles %s", (state, label, tone) => {
    expect(stateLabel(state)).toBe(label);
    expect(stateTone(state)).toBe(tone);
  });
});

describe("firstFilename", () => {
  it("returns undefined for no documents", () => {
    expect(firstFilename([])).toBeUndefined();
  });

  it.each([
    ["9", "10", "20"],
    ["10", "9", "20"],
    ["20", "10", "9"],
  ])("chooses the lowest numeric id from %s, %s, %s", (...ids) => {
    const documents = ids.map((id) => ({
      id,
      filename: `synthetic-document-${id}.pdf`,
    }));

    expect(firstFilename(documents)).toBe("synthetic-document-9.pdf");
  });
});
