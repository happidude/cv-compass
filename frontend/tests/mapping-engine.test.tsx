import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { mappingEngineLabel } from "../src/features/candidates/mapping-engine";
import { CandidatePage } from "../src/features/candidates/profile-page";
import { profileSchema } from "../src/features/candidates/schema";

vi.mock("../src/features/candidates/pdf-pane", () => ({
  PdfPane: () => null,
}));
afterEach(() => vi.unstubAllGlobals());

const profile = {
  id: 1,
  version: 1,
  basic: {},
  basicState: "UNVERIFIED",
  basicOrigin: "MAPPING",
  extensions: {},
  experiences: [],
  documents: [],
};

describe("mappingEngineLabel", () => {
  it("labels the published LLM engine and configuration", () => {
    expect(
      mappingEngineLabel({
        mapping: {
          engineVersion: "llm-extraction/1.0",
          configRevision: "llm-extraction-v1-r1",
          schemaVersion: 1,
          unrelated: "unvalidated envelope data",
        },
      }),
    ).toBe(
      "Trích xuất tự động: LLM (llm-extraction/1.0) · cấu hình llm-extraction-v1-r1",
    );
  });

  it.each(["3.0", "3.1", "3.2", "3.3", "3.4", "3.5"])(
    "labels personal information rules %s without a configuration",
    (version) => {
      expect(
        mappingEngineLabel({
          mapping: { engineVersion: `personal-info-mapping/${version}` },
        }),
      ).toBe(
        `Trích xuất tự động: quy tắc ${version} (personal-info-mapping/${version})`,
      );
    },
  );

  it.each(["experience-mapping/2.0", "literal-mapping/1.0"])(
    "labels the known rules engine %s",
    (engineVersion) => {
      expect(mappingEngineLabel({ mapping: { engineVersion } })).toBe(
        `Trích xuất tự động: quy tắc ${engineVersion.split("/")[1]} (${engineVersion})`,
      );
    },
  );

  it.each([
    "future-engine/2.0_rc-1",
    "llm-extraction/2.0",
    "constructor",
    "a".repeat(64),
  ])("preserves the valid unknown engine %s", (engineVersion) => {
    expect(mappingEngineLabel({ mapping: { engineVersion } })).toBe(
      `Trích xuất tự động: ${engineVersion}`,
    );
  });

  it.each([
    undefined,
    null,
    {},
    { mapping: null },
    { mapping: [] },
    { mapping: "llm-extraction/1.0" },
    { mapping: {} },
    { mapping: { configRevision: "revision-1" } },
    ...[
      null,
      1,
      "",
      "LLM/1",
      " engine/1",
      "engine/1\n",
      "<script>",
      "a".repeat(65),
    ].map((engineVersion) => ({ mapping: { engineVersion } })),
    ...[
      null,
      1,
      "",
      "Revision",
      "bad revision",
      "revision\n",
      "a".repeat(65),
    ].map((configRevision) => ({
      mapping: { engineVersion: "llm-extraction/1.0", configRevision },
    })),
  ])("hides missing or malformed metadata: %j", (extensions) => {
    expect(mappingEngineLabel(extensions)).toBeUndefined();
  });

  it("keeps malformed mapping metadata from breaking profile decoding", () => {
    const extensions = { mapping: { engineVersion: 42, configRevision: [] } };
    expect(profileSchema.parse({ ...profile, extensions }).extensions).toEqual(
      extensions,
    );
  });
});

describe("candidate page mapping engine", () => {
  it.each([
    {
      mapping: {
        engineVersion: "llm-extraction/1.0",
        configRevision: "llm-extraction-v1-r1",
      },
    },
    {},
    { mapping: { engineVersion: "<invalid>" } },
  ])("renders the profile safely for extensions %j", async (extensions) => {
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const url = String(input);
        if (url.endsWith("/proposals") || url.endsWith("/history"))
          return Response.json([]);
        if (url.endsWith("/candidates/1"))
          return Response.json({ ...profile, extensions });
        throw new Error("Unexpected test request");
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
    expect(await screen.findByText("Phiên bản 1")).toBeVisible();
    if ("mapping" in extensions && extensions.mapping?.configRevision) {
      expect(
        screen.getByText(
          "Trích xuất tự động: LLM (llm-extraction/1.0) · cấu hình llm-extraction-v1-r1",
        ),
      ).toBeVisible();
    } else {
      expect(screen.queryByText(/Trích xuất tự động:/)).not.toBeInTheDocument();
    }
  });
});
