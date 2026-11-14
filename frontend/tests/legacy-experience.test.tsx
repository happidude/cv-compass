import { afterEach, expect, it, vi } from "vitest";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { CandidatePage } from "../src/features/candidates/pages";
import beforeFixProfile from "./fixtures/llm-remote/before-fix-profile.json";

vi.mock("../src/features/candidates/pdf-pane", () => ({ PdfPane: () => null }));
afterEach(() => vi.unstubAllGlobals());

it("displays a persisted null description as empty and submits a string from the edit form", async () => {
  const profile = {
    ...structuredClone(beforeFixProfile),
    experiences: [structuredClone(beforeFixProfile.experiences[0])],
    documents: [],
  };
  const writes: Record<string, unknown>[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/auth/csrf"))
        return Response.json({
          token: "synthetic",
          headerName: "X-CSRF-TOKEN",
        });
      if (init?.method === "POST") {
        writes.push(JSON.parse(String(init.body)));
        return Response.json(profile);
      }
      if (url.endsWith("/proposals") || url.endsWith("/history"))
        return Response.json([]);
      return Response.json(profile);
    }),
  );
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
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
  const edit = await screen.findByRole("button", { name: "Sửa kinh nghiệm" });
  expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  const experience = edit.closest("article")!;
  expect(within(experience).queryByText(/^null$/)).not.toBeInTheDocument();
  expect(experience.querySelector(".xp > pre")).toBeNull();
  // Raw evidence stays in the collapsed disclosure rather than the displayed description.
  expect(
    within(experience).getByText(/"descriptionRaw": null/),
  ).not.toBeVisible();
  const user = userEvent.setup();
  await user.click(edit);
  expect(screen.getByLabelText("Mô tả")).toHaveValue("");
  await user.click(screen.getByRole("button", { name: "Lưu kinh nghiệm" }));
  await waitFor(() => expect(writes).toHaveLength(1));
  expect(writes[0]).toMatchObject({
    operation: "SAVE",
    data: { descriptionRaw: "" },
  });
  await waitFor(() =>
    expect(screen.queryByLabelText("Mô tả")).not.toBeInTheDocument(),
  );
  client.clear();
});
