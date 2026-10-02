import { expect, test } from "@playwright/test";

test("the placeholder page loads with a title", async ({ page }) => {
    const response = await page.goto("/");

    expect(response?.ok()).toBe(true);
    await expect(page).toHaveTitle(/\S/);
    await expect(page.getByRole("heading", { level: 1 })).toBeVisible();
});
