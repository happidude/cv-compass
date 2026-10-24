import { defineConfig } from "@playwright/test";
import { testOrigins } from "./test-origins";
const [mockOrigin, networkOrigin] = await testOrigins(2);
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
      use: { baseURL: mockOrigin },
    },
    {
      name: "http-scenarios",
      testMatch: "network.spec.ts",
      use: { baseURL: networkOrigin, serviceWorkers: "block" },
    },
  ],
  webServer: [
    {
      command: `npm run dev -- --port ${new URL(mockOrigin).port}`,
      url: mockOrigin,
      reuseExistingServer: false,
      env: {
        VITE_MOCK: "true",
        VITE_POLL_INTERVAL_MS: "500",
        VITE_POLL_MAX_MS: "5000",
      },
    },
    {
      command: `npm run dev -- --port ${new URL(networkOrigin).port}`,
      url: networkOrigin,
      reuseExistingServer: false,
      env: {
        VITE_MOCK: "false",
        API_PROXY_TARGET: mockOrigin,
        VITE_POLL_INTERVAL_MS: "500",
        VITE_POLL_MAX_MS: "5000",
      },
    },
  ],
});
