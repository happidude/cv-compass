import { defineConfig } from "@playwright/test";
export default defineConfig({
  testDir: "./tests/browser",
  fullyParallel: false,
  workers: 1,
  reporter: [["list"], ["html", { open: "never" }]],
  use: { browserName: "chromium", trace: "retain-on-failure" },
  projects: [
    {
      name: "synthetic-msw",
      testMatch: "frontend.spec.ts",
      use: { baseURL: "http://127.0.0.1:5173" },
    },
    {
      name: "http-scenarios",
      testMatch: "network.spec.ts",
      use: { baseURL: "http://127.0.0.1:5174", serviceWorkers: "block" },
    },
  ],
  webServer: [
    {
      command: "npm run dev",
      url: "http://127.0.0.1:5173",
      reuseExistingServer: false,
      env: {
        VITE_MOCK: "true",
        VITE_POLL_INTERVAL_MS: "500",
        VITE_POLL_MAX_MS: "1500",
      },
    },
    {
      command: "npm run dev -- --port 5174",
      url: "http://127.0.0.1:5174",
      reuseExistingServer: false,
      env: {
        VITE_MOCK: "false",
        VITE_POLL_INTERVAL_MS: "500",
        VITE_POLL_MAX_MS: "1500",
      },
    },
  ],
});
