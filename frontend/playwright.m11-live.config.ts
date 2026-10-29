import { defineConfig } from "@playwright/test";
import { readFileSync } from "node:fs";
import { testOrigins } from "./test-origins";
const context = JSON.parse(
  readFileSync("../backend/build/m11/live/ready.json", "utf8"),
) as { port: number };
if (!Number.isInteger(context.port) || context.port < 1 || context.port > 65535)
  throw new Error("Invalid smoke port");
const [origin] = await testOrigins(1);
export default defineConfig({
  testDir: "./tests/m11-live",
  outputDir: ".ci-reports/m11-live/artifacts",
  workers: 1,
  reporter: [["list"]],
  timeout: 60000,
  use: {
    baseURL: origin,
    browserName: "chromium",
    trace: "retain-on-failure",
  },
  webServer: {
    command: `npm run dev -- --port ${new URL(origin).port}`,
    url: origin,
    reuseExistingServer: false,
    env: {
      VITE_MOCK: "false",
      API_PROXY_TARGET: `http://localhost:${context.port}`,
    },
  },
});
