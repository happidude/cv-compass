import { fireEvent, render, screen } from "@testing-library/react";
import { beforeEach, expect, it, vi } from "vitest";
import { PdfCanvas } from "../src/features/candidates/pdf-canvas";

const { getPage } = vi.hoisted(() => ({ getPage: vi.fn() }));

vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(() => ({
    promise: Promise.resolve({ numPages: 2, getPage }),
    destroy: vi.fn(),
  })),
}));
vi.mock("pdfjs-dist/build/pdf.worker.min.mjs?url", () => ({
  default: "worker.js",
}));

beforeEach(() => {
  getPage.mockReset();
  getPage.mockImplementation(async (page: number) => {
    if (page < 1 || page > 2) throw new Error("Invalid page request");
    return {
      render: () => ({ promise: Promise.resolve(), cancel: vi.fn() }),
      getTextContent: async () => ({ items: [] }),
      getViewport: () => ({ width: 100, height: 100 }),
    };
  });
});

it("keeps the pager available above the PDF page range", async () => {
  const onPage = vi.fn();
  render(<PdfCanvas documentId="12" page={5} onPage={onPage} />);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Trang 5 không có trong PDF này (2 trang).",
  );
  expect(getPage).not.toHaveBeenCalled();
  expect(screen.getByRole("button", { name: "Trang PDF tiếp" })).toBeDisabled();
  expect(screen.getByLabelText("PDF tài liệu 12, trang 5")).not.toBeVisible();
  fireEvent.click(screen.getByRole("button", { name: "Trang PDF trước" }));
  expect(onPage).toHaveBeenCalledWith(2);
});

it("keeps the pager available below the PDF page range", async () => {
  const onPage = vi.fn();
  render(<PdfCanvas documentId="12" page={0} onPage={onPage} />);

  expect(await screen.findByRole("alert")).toHaveTextContent(
    "Trang 0 không có trong PDF này (2 trang).",
  );
  expect(getPage).not.toHaveBeenCalled();
  expect(
    screen.getByRole("button", { name: "Trang PDF trước" }),
  ).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "Trang PDF tiếp" }));
  expect(onPage).toHaveBeenCalledWith(1);
});

it("renders a valid PDF page with its pager", async () => {
  const onPage = vi.fn();
  render(<PdfCanvas documentId="12" page={1} onPage={onPage} />);

  expect(await screen.findByText("Trang 1/2")).toBeVisible();
  expect(getPage).toHaveBeenCalledWith(1);
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  expect(screen.getByRole("img")).toBeVisible();
  expect(
    screen.getByRole("button", { name: "Trang PDF trước" }),
  ).toBeDisabled();
  fireEvent.click(screen.getByRole("button", { name: "Trang PDF tiếp" }));
  expect(onPage).toHaveBeenCalledWith(2);
});
