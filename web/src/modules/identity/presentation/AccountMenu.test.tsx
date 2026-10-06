import { ok } from "@/test/msw/envelopes";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AccountMenu } from "./AccountMenu";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

beforeEach(() => {
    vi.clearAllMocks();
    server.use(
        http.get(bffUrl("/identity/users/me"), () =>
            ok({ sub: "user-amani", email: "amani@kilimalabs.co", displayName: "Amani Otieno", status: "ACTIVE" }),
        ),
    );
});

async function openMenu() {
    const rendered = renderWithProviders(<AccountMenu />);
    await screen.findByText("AO");
    await rendered.user.click(screen.getByRole("button", { name: "Account menu" }));
    return rendered;
}

describe("AccountMenu", () => {
    it("names the signed-in account and links to its settings", async () => {
        await openMenu();

        expect(screen.getByText("Amani Otieno")).toBeInTheDocument();
        expect(screen.getByText("amani@kilimalabs.co")).toBeInTheDocument();
        expect(screen.getByRole("menuitem", { name: "Your account" })).toHaveAttribute("href", "/account");
        expect(screen.getByRole("menuitem", { name: "Password and sessions" })).toHaveAttribute(
            "href",
            "/account#security",
        );
    });

    it("signs out from the menu", async () => {
        const logouts: Request[] = [];
        server.use(
            http.post(sessionApiUrl("/logout"), ({ request }) => {
                logouts.push(request);
                return new HttpResponse(null, { status: 204 });
            }),
        );
        const { user } = await openMenu();

        await user.click(screen.getByRole("menuitem", { name: "Sign out" }));

        await vi.waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login");
        });
        expect(logouts).toHaveLength(1);
    });
});
