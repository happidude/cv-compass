import { test, expect } from "@playwright/test";
import { readFileSync, writeFileSync, existsSync } from "node:fs";
const directory = "../backend/build/m11/live";
const context = JSON.parse(readFileSync(directory + "/ready.json", "utf8")) as {
  port: number;
  candidateId: number;
  documentId: number;
};
test("real session, PDF range, edit/history and protected remapping proposal", async ({
  page,
  request,
}) => {
  try {
    await page.goto("/candidates");
    await expect(
      page.getByRole("heading", { name: "Đăng nhập CV Compass" }),
    ).toBeVisible();
    await page.getByLabel("Tài khoản").fill("synthetic-smoke");
    await page.getByLabel("Mật khẩu").fill("synthetic-smoke-only");
    await page.getByRole("button", { name: "Đăng nhập", exact: true }).click();
    await page
      .getByRole("link", {
        name: "Ứng viên #" + context.candidateId,
        exact: true,
      })
      .click();
    await expect(
      page.getByText("Chưa xác minh · Nguồn MAPPING", { exact: true }).first(),
    ).toBeVisible();
    await expect(
      page.getByRole("img", {
        name: `PDF tài liệu ${context.documentId}, trang 1`,
      }),
    ).toBeVisible();
    await expect(page.getByText("Trang 1/1", { exact: true })).toBeVisible();
    expect(
      await page.locator(".pdf-canvas-view canvas").evaluate((element) => {
        const canvas = element as HTMLCanvasElement;
        const pixels = canvas
          .getContext("2d")!
          .getImageData(0, 0, canvas.width, canvas.height).data;
        for (let i = 0; i < pixels.length; i += 4)
          if (
            pixels[i] < 200 &&
            pixels[i + 1] < 200 &&
            pixels[i + 2] < 200 &&
            pixels[i + 3] > 0
          )
            return true;
        return false;
      }),
    ).toBe(true);
    await page
      .getByText("Văn bản của trang PDF (nếu có)", { exact: true })
      .click();
    await expect(page.locator(".pdf-canvas-view pre")).toContainText(
      "Northstar Devices",
    );
    await page
      .getByRole("button", { name: "Đối chiếu trang 1, đoạn 1" })
      .first()
      .click();
    await expect(page.getByRole("blockquote")).toContainText(
      "Northstar Devices",
    );
    const cookies = await page.context().cookies();
    const range = await request.get(
      `http://localhost:${context.port}/api/documents/${context.documentId}/content`,
      {
        headers: {
          Cookie: cookies
            .map((cookie) => cookie.name + "=" + cookie.value)
            .join("; "),
          Range: "bytes=0-3",
        },
      },
    );
    expect(range.status()).toBe(206);
    expect((await range.body()).toString()).toBe("%PDF");
    await page.getByRole("button", { name: "Sửa thông tin cơ bản" }).click();
    await page
      .getByLabel("Họ tên", { exact: true })
      .fill("Synthetic live USER");
    await page.getByRole("button", { name: "Lưu thông tin cơ bản" }).click();
    await expect(
      page.getByRole("heading", { name: "Synthetic live USER" }),
    ).toBeVisible();
    await expect(page.getByText(/synthetic-smoke · EDIT_BASIC/)).toBeVisible();
    await page
      .getByRole("button", { name: "Xác nhận kinh nghiệm", exact: true })
      .click();
    await expect(
      page.getByText("Đã xác nhận · Nguồn MAPPING", { exact: true }),
    ).toBeVisible();
    writeFileSync(directory + "/remap", "requested");
    await expect.poll(() => existsSync(directory + "/remap-done")).toBe(true);
    await page.reload();
    await expect(
      page.getByRole("heading", { name: "Synthetic live USER" }),
    ).toBeVisible();
    await expect(
      page.getByRole("heading", {
        name: "Đề xuất từ tài liệu #" + context.documentId,
      }),
    ).toBeVisible();
    await expect(
      page.getByText("Đã xác nhận · Nguồn MAPPING", { exact: true }),
    ).toBeVisible();
    await expect(page.getByText("Trang 1/1", { exact: true })).toBeVisible();
    await page.screenshot({
      path: ".ci-reports/m11-live/profile-source.png",
      fullPage: true,
    });
    await page.getByRole("button", { name: "Từ chối đề xuất" }).click();
    await expect(page.getByText("Không có đề xuất đang mở.")).toBeVisible();
    await page.getByRole("button", { name: "Đăng xuất" }).click();
    await expect(page).toHaveURL(/login$/);
  } finally {
    writeFileSync(directory + "/stop", "done");
  }
});
