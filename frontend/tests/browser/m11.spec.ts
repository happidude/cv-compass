import { test, expect, type Page } from "@playwright/test";
import type { ExperienceSummary } from "../../src/features/candidates/schema";
import { m11Pdf } from "../fixtures/m11-pdf";
test("document association conflict keeps its own safe message", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/candidates/1/documents", (route) =>
    route.fulfill({
      status: 409,
      json: {
        code: "DOCUMENT_ALREADY_LINKED",
        message: "safe",
        correlationId: null,
        details: [],
      },
    }),
  );
  await page.goto("/candidates/1");
  await page.getByLabel("ID tài liệu đã nhập cần liên kết").fill("42");
  await page
    .getByRole("button", { name: "Liên kết tài liệu với hồ sơ này" })
    .click();
  await expect(page.getByRole("alert")).toContainText(
    "Tài liệu đã thuộc hồ sơ khác",
  );
  await expect(page.getByRole("alert")).not.toContainText("Xung đột phiên bản");
});
test("unknown title can remain empty when a USER adds an employer", async ({
  page,
}) => {
  const state = await setup(page);
  await page.goto("/candidates/1");
  await page
    .getByRole("button", { name: "Thêm kinh nghiệm", exact: true })
    .click();
  await page
    .getByLabel("Công ty", { exact: true })
    .fill("Synthetic employer without title");
  await expect(page.getByLabel("Tính vào tổng kinh nghiệm")).toHaveCount(0);
  await page
    .getByRole("button", { name: "Lưu kinh nghiệm", exact: true })
    .click();
  const article = page.locator("article").filter({
    has: page.getByText("Synthetic employer without title", { exact: true }),
  });
  await expect(
    article.getByText("Chức danh chưa xác định", { exact: false }).first(),
  ).toBeVisible();
  expect(state.writes).toEqual([
    expect.objectContaining({
      version: 1,
      data: expect.objectContaining({
        title: expect.objectContaining({ raw: null }),
      }),
    }),
  ]);
});
test("add, edit, confirm and delete a USER experience with no fabricated PDF evidence", async ({
  page,
}) => {
  const state = await setup(page);
  await page.goto("/candidates/1");
  await page
    .getByRole("button", { name: "Thêm kinh nghiệm", exact: true })
    .click();
  await page
    .getByLabel("Công ty", { exact: true })
    .fill("Synthetic New Company");
  await page.getByLabel("Chức danh", { exact: true }).fill("Synthetic Title");
  await page.getByLabel("Bắt đầu", { exact: true }).fill("2020-01");
  await page.getByLabel("Kết thúc", { exact: true }).fill("2021");
  await page
    .getByRole("button", { name: "Lưu kinh nghiệm", exact: true })
    .click();
  const article = page
    .locator("article")
    .filter({ has: page.getByText("Synthetic New Company", { exact: true }) });
  await expect(
    article.getByText("Đã chỉnh sửa · Nguồn USER", { exact: true }),
  ).toBeVisible();
  await expect(article.getByRole("button", { name: /Đối chiếu/ })).toHaveCount(
    0,
  );
  await article
    .getByRole("button", { name: "Sửa kinh nghiệm", exact: true })
    .click();
  await page
    .getByLabel("Chức danh", { exact: true })
    .fill("Synthetic Edited Title");
  await page
    .getByRole("button", { name: "Lưu kinh nghiệm", exact: true })
    .click();
  await expect(
    article.getByText("Synthetic Edited Title", { exact: false }).first(),
  ).toBeVisible();
  await article
    .getByRole("button", { name: "Xác nhận kinh nghiệm", exact: true })
    .click();
  await expect(
    article.getByText("Đã xác nhận · Nguồn USER", { exact: true }),
  ).toBeVisible();
  page.once("dialog", (dialog) => dialog.accept());
  await article
    .getByRole("button", { name: "Xóa kinh nghiệm", exact: true })
    .click();
  await expect(article).toHaveCount(0);
  expect(state.writes).toHaveLength(4);
});
const experience = {
  company: {
    raw: "Synthetic Devices",
    normalized: "synthetic devices",
    industryCodes: ["MANUFACTURING"],
  },
  title: {
    raw: "Engineering Manager",
    functionCodes: ["ENGINEERING"],
    seniorityCode: "MANAGER",
  },
  period: {
    start: { year: 2018, month: null, day: null, precision: "YEAR" },
    end: null,
    ongoingAsStated: true,
    state: "PROPOSED",
  },
  descriptionRaw: "<img src=x onerror=alert(1)> synthetic text",
  evidence: [
    {
      extractionId: 8,
      page: 3,
      start: 0,
      end: 16,
      text: "Synthetic quote page three",
    },
  ],
  issues: [{ code: "READING_ORDER_UNVERIFIED", field: "source", evidence: [] }],
};
const key = "11111111-1111-4111-8111-111111111111";
const proposalKey = "22222222-2222-4222-8222-222222222222";
const experienceSummary: ExperienceSummary = {
  totalMonths: 105,
  countedIds: [key],
  skipped: [],
  ongoingReferenceDates: ["2026-10-28"],
  hasYearOnlyPeriods: true,
};
function profile() {
  return {
    id: 1,
    version: 1,
    basic: {},
    basicState: "UNVERIFIED",
    basicOrigin: "MAPPING",
    extensions: { schemaVersion: 1 },
    experiences: [
      {
        id: key,
        state: "UNVERIFIED",
        origin: "MAPPING",
        documentId: 2,
        resultId: 9,
        data: structuredClone(experience),
      },
    ],
    documents: [
      {
        id: 2,
        filename: "Synthetic source.pdf",
        importedAt: "2026-10-28T01:00:00Z",
      },
      {
        id: 3,
        filename: "Synthetic second.pdf",
        importedAt: "2026-10-28T02:00:00Z",
      },
    ],
  };
}
async function setup(
  page: Page,
  options: {
    loggedIn?: boolean;
    missingPdf?: boolean;
    withExperienceSummary?: boolean;
  } = {},
) {
  let loggedIn = options.loggedIn ?? true;
  let current: Omit<ReturnType<typeof profile>, "experiences"> & {
    experienceSummary?: ExperienceSummary;
    experiences: (Omit<
      ReturnType<typeof profile>["experiences"][number],
      "documentId" | "resultId" | "data"
    > & {
      documentId: number | null;
      resultId: number | null;
      data: unknown;
    })[];
  } = {
    ...profile(),
    experienceSummary: options.withExperienceSummary
      ? experienceSummary
      : undefined,
  };
  let proposals = [
    {
      id: proposalKey,
      documentId: 3,
      resultId: 10,
      data: structuredClone(experience),
    },
  ];
  const writes: unknown[] = [];
  await page.route("**/api/**", async (route) => {
    const request = route.request(),
      url = new URL(request.url()),
      path = url.pathname;
    if (path === "/api/auth/csrf")
      return route.fulfill({
        json: { token: "synthetic-csrf", headerName: "X-XSRF-TOKEN" },
      });
    if (path === "/api/auth/login") {
      expect(request.headers()["x-xsrf-token"]).toBe("synthetic-csrf");
      loggedIn = true;
      return route.fulfill({ status: 204 });
    }
    if (path === "/api/auth/logout") {
      expect(request.headers()["x-xsrf-token"]).toBe("synthetic-csrf");
      loggedIn = false;
      return route.fulfill({ status: 204 });
    }
    if (!loggedIn)
      return route.fulfill({
        status: 401,
        json: {
          code: "AUTH_REQUIRED",
          message: "safe",
          details: [],
          correlationId: null,
        },
      });
    if (path === "/api/auth/me")
      return route.fulfill({
        json: { username: "synthetic-user", roles: ["ROLE_USER"] },
      });
    if (path.endsWith("/content")) {
      if (options.missingPdf)
        return route.fulfill({
          status: 404,
          json: { code: "STORAGE_FILE_MISSING" },
        });
      return route.fulfill({
        status: request.headers().range ? 206 : 200,
        contentType: "application/pdf",
        body: m11Pdf(),
      });
    }
    if (request.method() !== "GET") {
      expect(request.headers()["x-xsrf-token"]).toBe("synthetic-csrf");
      const body = request.postDataJSON();
      writes.push(body);
      expect(body.version).toBe(current.version);
      current = { ...current, version: current.version + 1 };
      if (path.endsWith("/basic")) {
        if (!body.confirm) current.basic = body.data;
        current.basicState = body.confirm ? "CONFIRMED" : "EDITED";
        current.basicOrigin = body.confirm ? current.basicOrigin : "USER";
      }
      if (path.includes("/proposals/")) proposals = [];
      if (path.endsWith("/experiences")) {
        if (body.operation === "SAVE") {
          const value = {
            id: body.experienceId ?? "33333333-3333-4333-8333-333333333333",
            state: "EDITED",
            origin: "USER",
            documentId: null,
            resultId: null,
            data: body.data,
          };
          current.experiences = body.experienceId
            ? current.experiences.map((old) =>
                old.id === body.experienceId ? value : old,
              )
            : [...current.experiences, value];
          if (options.withExperienceSummary && body.experienceId === key) {
            current.experienceSummary =
              body.data.countInTotal === false
                ? {
                    totalMonths: 0,
                    countedIds: [],
                    skipped: [{ id: key, reason: "EXCLUDED_BY_USER" }],
                    ongoingReferenceDates: [],
                    hasYearOnlyPeriods: false,
                  }
                : experienceSummary;
          }
        } else if (body.operation === "DELETE")
          current.experiences = current.experiences.filter(
            (old) => old.id !== body.experienceId,
          );
        else
          current.experiences = current.experiences.map((old) =>
            old.id === body.experienceId ? { ...old, state: "CONFIRMED" } : old,
          );
      }
      return route.fulfill({ json: current });
    }
    if (path === "/api/candidates")
      return route.fulfill({
        json: {
          content: [
            {
              id: 1,
              version: 1,
              fullName: null,
              basicState: "UNVERIFIED",
              importedAt: "2026-10-28T01:00:00Z",
            },
          ],
          hasNext: false,
          nextBeforeId: null,
        },
      });
    if (path.endsWith("/history"))
      return route.fulfill({
        json: [
          {
            id: 1,
            version: current.version,
            actor: "synthetic-user",
            action: "EDIT_BASIC",
            before: {},
            after: current.basic,
            at: "2026-10-28T02:00:00Z",
          },
        ],
      });
    if (path.endsWith("/proposals")) return route.fulfill({ json: proposals });
    if (path === "/api/candidates/1") return route.fulfill({ json: current });
    return route.fulfill({ status: 404, json: { code: "NOT_FOUND" } });
  });
  return { writes };
}
test("profile shows the estimate and saves the HR exclusion", async ({
  page,
}) => {
  const state = await setup(page, { withExperienceSummary: true });
  await page.goto("/candidates/1");
  const section = page.getByRole("region", {
    name: "Kinh nghiệm",
    exact: true,
  });
  await expect(
    section.getByText("Tổng kinh nghiệm: 8 năm 9 tháng"),
  ).toBeVisible();
  await expect(
    section.getByText(/ước tính · tính đến 28\/10\/2026/),
  ).toBeVisible();
  await expect(section.getByText(/Có kỳ chỉ ghi năm/)).toBeVisible();

  const edit = section.getByRole("button", {
    name: "Sửa kinh nghiệm",
    exact: true,
  });
  const countInTotal = section.getByRole("checkbox", {
    name: "Tính vào tổng kinh nghiệm",
  });
  await edit.click();
  await expect(countInTotal).toBeChecked();
  await countInTotal.uncheck();
  await section
    .getByRole("button", { name: "Lưu kinh nghiệm", exact: true })
    .click();
  await expect(
    section.getByText("Tổng kinh nghiệm: Chưa tính được"),
  ).toBeVisible();
  await expect(
    section.getByText(
      "Synthetic Devices · Engineering Manager · Đã bỏ khỏi tổng",
    ),
  ).toBeVisible();
  expect(state.writes).toEqual([
    expect.objectContaining({
      version: 1,
      experienceId: key,
      operation: "SAVE",
      data: expect.objectContaining({ countInTotal: false }),
    }),
  ]);
  await edit.click();
  await expect(countInTotal).not.toBeChecked();
});

test("login, candidate list and CSRF logout", async ({ page }) => {
  await setup(page, { loggedIn: false });
  await page.goto("/candidates");
  await expect(
    page.getByRole("heading", { name: "Đăng nhập CV Compass" }),
  ).toBeVisible();
  await page.getByLabel("Tài khoản").fill("synthetic-user");
  await page.getByLabel("Mật khẩu").fill("test-only");
  await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
  await expect(page.getByRole("link", { name: "Ứng viên #1" })).toBeVisible();
  await page.getByRole("button", { name: "Đăng xuất" }).click();
  await expect(page).toHaveURL(/login$/);
});
test("unverified profile renders text safely and selects correct document/page evidence", async ({
  page,
}) => {
  await setup(page);
  await page.goto("/candidates/1");
  // No parsed name: the heading falls back to the earliest source file name.
  await expect(
    page.getByRole("heading", { name: "Synthetic source.pdf" }),
  ).toBeVisible();
  await expect(
    page
      .getByText("<img src=x onerror=alert(1)> synthetic text", {
        exact: true,
      })
      .first(),
  ).toBeVisible();
  await expect(page.locator(".candidate-split img")).toHaveCount(0);
  await page
    .getByRole("button", { name: "Đối chiếu trang 3, đoạn 1" })
    .first()
    .click();
  await expect(page.getByRole("blockquote")).toContainText(
    "Synthetic quote page three",
  );
  await expect(page.locator(".pdf-canvas-view")).toHaveAttribute(
    "data-document-id",
    "2",
  );
  await expect(
    page.getByRole("img", { name: "PDF tài liệu 2, trang 3" }),
  ).toBeVisible();
  await expect(page.getByText("Trang 3/3", { exact: true })).toBeVisible();
  await page.getByLabel("Tài liệu nguồn", { exact: true }).selectOption("3");
  await expect(page.locator(".pdf-canvas-view")).toHaveAttribute(
    "data-document-id",
    "3",
  );
  await expect(
    page.getByRole("img", { name: "PDF tài liệu 3, trang 1" }),
  ).toBeVisible();
  await expect(page.getByText("Trang 1/3", { exact: true })).toBeVisible();
  await expect(
    page.getByText("Đang làm theo CV", { exact: false }).first(),
  ).toBeVisible();
});
test("edit and confirm only one section with version and visible history", async ({
  page,
}) => {
  const state = await setup(page);
  await page.goto("/candidates/1");
  await page.getByRole("button", { name: "Sửa thông tin cơ bản" }).click();
  await page.getByLabel("Họ tên", { exact: true }).fill("Synthetic edited");
  await page.getByRole("button", { name: "Lưu thông tin cơ bản" }).click();
  await expect(
    page.getByRole("heading", { name: "Synthetic edited" }),
  ).toBeVisible();
  expect(state.writes).toEqual([
    { version: 1, data: { fullName: "Synthetic edited" }, confirm: false },
  ]);
  await page.getByRole("button", { name: "Xác nhận thông tin cơ bản" }).click();
  await expect(
    page.getByText("Đã xác nhận · Nguồn USER", { exact: true }),
  ).toBeVisible();
  await expect(page.getByText(/synthetic-user · EDIT_BASIC/)).toBeVisible();
});
test("proposal requires explicit add or replace target and can be rejected", async ({
  page,
}) => {
  const state = await setup(page);
  await page.goto("/candidates/1");
  await expect(
    page.getByRole("button", { name: "Chấp nhận đề xuất" }),
  ).toBeDisabled();
  await page.getByLabel("Đối sánh do người dùng chọn").selectOption(key);
  await expect(
    page.getByRole("heading", { name: "Giá trị hiện hành" }),
  ).toBeVisible();
  await page.getByRole("button", { name: "Từ chối đề xuất" }).click();
  await expect(page.getByText("Không có đề xuất đang mở.")).toBeVisible();
  expect(state.writes).toEqual([{ version: 1, decision: "REJECT" }]);
});
test("optimistic conflict remains visible and requires explicit reload", async ({
  page,
}) => {
  await setup(page);
  await page.route("**/api/candidates/1/basic", (route) =>
    route.fulfill({
      status: 409,
      json: {
        code: "VERSION_CONFLICT",
        message: "safe",
        correlationId: null,
        details: [],
      },
    }),
  );
  await page.goto("/candidates/1");
  await page.getByRole("button", { name: "Xác nhận thông tin cơ bản" }).click();
  await expect(page.getByRole("alert")).toContainText("Xung đột phiên bản");
  await expect(
    page.getByRole("button", { name: "Tải lại phiên bản mới" }),
  ).toBeVisible();
});
test("PDF missing has a safe error and no canvas", async ({ page }) => {
  await setup(page, { missingPdf: true });
  await page.goto("/candidates/1");
  await expect(page.getByRole("alert")).toContainText(
    "File PDF nguồn không còn",
  );
  await expect(page.locator("canvas")).toHaveCount(0);
});
test("expired session redirects to login without mock fallback", async ({
  page,
}) => {
  await setup(page, { loggedIn: false });
  await page.goto("/candidates/1");
  await expect(page).toHaveURL(/login$/);
  await expect(
    page.getByText("Engineering Manager", { exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText("Dữ liệu mẫu")).toHaveCount(0);
});
