import { defineConfig, devices } from "@playwright/test";

const baseURL = "http://localhost:5173";
const isCi = Boolean(process.env.CI);

export default defineConfig({
    testDir: "./e2e",
    fullyParallel: true,
    forbidOnly: isCi,
    retries: isCi ? 2 : 1,
    reporter: isCi ? [["github"], ["html", { open: "never" }]] : [["list"], ["html", { open: "never" }]],
    use: {
        baseURL,
        trace: "on-first-retry",
    },
    projects: [
        { name: "chromium", use: { ...devices["Desktop Chrome"] } },
        { name: "firefox", use: { ...devices["Desktop Firefox"] } },
    ],
    ...(process.env.PLAYWRIGHT_START_SERVER === "1" && {
        webServer: {
            command: "pnpm start",
            url: baseURL,
            reuseExistingServer: !isCi,
            timeout: 120_000,
        },
    }),
});
