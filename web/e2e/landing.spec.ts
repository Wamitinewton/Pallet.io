import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const LCP_BUDGET_MS = 2500;

test.describe("landing page", () => {
    test("renders with one h1 and never touches the BFF", async ({ page }) => {
        const bffRequests: string[] = [];
        page.on("request", (request) => {
            if (new URL(request.url()).pathname.startsWith("/bff/")) bffRequests.push(request.url());
        });

        const response = await page.goto("/");
        await page.waitForLoadState("networkidle");

        expect(response?.ok()).toBe(true);
        await expect(page).toHaveTitle(/Pallet/);
        await expect(page.getByRole("heading", { level: 1 })).toHaveCount(1);
        await expect(page.getByRole("heading", { level: 1 })).toContainText("Push to your branch");
        expect(bffRequests).toEqual([]);
    });

    test("the sign-up call to action goes to /signup", async ({ page }) => {
        await page.goto("/");

        await page.getByRole("link", { name: "Create your organization" }).click();

        await expect(page).toHaveURL(/\/signup$/);
    });

    test("a visitor without a session is offered sign in", async ({ page }) => {
        await page.goto("/");

        await expect(page.getByRole("banner").getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    });

    test("a signed-in visitor is offered the dashboard", async ({ page }) => {
        await page.route("**/api/session", (route) =>
            route.fulfill({
                json: {
                    success: true,
                    message: "Current session",
                    data: {
                        userId: "user-amani",
                        email: "amani@kilimalabs.co",
                        accessExpiresAt: new Date(Date.now() + 5 * 60_000).toISOString(),
                    },
                },
            }),
        );

        await page.goto("/");

        await expect(page.getByRole("banner").getByRole("link", { name: "Open dashboard" })).toHaveAttribute(
            "href",
            "/orgs",
        );
    });

    for (const colorScheme of ["light", "dark"] as const) {
        test(`has no axe violations in the ${colorScheme} theme`, async ({ page }) => {
            // Reduced motion renders every animated element in its final state, which is what contrast is judged on.
            await page.emulateMedia({ colorScheme, reducedMotion: "reduce" });
            await page.goto("/");

            const results = await new AxeBuilder({ page })
                .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
                .analyze();

            expect(results.violations).toEqual([]);
        });
    }

    test("largest contentful paint stays within budget", async ({ page, browserName }) => {
        test.skip(browserName !== "chromium", "LargestContentfulPaint is exposed reliably only in Chromium");

        await page.goto("/");
        const lcp = await page.evaluate(
            () =>
                new Promise<number>((resolve) => {
                    new PerformanceObserver((list) => {
                        const entries = list.getEntries();
                        resolve(entries[entries.length - 1]?.startTime ?? 0);
                    }).observe({ type: "largest-contentful-paint", buffered: true });
                }),
        );

        expect(lcp).toBeGreaterThan(0);
        expect(lcp).toBeLessThan(LCP_BUDGET_MS);
    });

    test("publishes robots rules that keep private routes out of search", async ({ request }) => {
        const robots = await (await request.get("/robots.txt")).text();

        for (const path of [
            "/orgs",
            "/account",
            "/notifications",
            "/invites",
            "/reset-password",
            "/github",
            "/api",
            "/bff",
        ]) {
            expect(robots).toContain(`Disallow: ${path}`);
        }
        expect(robots).toMatch(/Sitemap: .+\/sitemap\.xml/);
    });

    test("lists the public pages in the sitemap", async ({ request }) => {
        const sitemap = await (await request.get("/sitemap.xml")).text();

        for (const path of ["/", "/login", "/signup"]) {
            expect(sitemap).toMatch(new RegExp(`<loc>[^<]+${path.replace("/", "\\/")}</loc>`));
        }
    });
});
