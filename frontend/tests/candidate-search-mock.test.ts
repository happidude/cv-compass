import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { setupServer } from "msw/node";
import { candidateHandlers } from "../src/mocks/candidates";

const server = setupServer(...candidateHandlers);
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

function search(query: string) {
  return fetch(
    new URL("/api/candidates/search?" + query, window.location.origin),
  );
}

describe("company search mock contract", () => {
  it.each([
    ["companyId=22", [1]],
    ["companyId=23", [1]],
    ["companyId=24", [8]],
    ["companyId=999", []],
    ["companyId=9223372036854775807", []],
    ["company=22", []],
    ["company=1080", [8]],
    ["company=24h", []],
    ["company=Synthetic+Logistics", [1]],
    ["company=Synthetic+Group", [1]],
  ])("filters %s by catalog ID or name", async (query, ids) => {
    const response = await search(query);
    expect(response.status).toBe(200);
    const page = await response.json();
    expect(page.content.map((row: { id: number }) => row.id)).toEqual(ids);
    expect(page.hasNext).toBe(ids.includes(1));
    expect(page.nextCursor === null).toBe(!page.hasNext);
  });

  it("keeps the company filter on the next page, including past employers", async () => {
    const response = await search("companyId=22&cursor=NjQwLjAwMDAwMHw4");
    expect(response.status).toBe(200);
    expect(await response.json()).toMatchObject({
      content: [{ id: 3, currentCompany: null }],
      hasNext: false,
      nextCursor: null,
    });
    const missing = await search("companyId=999&cursor=NjQwLjAwMDAwMHw4");
    expect(await missing.json()).toMatchObject({
      content: [],
      hasNext: false,
      nextCursor: null,
    });
  });

  it.each([
    "company=22&companyId=22",
    "company=&companyId=22",
    "company=Synthetic&companyId=22",
    "companyId=22&companyId=22",
    "companyId=",
    "companyId=0",
    "companyId=-1",
    "companyId=1.5",
    "companyId=abc",
    "companyId=9223372036854775808",
  ])("returns a 400 ApiError for %s", async (query) => {
    const response = await search(query);
    expect(response.status).toBe(400);
    expect(await response.json()).toEqual({
      code: "VALIDATION_ERROR",
      message: expect.any(String),
      correlationId: null,
      details: [{ field: "companyId" }],
    });
  });
});
