import { test, expect, type Page } from "@playwright/test";
import {
  documentPage,
  details,
  batch,
  items,
  metadata,
  apiFailure,
} from "../../src/mocks/fixtures";
const documentListRoute = /\/api\/documents(?:\?.*)?$/;
async function defaults(page: Page) {
  await page.route("**/api/**", async (route) => {
    const url = new URL(route.request().url());
    const path = url.pathname;
    let data: unknown;
    if (path === "/api/auth/me")
      data = { username: "synthetic-user", roles: ["ROLE_USER"] };
    else if (path === "/api/auth/csrf")
      data = { token: "synthetic-csrf", headerName: "X-XSRF-TOKEN" };
    else if (path === "/api/documents") data = documentPage;
    else if (/\/documents\/\d+$/.test(path))
      data = details.find((d) => String(d.id) === path.split("/").at(-1));
    else if (path === "/api/batches")
      data = {
        content: [batch()],
        page: Number(url.searchParams.get("page")),
        size: 20,
        totalElements: 21,
        totalPages: 2,
      };
    else if (path.endsWith("/items"))
      data = {
        content: items,
        page: Number(url.searchParams.get("page")),
        size: 20,
        totalElements: 4,
        totalPages: 1,
      };
    else data = batch();
    await route.fulfill({
      status: data ? 200 : 404,
      json: data ?? { ...apiFailure, code: "NOT_FOUND" },
    });
  });
}
const pdf = (name = "safe.pdf") => ({
  name,
  mimeType: "application/pdf",
  buffer: Buffer.from("%PDF-1.4 synthetic %%EOF"),
});
test.beforeEach(async ({ page }) => {
  await defaults(page);
});
test("loading then empty, safe HTTP error and contract failure", async ({
  page,
}) => {
  let release!: () => void;
  const gate = new Promise<void>((resolve) => {
    release = resolve;
  });
  await page.route(documentListRoute, async (route) => {
    await gate;
    await route.fulfill({ json: { ...documentPage, content: [] } });
  });
  await page.goto("/documents");
  await expect(page.getByRole("status")).toContainText("Đang tải");
  release();
  await expect(page.getByText(/Chưa có CV/)).toBeVisible();
  await page.unroute(documentListRoute);
  await page.route(documentListRoute, (route) =>
    route.fulfill({
      status: 400,
      json: { ...apiFailure, code: "INVALID_REQUEST" },
    }),
  );
  await page.reload();
  await expect(page.getByRole("alert")).toContainText("INVALID_REQUEST");
  await expect(page.getByText(apiFailure.message)).toHaveCount(0);
  await page.unroute(documentListRoute);
  await page.route(documentListRoute, (route) =>
    route.fulfill({ json: { id: 9007199254740992 } }),
  );
  await page.reload();
  await expect(page.getByRole("alert")).toContainText(
    "Lỗi contract: phản hồi máy chủ không đúng cấu trúc hoặc ID không an toàn. (xem console trình duyệt để biết trường sai)",
  );
});
test("cursor load-more keeps rows on failure, retries in place, refetch dedupes", async ({
  page,
}) => {
  const first = {
    ...documentPage,
    content: documentPage.content.slice(0, 2),
    hasNext: true,
    nextBeforeId: 6,
  };
  let fail = true;
  let cursor = "";
  let nextCalls = 0;
  await page.route(documentListRoute, (route) => {
    const before = new URL(route.request().url()).searchParams.get("beforeId");
    expect(new URL(route.request().url()).searchParams.has("size")).toBe(false);
    cursor = before ?? "";
    if (before) {
      nextCalls++;
      return route.fulfill(
        fail
          ? { status: 400, json: apiFailure }
          : {
              json: { ...documentPage, content: documentPage.content.slice(2) },
            },
      );
    }
    return route.fulfill({ json: first });
  });
  await page.goto("/documents");
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByRole("button", { name: "Tải thêm", exact: true }).click();
  await expect(page.getByRole("alert")).toBeVisible();
  await expect(page.locator("tbody tr")).toHaveCount(2);
  expect(cursor).toBe("6");
  expect(nextCalls).toBe(1);
  fail = false;
  await page.getByRole("button", { name: "Thử lại" }).click();
  await expect(page.locator("tbody tr")).toHaveCount(7);
  await expect(page.getByText("Đã hết dữ liệu")).toBeVisible();
  await page.getByRole("link", { name: "Lịch sử nhập" }).click();
  await page.goBack();
  await expect(page.locator("tbody tr")).toHaveCount(7);
});
test("upload does not retry or double-submit; unknown result requires inspection", async ({
  page,
}) => {
  let calls = 0;
  let release!: () => void;
  const gate = new Promise<void>((r) => {
    release = r;
  });
  await page.route("**/api/documents", async (route) => {
    calls++;
    await gate;
    await route.fulfill({ status: 503, json: apiFailure });
  });
  await page.goto("/documents/upload");
  await page.getByLabel("Chọn file PDF").setInputFiles(pdf());
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await expect(
    page.getByRole("button", { name: "Đang upload…" }),
  ).toBeDisabled();
  release();
  await expect(
    page.getByRole("heading", { name: "Chưa xác định kết quả" }),
  ).toBeVisible();
  expect(calls).toBe(1);
  await expect(page.getByRole("link", { name: "CV đã lưu" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Upload 1 file", exact: true }),
  ).toHaveCount(0);
});
test("abort warns about possible server processing; router blocker traps focus", async ({
  page,
}) => {
  await page.route("**/api/documents", async (route) => {
    await new Promise((r) => setTimeout(r, 4000));
    await route.fulfill({ json: metadata() }).catch(() => {});
  });
  await page.goto("/documents/upload");
  await page.getByLabel("Chọn file PDF").setInputFiles(pdf());
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await page.getByRole("link", { name: "Lịch sử nhập" }).click();
  await expect(page.getByRole("dialog")).toBeVisible();
  await expect(page.getByRole("button", { name: "Ở lại" })).toBeFocused();
  await page.keyboard.press("Escape");
  await expect(page.getByRole("dialog")).not.toBeVisible();
  await page.getByRole("button", { name: "Dừng gửi" }).click();
  await expect(
    page.getByRole("heading", { name: "Chưa xác định kết quả" }),
  ).toBeVisible();
});
test("batch aggregate is independent of item page and URL pagination works", async ({
  page,
}) => {
  await defaults(page);
  await page.goto("/batches");
  await expect(
    page.getByRole("button", { name: "Trước", exact: true }),
  ).toBeDisabled();
  await page.getByRole("button", { name: "Sau", exact: true }).click();
  await expect(page).toHaveURL(/page=1/);
  await expect(
    page.getByRole("button", { name: "Sau", exact: true }),
  ).toBeDisabled();
  await page.goto("/batches/142");
  await expect(page.locator(".stats")).toContainText("Upload thành công3");
  await expect(
    page.getByRole("columnheader", { name: "Xử lý OCR" }),
  ).toHaveCount(0);
  await expect(
    page.getByRole("cell", { name: "Chưa lưu", exact: true }),
  ).toBeVisible();
});
test("polling stops at deadline, terminal state and unmount without old details", async ({
  page,
}) => {
  await defaults(page);
  let pendingCalls = 0;
  let terminalCalls = 0;
  // StrictMode aborts its probe fetch. Count completed HTTP responses, not aborted requests.
  page.on("response", (response) => {
    if (
      response.url().endsWith("/api/documents/7") &&
      response.status() === 200
    )
      terminalCalls++;
  });
  await page.route("**/api/documents/5", (route) => {
    pendingCalls++;
    return route.fulfill({ json: details[2] });
  });
  await page.route("**/api/documents/7", (route) => {
    return route.fulfill({ json: details[0] });
  });
  await page.goto("/documents/5");
  // The 5000 ms budget (VITE_POLL_MAX_MS) starts when the detail page mounts, after module loading
  // and the session request, not when goto resolves; 6000 ms left too little margin on a busy machine.
  // Stopping itself is still asserted below by the unchanged call count.
  await expect(page.getByText(/Đã dừng tự cập nhật/)).toBeVisible({
    timeout: 10000,
  });
  const calls = pendingCalls;
  // Polling really ran before the deadline stopped it.
  expect(calls).toBeGreaterThan(1);
  await page.waitForTimeout(800);
  expect(pendingCalls).toBe(calls);
  await page.goto("/documents/7");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Synthetic_scan.pdf",
  );
  await page.waitForTimeout(1700);
  expect(terminalCalls).toBe(1);
  expect(pendingCalls).toBe(calls);
});
test("unknown enum displays unknown, and batch list/item empty/error states", async ({
  page,
}) => {
  await defaults(page);
  await page.goto("/documents/1");
  await expect(
    page.getByRole("definition").filter({ hasText: "Không xác định" }),
  ).toBeVisible();
  await page.route("**/api/batches?*", (route) =>
    route.fulfill({
      json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    }),
  );
  await page.goto("/batches");
  await expect(page.getByText(/Chưa có batch/)).toBeVisible();
  await page.route("**/api/batches/142/items?*", (route) =>
    route.fulfill({ status: 400, json: apiFailure }),
  );
  await page.goto("/batches/142");
  await expect(page.getByRole("alert")).toBeVisible();
});

test("polling pauses in hidden tabs and resumes when visible", async ({
  page,
}) => {
  await defaults(page);
  let calls = 0;
  await page.addInitScript(() => {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => "visible",
    });
  });
  await page.route("**/api/documents/5", (route) => {
    calls++;
    return route.fulfill({ json: details[2] });
  });
  await page.goto("/documents/5");
  await expect(page.getByText(/Chưa có kết quả OCR/)).toBeVisible();
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => "hidden",
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  const before = calls;
  await page.waitForTimeout(700);
  expect(calls).toBe(before);
  await page.evaluate(() => {
    Object.defineProperty(document, "visibilityState", {
      configurable: true,
      get: () => "visible",
    });
    document.dispatchEvent(new Event("visibilitychange"));
  });
  await expect.poll(() => calls, { timeout: 3000 }).toBeGreaterThan(before);
});
test("network disconnect and malformed success are unknown upload outcomes", async ({
  page,
}) => {
  await page.route("**/api/documents", (route) => route.abort("failed"));
  await page.goto("/documents/upload");
  await page.getByLabel("Chọn file PDF").setInputFiles(pdf());
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Chưa xác định kết quả" }),
  ).toBeVisible();
  await page.unroute("**/api/documents");
  await page.route("**/api/documents", (route) =>
    route.fulfill({ status: 201, json: { id: 8 } }),
  );
  await page.getByRole("button", { name: "Bắt đầu lượt mới" }).click();
  await page.getByLabel("Chọn file PDF").setInputFiles(pdf());
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Chưa xác định kết quả" }),
  ).toBeVisible();
});
test("batch detail and document errors, item empty state", async ({ page }) => {
  await defaults(page);
  await page.route("**/api/batches/142", (route) =>
    route.fulfill({ status: 404, json: { ...apiFailure, code: "NOT_FOUND" } }),
  );
  await page.goto("/batches/142");
  await expect(page.getByRole("alert")).toContainText("Không tìm thấy");
  await page.goto("/documents/999");
  await expect(page.getByRole("alert")).toContainText("Không tìm thấy");
  await page.route("**/api/batches/140/items?*", (route) =>
    route.fulfill({
      json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 },
    }),
  );
  await page.goto("/batches/140");
  await expect(
    page.getByText(/Chưa có kết quả upload ở trang này/),
  ).toBeVisible();
});

test("nullable LEGACY text does not break document metadata or the page", async ({
  page,
}) => {
  await page.route("**/api/documents/6", (route) =>
    route.fulfill({
      json: { ...details[1], ocr: { ...details[1].ocr, legacyRawText: null } },
    }),
  );
  await page.goto("/documents/6");
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Synthetic_legacy.pdf",
  );
  await expect(
    page.getByText("Kết quả cũ không có văn bản", { exact: true }),
  ).toBeVisible();
  await expect(
    page.getByRole("heading", { name: "Metadata", exact: true }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
});
