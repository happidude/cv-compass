import { act, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { createMemoryRouter, RouterProvider } from "react-router-dom";
import { afterAll, afterEach, beforeAll, describe, expect, it } from "vitest";
import { delay, http, HttpResponse } from "msw";
import { setupServer } from "msw/node";
import { CandidatesPage } from "../src/features/candidates/list-page";
import { SearchPage } from "../src/features/candidates/search-page";
import {
  legacySearchRedirect,
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

const guidance = "Nhập từ khóa hoặc chọn bộ lọc để tìm ứng viên";
const pendingHint = "Bấm Tìm kiếm để áp dụng từ khóa";

function renderPage(url = "/search") {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: Infinity } },
  });
  clients.push(client);
  const router = createMemoryRouter(
    [
      { path: "/candidates", element: <CandidatesPage /> },
      { path: "/search", element: <SearchPage /> },
    ],
    { initialEntries: [url] },
  );
  const view = render(
    <QueryClientProvider client={client}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  );
  return { ...view, router, user: userEvent.setup() };
}
/** Records every search and list request the page makes. */
function recordRequests() {
  const searches: URL[] = [];
  const lists: URL[] = [];
  server.use(
    http.get("/api/candidates/search", ({ request }) => {
      searches.push(new URL(request.url));
      return HttpResponse.json(candidateSearchPage);
    }),
    http.get("/api/candidates", ({ request }) => {
      lists.push(new URL(request.url));
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
  );
  return { searches, lists };
}
const urlParams = (router: ReturnType<typeof renderPage>["router"]) =>
  new URLSearchParams(router.state.location.search);
const companyInput = () => screen.getByRole("combobox", { name: "Công ty" });
async function openGroup(
  user: ReturnType<typeof userEvent.setup>,
  label: string,
) {
  const trigger = screen.getByRole("button", { name: new RegExp(`^${label}`) });
  if (trigger.getAttribute("aria-expanded") !== "true")
    await user.click(trigger);
  return trigger;
}
const settle = () => act(() => delay(450));

describe("search route and legacy links", () => {
  it("shows guidance and calls neither the search nor the list API without conditions", async () => {
    const { searches, lists } = recordRequests();
    renderPage("/search");
    expect(screen.getByText(guidance)).toBeVisible();
    expect(
      screen.getByRole("heading", { level: 1, name: "Tìm kiếm & xếp hạng" }),
    ).toBeVisible();
    await screen.findByRole("button", { name: /^Nhóm nghề/ });
    await settle();
    expect(searches).toHaveLength(0);
    expect(lists).toHaveLength(0);
    expect(screen.queryByRole("table")).toBeNull();
  });

  it("keeps /candidates as the plain list without a search form", async () => {
    const { searches, lists } = recordRequests();
    const { user } = renderPage("/candidates");
    await screen.findByRole("link", { name: "Synthetic List Only" });
    expect(screen.queryByRole("search")).toBeNull();
    expect(screen.queryByRole("button", { name: "Tìm kiếm" })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Trang tiếp" }));
    await waitFor(() =>
      expect(lists[1]?.searchParams.get("beforeId")).toBe("90"),
    );
    expect(searches).toHaveLength(0);
  });

  it("replaces an old /candidates filter link with /search, dropping the retired experience range", async () => {
    const { searches } = recordRequests();
    const { router } = renderPage(
      "/candidates?q=ky+su&function=IT&function=ENGINEERING&companyId=22&companyName=Synthetic+Logistics&minYears=1&maxYears=6&beforeId=3",
    );
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(router.state.location.pathname).toBe("/search");
    expect(router.state.historyAction).toBe("REPLACE");
    expect(router.state.location.search).toBe(
      "?q=ky+su&companyId=22&companyName=Synthetic+Logistics&function=IT&function=ENGINEERING",
    );
    expect(searches).toHaveLength(1);
    expect(searches[0].searchParams.has("minYears")).toBe(false);
    expect(searches[0].searchParams.has("maxYears")).toBe(false);
    expect(searches[0].searchParams.has("companyName")).toBe(false);
    expect(screen.getByLabelText("Từ khóa")).toHaveValue("ky su");
    expect(companyInput()).toHaveValue("Synthetic Logistics");
  });

  it.each(["minYears=5&maxYears=2", "minYears=abc"])(
    "ignores retired experience filters on /search without an error: %s",
    async (query) => {
      const { searches } = recordRequests();
      renderPage("/search?" + query);
      expect(screen.getByText(guidance)).toBeVisible();
      expect(screen.queryByRole("alert")).toBeNull();
      expect(screen.queryByLabelText(/Số năm/)).toBeNull();
      await settle();
      expect(searches).toHaveLength(0);
    },
  );

  it("sends a search beside retired filters without them", async () => {
    const { searches } = recordRequests();
    renderPage("/search?q=seed&minYears=1&maxYears=4");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(searches[0].searchParams.toString()).toBe("q=seed");
  });
});

describe("text filters apply only on submit", () => {
  it("does not search while typing; the button and Enter apply the text", async () => {
    const { searches } = recordRequests();
    const { router, user } = renderPage();
    const keyword = screen.getByLabelText("Từ khóa");
    await user.type(keyword, "keyword");
    expect(screen.getByText(pendingHint)).toBeVisible();
    await settle();
    expect(searches).toHaveLength(0);
    expect(router.state.location.search).toBe("");
    expect(screen.getByText(guidance)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Tìm kiếm" }));
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(searches).toHaveLength(1);
    expect(searches[0].searchParams.get("q")).toBe("keyword");
    expect(screen.queryByText(pendingHint)).toBeNull();
    await user.type(
      screen.getByLabelText("Chức danh", { exact: true }),
      "Engineer{Enter}",
    );
    await waitFor(() => expect(searches).toHaveLength(2));
    expect(searches[1].searchParams.get("title")).toBe("Engineer");
    expect(searches[1].searchParams.get("q")).toBe("keyword");
    expect(urlParams(router).get("title")).toBe("Engineer");
    expect(router.state.historyAction).toBe("PUSH");
    await act(() => router.navigate(-1));
    expect(screen.getByLabelText("Chức danh", { exact: true })).toHaveValue("");
    expect(keyword).toHaveValue("keyword");
  });

  it("applies a checkbox at once without the text still being typed", async () => {
    const { searches } = recordRequests();
    const { router, user } = renderPage("/search?title=Engineer");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    const keyword = screen.getByLabelText("Từ khóa");
    await user.type(keyword, "pending");
    await openGroup(user, "Nhóm nghề");
    await user.click(screen.getByRole("checkbox", { name: "Kỹ thuật" }));
    expect(urlParams(router).getAll("function")).toEqual(["ENGINEERING"]);
    expect(urlParams(router).has("q")).toBe(false);
    await waitFor(() => expect(searches).toHaveLength(2));
    expect(searches[1].searchParams.getAll("function")).toEqual([
      "ENGINEERING",
    ]);
    expect(searches[1].searchParams.get("title")).toBe("Engineer");
    expect(searches[1].searchParams.has("q")).toBe(false);
    expect(keyword).toHaveValue("pending");
    expect(screen.getByText(pendingHint)).toBeVisible();
    await user.click(screen.getByRole("button", { name: "Tìm kiếm" }));
    await waitFor(() => expect(searches).toHaveLength(3));
    expect(searches[2].searchParams.get("q")).toBe("pending");
    expect(searches[2].searchParams.getAll("function")).toEqual([
      "ENGINEERING",
    ]);
  });

  it("loads every filter from the URL and keeps repeated values when a group changes", async () => {
    const { router, user } = renderPage(
      "/search?q=ky+su&company=Synthetic&title=Engineer&function=IT&function=ENGINEERING&seniority=SENIOR&scope=REGION&industry=LOGISTICS&province=H%C3%A0+N%E1%BB%99i&province=H%E1%BB%93+Ch%C3%AD+Minh",
    );
    expect(screen.getByLabelText("Từ khóa")).toHaveValue("ky su");
    expect(companyInput()).toHaveValue("Synthetic");
    expect(screen.getByLabelText("Chức danh", { exact: true })).toHaveValue(
      "Engineer",
    );
    await screen.findByText("Nhóm nghề: Kỹ thuật");
    for (const [group, count] of [
      ["Nhóm nghề", "2"],
      ["Cấp bậc", "1"],
      ["Phạm vi", "1"],
      ["Ngành", "1"],
      ["Tỉnh/thành", "2"],
    ])
      expect(
        screen.getByRole("button", {
          name: `${group}, đã chọn ${count}`,
        }),
      ).toBeVisible();
    await openGroup(user, "Phạm vi");
    await user.click(
      screen.getByRole("checkbox", { name: "Toàn công ty/tập đoàn" }),
    );
    expect(urlParams(router).getAll("scope")).toEqual(["REGION", "COMPANY"]);
    expect(urlParams(router).getAll("function")).toEqual(["IT", "ENGINEERING"]);
    expect(urlParams(router).getAll("province")).toEqual([
      "Hà Nội",
      "Hồ Chí Minh",
    ]);
    await openGroup(user, "Nhóm nghề");
    await user.click(screen.getByRole("checkbox", { name: "Kỹ thuật" }));
    expect(urlParams(router).getAll("function")).toEqual(["IT"]);
    await act(() => router.navigate(-1));
    expect(screen.getByRole("checkbox", { name: "Kỹ thuật" })).toBeChecked();
  });
});

describe("company combobox", () => {
  it("debounces suggestions, works from the keyboard and applies a pick at once", async () => {
    const suggestions: string[] = [];
    const { searches } = recordRequests();
    server.use(
      http.get("/api/candidates/search/companies", ({ request }) => {
        suggestions.push(new URL(request.url).searchParams.get("q")!);
        return HttpResponse.json(candidateCompanySuggestions.slice(0, 2));
      }),
    );
    const { router, user } = renderPage();
    await user.type(screen.getByLabelText("Từ khóa"), "pending");
    const input = companyInput();
    await user.type(input, "Syn");
    expect(suggestions).toEqual([]);
    expect(input).toHaveAttribute("aria-expanded", "false");
    const listbox = await screen.findByRole("listbox", {
      name: "Gợi ý công ty",
    });
    expect(suggestions).toEqual(["Syn"]);
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(input).toHaveAttribute("aria-controls", listbox.id);
    const options = within(listbox).getAllByRole("option");
    expect(options.map((option) => option.textContent)).toEqual([
      "Synthetic Logistics (Synthetic Group)",
      "Synthetic Group",
    ]);
    await user.keyboard("{ArrowDown}{ArrowDown}");
    expect(input).toHaveAttribute("aria-activedescendant", options[1].id);
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    await user.keyboard("{ArrowDown}");
    expect(input).toHaveAttribute("aria-activedescendant", options[0].id);
    await user.keyboard("{ArrowUp}");
    expect(input).toHaveAttribute("aria-activedescendant", options[1].id);
    await user.keyboard("{Escape}");
    expect(input).toHaveAttribute("aria-expanded", "false");
    expect(input).not.toHaveAttribute("aria-activedescendant");
    await user.keyboard("{ArrowDown}");
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(input).toHaveAttribute(
      "aria-activedescendant",
      within(screen.getByRole("listbox")).getAllByRole("option")[0].id,
    );
    await user.keyboard("{Enter}");
    expect(urlParams(router).get("companyId")).toBe("22");
    expect(urlParams(router).get("companyName")).toBe("Synthetic Logistics");
    expect(urlParams(router).has("company")).toBe(false);
    // Only the pick is applied; the typed keyword is still pending.
    expect(urlParams(router).has("q")).toBe(false);
    expect(input).toHaveValue("Synthetic Logistics");
    expect(input).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByText("Đã chọn công ty từ danh mục.")).toBeVisible();
    await waitFor(() => expect(searches).toHaveLength(1));
    expect(searches[0].searchParams.toString()).toBe("companyId=22");
  });

  it("selects a suggestion with the mouse and turns later edits into free text applied on submit", async () => {
    const { searches } = recordRequests();
    const { router, user } = renderPage();
    const input = companyInput();
    await user.type(input, "Syn");
    await user.click(
      await screen.findByRole("option", {
        name: "Synthetic Logistics (Synthetic Group)",
      }),
    );
    await waitFor(() => expect(searches).toHaveLength(1));
    expect(searches[0].searchParams.get("companyId")).toBe("22");
    await user.clear(input);
    await user.type(input, "1080");
    expect(input).toHaveValue("1080");
    expect(screen.queryByText("Đã chọn công ty từ danh mục.")).toBeNull();
    expect(urlParams(router).get("companyId")).toBe("22");
    await user.click(screen.getByRole("button", { name: "Tìm kiếm" }));
    await waitFor(() => expect(searches).toHaveLength(2));
    expect(searches[1].searchParams.get("company")).toBe("1080");
    expect(searches[1].searchParams.has("companyId")).toBe(false);
    expect(urlParams(router).get("company")).toBe("1080");
    expect(urlParams(router).has("companyId")).toBe(false);
    expect(urlParams(router).has("companyName")).toBe(false);
  });

  it("never opens or fetches suggestions for text it did not see typed, and closes on Enter submit", async () => {
    const suggestions: string[] = [];
    const { searches } = recordRequests();
    server.use(
      http.get("/api/candidates/search/companies", ({ request }) => {
        suggestions.push(new URL(request.url).searchParams.get("q")!);
        return HttpResponse.json(candidateCompanySuggestions.slice(0, 2));
      }),
    );
    const { router, user } = renderPage("/search?company=Synthetic");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    await settle();
    expect(suggestions).toEqual([]);
    expect(companyInput()).toHaveAttribute("aria-expanded", "false");
    await user.type(companyInput(), " Lo");
    await screen.findByRole("listbox", { name: "Gợi ý công ty" });
    expect(suggestions).toEqual(["Synthetic Lo"]);
    await user.keyboard("{Enter}");
    expect(companyInput()).toHaveAttribute("aria-expanded", "false");
    expect(urlParams(router).get("company")).toBe("Synthetic Lo");
    await waitFor(() =>
      expect(searches.at(-1)?.searchParams.get("company")).toBe("Synthetic Lo"),
    );
    // Submitting the same search again adds no history entry.
    const key = router.state.location.key;
    await user.click(screen.getByRole("button", { name: "Tìm kiếm" }));
    expect(router.state.location.key).toBe(key);
    await act(() => router.navigate(-1));
    expect(companyInput()).toHaveValue("Synthetic");
    await settle();
    expect(companyInput()).toHaveAttribute("aria-expanded", "false");
  });

  it("restores a shared selection, sends only the ID and deselects at once", async () => {
    const { searches } = recordRequests();
    const { router, user } = renderPage(
      "/search?companyId=22&companyName=Synthetic+Logistics",
    );
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(companyInput()).toHaveValue("Synthetic Logistics");
    expect(searches[0].searchParams.toString()).toBe("companyId=22");
    expect(screen.queryByText("22")).toBeNull();
    await user.click(screen.getByRole("button", { name: "Bỏ chọn công ty" }));
    expect(router.state.location.search).toBe("");
    expect(companyInput()).toHaveValue("");
    expect(await screen.findByText(guidance)).toBeVisible();
  });

  it.each(["", "abc", "0", "-1", "1.5", "9223372036854775808"])(
    "ignores a display name paired with invalid companyId=%s",
    async (companyId) => {
      const { searches } = recordRequests();
      renderPage(
        "/search?" +
          new URLSearchParams({
            q: "seed",
            companyId,
            companyName: "Synthetic Hidden",
          }),
      );
      expect(companyInput()).toHaveValue("");
      expect(screen.queryByText("Đã chọn công ty từ danh mục.")).toBeNull();
      expect(screen.queryByText(/Synthetic Hidden/)).toBeNull();
      await waitFor(() => expect(searches).toHaveLength(1));
      expect(searches[0].searchParams.toString()).toBe("q=seed");
    },
  );

  it.each(["company=1080&companyName=Stale", "companyName=Stale"])(
    "ignores an orphan display name in an old URL: %s",
    async (query) => {
      const { searches } = recordRequests();
      renderPage("/search?" + query);
      expect(companyInput()).toHaveValue(
        query.startsWith("company=") ? "1080" : "",
      );
      expect(screen.queryByText(/Stale/)).toBeNull();
      if (query.startsWith("company=")) {
        await waitFor(() => expect(searches).toHaveLength(1));
        expect(searches[0].searchParams.toString()).toBe("company=1080");
      } else {
        expect(screen.getByText(guidance)).toBeVisible();
        await settle();
        expect(searches).toHaveLength(0);
      }
    },
  );
});

describe("filter groups, chips and clearing", () => {
  it("shows applied chips, removes one at a time and clears everything back to guidance", async () => {
    const { searches } = recordRequests();
    const { router, user } = renderPage(
      "/search?q=ky+su&companyId=22&companyName=Synthetic+Logistics&title=Engineer&function=IT&province=H%C3%A0+N%E1%BB%99i",
    );
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    expect(screen.getByText("Đang áp dụng 5 bộ lọc")).toBeVisible();
    const chips = screen.getByRole("list", { name: "Bộ lọc đang áp dụng" });
    expect(
      within(chips)
        .getAllByRole("listitem")
        .map((chip) => chip.textContent),
    ).toEqual([
      "Từ khóa: ky su",
      "Công ty: Synthetic Logistics",
      "Chức danh: Engineer",
      "Nhóm nghề: Công nghệ thông tin",
      "Tỉnh/thành: Hà Nội",
    ]);
    await user.click(
      screen.getByRole("button", {
        name: "Bỏ lọc Nhóm nghề: Công nghệ thông tin",
      }),
    );
    expect(urlParams(router).has("function")).toBe(false);
    expect(urlParams(router).get("q")).toBe("ky su");
    expect(urlParams(router).get("companyId")).toBe("22");
    await user.click(
      screen.getByRole("button", { name: "Bỏ lọc Từ khóa: ky su" }),
    );
    expect(urlParams(router).has("q")).toBe(false);
    expect(screen.getByLabelText("Từ khóa")).toHaveValue("");
    expect(screen.getByText("Đang áp dụng 3 bộ lọc")).toBeVisible();
    await waitFor(() =>
      expect(searches.at(-1)?.searchParams.has("q")).toBe(false),
    );
    // A pending draft must not survive or come back after clearing.
    await user.type(
      screen.getByLabelText("Chức danh", { exact: true }),
      " draft",
    );
    await user.click(screen.getByRole("button", { name: "Xóa lọc" }));
    expect(router.state.location.search).toBe("");
    expect(screen.getByText(guidance)).toBeVisible();
    expect(companyInput()).toHaveValue("");
    expect(screen.getByLabelText("Chức danh", { exact: true })).toHaveValue("");
    expect(screen.queryByText(pendingHint)).toBeNull();
    expect(screen.queryByRole("button", { name: "Xóa lọc" })).toBeNull();
    await user.type(screen.getByLabelText("Từ khóa"), "new{Enter}");
    expect(router.state.location.search).toBe("?q=new");
  });

  it("restarts pagination when a filter changes", async () => {
    const { router, user } = renderPage("/search?q=seed&cursor=stale");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    await openGroup(user, "Ngành");
    await user.click(
      screen.getByRole("checkbox", { name: "Sản xuất công nghiệp" }),
    );
    expect(urlParams(router).has("cursor")).toBe(false);
  });

  it("filters a long group by accent-insensitive text and wraps long labels", async () => {
    const { router, user } = renderPage();
    await openGroup(user, "Nhóm nghề");
    expect(screen.queryByLabelText("Lọc nhanh Nhóm nghề")).toBeNull();
    await openGroup(user, "Tỉnh/thành");
    const quick = screen.getByLabelText("Lọc nhanh Tỉnh/thành");
    await user.type(quick, "ha noi{Enter}");
    const panel = within(screen.getByRole("group", { name: "Tỉnh/thành" }));
    expect(
      panel
        .getAllByRole("checkbox")
        .map((box) => box.closest("label")!.textContent),
    ).toEqual(["Hà Nội"]);
    await user.clear(quick);
    await user.type(quick, "zzz");
    expect(panel.getByText("Không có lựa chọn phù hợp.")).toBeVisible();
    // Enter in the quick filter never submitted; reopening starts unfiltered.
    expect(router.state.location.search).toBe("");
    await user.keyboard("{Escape}");
    await openGroup(user, "Tỉnh/thành");
    expect(screen.getByLabelText("Lọc nhanh Tỉnh/thành")).toHaveValue("");
    expect(
      within(screen.getByRole("group", { name: "Tỉnh/thành" })).getAllByRole(
        "checkbox",
      ),
    ).toHaveLength(10);
  });

  it("closes a group popover when focus tabs out of it", async () => {
    const { user } = renderPage();
    const trigger = await openGroup(user, "Phạm vi");
    await user.tab();
    expect(
      screen.getByRole("checkbox", { name: "Toàn công ty/tập đoàn" }),
    ).toHaveFocus();
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    await user.tab();
    await user.tab();
    expect(trigger).toHaveAttribute("aria-expanded", "false");
  });

  it("opens one group at a time; Escape and an outside click close it", async () => {
    const { user } = renderPage();
    const trigger = await openGroup(user, "Cấp bậc");
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    const panel = document.getElementById(
      trigger.getAttribute("aria-controls")!,
    )!;
    expect(panel).toBeVisible();
    await user.click(within(panel).getByRole("checkbox", { name: "Quản lý" }));
    expect(trigger).toHaveAttribute("aria-expanded", "true");
    await user.keyboard("{Escape}");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    expect(trigger).toHaveFocus();
    await openGroup(user, "Cấp bậc");
    const other = await openGroup(user, "Ngành");
    expect(trigger).toHaveAttribute("aria-expanded", "false");
    await user.click(screen.getByRole("heading", { level: 1 }));
    expect(other).toHaveAttribute("aria-expanded", "false");
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
      "/search?function=IT&function=ENGINEERING&seniority=FUTURE_LEVEL",
    );
    await openGroup(user, "Nhóm nghề");
    expect(screen.getByText("Đang tải…")).toBeVisible();
    expect(screen.getAllByText("Đang tải…")).toHaveLength(1);
    expect(screen.getAllByText(/đang tải nhãn/)).toHaveLength(3);
    for (const code of ["IT", "ENGINEERING", "FUTURE_LEVEL"])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
    expect(
      await screen.findByRole("checkbox", { name: "Kỹ thuật" }),
    ).toBeChecked();
    expect(screen.getByText(/Một lựa chọn chưa có nhãn/)).toBeVisible();
    expect(screen.getByText("Nhóm nghề: lựa chọn chưa có nhãn")).toBeVisible();
    expect(screen.getByText("Cấp bậc: lựa chọn chưa có nhãn")).toBeVisible();
    for (const code of ["IT", "ENGINEERING", "FUTURE_LEVEL"])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
    await user.click(screen.getByRole("button", { name: "Xóa lọc" }));
    expect(
      screen.queryByRole("list", { name: "Bộ lọc đang áp dụng" }),
    ).toBeNull();
    await openGroup(user, "Nhóm nghề");
    expect(
      screen.getByRole("checkbox", { name: "Kỹ thuật" }),
    ).not.toBeChecked();
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
    const { user } = renderPage(
      "/search?function=UNKNOWN_FUNCTION&scope=UNKNOWN_SCOPE",
    );
    expect(
      (await screen.findAllByRole("alert")).some((alert) =>
        alert.closest("form"),
      ),
    ).toBe(true);
    await openGroup(user, "Phạm vi");
    expect(screen.getByText("Không tải được lựa chọn.")).toBeVisible();
    expect(screen.queryByText("Đang tải…")).toBeNull();
    for (const code of [
      "UNKNOWN_FUNCTION",
      "UNKNOWN_SCOPE",
      "synthetic raw error",
    ])
      expect(screen.queryByText(code, { exact: true })).toBeNull();
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
      "/search?q=ky+su&function=IT&function=ENGINEERING&minYears=0",
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
    expect(requests[1].searchParams.has("minYears")).toBe(false);
    expect(
      within(table)
        .getAllByRole("link")
        .map((link) => link.textContent),
    ).toEqual(["Synthetic An Nguyen", "Synthetic Binh Tran", "Ứng viên #3"]);
    expect(screen.queryByRole("button", { name: "Tải thêm" })).toBeNull();
    await user.type(
      screen.getByLabelText("Chức danh", { exact: true }),
      "x{Enter}",
    );
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
    const { user } = renderPage("/search?q=before");
    await screen.findByRole("link", { name: "Synthetic An Nguyen" });
    await user.clear(screen.getByLabelText("Từ khóa"));
    await user.type(screen.getByLabelText("Từ khóa"), "after{Enter}");
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
    const { user } = renderPage("/search?q=duplicate");
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
    renderPage("/search?q=cv");
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
    renderPage("/search?q=empty");
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
    renderPage("/search?function=INVALID_CODE");
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
      searchFilters(
        new URLSearchParams("minYears=1&maxYears=5&q=a"),
      ).toString(),
    ).toBe("q=a");
  });
  it.each([
    ["", undefined],
    ["beforeId=3", undefined],
    ["minYears=2", "/search"],
    ["companyName=Stale", "/search?companyName=Stale"],
    [
      "q=a&debug=1&maxYears=4&province=H%C3%A0+N%E1%BB%99i&cursor=x",
      "/search?q=a&province=H%C3%A0+N%E1%BB%99i",
    ],
  ])("maps the old list URL ?%s to %s", (query, target) => {
    expect(legacySearchRedirect(new URLSearchParams(query))).toBe(target);
  });
  it("whitelists companyId, excludes the display name and never sends both company filters", () => {
    expect(
      searchFilters(
        new URLSearchParams("companyId=22&companyName=Synthetic"),
      ).toString(),
    ).toBe("companyId=22");
    expect(
      searchFilters(
        new URLSearchParams("company=1080&companyName=Stale"),
      ).toString(),
    ).toBe("company=1080");
    expect(
      searchFilters(
        new URLSearchParams("company=1080&companyId=22&companyName=Synthetic"),
      ).toString(),
    ).toBe("companyId=22");
  });
});
