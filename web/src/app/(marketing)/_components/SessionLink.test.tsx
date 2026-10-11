import { sessionKeys } from "@/modules/session";
import { error, ok } from "@/test/msw/envelopes";
import { sessionSummaryBody } from "@/test/msw/identity";
import { server, sessionApiUrl } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { SessionLink } from "./SessionLink";

describe("SessionLink", () => {
    it("offers the dashboard once the session endpoint confirms a session", async () => {
        server.use(http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")));

        renderWithProviders(<SessionLink />);

        expect(await screen.findByRole("link", { name: "Open dashboard" })).toHaveAttribute("href", "/orgs");
        expect(screen.queryByRole("link", { name: "Sign in" })).not.toBeInTheDocument();
    });

    it("renders sign in first, so the static page never waits on the session", () => {
        server.use(http.get(sessionApiUrl(""), () => new Promise<never>(() => undefined)));

        renderWithProviders(<SessionLink />);

        expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    });

    it.each([
        ["no session", () => error(401, "SESSION_EXPIRED")],
        ["a server error", () => error(500, "INTERNAL_ERROR")],
        ["a network failure", () => HttpResponse.error()],
    ])("keeps sign in after %s", async (_, reply) => {
        server.use(http.get(sessionApiUrl(""), reply));

        const { queryClient } = renderWithProviders(<SessionLink />);

        await waitFor(() => {
            expect(queryClient.getQueryState(sessionKeys.summary())?.status).toBe("error");
        });
        expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login");
    });
});
