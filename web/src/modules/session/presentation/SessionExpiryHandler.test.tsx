import { SessionExpiredError } from "@/shared/domain/errors";
import { renderWithProviders } from "@/test/render";
import { waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SessionExpiryHandler } from "./SessionExpiryHandler";

const router = vi.hoisted(() => ({ replace: vi.fn(), refresh: vi.fn(), push: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const expire = (queryKey: string[]) => ({ queryKey, queryFn: () => Promise.reject(new SessionExpiredError()) });

beforeEach(() => {
    vi.clearAllMocks();
    window.history.pushState({}, "", "/orgs/o-1/apps?page=2");
});

afterEach(() => {
    window.history.pushState({}, "", "/");
});

describe("SessionExpiryHandler", () => {
    it("sends the first expiry to sign in with the way back, and ignores the rest", async () => {
        const { queryClient } = renderWithProviders(<SessionExpiryHandler />);
        queryClient.setQueryData(["org", "o-1", "members"], ["ada"]);

        await queryClient.query(expire(["org", "o-1", "apps"])).catch(() => undefined);
        await queryClient.query(expire(["org", "o-1", "teams"])).catch(() => undefined);

        await waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/login?reason=expired&next=%2Forgs%2Fo-1%2Fapps%3Fpage%3D2");
        });
        expect(router.replace).toHaveBeenCalledTimes(1);
        expect(queryClient.getQueryData(["org", "o-1", "members"])).toBeUndefined();
    });

    it("leaves any other failure alone", async () => {
        const { queryClient } = renderWithProviders(<SessionExpiryHandler />);

        await queryClient
            .query({ queryKey: ["x"], queryFn: () => Promise.reject(new Error("boom")) })
            .catch(() => undefined);

        expect(router.replace).not.toHaveBeenCalled();
    });
});
