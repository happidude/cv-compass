import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
test("candidate filters persist on reload, show matches and load the next search page", async ({
  page,
}) => {
  await page.goto("/candidates");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Hồ sơ ứng viên",
  );
  await expect(page.getByText("Dữ liệu mẫu", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Trang tiếp" })).toBeVisible();
  await page.getByLabel("Từ khóa").fill("ky su");
  await page.getByLabel("Công nghệ thông tin", { exact: true }).check();
  await page.getByLabel("Kỹ thuật", { exact: true }).check();
  await page.getByLabel("Công ty", { exact: true }).fill("Synthetic");
  await page
    .getByRole("button", {
      name: "Synthetic Logistics (Synthetic Group)",
      exact: true,
    })
    .click();
  await page.getByLabel("Số năm tối thiểu").fill("0");
  await page.getByLabel("Số năm tối đa").fill("6");
  await expect(
    page.getByRole("columnheader", { name: "Lý do khớp" }),
  ).toBeVisible();
  await expect
    .poll(() => new URL(page.url()).searchParams.get("maxYears"))
    .toBe("6");
  const params = new URL(page.url()).searchParams;
  expect(params.getAll("function")).toEqual(["IT", "ENGINEERING"]);
  expect(params.get("company")).toBe("22");
  expect(params.get("companyName")).toBe("Synthetic Logistics");
  await page.reload();
  await expect(page.getByLabel("Từ khóa")).toHaveValue("ky su");
  await expect(page.getByLabel("Công ty", { exact: true })).toHaveValue(
    "Synthetic Logistics",
  );
  await expect(page.getByLabel("Kỹ thuật", { exact: true })).toBeChecked();
  await expect(
    page.getByLabel("Công nghệ thông tin", { exact: true }),
  ).toBeChecked();
  await expect(page.getByLabel("Số năm tối đa")).toHaveValue("6");
  await expect(
    page.getByText(/Từng làm tại Synthetic Group \(tập đoàn mẹ\)/),
  ).toBeVisible();
  await expect(page.getByText("AI đề xuất", { exact: true })).toHaveCount(2);
  await expect(page.getByText("~4 năm")).toHaveAttribute(
    "title",
    "Tính từ các mốc thời gian trong CV, có thể chưa chính xác",
  );
  await page.getByRole("button", { name: "Tải thêm", exact: true }).click();
  await expect(page.getByRole("link", { name: "Ứng viên #3" })).toBeVisible();
  await expect(
    page.getByRole("button", { name: "Tải thêm", exact: true }),
  ).toHaveCount(0);
  await expect(page.getByText("FUTURE_REASON", { exact: true })).toHaveCount(0);
  for (const code of ["IT", "ENGINEERING", "SENIOR", "COMPANY"])
    await expect(page.getByText(code, { exact: true })).toHaveCount(0);
  expect((await new AxeBuilder({ page }).analyze()).violations).toEqual([]);
  await page.getByLabel("Số năm tối thiểu").fill("7");
  await expect(page.getByRole("alert")).toContainText(
    "Số năm tối thiểu phải nhỏ hơn hoặc bằng số năm tối đa.",
  );
  await expect(page.getByRole("table")).toHaveCount(0);
  await page.getByRole("button", { name: "Xóa lọc", exact: true }).click();
  await expect(page.getByRole("button", { name: "Trang tiếp" })).toBeVisible();
  expect(new URL(page.url()).search).toBe("");
});
test("candidate typing debounces search and replaces URL history", async ({
  page,
}) => {
  await page.goto("/candidates");
  await expect(page.getByRole("heading", { level: 1 })).toHaveText(
    "Hồ sơ ứng viên",
  );
  await page.getByLabel("Kỹ thuật", { exact: true }).check();
  await expect(
    page.getByRole("link", { name: "Synthetic An Nguyen", exact: true }),
  ).toBeVisible();
  const searchRequests: URL[] = [];
  page.on("request", (request) => {
    const url = new URL(request.url());
    if (url.pathname === "/api/candidates/search") searchRequests.push(url);
  });
  await page.getByLabel("Từ khóa").pressSequentially("keyword");
  await expect(page.getByLabel("Từ khóa")).toHaveValue("keyword");
  await expect.poll(() => searchRequests.length).toBe(1);
  expect(searchRequests[0].searchParams.get("q")).toBe("keyword");
  await page.goBack();
  await expect(
    page.getByRole("columnheader", { name: "Thông tin cơ bản" }),
  ).toBeVisible();
  expect(new URL(page.url()).search).toBe("");
  await page.getByLabel("Công ty", { exact: true }).pressSequentially("24h");
  await expect(page.getByLabel("Công ty", { exact: true })).toHaveValue("24h");
  await expect
    .poll(() => new URL(page.url()).searchParams.get("company"))
    .toBe("24h");
  await expect
    .poll(() =>
      searchRequests.some((url) => url.searchParams.get("company") === "24h"),
    )
    .toBe(true);
  expect(new URL(page.url()).searchParams.has("companyName")).toBe(false);
});
const screens = [
  ["/", "Tổng quan"],
  ["/candidates", "Hồ sơ ứng viên"],
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
        path: `test-results/screens/${width}-${route.replaceAll("/", "_")}.png`,
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
  await expect(nav.locator('[aria-disabled="true"]')).toHaveCount(3);
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
