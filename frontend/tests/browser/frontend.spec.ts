import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const searchPath = "/api/candidates/search";
test("search page: submit-only text, instant checkboxes, combobox, chips and reload", async ({
  page,
}) => {
  const searches: URL[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === searchPath) searches.push(url);
  });
  await page.goto("/");
  await page
    .getByRole("navigation")
    .getByRole("link", { name: "Tìm kiếm & xếp hạng" })
    .click();
  await expect(page).toHaveURL(/\/search$/);
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Tìm kiếm & xếp hạng",
  );
  await expect(
    page.getByText("Nhập từ khóa hoặc chọn bộ lọc để tìm ứng viên"),
  ).toBeVisible();
  await page.getByLabel("Từ khóa", { exact: true }).pressSequentially("ky su");
  await expect(page.getByText("Bấm Tìm kiếm để áp dụng từ khóa")).toBeVisible();
  await page.waitForTimeout(600);
  expect(searches).toHaveLength(0);
  await page.getByRole("button", { name: "Tìm kiếm", exact: true }).click();
  await expect(
    page.getByRole("link", { name: "Synthetic An Nguyen", exact: true }),
  ).toBeVisible();
  expect(searches.at(-1)?.searchParams.get("q")).toBe("ky su");
  // A checkbox applies at once and leaves unsubmitted text alone.
  await page.getByLabel("Chức danh", { exact: true }).fill("draft");
  await page.getByRole("button", { name: /^Nhóm nghề/ }).click();
  await page.getByLabel("Công nghệ thông tin", { exact: true }).check();
  await page.getByLabel("Kỹ thuật", { exact: true }).check();
  await expect
    .poll(() => new URL(page.url()).searchParams.getAll("function"))
    .toEqual(["IT", "ENGINEERING"]);
  expect(new URL(page.url()).searchParams.has("title")).toBe(false);
  await expect
    .poll(() => searches.at(-1)?.searchParams.getAll("function"))
    .toEqual(["IT", "ENGINEERING"]);
  expect(searches.every((url) => !url.searchParams.has("title"))).toBe(true);
  await page.keyboard.press("Escape");
  await page.getByLabel("Chức danh", { exact: true }).fill("");
  // Company combobox from the keyboard.
  const company = page.getByRole("combobox", { name: "Công ty" });
  await company.pressSequentially("Synthetic");
  await expect(
    page.getByRole("listbox", { name: "Gợi ý công ty" }),
  ).toBeVisible();
  const selectedRequest = page.waitForRequest((request) => {
    const url = new URL(request.url());
    return (
      url.pathname === searchPath && url.searchParams.get("companyId") === "22"
    );
  });
  await company.press("ArrowDown");
  await expect(
    page.getByRole("option", {
      name: "Synthetic Logistics (Synthetic Group)",
    }),
  ).toHaveAttribute("aria-selected", "true");
  await company.press("Enter");
  const selectedParams = new URL((await selectedRequest).url()).searchParams;
  expect(selectedParams.has("company")).toBe(false);
  expect(selectedParams.has("companyName")).toBe(false);
  await expect(company).toHaveValue("Synthetic Logistics");
  await expect(page.getByText("Đang áp dụng 4 bộ lọc")).toBeVisible();
  await expect(
    page.getByRole("columnheader", { name: "Kinh nghiệm (dự tính)" }),
  ).toBeVisible();
  await expect(page.getByText("~4 năm")).toHaveAttribute(
    "title",
    "Tính từ các mốc thời gian trong CV, có thể chưa chính xác",
  );
  await expect(page.getByLabel(/Số năm/)).toHaveCount(0);
  await page.reload();
  await expect(page.getByLabel("Từ khóa", { exact: true })).toHaveValue(
    "ky su",
  );
  await expect(company).toHaveValue("Synthetic Logistics");
  await expect(page.getByText("Đã chọn công ty từ danh mục.")).toBeVisible();
  await expect(
    page.getByText(/Từng làm tại Synthetic Group \(tập đoàn mẹ\)/),
  ).toBeVisible();
  await expect(page.getByText("AI đề xuất", { exact: true })).toHaveCount(2);
  await page.getByRole("button", { name: "Tải thêm", exact: true }).click();
  await expect(page.getByRole("link", { name: "Ứng viên #3" })).toBeVisible();
  for (const code of ["IT", "ENGINEERING", "SENIOR", "COMPANY", "22"])
    await expect(page.getByText(code, { exact: true })).toHaveCount(0);
  await page.getByRole("button", { name: /^Tỉnh\/thành/ }).click();
  await page.getByLabel("Lọc nhanh Tỉnh/thành").fill("da nang");
  await expect(page.getByRole("checkbox")).toHaveCount(1);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.keyboard.press("Escape");
  await page
    .getByRole("button", { name: "Bỏ lọc Nhóm nghề: Kỹ thuật" })
    .click();
  await expect
    .poll(() => new URL(page.url()).searchParams.getAll("function"))
    .toEqual(["IT"]);
  await page.getByRole("button", { name: "Xóa lọc", exact: true }).click();
  expect(new URL(page.url()).search).toBe("");
  await expect(
    page.getByText("Nhập từ khóa hoặc chọn bộ lọc để tìm ứng viên"),
  ).toBeVisible();
});
test("old candidate filter links open the search page without the experience range", async ({
  page,
}) => {
  await page.goto("/candidates?company=1080&function=IT&minYears=0&maxYears=6");
  await expect(page).toHaveURL(/\/search\?company=1080&function=IT$/);
  await expect(page.getByRole("combobox", { name: "Công ty" })).toHaveValue(
    "1080",
  );
  await expect(
    page.getByRole("link", { name: "Synthetic Binh Tran" }),
  ).toBeVisible();
  await expect(page.getByRole("alert")).toHaveCount(0);
  await page.goto("/candidates");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Hồ sơ ứng viên",
  );
  await expect(page.getByRole("search")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "Trang tiếp" })).toBeVisible();
});
const screens = [
  ["/", "Tổng quan"],
  ["/candidates", "Hồ sơ ứng viên"],
  ["/search?q=ky+su&function=IT", "Tìm kiếm & xếp hạng"],
  ["/candidates/1", "Synthetic An Nguyen"],
  ["/documents", "Quản lý CV"],
  ["/documents/upload", "Upload CV"],
  ["/batches", "Lịch sử nhập"],
  ["/batches/142", "Batch #142"],
  ["/documents/7", "Synthetic_scan.pdf"],
];
for (const width of [1440, 1024, 768]) {
  for (const [route, title] of screens)
    test(`${width}px ${route} layout and axe`, async ({ page }) => {
      await page.setViewportSize({ width, height: 960 });
      await page.goto(route);
      await expect(page.getByRole("heading", { level: 1 })).toContainText(
        title,
      );
      await expect(page.getByText("Dữ liệu mẫu")).toBeVisible();
      if (route !== "/documents/upload")
        await expect(page.getByText("Đang tải dữ liệu…")).toHaveCount(0);
      await expect(page.locator("body")).toHaveJSProperty("scrollWidth", width);
      expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
      await page.screenshot({
        path: `test-results/screens/${width}-${route.replace(/[^a-z0-9]+/gi, "_")}.png`,
        fullPage: true,
      });
    });
}
test("upload validation, single upload and no fake batch", async ({ page }) => {
  await page.goto("/documents/upload");
  await page.getByLabel("Chọn file PDF").setInputFiles([
    {
      name: "wrong.txt",
      mimeType: "text/plain",
      buffer: Buffer.from("synthetic"),
    },
    {
      name: "safe.pdf",
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4 synthetic %%EOF"),
    },
  ]);
  await expect(page.getByText(/Chưa gửi — Chỉ nhận/)).toBeVisible();
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Kết quả từ máy chủ" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Mở CV" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem batch" })).toHaveCount(0);
});
test("multi-upload shows actual aggregate result", async ({ page }) => {
  await page.goto("/documents/upload");
  await page.getByLabel("Chọn file PDF").setInputFiles(
    ["one", "two"].map((n) => ({
      name: `${n}.pdf`,
      mimeType: "application/pdf",
      buffer: Buffer.from("%PDF-1.4 synthetic %%EOF"),
    })),
  );
  await page
    .getByRole("button", { name: "Upload 2 file", exact: true })
    .click();
  await expect(page.getByRole("link", { name: "Xem batch" })).toBeVisible();
});
test("detail NONE/LEGACY, route change and 404", async ({ page }) => {
  await page.goto("/documents/6");
  await expect(page.getByText(/Kết quả cũ — nội dung/)).toBeVisible();
  await page.getByText(/Hiện thêm nội dung/).click();
  await expect(page.getByText(/Synthetic legacy text/)).toBeVisible();
  await page.goto("/documents/5");
  await expect(page.getByText(/Chưa có kết quả OCR/)).toBeVisible();
  await expect(page.getByText(/Kết quả cũ — nội dung/)).toHaveCount(0);
  await expect(page.getByText(/Đã dừng tự cập nhật/)).toBeVisible({
    timeout: 6000,
  });
  await page.goto("/not-found");
  await expect(page.getByRole("heading", { name: /404/ })).toBeVisible();
});
test("basic keyboard navigation and dropzone input", async ({ page }) => {
  await page.goto("/documents/upload");
  await expect(page.getByRole("heading", { name: "Upload CV" })).toBeVisible();
  await page.keyboard.press("Tab");
  await expect(page.getByText("Đến nội dung chính")).toBeFocused();
  await page.getByLabel("Chọn file PDF").focus();
  await expect(page.getByLabel("Chọn file PDF")).toBeFocused();
});
test("overview lists only loaded work and roadmap items are not links", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText("Tổng quan");
  // Default filter shows OCR failures and partial results of the first page.
  await expect(page.getByText("Trong 7 CV mới nhất")).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Mở CV Synthetic_failed.pdf" }),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Mở CV Synthetic_pending.pdf" }),
  ).toHaveCount(0);
  await page.getByRole("button", { name: /Đang xử lý/ }).click();
  await expect(
    page.getByRole("link", { name: "Mở CV Synthetic_pending.pdf" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Mở batch 142" })).toBeVisible();
  const nav = page.getByRole("navigation", { name: "Điều hướng chính" });
  await expect(nav.getByText("Xuất PDF")).toBeVisible();
  await expect(nav.getByRole("link", { name: /Xuất PDF/ })).toHaveCount(0);
  await expect(nav.locator('[aria-disabled="true"]')).toHaveCount(2);
  await expect(
    nav.getByRole("link", { name: "Tìm kiếm & xếp hạng" }),
  ).toHaveAttribute("href", "/search");
});
test("overview truncates long file names inside their column", async ({
  page,
}) => {
  await page.setViewportSize({ width: 1440, height: 960 });
  await page.goto("/");
  await page.getByRole("button", { name: /Tất cả/ }).click();
  const link = page.getByRole("link", { name: /Ten_file_rat_dai/ });
  const row = page.locator(".queue__item").filter({ has: link });
  const linkBox = (await link.boundingBox())!;
  const routeBox = (await row.locator(".route").boundingBox())!;
  expect(linkBox.x + linkBox.width).toBeLessThanOrEqual(routeBox.x);
  await expect(page.locator("body")).toHaveJSProperty("scrollWidth", 1440);
});
