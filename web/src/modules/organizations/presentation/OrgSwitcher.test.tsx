import { asOrgId } from "@/shared/domain/ids";
import { error, page } from "@/test/msw/envelopes";
import { orgSummaryDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { renderWithProviders } from "@/test/render";
import { screen, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { OrgSwitcher } from "./OrgSwitcher";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
const navigation = vi.hoisted(() => ({ pathname: "/orgs/org-kilima" }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => navigation.pathname }));

const MY_ORGS_URL = bffUrl("/org-team/orgs");

const kilima = orgSummaryDto();
const personal = orgSummaryDto({ orgId: "org-amani", name: "Amani Otieno", slug: "amani-otieno", kind: "PERSONAL" });
const savanna = orgSummaryDto({ orgId: "org-savanna", name: "Savanna Pay", slug: "savanna-pay", myRole: "DEVELOPER" });

function replyWithMyOrganizations() {
    const queries: URLSearchParams[] = [];
    server.use(
        http.get(MY_ORGS_URL, ({ request }) => {
            queries.push(new URL(request.url).searchParams);
            return page([kilima, personal, savanna], { size: 100 });
        }),
    );
    return queries;
}

async function openSwitcher() {
    const rendered = renderWithProviders(<OrgSwitcher activeOrgId={asOrgId("org-kilima")} />);
    const trigger = await screen.findByRole("button", { name: /Kilima Labs/ });
    trigger.focus();
    await rendered.user.keyboard("{Enter}");
    return { ...rendered, trigger, menu: screen.getByRole("menu") };
}

beforeEach(() => {
    vi.clearAllMocks();
    navigation.pathname = "/orgs/org-kilima";
});

describe("OrgSwitcher", () => {
    it("names the open organization with its kind and my role in it", async () => {
        const queries = replyWithMyOrganizations();
        renderWithProviders(<OrgSwitcher activeOrgId={asOrgId("org-kilima")} />);

        expect(await screen.findByRole("button", { name: "Kilima Labs, Team · you're the owner" })).toBeInTheDocument();
        expect(queries[0]?.get("size")).toBe("100");
    });

    it("lists every organization of mine, checking the open one, then New organization", async () => {
        replyWithMyOrganizations();
        const { menu } = await openSwitcher();

        expect(within(menu).getAllByRole("menuitem")).toEqual([
            screen.getByRole("menuitem", { name: "Kilima Labs, Team · Owner, open" }),
            screen.getByRole("menuitem", { name: "Amani Otieno, Personal · Owner" }),
            screen.getByRole("menuitem", { name: "Savanna Pay, Team · Developer" }),
            screen.getByRole("menuitem", { name: "New organization" }),
        ]);
    });

    it.each([
        ["/orgs/org-kilima/apps", "/orgs/org-savanna/apps"],
        ["/orgs/org-kilima/apps/app-1", "/orgs/org-savanna/apps"],
        ["/orgs/org-kilima/members", "/orgs/org-savanna/members"],
        ["/orgs/org-kilima", "/orgs/org-savanna"],
        ["/account", "/orgs/org-savanna"],
    ])("chosen by keyboard from %s, opens %s", async (pathname, target) => {
        navigation.pathname = pathname;
        replyWithMyOrganizations();
        const { user } = await openSwitcher();

        expect(screen.getByRole("menuitem", { name: /Kilima Labs/ })).toHaveFocus();
        await user.keyboard("{ArrowDown}{ArrowDown}");
        expect(screen.getByRole("menuitem", { name: /Savanna Pay/ })).toHaveFocus();
        await user.keyboard("{Enter}");

        expect(router.push).toHaveBeenCalledExactlyOnceWith(target);
        expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });

    it("stays where it is when the open organization is chosen again", async () => {
        replyWithMyOrganizations();
        const { user, trigger } = await openSwitcher();

        await user.keyboard("{Enter}");

        expect(router.push).not.toHaveBeenCalled();
        expect(trigger).toHaveFocus();
    });

    it("opens the new organization dialog from the menu", async () => {
        replyWithMyOrganizations();
        const { user } = await openSwitcher();

        await user.click(screen.getByRole("menuitem", { name: "New organization" }));

        expect(await screen.findByRole("dialog", { name: "New organization" })).toBeInTheDocument();
        expect(screen.getByLabelText("Name")).toHaveFocus();
        expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });

    it("offers to try again when the list can't load", async () => {
        let attempts = 0;
        server.use(
            http.get(MY_ORGS_URL, () => {
                attempts += 1;
                return attempts === 1 ? error(503, "SERVICE_UNAVAILABLE") : page([kilima], { size: 100 });
            }),
        );
        const { user } = renderWithProviders(<OrgSwitcher activeOrgId={asOrgId("org-kilima")} />);
        await user.click(screen.getByRole("button", { name: "Your organizations" }));

        await user.click(await screen.findByRole("menuitem", { name: "Couldn't load them. Try again" }));

        expect(await screen.findByRole("button", { name: /Kilima Labs/ })).toBeInTheDocument();
        expect(attempts).toBe(2);
    });
});
