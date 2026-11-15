// @vitest-environment node
import { beforeAll, afterAll, afterEach, it, expect, vi } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { metadataSchema } from "../src/features/documents/schema";
import { get, RequestError, errorMessage } from "../src/shared/http";
import { metadata, apiFailure } from "../src/mocks/fixtures";
vi.stubGlobal("window", { location: { origin: "http://localhost:3000" } });
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
it("uses HTTP and schema validation with safe ApiError and correlationId", async () => {
  server.use(
    http.get("http://localhost:3000/api/documents/7", () =>
      HttpResponse.json(apiFailure, { status: 503 }),
    ),
  );
  const error = await get("/documents/7", metadataSchema).catch(
    (e) => e as RequestError,
  );
  expect(error).toMatchObject({
    kind: "http",
    status: 503,
    code: "INTERNAL_ERROR",
    correlationId: "synthetic-trace-123",
  });
  expect(errorMessage(error)).not.toContain(apiFailure.message);
  server.use(
    http.get("http://localhost:3000/api/documents/7", () =>
      HttpResponse.json(metadata()),
    ),
  );
  expect((await get("/documents/7", metadataSchema)).id).toBe("7");
});
it("rejects malformed GET responses at the boundary", async () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  server.use(
    http.get("http://localhost:3000/api/documents/7", () =>
      HttpResponse.json({ id: 1 }),
    ),
  );
  await expect(get("/documents/7", metadataSchema)).rejects.toMatchObject({
    kind: "contract",
  });
  expect(log).toHaveBeenCalledTimes(1);
  expect(
    JSON.parse(String(log.mock.calls[0][0]).slice("[contract] ".length)),
  ).toMatchObject({
    endpoint: "/documents/:id",
    issues: expect.arrayContaining([
      { path: "originalFilename", code: "invalid_type" },
    ]),
  });
});
it("honors cancellation", async () => {
  const controller = new AbortController();
  controller.abort();
  await expect(
    get("/documents/7", metadataSchema, controller.signal),
  ).rejects.toMatchObject({ kind: "abort" });
});

it("distinguishes a timeout from a caller abort", async () => {
  vi.useFakeTimers();
  server.use(
    http.get(
      "http://localhost:3000/api/documents/7",
      () => new Promise(() => {}),
    ),
  );
  try {
    const request = get("/documents/7", metadataSchema);
    const assertion = expect(request).rejects.toMatchObject({
      kind: "timeout",
    });
    await vi.advanceTimersByTimeAsync(20000);
    await assertion;
  } finally {
    vi.useRealTimers();
  }
});
