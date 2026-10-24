import { beforeAll, afterAll, afterEach, it, expect } from "vitest";
import { setupServer } from "msw/node";
import { http, HttpResponse } from "msw";
import { metadataSchema } from "../src/features/documents/schema";
import { upload } from "../src/shared/http";
import { apiFailure } from "../src/mocks/fixtures";
const server = setupServer();
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => server.resetHandlers());
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
});
