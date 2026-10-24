// @vitest-environment node
import { beforeAll, afterAll, afterEach, it, expect, vi } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { listDocuments } from "../src/features/documents/api";
import { listBatches, getItems } from "../src/features/batches/api";
import { overview, batch, items } from "../src/mocks/fixtures";
vi.stubGlobal("window", { location: { origin: "http://localhost:3000" } });
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());
it("omits size and follows the exact backend document cursor", async () => {
  const requests: string[] = [];
  server.use(
    http.get("http://localhost:3000/api/documents", ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.search);
      expect(url.searchParams.has("size")).toBe(false);
      return HttpResponse.json(
        url.searchParams.has("beforeId")
          ? {
              content: [overview(7)],
              size: 2,
              hasNext: false,
              nextBeforeId: null,
            }
          : {
              content: [overview(9), overview(8)],
              size: 2,
              hasNext: true,
              nextBeforeId: 8,
            },
      );
    }),
  );
  const first = await listDocuments(null);
  const second = await listDocuments(first.nextBeforeId);
  expect(first.size).toBe(2);
  expect(first.content.map((d) => d.metadata.id)).toEqual(["9", "8"]);
  expect(second.content[0].metadata.id).toBe("7");
  expect(requests).toEqual(["", "?beforeId=8"]);
});
it("omits size for batches and items while preserving zero-based page", async () => {
  const requests: string[] = [];
  server.use(
    http.get("http://localhost:3000/api/batches", ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.pathname + url.search);
      return HttpResponse.json({
        content: [batch()],
        page: 2,
        size: 3,
        totalElements: 7,
        totalPages: 3,
      });
    }),
    http.get("http://localhost:3000/api/batches/142/items", ({ request }) => {
      const url = new URL(request.url);
      requests.push(url.pathname + url.search);
      return HttpResponse.json({
        content: [items[0]],
        page: 1,
        size: 3,
        totalElements: 4,
        totalPages: 2,
      });
    }),
  );
  const batches = await listBatches(2);
  const results = await getItems("142", 1);
  expect(batches.page).toBe(2);
  expect(batches.size).toBe(3);
  expect(results.page).toBe(1);
  expect(results.size).toBe(3);
  expect(requests).toEqual([
    "/api/batches?page=2",
    "/api/batches/142/items?page=1",
  ]);
});
