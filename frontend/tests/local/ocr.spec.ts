import {
  test,
  expect,
  type Page,
  type APIRequestContext,
} from "@playwright/test";
import { randomUUID } from "node:crypto";

function syntheticPdf(text: string) {
  const stream = `BT /F1 12 Tf 36 120 Td (${text}) Tj ET`;
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 180] /Resources << /Font << /F1 4 0 R >> >> /Contents 5 0 R >>",
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

test("UI upload outside manifest is processed by the real worker and renders persisted page rawText", async ({
  page,
  request,
}) => {
  const text = `CV Compass synthetic local OCR ${randomUUID()}`;
  await verifyUpload(page, request, syntheticPdf(text), text);
});

test("UI scanned synthetic PDF runs real OCR and renders stored page text", async ({
  page,
  request,
}) => {
  const text = "LOCAL SYNTHETIC OCR TEST";
  await page.goto("/documents/upload");
  const image = await page.evaluate((value) => {
    const canvas = document.createElement("canvas");
    canvas.width = 1800;
    canvas.height = 900;
    const context = canvas.getContext("2d");
    if (!context) throw new Error("Synthetic canvas unavailable");
    context.fillStyle = "white";
    context.fillRect(0, 0, canvas.width, canvas.height);
    context.fillStyle = "black";
    context.font = "64px Arial";
    context.fillText(value, 100, 220);
    // Meet the existing text-usability policy without changing its thresholds.
    context.fillText("Ordinary words for safe page extraction", 100, 360);
    context.fillText(
      "Generated fixture without personal information",
      100,
      500,
    );
    return canvas.toDataURL("image/jpeg", 1).split(",")[1];
  }, text);
  const jpeg = Buffer.from(image, "base64");
  const stream = "q 600 0 0 300 0 0 cm /Im0 Do Q";
  const objects = [
    Buffer.from("<< /Type /Catalog /Pages 2 0 R >>"),
    Buffer.from("<< /Type /Pages /Kids [3 0 R] /Count 1 >>"),
    Buffer.from(
      "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 300] /Resources << /XObject << /Im0 4 0 R >> >> /Contents 5 0 R >>",
    ),
    Buffer.concat([
      Buffer.from(
        `<< /Type /XObject /Subtype /Image /Width 1800 /Height 900 /ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode /Length ${jpeg.length} >>\nstream\n`,
      ),
      jpeg,
      Buffer.from("\nendstream"),
    ]),
    Buffer.from(`<< /Length ${stream.length} >>\nstream\n${stream}\nendstream`),
  ];
  const chunks = [Buffer.from(`%PDF-1.4\n% synthetic run ${randomUUID()}\n`)];
  const offsets = [0];
  for (const [index, object] of objects.entries()) {
    offsets.push(Buffer.concat(chunks).length);
    chunks.push(
      Buffer.from(`${index + 1} 0 obj\n`),
      object,
      Buffer.from("\nendobj\n"),
    );
  }
  const start = Buffer.concat(chunks).length;
  let xref = `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  for (const offset of offsets.slice(1))
    xref += `${String(offset).padStart(10, "0")} 00000 n \n`;
  chunks.push(
    Buffer.from(
      xref +
        `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${start}\n%%EOF\n`,
    ),
  );
  const detail = await verifyUpload(page, request, Buffer.concat(chunks), text);
  expect(detail.ocr.pages[0].method).toBe("ocr");
});

async function verifyUpload(
  page: Page,
  request: APIRequestContext,
  buffer: Buffer,
  text: string,
) {
  await page.goto("/documents/upload");
  await expect(page.getByText("Dữ liệu mẫu")).toHaveCount(0);
  await page.getByLabel("Chọn file PDF").setInputFiles({
    name: "synthetic-local-e2e.pdf",
    mimeType: "application/pdf",
    buffer,
  });
  const uploaded = page.waitForResponse(
    (response) =>
      new URL(response.url()).pathname === "/api/documents" &&
      response.request().method() === "POST",
  );
  await page
    .getByRole("button", { name: "Upload 1 file", exact: true })
    .click();
  const response = await uploaded;
  expect(response.status()).toBe(201);
  const document = await response.json();
  console.log(`Synthetic uploaded document ID=${document.id}`);
  const initial = await request.get(`/api/documents/${document.id}`);
  expect(initial.ok()).toBe(true);
  expect((await initial.json()).processing.jobId).not.toBeNull();
  await page.getByRole("link", { name: "Mở CV", exact: true }).click();
  await expect(page.locator(".ocr-page").first()).toContainText(text, {
    timeout: Number(process.env.LOCAL_E2E_TIMEOUT_MS ?? 180000),
  });
  const persisted = await request.get(`/api/documents/${document.id}`);
  const detail = await persisted.json();
  expect(detail.processing.status).toBe("COMPLETED");
  expect(detail.ocr.availability).toBe("PAGE_RESULT");
  expect(detail.ocr.pages[0].rawText).toContain(text);
  console.log(
    `Synthetic document ID=${document.id}; job ID=${detail.processing.jobId}; persisted page rawText and UI verified`,
  );
  return detail;
}
