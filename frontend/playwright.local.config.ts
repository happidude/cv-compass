import { defineConfig } from "@playwright/test";

const origin = process.env.LOCAL_E2E_UI_ORIGIN;
if (!origin)
  throw new Error(
    "LOCAL_E2E_UI_ORIGIN must identify the existing UI with VITE_MOCK=false",
  );
const url = new URL(origin);
if (
  !["http:", "https:"].includes(url.protocol) ||
  url.username ||
  url.password ||
  url.pathname !== "/" ||
  url.search ||
  url.hash
)
  throw new Error(
    "LOCAL_E2E_UI_ORIGIN must be an origin without credentials or path",
  );

export default defineConfig({
  testDir: "./tests/local",
  workers: 1,
  retries: 0,
  timeout: Number(process.env.LOCAL_E2E_TIMEOUT_MS ?? 180000),
  reporter: [["list"], ["junit", { outputFile: ".ci-reports/local-e2e.xml" }]],
  use: { baseURL: origin, browserName: "chromium", serviceWorkers: "block" },
  // Uses the operator's running backend/UI/worker; never starts another backend or database.
});
