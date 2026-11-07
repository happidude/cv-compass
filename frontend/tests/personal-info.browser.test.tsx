import {
  PersonalFields,
  PersonalForm,
} from "../src/features/candidates/personal-info";
import { profileSchema } from "../src/features/candidates/schema";
import { it, expect, vi, afterEach } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import { CandidatePage } from "../src/features/candidates/pages";
import {
  personalProfile,
  personalProposal,
  personalMetadata,
} from "./personal-info-fixtures";
vi.mock("../src/features/candidates/pdf-pane", () => ({
  PdfPane: ({
    documentId,
    page,
    quote,
  }: {
    documentId: string;
    page: number;
    quote: string;
  }) => (
    <section aria-label="PDF nguồn">
      <h2>
        PDF nguồn — tài liệu {documentId}, trang {page}
      </h2>
      {quote && <blockquote aria-label="Quote evidence">{quote}</blockquote>}
    </section>
  ),
}));
afterEach(() => vi.unstubAllGlobals());
function setup({ proposal = true, conflict = false } = {}) {
  const writes: Record<string, unknown>[] = [];
  const profile = structuredClone(personalProfile);
  const fetcher = vi.fn(
    async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/auth/csrf"))
        return Response.json({
          token: "synthetic",
          headerName: "X-CSRF-TOKEN",
        });
      if (init?.method && init.method !== "GET") {
        writes.push(JSON.parse(String(init.body)));
        if (conflict)
          return Response.json(
            {
              code: "VERSION_CONFLICT",
              message: "safe",
              correlationId: null,
              details: [],
            },
            { status: 409 },
          );
        const body = writes.at(-1)!;
        if (url.endsWith("/basic") && body.data) {
          Object.assign(profile.basic, body.data);
          profile.version++;
        }
        return Response.json(profile);
      }
      if (url.endsWith("/proposals"))
        return Response.json(proposal ? [personalProposal] : []);
      if (url.endsWith("/history")) return Response.json([]);
      return Response.json(profile);
    },
  );
  vi.stubGlobal("fetch", fetcher);
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  render(
    <QueryClientProvider client={client}>
      <MemoryRouter initialEntries={["/candidates/1"]}>
        <Routes>
          <Route path="/candidates/:id" element={<CandidatePage />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { writes, fetcher };
}
it("shows six fields, raw source and selects the exact personal document/page/quote", async () => {
  setup({ proposal: false });
  const user = userEvent.setup();
  await screen.findByText("Giới tính:");
  for (const label of [
    "Họ tên:",
    "Email:",
    "Điện thoại:",
    "Địa điểm:",
    "Tóm tắt:",
    "Giới tính:",
  ])
    expect(screen.getByText(label)).toBeInTheDocument();
  await user.click(
    screen.getByRole("button", {
      name: "Đối chiếu Giới tính — trang 2, đoạn 1",
    }),
  );
  expect(
    screen.getByRole("heading", { name: "PDF nguồn — tài liệu 12, trang 2" }),
  ).toBeInTheDocument();
  expect(
    screen.getByRole("blockquote", { name: "Quote evidence" }),
  ).toHaveTextContent("Nam");
});
it("personal accept requires no experience target and sends a single versioned mutation", async () => {
  const { writes } = setup();
  const user = userEvent.setup();
  await screen.findByText("Đề xuất thông tin cá nhân từ tài liệu #12");
  expect(
    screen.queryByLabelText("Đối sánh do người dùng chọn"),
  ).not.toBeInTheDocument();
  await user.click(screen.getByRole("button", { name: "Chấp nhận đề xuất" }));
  await waitFor(() =>
    expect(writes).toEqual([
      { decision: "ACCEPT", targetExperienceId: null, version: 1 },
    ]),
  );
});
it("basic edits send only changed fields and preserve unknown gender text", async () => {
  const { writes } = setup({ proposal: false });
  const user = userEvent.setup();
  await screen.findByText("Giới tính:");
  await user.click(
    screen.getByRole("button", { name: "Sửa thông tin cơ bản" }),
  );
  await user.clear(screen.getByLabelText("Giới tính"));
  await user.type(screen.getByLabelText("Giới tính"), "Other self-described");
  await user.click(
    screen.getByRole("button", { name: "Lưu thông tin cơ bản" }),
  );
  await waitFor(() =>
    expect(writes).toEqual([
      { data: { gender: "Other self-described" }, confirm: false, version: 1 },
    ]),
  );
});
it("clearing a field sends explicit null while confirm sends no data patch", async () => {
  const { writes } = setup({ proposal: false });
  const user = userEvent.setup();
  await screen.findByText("Giới tính:");
  await user.click(
    screen.getByRole("button", { name: "Sửa thông tin cơ bản" }),
  );
  await user.clear(screen.getByLabelText("Giới tính"));
  await user.click(
    screen.getByRole("button", { name: "Lưu thông tin cơ bản" }),
  );
  await waitFor(() =>
    expect(writes[0]).toEqual({
      data: { gender: null },
      confirm: false,
      version: 1,
    }),
  );
  await user.click(
    screen.getByRole("button", { name: "Xác nhận thông tin cơ bản" }),
  );
  await waitFor(() => expect(writes[1]).toEqual({ confirm: true, version: 2 }));
});
it("personal edit and reject keep separate semantics from experience proposals", async () => {
  const { writes } = setup();
  const user = userEvent.setup();
  await screen.findByText("Đề xuất thông tin cá nhân từ tài liệu #12");
  await user.click(screen.getByRole("button", { name: "Sửa đề xuất rồi lưu" }));
  await user.clear(screen.getByLabelText("Giới tính"));
  await user.type(screen.getByLabelText("Giới tính"), "Other");
  await user.click(
    screen.getByRole("button", { name: "Lưu thông tin cơ bản" }),
  );
  await waitFor(() =>
    expect(writes[0]).toEqual({
      decision: "EDIT",
      targetExperienceId: null,
      data: { gender: "Other" },
      version: 1,
    }),
  );
  await user.click(screen.getByRole("button", { name: "Từ chối đề xuất" }));
  await waitFor(() =>
    expect(writes[1]).toEqual({
      decision: "REJECT",
      targetExperienceId: null,
      version: 1,
    }),
  );
});
it("conflict is visible and a failed mutation is never retried automatically", async () => {
  const { writes } = setup({ proposal: false, conflict: true });
  const user = userEvent.setup();
  await screen.findByText("Giới tính:");
  await user.click(
    screen.getByRole("button", { name: "Xác nhận thông tin cơ bản" }),
  );
  await screen.findByText(/Xung đột phiên bản/);
  expect(writes).toHaveLength(1);
  await user.click(
    screen.getByRole("button", { name: "Tải lại phiên bản mới" }),
  );
  expect(writes).toHaveLength(1);
});
it("source text and alternatives are rendered literally and never as HTML", async () => {
  const text = '<img src=x onerror="alert(1)">';
  const meta = personalMetadata.fields.gender;
  const original = meta.raw;
  meta.raw = text;
  try {
    setup({ proposal: false });
    await screen.findByText(text);
    expect(document.querySelector("img")).toBeNull();
    const details = screen
      .getByText("Phương án khác — Giới tính")
      .closest("details")!;
    expect(within(details).getByText("Nam")).toBeInTheDocument();
  } finally {
    meta.raw = original;
  }
});
it("never substitutes another linked document when field evidence source is absent", () => {
  const profile = profileSchema.parse(personalProfile);
  const view = vi.fn();
  render(
    <PersonalFields
      basic={profile.basic}
      metadata={profile.extensions.personalInfo}
      documents={[{ id: "99" }]}
      onEvidence={view}
    />,
  );
  expect(
    screen.queryByRole("button", { name: /Đối chiếu/ }),
  ).not.toBeInTheDocument();
  expect(view).not.toHaveBeenCalled();
  expect(
    screen.getByText(/Nguồn Giới tính chưa có tài liệu liên kết/),
  ).toBeInTheDocument();
});
it("sparse proposal fields explicitly keep current values", async () => {
  setup();
  await screen.findByText("Đề xuất thông tin cá nhân từ tài liệu #12");
  expect(
    screen.getAllByText("Giữ nguyên (không có thay đổi đề xuất)"),
  ).toHaveLength(5);
});

it("summary edits preserve internal line breaks and send no untouched personal fields", async () => {
  const save = vi.fn(async () => {});
  const user = userEvent.setup();
  render(
    <PersonalForm
      initial={{ summary: "First line\nSecond line", gender: "Other" }}
      onSave={save}
      onCancel={vi.fn()}
    />,
  );
  expect(screen.getByLabelText("Tóm tắt")).toHaveValue(
    "First line\nSecond line",
  );
  await user.clear(screen.getByLabelText("Tóm tắt"));
  await user.type(
    screen.getByLabelText("Tóm tắt"),
    "First line{enter}Changed line",
  );
  await user.click(
    screen.getByRole("button", { name: "Lưu thông tin cơ bản" }),
  );
  await waitFor(() =>
    expect(save).toHaveBeenCalledWith({ summary: "First line\nChanged line" }),
  );
  expect(save).toHaveBeenCalledTimes(1);
});
it("personal proposal conflict stays visible without retrying the decision", async () => {
  const { writes } = setup({ conflict: true });
  const user = userEvent.setup();
  await screen.findByText("Đề xuất thông tin cá nhân từ tài liệu #12");
  await user.click(screen.getByRole("button", { name: "Chấp nhận đề xuất" }));
  await screen.findByText(/Xung đột phiên bản/);
  expect(writes).toEqual([
    { decision: "ACCEPT", targetExperienceId: null, version: 1 },
  ]);
  await user.click(
    screen.getByRole("button", { name: "Tải lại phiên bản mới" }),
  );
  await screen.findByText("Đề xuất thông tin cá nhân từ tài liệu #12");
  expect(writes).toHaveLength(1);
});
