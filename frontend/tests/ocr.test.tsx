import { it, expect } from "vitest";
import { render, screen } from "@testing-library/react";
import { OcrResult } from "../src/features/documents/pages";
import { detailSchema } from "../src/features/documents/schema";
import { details } from "../src/mocks/fixtures";
it("renders one raw content area per page and XSS as text", () => {
  const d = detailSchema.parse(details[0]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText("Trang 1")).toBeInTheDocument();
  expect(screen.getByText("Trang trống")).toBeInTheDocument();
  expect(screen.getByText("Chưa xử lý")).toBeInTheDocument();
  expect(screen.getByText(/Mã: OCR_FAILED/)).toBeInTheDocument();
  expect(document.querySelector("script")).toBeNull();
  expect(screen.getAllByText("Nội dung trích xuất")).toHaveLength(4);
  expect(screen.queryByText("Text gốc (rawText)")).toBeNull();
  expect(screen.getByText(/<script>alert/)).toBeInTheDocument();
});
it("keeps legacy raw text as one expandable block", () => {
  const d = detailSchema.parse(details[1]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText(/Kết quả cũ/)).toBeInTheDocument();
  expect(screen.getByText(/Hiện thêm nội dung/)).toBeInTheDocument();
  expect(screen.queryByText("Trang 1")).not.toBeInTheDocument();
  expect(screen.getByText(/Synthetic legacy text/).textContent).toBe(
    d.ocr.legacyRawText!,
  );
});
it("explains NONE according to job status", () => {
  const d = detailSchema.parse(details[2]);
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText(/Chưa có kết quả OCR.*đang chờ/)).toBeInTheDocument();
});

it("accepts nullable legacy text and shows a useful empty result", () => {
  const d = detailSchema.parse({
    ...details[1],
    ocr: { ...details[1].ocr, legacyRawText: null },
  });
  render(<OcrResult ocr={d.ocr} processing={d.processing} />);
  expect(screen.getByText("Kết quả cũ không có văn bản")).toBeInTheDocument();
  expect(screen.queryByText("Trang 1")).not.toBeInTheDocument();
});
