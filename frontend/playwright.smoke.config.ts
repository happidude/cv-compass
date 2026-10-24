import { defineConfig } from "@playwright/test";
import { testOrigins } from "./test-origins";
const [uiOrigin] = await testOrigins(1);
const runId = process.env.M9_SMOKE_RUN_ID;
const backendPort = process.env.M9_SMOKE_BACKEND_PORT;
if (
  !runId ||
  !/^[0-9a-f]{32}$/.test(runId) ||
  !backendPort ||
  !/^\d+$/.test(backendPort) ||
  Number(backendPort) < 1 ||
  Number(backendPort) > 65535
)
  throw new Error("An explicitly owned isolated M9 smoke backend is required");
export default defineConfig({
  testDir: "./tests/smoke",
  workers: 1,
  retries: 0,
  reporter: [
    ["list"],
    ["html", { outputFolder: ".ci-reports/smoke-html", open: "never" }],
  ],
  outputDir: ".ci-reports/smoke-results",
  use: {
    baseURL: uiOrigin,
    browserName: "chromium",
    serviceWorkers: "block",
    trace: "retain-on-failure",
  },
  webServer: {
    command: `npm run dev -- --port ${new URL(uiOrigin).port}`,
    url: uiOrigin,
    reuseExistingServer: false,
    env: {
      VITE_MOCK: "false",
      VITE_API_BASE_URL: "/api",
      API_PROXY_TARGET: `http://127.0.0.1:${backendPort}`,
    },
  },
});
