import { afterEach, expect, it, vi } from "vitest";
import { z } from "zod";
import { decode, errorMessage, RequestError } from "../src/shared/http";

afterEach(() => vi.restoreAllMocks());

it("logs only sanitized paths and codes for invalid types, enums and literals", () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  const marker = "SENTINEL_PII_7f3a";
  const schema = z.object({
    rows: z.array(
      z.object({
        count: z.number(),
        state: z.enum(["OPEN", "CLOSED"]),
        kind: z.literal("EXPERIENCE"),
      }),
    ),
  });
  expect(() =>
    decode(
      JSON.stringify({
        rows: [{ count: marker, state: marker, kind: marker }],
      }),
      schema,
      "/candidates/123?search=" + marker,
    ),
  ).toThrow(RequestError);
  expect(log).toHaveBeenCalledTimes(1);
  expect(log.mock.calls[0]).toHaveLength(1);
  const line = String(log.mock.calls[0][0]);
  expect(line.startsWith("[contract] ")).toBe(true);
  expect(JSON.parse(line.slice("[contract] ".length))).toEqual({
    endpoint: "/candidates/:id",
    issues: [
      { path: "rows.0.count", code: "invalid_type" },
      { path: "rows.0.state", code: "invalid_value" },
      { path: "rows.0.kind", code: "invalid_value" },
    ],
  });
  expect(line).not.toContain(marker);
  for (const forbidden of ["message", "input", "received", "123", "search"])
    expect(line).not.toContain(forbidden);
});

it("redacts unsafe path segments and caps issue output at twenty", () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  const key = "private@example.invalid\n";
  expect(() =>
    decode(
      JSON.stringify({ [key]: Array(25).fill("SENTINEL_PII_7f3a") }),
      z.object({ [key]: z.array(z.number()) }),
    ),
  ).toThrow(RequestError);
  const line = String(log.mock.calls[0][0]);
  const diagnostic = JSON.parse(line.slice("[contract] ".length));
  expect(diagnostic).toEqual({
    issues: Array.from({ length: 20 }, (_, i) => ({
      path: `?.${i}`,
      code: "invalid_type",
    })),
  });
  expect(line).not.toContain(key);
  expect(line).not.toContain("SENTINEL_PII_7f3a");
});

it("logs a fixed code for malformed JSON without the text or parse error", () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  expect(() =>
    decode(
      "SENTINEL_PII_7f3a",
      z.unknown(),
      "/candidates/7/history?secret=SENTINEL_PII_7f3a",
    ),
  ).toThrow(RequestError);
  expect(log).toHaveBeenCalledExactlyOnceWith("[contract] INVALID_JSON");
});

it("keeps the contract message and adds the browser console hint", () => {
  expect(errorMessage(new RequestError("contract"))).toBe(
    "Lỗi contract: phản hồi máy chủ không đúng cấu trúc hoặc ID không an toàn. (xem console trình duyệt để biết trường sai)",
  );
});
