import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { delay, http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { CandidatesPage } from "../src/features/candidates/list-page";
import {
  experienceFilterError,
  searchFilters,
} from "../src/features/candidates/search-filters";
import {
  estimatedExperience,
  estimatedExperienceHint,
  matchReasonLabel,
} from "../src/features/candidates/search-results";
import {
  companySuggestionsSchema,
  searchOptionsSchema,
  searchPageSchema,
} from "../src/features/candidates/schema";
import {
  candidateCompanySuggestions,
  candidateHandlers,
  candidateSearchNextPage,
  candidateSearchOptions,
  candidateSearchPage,
} from "../src/mocks/candidates";

const server = setupServer(...candidateHandlers);
const clients: QueryClient[] = [];
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterEach(() => {
  server.resetHandlers();
  for (const client of clients.splice(0)) client.clear();
});
afterAll(() => server.close());

function renderPage(url = "/candidates") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  clients.push(client);
  const router = createMemoryRouter(
    [{ path: "/candidates", element: <CandidatesPage /> }],
    { initialEntries: [url] },
  );
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...view, router, user: userEvent.setup() };
}

describe("candidate search filters", () => {
  it("loads every filter from the URL and writes repeated values without losing other groups", async () => {
    const { router, user } = renderPage(
      "/candidates?q=ky+su&company=Synthetic&title=Engineer&function=IT&function=ENGINEERING&seniority=SENIOR&scope=REGION&industry=LOGISTICS&province=H%C3%A0+N%E1%BB%99i&province=H%E1%BB%93+Ch%C3%AD+Minh&minYears=0&maxYears=6",
    );
    expect(screen.getByLabelText("Từ khóa")).toHaveValue("ky su");
    expect(screen.getByLabelText("Công ty", { exact: true })).toHaveValue(
      "Synthetic",
    );
    expect(screen.getByLabelText("Chức danh", { exact: true })).toHaveValue(
      "Engineer",
    );
    expect(screen.getByLabelText("Số năm tối thiểu")).toHaveValue("0");
    expect(screen.getByLabelText("Số năm tối đa")).toHaveValue("6");
    for (const label of [
      "Công nghệ thông tin",
      "Kỹ thuật",
      "Chuyên viên cao cấp",
      "Vùng/khu vực",
      "Logistics / vận tải / phân phối",
      "Hà Nội",
      "Hồ Chí Minh",
    ])
      expect(
        await screen.findByRole("checkbox", { name: label }),
      ).toBeChecked();
    await user.click(screen.getByLabelText("Toàn công ty/tập đoàn"));
    let params = new URLSearchParams(router.state.location.search);
    expect(params.getAll("scope")).toEqual(["REGION", "COMPANY"]);
    expect(params.getAll("function")).toEqual(["IT", "ENGINEERING"]);
    expect(params.getAll("province")).toEqual(["Hà Nội", "Hồ Chí Minh"]);
    await user.click(screen.getByLabelText("Kỹ thuật"));
    params = new URLSearchParams(router.state.location.search);
    expect(params.getAll("function")).toEqual(["IT"]);
    await act(() => router.navigate(-1));
    expect(screen.getByLabelText("Kỹ thuật")).toBeChecked();
    const savedUrl =
      router.state.location.pathname + router.state.location.search;
    await act(() => router.navigate("/candidates"));
    await act(() => router.navigate(savedUrl));
    expect(screen.getByLabelText("Kỹ thuật")).toBeChecked();
    expect(screen.getByLabelText("Số năm tối đa")).toHaveValue("6");
  });

  it("switches list to search and back, keeping legacy list pagination", async () => {
    const listRequests: string[] = [];
    const searches: string[] = [];
    server.use(
      http.get("/api/candidates", ({ request }) => {
        listRequests.push(request.url);
        return HttpResponse.json({
          content: [
            {
              id: 90,
              version: 1,
              fullName: "Synthetic List Only",
              basicState: "UNVERIFIED",
              importedAt: "2026-11-01T00:00:00Z",
            },
          ],
          hasNext: !new URL(request.url).searchParams.has("beforeId"),
          nextBeforeId: 90,
        });
      }),
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(request.url);
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { user, router } = renderPage();
    await screen.findByRole("link", { name: "Synthetic List Only" });
    expect(searches).toHaveLength(0);
    await user.click(screen.getByRole("button", { name: "Trang tiếp" }));
    await waitFor(() =>
      expect(new URL(listRequests[1]).searchParams.get("beforeId")).toBe("90"),
    );
    await user.type(screen.getByLabelText("Từ khóa"), "cv");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(
      screen.queryByRole("link", { name: "Synthetic List Only" }),
    ).toBeNull();
    expect(new URLSearchParams(router.state.location.search).get("q")).toBe(
      "cv",
    );
    expect(listRequests).toHaveLength(2);
    await user.click(screen.getByRole("button", { name: "Xóa lọc" }));
    await screen.findByRole("link", { name: "Synthetic List Only" });
    expect(router.state.location.search).toBe("");
    expect(screen.getByRole("button", { name: "Trang tiếp" })).toBeEnabled();
  });

  it("debounces company suggestions, selects an ID, then sends free text on edit", async () => {
    const suggestions: string[] = [];
    const searches: string[] = [];
    server.use(
      http.get("/api/candidates/search/companies", ({ request }) => {
        suggestions.push(new URL(request.url).searchParams.get("q")!);
        return HttpResponse.json(candidateCompanySuggestions);
      }),
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(new URL(request.url).searchParams.get("company")!);
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { user, router } = renderPage();
    const company = screen.getByLabelText("Công ty", { exact: true });
    await user.type(company, "Syn");
    expect(suggestions).toEqual([]);
    await user.click(
      await screen.findByRole("button", {
        name: "Synthetic Logistics (Synthetic Group)",
      }),
    );
    expect(suggestions).toEqual(["Syn"]);
    expect(
      new URLSearchParams(router.state.location.search).get("company"),
    ).toBe("22");
    expect(
      new URLSearchParams(router.state.location.search).get("companyName"),
    ).toBe("Synthetic Logistics");
    expect(company).toHaveValue("Synthetic Logistics");
    await waitFor(() => expect(searches).toContain("22"));
    await user.clear(company);
    await user.type(company, "Other");
    expect(company).toHaveValue("Other");
    await waitFor(() => expect(searches).toContain("Other"));
    expect(
      new URLSearchParams(router.state.location.search).get("company"),
    ).toBe("Other");
    expect(
      new URLSearchParams(router.state.location.search).has("companyName"),
    ).toBe(false);
  });

  it("restores the selected company name from a shared URL while sending only the ID", async () => {
    const searches: URL[] = [];
    server.use(
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(new URL(request.url));
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { router, user } = renderPage(
      "/candidates?company=22&companyName=Synthetic+Logistics",
    );
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(
      new URLSearchParams(router.state.location.search).get("company"),
    ).toBe("22");
    expect(screen.getByLabelText("Công ty", { exact: true })).toHaveValue(
      "Synthetic Logistics",
    );
    expect(searches[0].searchParams.get("company")).toBe("22");
    expect(searches[0].searchParams.has("companyName")).toBe(false);
    expect(screen.queryByText("22")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Bỏ chọn công ty" }));
    expect(
      new URLSearchParams(router.state.location.search).has("company"),
    ).toBe(false);
    expect(
      new URLSearchParams(router.state.location.search).has("companyName"),
    ).toBe(false);
  });

  it("keeps a typed company name with digits intact and sends it as free text", async () => {
    const searches: URL[] = [];
    server.use(
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(new URL(request.url));
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { router, user } = renderPage();
    const input = screen.getByLabelText("Công ty", { exact: true });
    await user.type(input, "24h");
    expect(input).toHaveValue("24h");
    expect(searches).toHaveLength(0);
    await waitFor(() => expect(searches).toHaveLength(1));
    expect(searches[0].searchParams.get("company")).toBe("24h");
    expect(
      new URLSearchParams(router.state.location.search).has("companyName"),
    ).toBe(false);
  });

  it("keeps all-numeric free text visible and requires catalog selection instead of searching it as an ID", async () => {
    const searches: URL[] = [];
    server.use(
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(new URL(request.url));
        return HttpResponse.json(candidateSearchPage);
      }),
      http.get("/api/candidates/search/companies", () =>
        HttpResponse.json([{ id: 42, name: "1080", parentName: null }]),
      ),
    );
    const { router, user } = renderPage();
    const input = screen.getByLabelText("Công ty", { exact: true });
    await user.type(input, "1080");
    expect(input).toHaveValue("1080");
    expect(screen.getByText(/Tên công ty chỉ có chữ số/)).toBeVisible();
    const option = await screen.findByRole("button", {
      name: "1080",
    });
    await waitFor(() =>
      expect(
        new URLSearchParams(router.state.location.search).get("company"),
      ).toBe("1080"),
    );
    expect(searches).toHaveLength(0);
    await user.click(option);
    await waitFor(() => expect(searches).toHaveLength(1));
    expect(searches[0].searchParams.get("company")).toBe("42");
    expect(searches[0].searchParams.has("companyName")).toBe(false);
    expect(input).toHaveValue("1080");
    expect(screen.queryByText(/Tên công ty chỉ có chữ số/)).toBeNull();
  });

  it("debounces typing to one search and replaces history while preserving back navigation", async () => {
    const searches: URL[] = [];
    server.use(
      http.get("/api/candidates/search", ({ request }) => {
        searches.push(new URL(request.url));
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { router, user } = renderPage();
    await act(() => router.navigate("/candidates?q=seed"));
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    const input = screen.getByLabelText("Từ khóa");
    await user.clear(input);
    await user.type(input, "keyword");
    expect(input).toHaveValue("keyword");
    expect(new URLSearchParams(router.state.location.search).get("q")).toBe(
      "seed",
    );
    expect(searches).toHaveLength(1);
    await waitFor(() => expect(searches).toHaveLength(2));
    expect(searches[1].searchParams.get("q")).toBe("keyword");
    expect(router.state.historyAction).toBe("REPLACE");
    await act(() => router.navigate(-1));
    expect(router.state.location.search).toBe("");
    expect(input).toHaveValue("");
  });

  it.each([
    ["Chức danh", "title", "Engineer"],
    ["Số năm tối thiểu", "minYears", "12"],
    ["Số năm tối đa", "maxYears", "24"],
  ])(
    "updates %s locally before committing it to the URL",
    async (label, name, value) => {
      const searches: URL[] = [];
      server.use(
        http.get("/api/candidates/search", ({ request }) => {
          searches.push(new URL(request.url));
          return HttpResponse.json(candidateSearchPage);
        }),
      );
      const { router, user } = renderPage();
      const input = screen.getByLabelText(label, { exact: true });
      await user.type(input, value);
      expect(input).toHaveValue(value);
      expect(new URLSearchParams(router.state.location.search).has(name)).toBe(
        false,
      );
      expect(searches).toHaveLength(0);
      await waitFor(() => expect(searches).toHaveLength(1));
      expect(searches[0].searchParams.get(name)).toBe(value);
      expect(router.state.historyAction).toBe("REPLACE");
    },
  );

  it("commits pending typing with checkbox selection and cancels it on clear", async () => {
    const { router, user } = renderPage();
    await user.type(screen.getByLabelText("Từ khóa"), "pending");
    await user.click(await screen.findByLabelText("Kỹ thuật"));
    expect(new URLSearchParams(router.state.location.search).get("q")).toBe(
      "pending",
    );
    expect(
      new URLSearchParams(router.state.location.search).getAll("function"),
    ).toEqual(["ENGINEERING"]);
    await user.type(
      screen.getByLabelText("Chức danh", { exact: true }),
      "draft",
    );
    await user.click(screen.getByRole("button", { name: "Xóa lọc" }));
    await screen.findByRole("button", { name: "Trang tiếp" });
    expect(router.state.location.search).toBe("");
    expect(screen.getByLabelText("Chức danh", { exact: true })).toHaveValue("");
    // A later edit must not resurrect a pending draft from before the reset.
    await user.type(screen.getByLabelText("Từ khóa"), "new");
    await waitFor(() =>
      expect(new URLSearchParams(router.state.location.search).get("q")).toBe(
        "new",
      ),
    );
    expect(new URLSearchParams(router.state.location.search).has("title")).toBe(
      false,
    );
  });

  it("shows loading or safe labels, including missing and unknown taxonomy options", async () => {
    server.use(
      http.get("/api/candidates/search/options", async () => {
        await delay(100);
        return HttpResponse.json({
          ...candidateSearchOptions,
          FUNCTION: [
            { code: "IT", label: "" },
            { code: "ENGINEERING", label: "Kỹ thuật" },
          ],
        });
      }),
    );
    const { user } = renderPage(
      "/candidates?function=IT&function=ENGINEERING&seniority=FUTURE_LEVEL",
    );
    expect(screen.getAllByText("Đang tải…")).toHaveLength(5);
    expect(screen.queryByText(/Một lựa chọn chưa có nhãn/)).toBeNull();
    for (const code of ["IT", "ENGINEERING", "FUTURE_LEVEL"])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
    expect(await screen.findByLabelText("Kỹ thuật")).toBeChecked();
    expect(screen.getAllByText(/Một lựa chọn chưa có nhãn/)).toHaveLength(2);
    for (const code of ["IT", "ENGINEERING", "FUTURE_LEVEL"])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Xóa lọc" }));
    expect(screen.getByLabelText("Kỹ thuật")).not.toBeChecked();
  });

  it("stops showing loading hints when options fail and never displays raw selected codes", async () => {
    server.use(
      http.get("/api/candidates/search/options", () =>
        HttpResponse.json(
          {
            code: "OPTIONS_UNAVAILABLE",
            message: "synthetic raw error",
            correlationId: null,
            details: [],
          },
          { status: 503 },
        ),
      ),
    );
    renderPage("/candidates?function=UNKNOWN_FUNCTION&scope=UNKNOWN_SCOPE");
    expect(await screen.findByRole("alert")).toBeVisible();
    expect(screen.getAllByText("Không tải được lựa chọn.")).toHaveLength(5);
    expect(screen.queryByText("Đang tải…")).toBeNull();
    expect(screen.queryByText(/Một lựa chọn chưa có nhãn/)).toBeNull();
    for (const code of [
      "UNKNOWN_FUNCTION",
      "UNKNOWN_SCOPE",
      "synthetic raw error",
    ])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
  });

  it.each([
    "minYears=5&maxYears=2",
    "minYears=-1",
    "maxYears=1.5",
    "minYears=abc",
  ])("blocks invalid experience filters: %s", async (filters) => {
    let calls = 0;
    server.use(
      http.get("/api/candidates/search", () => {
        calls++;
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    renderPage("/candidates?" + filters);
    expect(screen.getByRole("alert")).toHaveTextContent(/Số năm/);
    expect(screen.getByLabelText("Số năm tối thiểu")).toHaveAttribute(
      "aria-invalid",
      "true",
    );
    await screen.findByLabelText("Kỹ thuật");
    expect(calls).toBe(0);
  });

  it("starts searching once an invalid range is corrected", async () => {
    const { user } = renderPage("/candidates?minYears=5&maxYears=2");
    await user.clear(screen.getByLabelText("Số năm tối đa"));
    await user.type(screen.getByLabelText("Số năm tối đa"), "6");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(screen.queryByRole("alert")).toBeNull();
  });
});

describe("candidate search results", () => {
  it("preserves API order and filters, and sends the opaque next cursor only on load more", async () => {
    const requests: URL[] = [];
    server.use(
      http.get("/api/candidates/search", ({ request }) => {
        const url = new URL(request.url);
        requests.push(url);
        return HttpResponse.json(
          url.searchParams.has("cursor")
            ? candidateSearchNextPage
            : candidateSearchPage,
        );
      }),
    );
    const { user, router } = renderPage(
      "/candidates?q=ky+su&function=IT&function=ENGINEERING&minYears=0",
    );
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(requests).toHaveLength(1);
    expect(requests[0].searchParams.has("cursor")).toBe(false);
    const table = screen.getByRole("table");
    expect(
      within(table)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Synthetic An Nguyen", "Synthetic Binh Tran"]);
    await user.click(screen.getByRole("button", { name: "Tải thêm" }));
    await screen.findByRole("link", { name: "Ứng viên #3" });
    expect(requests).toHaveLength(2);
    expect(requests[1].searchParams.get("cursor")).toBe(
      candidateSearchPage.nextCursor,
    );
    expect(requests[1].searchParams.getAll("function")).toEqual([
      "IT",
      "ENGINEERING",
    ]);
    expect(requests[1].searchParams.get("q")).toBe("ky su");
    expect(requests[1].searchParams.get("minYears")).toBe("0");
    expect(
      within(table)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Synthetic An Nguyen", "Synthetic Binh Tran", "Ứng viên #3"]);
    expect(screen.queryByRole("button", { name: "Tải thêm" })).toBeNull();
    await user.type(screen.getByLabelText("Chức danh", { exact: true }), "x");
    await waitFor(() => expect(requests).toHaveLength(3));
    expect(requests[2].searchParams.has("cursor")).toBe(false);
    await waitFor(() =>
      expect(screen.queryByRole("link", { name: "Ứng viên #3" })).toBeNull(),
    );
    expect(
      new URLSearchParams(router.state.location.search).has("cursor"),
    ).toBe(false);
  });

  it("keeps the previous results visible while a new filter is loading", async () => {
    let releaseResponse = () => {};
    const responseReady = new Promise<void>((resolve) => {
      releaseResponse = resolve;
    });
    const searches: URL[] = [];
    server.use(
      http.get("/api/candidates/search", async ({ request }) => {
        const url = new URL(request.url);
        searches.push(url);
        if (url.searchParams.get("q") === "after") {
          await responseReady;
          return HttpResponse.json({
            content: [
              {
                ...candidateSearchPage.content[0],
                fullName: "Synthetic Updated Result",
              },
            ],
            hasNext: false,
            nextCursor: null,
          });
        }
        return HttpResponse.json(candidateSearchPage);
      }),
    );
    const { user } = renderPage("/candidates?q=before");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    await user.clear(screen.getByLabelText("Từ khóa"));
    await user.type(screen.getByLabelText("Từ khóa"), "after");
    try {
      await waitFor(() => expect(searches).toHaveLength(2));
      expect(
        screen.getByRole("link", { name: "Synthetic An Nguyen" }),
      ).toBeVisible();
      expect(screen.queryByText("Đang tải dữ liệu…")).toBeNull();
      expect(screen.queryByRole("button", { name: "Tải thêm" })).toBeNull();
    } finally {
      releaseResponse();
    }
    await screen.findByRole("link", { name: "Synthetic Updated Result" });
  });

  it("keeps the first occurrence when two search pages contain the same candidate ID", async () => {
    server.use(
      http.get("/api/candidates/search", ({ request }) =>
        HttpResponse.json(
          new URL(request.url).searchParams.has("cursor")
            ? {
                ...candidateSearchNextPage,
                content: [
                  {
                    ...candidateSearchPage.content[0],
                    fullName: "Synthetic Duplicate Later",
                  },
                  ...candidateSearchNextPage.content,
                ],
              }
            : candidateSearchPage,
        ),
      ),
    );
    const { user } = renderPage("/candidates?q=duplicate");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    await user.click(screen.getByRole("button", { name: "Tải thêm" }));
    await screen.findByRole("link", { name: "Ứng viên #3" });
    expect(
      within(screen.getByRole("table"))
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Synthetic An Nguyen", "Synthetic Binh Tran", "Ứng viên #3"]);
    expect(
      screen.queryByRole("link", { name: "Synthetic Duplicate Later" }),
    ).toBeNull();
  });

  it("renders parent match reasons, AI badges beside each normalized value and estimated experience", async () => {
    renderPage("/candidates?q=cv");
    const link = await screen.findByRole("link", {
      name: "Synthetic An Nguyen",
    });
    const row = within(link.closest("tr")!);
    expect(
      row.getByText(/Từng làm tại Synthetic Group \(tập đoàn mẹ\)/),
    ).toBeVisible();
    expect(row.getAllByText("AI đề xuất")).toHaveLength(2);
    expect(row.getByText("Kỹ sư phần mềm").closest("td")).toHaveTextContent(
      "AI đề xuất",
    );
    expect(
      row.getByText("Synthetic Logistics (Synthetic Group)").closest("td"),
    ).toHaveTextContent("AI đề xuất");
    expect(row.getByText("~4 năm")).toHaveAttribute(
      "title",
      estimatedExperienceHint,
    );
    expect(screen.getByText("~8 tháng")).toHaveAttribute(
      "title",
      estimatedExperienceHint,
    );
    const secondRow = screen
      .getByRole("link", { name: "Synthetic Binh Tran" })
      .closest("tr")!;
    expect(within(secondRow).queryByText("AI đề xuất")).toBeNull();
    expect(row.queryByText("640")).toBeNull();
    expect(
      screen.getByRole("columnheader", { name: "Kinh nghiệm (dự tính)" }),
    ).toHaveAttribute("title", estimatedExperienceHint);
  });

  it("handles empty search results", async () => {
    server.use(
      http.get("/api/candidates/search", () =>
        HttpResponse.json({ content: [], nextCursor: null, hasNext: false }),
      ),
    );
    renderPage("/candidates?q=empty");
    await screen.findByText("Không tìm thấy ứng viên phù hợp.");
    expect(screen.queryByRole("button", { name: "Tải thêm" })).toBeNull();
  });

  it("shows API 400 via ErrorNotice without rendering the raw message", async () => {
    server.use(
      http.get("/api/candidates/search", () =>
        HttpResponse.json(
          {
            code: "VALIDATION_ERROR",
            message: "synthetic raw error",
            correlationId: null,
            details: [{ field: "function" }],
          },
          { status: 400 },
        ),
      ),
    );
    renderPage("/candidates?function=INVALID_CODE");
    expect(await screen.findByRole("alert")).toHaveTextContent(
      "Dữ liệu gửi chưa hợp lệ.",
    );
    expect(screen.queryByText("synthetic raw error")).toBeNull();
  });
});

describe("search response boundaries and labels", () => {
  it("decodes all three responses using safe IDs and validates required contract fields", () => {
    expect(
      searchPageSchema.parse(candidateSearchPage).content[0].currentTitle?.id,
    ).toBe("11");
    expect(
      companySuggestionsSchema.parse(candidateCompanySuggestions)[0].id,
    ).toBe("22");
    expect(searchOptionsSchema.parse(candidateSearchOptions)).toEqual(
      candidateSearchOptions,
    );
    expect(
      companySuggestionsSchema.safeParse([
        { ...candidateCompanySuggestions[0], id: Number.MAX_SAFE_INTEGER + 1 },
      ]).success,
    ).toBe(false);
    expect(
      searchOptionsSchema.safeParse({
        ...candidateSearchOptions,
        SCOPE: [{ code: "COMPANY" }],
      }).success,
    ).toBe(false);
    for (const patch of [
      { totalExperienceMonths: -1 },
      { experienceEstimated: false },
      { currentCompany: { id: 2, name: "Synthetic", aiNormalized: true } },
    ])
      expect(
        searchPageSchema.safeParse({
          ...candidateSearchPage,
          content: [{ ...candidateSearchPage.content[0], ...patch }],
        }).success,
      ).toBe(false);
  });
  it.each([
    [null, "—"],
    [0, "~0 tháng"],
    [11, "~11 tháng"],
    [12, "~1 năm"],
    [59, "~4 năm"],
  ])("formats estimated months %s", (months, label) => {
    expect(estimatedExperience(months as number | null)).toBe(label);
  });
  it.each([
    ["COMPANY_DIRECT", "Từng làm tại Synthetic"],
    ["COMPANY_PARENT", "Từng làm tại Synthetic (tập đoàn mẹ)"],
    ["COMPANY_CURRENT", "Đang làm tại công ty khớp"],
    ["COMPANY_TEXT", "Tên công ty có trong hồ sơ"],
    ["TITLE_CATALOG", "Chức danh khớp"],
    ["TITLE_CURRENT", "Chức danh hiện tại khớp"],
    ["TITLE_TEXT", "Chức danh có trong hồ sơ"],
    ["KEYWORD_TEXT", "Khớp từ khóa"],
    ["KEYWORD_FUZZY", "Gần đúng từ khóa"],
    ["UNKNOWN_REASON", undefined],
  ])("labels reason %s", (reason, label) =>
    expect(matchReasonLabel(reason!, "Synthetic")).toBe(label),
  );
  it("omits company-specific reasons if there is no matched company", () => {
    expect(matchReasonLabel("COMPANY_DIRECT", null)).toBeUndefined();
    expect(matchReasonLabel("COMPANY_PARENT", null)).toBeUndefined();
  });
  it("keeps repeated filters and excludes pagination and unrelated parameters", () => {
    expect(
      searchFilters(
        new URLSearchParams(
          "q=+&function=IT&function=ENGINEERING&beforeId=3&cursor=opaque&debug=1&companyName=Synthetic",
        ),
      ).toString(),
    ).toBe("function=IT&function=ENGINEERING");
    expect(
      experienceFilterError(new URLSearchParams("minYears=0&maxYears=0")),
    ).toBeUndefined();
  });
});
