import { manualClock, type ManualClock } from "@/test/clock";
import { page } from "@/test/msw/envelopes";
import { orgSummaryDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders, TEST_NOW } from "@/test/render";
import { act, screen } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PROVISIONING_PATIENCE_MS, PROVISIONING_POLL_MS, ProvisioningState } from "./ProvisioningState";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router }));

const personal = orgSummaryDto({ orgId: "org-amani", name: "Amani Otieno", kind: "PERSONAL" });
const kilima = orgSummaryDto();

function replyWithLists(answer: (request: number) => readonly unknown[]) {
    let requests = 0;
    server.use(
        http.get(bffUrl("/org-team/orgs"), () => {
            requests += 1;
            return page(answer(requests), { size: 100 });
        }),
    );
    return () => requests;
}

function elapse(clock: ManualClock, millis: number) {
    act(() => {
        clock.advance(millis);
        vi.advanceTimersByTime(millis);
    });
}

let clock: ManualClock;

beforeEach(() => {
    vi.clearAllMocks();
    vi.useFakeTimers({ toFake: ["setInterval", "clearInterval"] });
    clock = manualClock(TEST_NOW);
});

describe("ProvisioningState", () => {
    it("waits while the list is empty, then opens the organization as soon as it appears", async () => {
        const requests = replyWithLists((request) => (request < 3 ? [] : [personal, kilima]));
        renderWithProviders(<ProvisioningState lastOrgId={undefined} />, { clock });

        expect(screen.getByRole("status")).toHaveTextContent("Setting up your organization");
        await vi.waitFor(() => {
            expect(requests()).toBe(1);
        });

        elapse(clock, PROVISIONING_POLL_MS);
        await vi.waitFor(() => {
            expect(requests()).toBe(2);
        });
        expect(router.replace).not.toHaveBeenCalled();

        elapse(clock, PROVISIONING_POLL_MS);
        await vi.waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs/org-kilima");
        });
    });

    it("gives up after thirty seconds without an error, then tries again on request", async () => {
        const requests = replyWithLists(() => []);
        const { user } = renderWithProviders(<ProvisioningState lastOrgId={undefined} />, { clock });
        await vi.waitFor(() => {
            expect(requests()).toBe(1);
        });

        for (let waited = 0; waited < PROVISIONING_PATIENCE_MS; waited += PROVISIONING_POLL_MS) {
            elapse(clock, PROVISIONING_POLL_MS);
        }

        expect(await screen.findByRole("alert")).toHaveTextContent("This is taking longer than usual");
        const asked = requests();
        expect(asked).toBeLessThanOrEqual(1 + PROVISIONING_PATIENCE_MS / PROVISIONING_POLL_MS);
        elapse(clock, PROVISIONING_POLL_MS * 5);
        expect(requests()).toBe(asked);

        await user.click(screen.getByRole("button", { name: "Try again" }));

        expect(await screen.findByRole("status")).toHaveTextContent("Setting up your organization");
        await vi.waitFor(() => {
            expect(requests()).toBe(asked + 1);
        });
        expect(router.replace).not.toHaveBeenCalled();
    });

    it("lands on the remembered organization when it is among the new ones", async () => {
        replyWithLists(() => [personal, kilima]);
        renderWithProviders(<ProvisioningState lastOrgId="org-amani" />, { clock });

        await vi.waitFor(() => {
            expect(router.replace).toHaveBeenCalledWith("/orgs/org-amani");
        });
    });
});
