import { error } from "@/test/msw/envelopes";
import { server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { useSignOut } from "./use-sign-out";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

function SignOutButton() {
    const { signOut, pending } = useSignOut();
    return (
        <button type="button" onClick={signOut} disabled={pending}>
            Sign out
        </button>
    );
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("useSignOut", () => {
    it("ends the session, empties the cache and goes to sign in", async () => {
        const requests: Request[] = [];
        server.use(
            http.post(sessionApiUrl("/logout"), ({ request }) => {
                requests.push(request);
                return new HttpResponse(null, { status: 204 });
            }),
        );
        const { user, queryClient } = renderWithProviders(<SignOutButton />);
        queryClient.setQueryData(["org", "o-1", "apps"], ["checkout"]);

        await user.click(screen.getByRole("button", { name: "Sign out" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login");
        });
        expect(router.refresh).toHaveBeenCalled();
        expect(requests[0]?.headers.get("X-Pallet-Request")).toBe("1");
        expect(queryClient.getQueryData(["org", "o-1", "apps"])).toBeUndefined();
    });

    it.each([
        ["a server error", () => error(500, "INTERNAL_ERROR")],
        ["a network failure", () => HttpResponse.error()],
    ])("still signs out on this device after %s", async (_, reply) => {
        server.use(http.post(sessionApiUrl("/logout"), reply));
        const { user, queryClient } = renderWithProviders(<SignOutButton />);
        queryClient.setQueryData(["org", "o-1", "apps"], ["checkout"]);

        await user.click(screen.getByRole("button", { name: "Sign out" }));

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login");
        });
        expect(queryClient.getQueryData(["org", "o-1", "apps"])).toBeUndefined();
    });
});
