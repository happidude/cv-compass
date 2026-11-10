// @vitest-environment node
import { afterEach, expect, it, vi } from "vitest";
import { z } from "zod";
import { RequestError, errorMessage } from "../src/shared/http";
import { change, login, logout, probePdf } from "../src/shared/session";

vi.stubGlobal("window", { location: { origin: "http://localhost:3000" } });
const schema = z.object({ saved: z.boolean() });
const csrfResponse = () =>
  Response.json({ token: "synthetic-csrf", headerName: "X-CSRF-TOKEN" });

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

it.each(["POST", "PUT", "DELETE"])(
  "classifies a failed %s change as an uncertain network error without retrying",
  async (method) => {
    const fetch = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(csrfResponse())
      .mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const error = await change("/synthetic", method, {}, schema).catch(
      (error: unknown) => error,
    );
    expect(error).toBeInstanceOf(RequestError);
    expect(error).toMatchObject({ kind: "network", uncertain: true });
    expect(fetch).toHaveBeenCalledTimes(2);
  },
);

it("times out a change after 30 seconds and clears its timer", async () => {
  vi.useFakeTimers();
  vi.spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(csrfResponse())
    .mockImplementationOnce(
      (_input, init) =>
        new Promise((_resolve, reject) => {
          init?.signal?.addEventListener("abort", () =>
            reject(new DOMException("Aborted", "AbortError")),
          );
        }),
    );
  const request = change("/synthetic", "POST", {}, schema);
  const assertion = expect(request).rejects.toMatchObject({
    kind: "timeout",
    uncertain: true,
  });
  await vi.advanceTimersByTimeAsync(29999);
  expect(vi.getTimerCount()).toBe(1);
  await vi.advanceTimersByTimeAsync(1);
  await assertion;
  expect(vi.getTimerCount()).toBe(0);
});

it("clears the change timer on success", async () => {
  vi.useFakeTimers();
  vi.spyOn(globalThis, "fetch")
    .mockResolvedValueOnce(csrfResponse())
    .mockResolvedValueOnce(Response.json({ saved: true }));
  await expect(change("/synthetic", "POST", {}, schema)).resolves.toEqual({
    saved: true,
  });
  expect(vi.getTimerCount()).toBe(0);
});

it.each([login.bind(null, "synthetic-user", "synthetic-password"), logout])(
  "classifies a session mutation network failure",
  async (request) => {
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(csrfResponse())
      .mockRejectedValueOnce(new TypeError("Failed to fetch"));
    const error = await request().catch((error: unknown) => error);
    expect(error).toBeInstanceOf(RequestError);
    expect(error).toMatchObject({ kind: "network", uncertain: false });
  },
);

it("classifies a PDF probe network failure", async () => {
  vi.spyOn(globalThis, "fetch").mockRejectedValueOnce(
    new TypeError("Failed to fetch"),
  );
  await expect(
    probePdf("7", new AbortController().signal),
  ).rejects.toMatchObject({ kind: "network" });
});

it("classifies PDF probe cancellation as abort", async () => {
  const controller = new AbortController();
  vi.spyOn(globalThis, "fetch").mockImplementationOnce(
    (_input, init) =>
      new Promise((_resolve, reject) => {
        init?.signal?.addEventListener("abort", () =>
          reject(new DOMException("Aborted", "AbortError")),
        );
      }),
  );
  const request = probePdf("7", controller.signal);
  controller.abort();
  await expect(request).rejects.toMatchObject({
    kind: "abort",
    uncertain: false,
  });
});

it.each(["network", "timeout"] as const)(
  "explains an uncertain %s outcome",
  (kind) => {
    expect(
      errorMessage(
        new RequestError(kind, undefined, undefined, undefined, true),
      ),
    ).toBe(
      "Không rõ thao tác đã được lưu hay chưa. Tải lại dữ liệu trước khi thử lại.",
    );
  },
);

it("preserves messages for other errors", () => {
  expect(errorMessage(new RequestError("network"))).toBe(
    "Không thể kết nối máy chủ. Kiểm tra kết nối của bạn.",
  );
  expect(errorMessage(new RequestError("timeout"))).toBe(
    "Yêu cầu quá thời gian. Vui lòng thử cập nhật lại.",
  );
  expect(
    errorMessage(
      new RequestError("abort", undefined, undefined, undefined, true),
    ),
  ).toBe("Yêu cầu đã dừng.");
});
