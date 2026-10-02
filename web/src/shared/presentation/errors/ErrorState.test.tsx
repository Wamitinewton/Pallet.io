import { NetworkError } from "@/shared/domain/errors";
import { testApiClients } from "@/test/clients";
import { error, ok, TEST_CORRELATION_ID } from "@/test/msw/envelopes";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { useQuery } from "@tanstack/react-query";
import { screen } from "@testing-library/react";
import { http } from "msw";
import { useState } from "react";
import { describe, expect, it } from "vitest";
import { ErrorState } from "./ErrorState";

function UnreadCount() {
    const [clients] = useState(testApiClients);
    const query = useQuery({
        queryKey: ["notifications", "unread-count"],
        queryFn: async () => (await clients.notification.GET("/notifications/unread-count")).data,
    });

    if (query.isError) {
        return <ErrorState error={query.error} onRetry={() => void query.refetch()} retrying={query.isFetching} />;
    }
    return <p>{query.isSuccess ? "Loaded" : "Loading"}</p>;
}

describe("ErrorState", () => {
    it("shows the failure with its correlation id and retries through refetch", async () => {
        let calls = 0;
        server.use(
            http.get(bffUrl("/notification/notifications/unread-count"), () => {
                calls += 1;
                return calls === 1 ? error(503, "SERVICE_UNAVAILABLE") : ok({ count: 2 });
            }),
        );
        const { user } = renderWithProviders(<UnreadCount />);

        const alert = await screen.findByRole("alert");
        expect(alert).toHaveTextContent("Pallet is temporarily unavailable. Try again in a moment.");
        expect(alert).toHaveTextContent(`Reference ${TEST_CORRELATION_ID}`);

        await user.click(screen.getByRole("button", { name: "Try again" }));

        expect(await screen.findByText("Loaded")).toBeInTheDocument();
        expect(calls).toBe(2);
    });

    it("shows no reference when the failure has none", () => {
        renderWithProviders(<ErrorState error={new NetworkError()} />);

        expect(screen.getByRole("alert")).not.toHaveTextContent("Reference");
        expect(screen.queryByRole("button", { name: "Try again" })).not.toBeInTheDocument();
    });
});
