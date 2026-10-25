import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
const screens = [
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
