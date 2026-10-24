import { it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { OcrResult } from "../src/features/documents/pages";
import { detailSchema } from "../src/features/documents/schema";
import { details } from "../src/mocks/fixtures";
it("renders all page statuses, separate raw text and XSS as text", () => {
  const d = detailSchema.parse(details[0]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText("Trang 1")).toBeInTheDocument();
  expect(screen.getByText("Trang trống")).toBeInTheDocument();
  expect(screen.getByText("Chưa xử lý")).toBeInTheDocument();
  expect(screen.getByText(/Mã: OCR_FAILED/)).toBeInTheDocument();
  expect(document.querySelector("script")).toBeNull();
  expect(screen.getByText(/<script>alert/)).toBeInTheDocument();
});
it("keeps legacy text as one expandable block", () => {
  const d = detailSchema.parse(details[1]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText(/Kết quả legacy/)).toBeInTheDocument();
  expect(screen.getByText(/Hiện thêm text/)).toBeInTheDocument();
  expect(screen.queryByText("Trang 1")).not.toBeInTheDocument();
});
it("explains NONE according to job status", () => {
  const d = detailSchema.parse(details[2]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText(/Chưa có kết quả OCR.*đang chờ/)).toBeInTheDocument();
});
