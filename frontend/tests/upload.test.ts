import { beforeAll, afterAll, afterEach, it, expect, vi } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { metadataSchema } from "../src/features/documents/schema";
import { upload } from "../src/shared/http";
import { apiFailure } from "../src/mocks/fixtures";
const server = setupServer(
  http.get("/api/auth/csrf", () =>
    HttpResponse.json({ token: "synthetic-csrf", headerName: "X-XSRF-TOKEN" }),
  ),
);
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  vi.restoreAllMocks();
});
afterAll(() => server.close());
it("does not retry an upload on 503 and marks outcome uncertain", async () => {
  let calls = 0;
  server.use(
    http.post("/api/documents", () => {
      calls++;
      return HttpResponse.json(apiFailure, { status: 503 });
    }),
  );
  const data = new FormData();
  data.append(
    "file",
    new File(["synthetic"], "sample.pdf", { type: "application/pdf" }),
  );
  await expect(
    upload(
      "/documents",
      data,
      metadataSchema,
      () => {},
      new AbortController().signal,
    ),
  ).rejects.toMatchObject({ kind: "http", uncertain: true });
  expect(calls).toBe(1);
});
it("treats malformed successful POST as uncertain", async () => {
  const log = vi.spyOn(console, "error").mockImplementation(() => {});
  server.use(http.post("/api/documents", () => HttpResponse.json({ id: 1 })));
  await expect(
    upload(
      "/documents",
      new FormData(),
      metadataSchema,
      () => {},
      new AbortController().signal,
    ),
  ).rejects.toMatchObject({ kind: "contract", uncertain: true });
  expect(log).toHaveBeenCalledTimes(1);
  expect(
    JSON.parse(String(log.mock.calls[0][0]).slice("[contract] ".length)),
  ).toMatchObject({
    endpoint: "/documents",
    issues: expect.arrayContaining([
      { path: "originalFilename", code: "invalid_type" },
    ]),
  });
});
