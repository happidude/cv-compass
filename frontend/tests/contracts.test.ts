import { describe, it, expect } from "vitest";
import {
  metadataSchema,
  detailSchema,
  cursorSchema,
} from "../src/features/documents/schema";
import {
  batchSchema,
  itemSchema,
  importSchema,
} from "../src/features/batches/schema";
import { dedupeDocuments } from "../src/features/documents/api";
import { idSchema } from "../src/shared/schema";
import { statusInfo } from "../src/shared/ui/status";
import {
  metadata,
  details,
  documentPage,
  batch,
  items,
} from "../src/mocks/fixtures";
import { readConfig } from "../src/shared/config";
import {
  shouldRetry,
  RequestError,
  decode,
  errorMessage,
} from "../src/shared/http";
import { pollingInterval } from "../src/shared/polling";
describe("API boundary and display", () => {
  it("keeps IDs strings and rejects unsafe/negative/fractional/string IDs", () => {
    expect(idSchema.parse(42)).toBe("42");
    for (const value of [9007199254740992, -1, 0, 1.5, "42"])
      expect(idSchema.safeParse(value).success).toBe(false);
  });
  it("separates upload metadata from detail; required omissions fail", () => {
    expect(metadataSchema.parse(metadata()).id).toBe("7");
    expect(detailSchema.safeParse(metadata()).success).toBe(false);
    expect(() => decode('{"id":1}', metadataSchema)).toThrow(RequestError);
  });
  it("validates all synthetic detail variants and batch responses", () => {
    details.forEach((d) =>
      expect(detailSchema.safeParse(d).success).toBe(true),
    );
    expect(batchSchema.parse(batch()).id).toBe("142");
    items.forEach((i) => itemSchema.parse(i));
    importSchema.parse({ batch: batch(), items });
  });
  it("accepts unknown enums but never renders success", () => {
    expect(statusInfo("job", "NEW_JOB")[0]).toBe("Không xác định");
    expect(statusInfo("batch", "NEW_BATCH")[1]).toBe("neutral");
    expect(statusInfo("page", "NEW_PAGE")[0]).toBe("Không xác định");
  });
  it("validates cursor and order; dedupes all pages by document ID", () => {
    const parsed = cursorSchema.parse(documentPage);
    expect(dedupeDocuments([parsed, parsed])).toHaveLength(
      parsed.content.length,
    );
    expect(
      cursorSchema.safeParse({ ...documentPage, hasNext: true }).success,
    ).toBe(false);
    expect(
      cursorSchema.safeParse({
        ...documentPage,
        content: [...documentPage.content].reverse(),
      }).success,
    ).toBe(false);
  });
  it("retries only GET transport/5xx failures, at most twice", () => {
    for (const kind of ["network", "timeout"] as const) {
      expect(shouldRetry(0, new RequestError(kind))).toBe(true);
      expect(shouldRetry(2, new RequestError(kind))).toBe(false);
    }
    expect(shouldRetry(1, new RequestError("http", 503))).toBe(true);
    for (const e of [
      new RequestError("http", 400),
      new RequestError("abort"),
      new RequestError("contract"),
    ])
      expect(shouldRetry(0, e)).toBe(false);
  });
  it("stops polling on terminal state, hidden tab, pending request or deadline", () => {
    expect(pollingInterval(100, 200, true, false, true)).toBe(5000);
    expect(pollingInterval(100, 200, false, false, true)).toBe(false);
    expect(pollingInterval(100, 200, true, true, true)).toBe(false);
    expect(pollingInterval(100, 200, true, false, false)).toBe(false);
    expect(pollingInterval(100, 120100, true, false, true)).toBe(false);
  });
  it("does not show raw errors and validates configuration", () => {
    expect(errorMessage(new Error("private CV"))).not.toContain("private");
    expect(() => readConfig({ VITE_MOCK: "true" }, true)).toThrow();
    expect(() =>
      readConfig({ VITE_UPLOAD_MAX_BYTES: "10485761" }, false),
    ).toThrow();
    expect(() => readConfig({ VITE_UPLOAD_MAX_FILES: "11" }, false)).toThrow();
    expect(() =>
      readConfig({ VITE_DISPLAY_TIMEZONE: "Invalid/Zone" }, false),
    ).toThrow();
  });
});

it("removes filename control characters without changing OCR text", async () => {
  const { displayName } = await import("../src/shared/format");
  expect(displayName("CV\u202e\u0000.pdf")).toBe("CV��.pdf");
});

it("treats inherited object property names as unknown API values", () => {
  for (const code of ["__proto__", "constructor", "toString"]) {
    for (const domain of ["job", "batch", "page", "result"] as const)
      expect(statusInfo(domain, code)[0]).toBe("Không xác định");
    expect(errorMessage(new RequestError("http", 400, code))).toBe(
      "Máy chủ chưa thể hoàn thành yêu cầu.",
    );
  }
});
