import { test, expect } from "@playwright/test";
// A valid one-page PDF built entirely from synthetic ASCII text; no CV fixture files.
function syntheticPdf() {
  const stream =
    "BT /F1 12 Tf 36 120 Td (CV Compass M9 synthetic smoke only) Tj ET";
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 300 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`,
  ];
  let pdf = "%PDF-1.4\n";
  const offsets = [0];
  objects.forEach((body, index) => {
    offsets.push(Buffer.byteLength(pdf));
    pdf += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const start = Buffer.byteLength(pdf);
  pdf += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets.slice(1))
    pdf += `${String(offset).padStart(10, "0")} 00000 n \n`;
  return Buffer.from(
    pdf +
      `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`,
  );
}
test("real isolated backend: single/batch upload, lists and detail readback", async ({
  page,
  request,
}) => {
  const initial = await request.get("/api/documents");
  expect(initial.ok()).toBe(true);
  expect((await initial.json()).content).toEqual([]);
  const apiFailures: string[] = [];
  page.on("response", (response) => {
    if (response.url().includes("/api/") && !response.ok())
      apiFailures.push(
        `${response.status()} ${new URL(response.url()).pathname}`,
      );
  });
  await page.goto("/documents/upload");
  await expect(page.getByText("Dữ liệu mẫu")).toHaveCount(0);
  await page.getByLabel("Chọn file PDF").setInputFiles({
    name: "M9_synthetic_single.pdf",
    mimeType: "application/pdf",
    buffer: syntheticPdf(),
  });
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Kết quả từ máy chủ" }),
  ).toBeVisible();
  await expect(page.getByRole("link", { name: "Xem batch" })).toHaveCount(0);
  await page.getByRole("link", { name: "Mở CV", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "M9_synthetic_single.pdf",
  );
  await expect(page.getByText(/Chưa có kết quả OCR/)).toBeVisible();
  await page
    .getByRole("link", { name: "Quản lý CV", exact: true })
    .first()
    .click();
  await expect(
    page.getByRole("cell", { name: "M9_synthetic_single.pdf", exact: true }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Upload CV", exact: true }).click();
  await page.getByLabel("Chọn file PDF").setInputFiles(
    ["one", "two"].map((n) => ({
      name: `M9_synthetic_batch_${n}.pdf`,
      mimeType: "application/pdf",
      buffer: syntheticPdf(),
    })),
  );
  await page
    .getByRole("button", { name: "Upload 2 file", exact: true })
    .click();
  await expect(
    page.getByRole("heading", { name: "Kết quả từ máy chủ" }),
  ).toBeVisible();
  await page.getByRole("link", { name: "Xem batch", exact: true }).click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Batch #",
  );
  await expect(page.locator(".stats")).toContainText("Upload thành công2");
  await expect(
    page.getByRole("cell", { name: "M9_synthetic_batch_one.pdf", exact: true }),
  ).toBeVisible();
  const batchUrl = page.url();
  await page.getByRole("link", { name: "Mở CV", exact: true }).first().click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "M9_synthetic_batch_one.pdf",
  );
  await expect(page.getByRole("link", { name: /Batch #/ })).toHaveAttribute(
    "href",
    new URL(batchUrl).pathname,
  );
  await page
    .getByRole("link", { name: "Lịch sử nhập", exact: true })
    .first()
    .click();
  await expect(page.locator("tbody tr")).toHaveCount(1);
  await page
    .getByRole("link", { name: "Chi tiết batch 1", exact: true })
    .click();
  await expect(page.getByRole("heading", { level: 1 })).toContainText(
    "Batch #1",
  );
  await page.goto("/documents");
  await expect(page.locator("tbody tr")).toHaveCount(2);
  await page.getByRole("button", { name: "Tải thêm", exact: true }).click();
  await expect(page.locator("tbody tr")).toHaveCount(3);
  expect(apiFailures).toEqual([]);
  await page.screenshot({
    path: ".ci-reports/smoke-documents.png",
    fullPage: true,
  });
});
