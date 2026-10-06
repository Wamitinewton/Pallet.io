import { asUserId } from "@/shared/domain/ids";
import { ok } from "@/test/msw/envelopes";
import { sessionDto, sessionSummaryBody } from "@/test/msw/identity";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders } from "@/test/render";
import { act, screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { AccountView } from "./AccountView";
import { identityKeys } from "./queries";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

function renderAccount() {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(identityKeys.profile(), {
        userId: asUserId("user-amani"),
        email: "amani@kilimalabs.co",
        displayName: "Amani Otieno",
        status: "ACTIVE",
    });
    return renderWithProviders(<AccountView />, { queryClient });
}

const tab = (name: string) => screen.getByRole("tab", { name });

beforeEach(() => {
    vi.clearAllMocks();
    window.history.replaceState(null, "", "/account");
    server.use(
        http.get(bffUrl("/identity/users/me/sessions"), () => ok([sessionDto()])),
        http.get(sessionApiUrl(""), () => ok(sessionSummaryBody())),
    );
});

afterEach(() => {
    window.history.replaceState(null, "", "/");
});

describe("AccountView", () => {
    it("names the account and opens on the profile", () => {
        renderAccount();

        expect(screen.getByRole("heading", { level: 1, name: "Amani Otieno" })).toBeInTheDocument();
        expect(screen.getByText("amani@kilimalabs.co")).toBeInTheDocument();
        expect(tab("Profile")).toHaveAttribute("aria-selected", "true");
        expect(screen.getByLabelText("Name")).toHaveValue("Amani Otieno");
    });

    it("opens on password and sessions when the address asks for #security", async () => {
        window.history.replaceState(null, "", "/account#security");
        renderAccount();

        await waitFor(() => {
            expect(tab("Password and sessions")).toHaveAttribute("aria-selected", "true");
        });
        expect(screen.getByLabelText("Current password")).toBeInTheDocument();
        expect(await screen.findByText("This device")).toBeInTheDocument();
    });

    it("follows a hash change while the page is open", async () => {
        const scrollIntoView = vi.spyOn(Element.prototype, "scrollIntoView");
        renderAccount();

        act(() => {
            window.history.pushState(null, "", "/account#security");
            window.dispatchEvent(new PopStateEvent("popstate"));
        });

        await waitFor(() => {
            expect(tab("Password and sessions")).toHaveAttribute("aria-selected", "true");
        });
        expect(scrollIntoView).toHaveBeenCalledWith({ block: "start" });
    });

    it("keeps the chosen tab in the address", async () => {
        const { user } = renderAccount();

        await user.click(tab("Password and sessions"));
        expect(window.location.hash).toBe("#security");

        await user.click(tab("Profile"));
        expect(window.location.hash).toBe("");
        expect(window.location.pathname).toBe("/account");
    });
});
