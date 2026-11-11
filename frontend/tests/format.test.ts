import { describe, expect, it, vi } from "vitest";
import { config } from "../src/shared/config";
import { date, day } from "../src/shared/format";

vi.mock("../src/shared/config", () => ({
  config: { timezone: "Asia/Ho_Chi_Minh" },
}));

describe("display date formatting", () => {
  const instant = "2026-11-10T18:30:00Z";

  it("formats the date and time in the configured timezone", () => {
    expect(date(instant)).toContain("11/11/26");
    expect(date(instant)).toContain("01:30");
  });

  it("formats only the day in the configured timezone", () => {
    expect(day(instant)).toBe("11/11/26");
  });

  it("uses a different configured timezone", () => {
    const originalTimezone = config.timezone;
    try {
      config.timezone = "UTC";
      expect(date(instant)).toContain("10/11/26");
      expect(date(instant)).toContain("18:30");
      expect(day(instant)).toBe("10/11/26");
    } finally {
      config.timezone = originalTimezone;
    }
  });

  it.each([undefined, null, "", "not-a-date", "2026-13-10T18:30:00Z"])(
    "returns a placeholder for %s",
    (value) => {
      expect(date(value)).toBe("—");
      expect(day(value)).toBe("—");
    },
  );
});
