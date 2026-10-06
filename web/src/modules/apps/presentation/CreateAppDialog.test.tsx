import { error } from "@/test/msw/envelopes";
import { orgDto } from "@/test/msw/org-team";
import { screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppsBackend, FATUMA, KEVIN, KILIMA, PAYMENTS, renderApps } from "./testing/render-apps";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/apps" }));

type User = Awaited<ReturnType<typeof renderApps>>["user"];

async function openNewApp(user: User) {
    await user.click(screen.getByRole("button", { name: "New app" }));
    return screen.findByRole("dialog", { name: "New app" });
}

const regionOptions = (dialog: HTMLElement) =>
    within(within(dialog).getByRole("combobox", { name: "Region" }))
        .getAllByRole("option")
        .map((option) => option.textContent);

async function fill(user: User, dialog: HTMLElement, { name = "Payments API", region = "Ireland (eu-west-1)" } = {}) {
    await user.type(within(dialog).getByLabelText("Name"), name);
    await user.selectOptions(within(dialog).getByRole("combobox", { name: "Region" }), region);
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("CreateAppDialog", () => {
    it("offers only the chosen cloud's regions, and clears a region the other cloud doesn't have", async () => {
        const { user } = await renderApps();
        const dialog = await openNewApp(user);

        expect(within(dialog).getByRole("radio", { name: /Amazon Web Services/ })).toBeChecked();
        expect(regionOptions(dialog)).toEqual([
            "Choose a region",
            "N. Virginia (us-east-1)",
            "Oregon (us-west-2)",
            "Ireland (eu-west-1)",
            "Frankfurt (eu-central-1)",
            "Singapore (ap-southeast-1)",
            "Cape Town (af-south-1)",
        ]);
        await user.selectOptions(within(dialog).getByRole("combobox", { name: "Region" }), "Ireland (eu-west-1)");

        await user.click(within(dialog).getByRole("radio", { name: /Google Cloud/ }));

        expect(regionOptions(dialog)).toContain("Belgium (europe-west1)");
        expect(regionOptions(dialog)).not.toContain("Ireland (eu-west-1)");
        expect(within(dialog).getByRole("combobox", { name: "Region" })).toHaveValue("");
        expect(within(dialog).getByText(/can't be changed once the app exists/)).toBeInTheDocument();
    });

    it("creates the app with what was chosen, then opens it and refreshes the list and the counts", async () => {
        const { user, backend, queryClient } = await renderApps();
        queryClient.setQueryData(["org", KILIMA, "organization"], orgDto());
        const dialog = await openNewApp(user);

        await fill(user, dialog);
        expect(within(dialog).getByLabelText("Slug")).toHaveValue("payments-api");
        await user.selectOptions(within(dialog).getByRole("combobox", { name: /Team/ }), "Payments");
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        await waitFor(() => {
            expect(router.push).toHaveBeenCalledExactlyOnceWith(
                "/orgs/org-kilima/apps/0d0c1a2b-3c4d-4e5f-8a6b-000000000001",
            );
        });
        expect(JSON.parse(backend.sent("create")[0]?.raw ?? "")).toEqual({
            name: "Payments API",
            slug: "payments-api",
            cloudProvider: "AWS",
            region: "eu-west-1",
            teamId: PAYMENTS.id,
        });
        expect(await screen.findByText("Payments API created")).toBeInTheDocument();
        expect(queryClient.getQueryState(["org", KILIMA, "organization"])?.isInvalidated).toBe(true);
        await waitFor(() => {
            expect(backend.sent("list")).toHaveLength(2);
        });
    });

    it("leaves the team out when none is chosen", async () => {
        const { user, backend } = await renderApps();
        const dialog = await openNewApp(user);

        await fill(user, dialog);
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        await waitFor(() => {
            expect(backend.sent("create")).toHaveLength(1);
        });
        expect(JSON.parse(backend.sent("create")[0]?.raw ?? "")).not.toHaveProperty("teamId");
    });

    it("puts INVALID_REGION on the region field", async () => {
        const backend = new AppsBackend();
        backend.withdrawn.add("eu-west-1");
        const { user } = await renderApps({ backend });
        const dialog = await openNewApp(user);

        await fill(user, dialog);
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        expect(await within(dialog).findByText("This region isn't available any more. Choose another.")).toBeVisible();
        expect(within(dialog).getByRole("combobox", { name: "Region" })).toHaveAttribute("aria-invalid", "true");
        expect(router.push).not.toHaveBeenCalled();
    });

    it("puts SLUG_TAKEN on the slug field", async () => {
        const { user } = await renderApps();
        const dialog = await openNewApp(user);

        await fill(user, dialog, { name: "Checkout API" });
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        expect(await within(dialog).findByText("Another app already uses that slug. Try another.")).toBeVisible();
        expect(within(dialog).getByLabelText("Slug")).toHaveAttribute("aria-invalid", "true");
    });

    it("explains the organization's app limit with the backend's own words", async () => {
        const backend = new AppsBackend();
        backend.replies.set("create", () =>
            error(409, "QUOTA_EXCEEDED", { message: "This organization has reached its app limit." }),
        );
        const { user } = await renderApps({ backend });
        const dialog = await openNewApp(user);

        await fill(user, dialog);
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        expect(await within(dialog).findByRole("alert")).toHaveTextContent(
            "This organization has reached its app limit.",
        );
    });

    it("refuses to send a form without a region", async () => {
        const { user, backend } = await renderApps();
        const dialog = await openNewApp(user);

        await user.type(within(dialog).getByLabelText("Name"), "Payments API");
        await user.click(within(dialog).getByRole("button", { name: "Create app" }));

        expect(await within(dialog).findByText("Choose a region", { selector: "span" })).toBeInTheDocument();
        expect(backend.sent("create")).toEqual([]);
    });

    it("is offered to a developer", async () => {
        await renderApps({ backend: new AppsBackend(undefined, undefined, FATUMA) });

        expect(screen.getByRole("button", { name: "New app" })).toBeInTheDocument();
    });

    it("is hidden from a viewer", async () => {
        await renderApps({ backend: new AppsBackend(undefined, undefined, KEVIN) });

        expect(screen.getByRole("table", { name: "App" })).toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "New app" })).not.toBeInTheDocument();
    });
});
