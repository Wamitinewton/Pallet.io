import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { error, ok, page } from "@/test/msw/envelopes";
import { disconnectedRepoLinkDto, repoLinkDto, type RepoLinkDto } from "@/test/msw/git-integration";
import { appDto, orgDto, type MemberDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders } from "@/test/render";
import { screen, waitFor, within } from "@testing-library/react";
import { delay, http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { AppsWithRepositoriesView } from "./AppsWithRepositoriesView";
import { AMANI, KEVIN, KILIMA } from "./testing/render-repo-links";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima/apps" }));

const ORG_URL = bffUrl("/org-team/orgs/org-kilima");
const APPS_LINKS_URL = bffUrl("/git-integration/orgs/org-kilima/apps/:appId/repo-link");

const CHECKOUT = appDto();
const RECEIPTS = appDto({ id: "8a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d", name: "Receipts worker", slug: "receipts" });
const DOCS = appDto({ id: "9b3c4d5e-6f7a-4b2c-8d3e-4f5a6b7c8d9e", name: "Docs", slug: "docs", teamId: null });

const LINKS: Readonly<Record<string, RepoLinkDto | null>> = {
    [CHECKOUT.id]: repoLinkDto({ rootDirectory: "apps/api" }),
    [RECEIPTS.id]: disconnectedRepoLinkDto("REPOSITORY_ACCESS_REMOVED", {
        appId: RECEIPTS.id,
        repoFullName: "kilima-labs/receipts",
    }),
    [DOCS.id]: null,
};

function install({ me = AMANI, linkDelay = 0 }: { me?: MemberDto; linkDelay?: number } = {}) {
    const linkReads: string[] = [];
    server.use(
        http.get(ORG_URL, () => ok(orgDto())),
        http.get(`${ORG_URL}/members/me`, () => ok(me)),
        http.get(`${ORG_URL}/teams`, () => page([])),
        http.get(`${ORG_URL}/apps`, () => page([CHECKOUT, RECEIPTS, DOCS])),
        http.get(APPS_LINKS_URL, async ({ params }) => {
            const appId = String(params.appId);
            linkReads.push(appId);
            await delay(linkDelay);
            const link = LINKS[appId];
            return link == null ? error(404, "REPO_LINK_NOT_FOUND") : ok(link);
        }),
    );
    return linkReads;
}

function renderList(me: MemberDto = AMANI) {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), me);
    return renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <AppsWithRepositoriesView orgId={KILIMA} orgName="Kilima Labs" />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient },
    );
}

const row = (name: string) => {
    const cell = screen.getByText(name, { selector: "span" }).closest("tr");
    if (cell === null) throw new Error(`No row for ${name}`);
    return cell;
};

beforeEach(() => {
    vi.clearAllMocks();
});

describe("AppsWithRepositoriesView", () => {
    it("shows each row's repository: connected, disconnected, or a way to link one", async () => {
        const linkReads = install();
        renderList();

        await waitFor(() => {
            expect(within(row("Checkout API")).getByText("Connected")).toBeInTheDocument();
        });
        expect(row("Checkout API")).toHaveTextContent("kilima-labs/checkout-api · apps/api");
        expect(within(row("Receipts worker")).getByText("Disconnected")).toBeInTheDocument();
        expect(row("Receipts worker")).toHaveTextContent("kilima-labs/receipts");
        expect(await within(row("Docs")).findByRole("link", { name: "Link a repository to Docs" })).toHaveAttribute(
            "href",
            `/orgs/org-kilima/apps/${DOCS.id}/connect`,
        );
        expect(linkReads.toSorted()).toEqual([CHECKOUT.id, DOCS.id, RECEIPTS.id].toSorted());
    });

    it("draws a neutral badge until a row's link has been read", async () => {
        install({ linkDelay: 200 });
        renderList();

        expect(await within(await screen.findByRole("table", { name: "App" })).findAllByText("Checking…")).toHaveLength(
            3,
        );
        await waitFor(() => {
            expect(screen.queryByText("Checking…")).not.toBeInTheDocument();
        });
    });

    it("tells someone who can't link that an app has no repository", async () => {
        install({ me: KEVIN });
        renderList(KEVIN);
        await screen.findByText("Docs", { selector: "span" });

        expect(await within(row("Docs")).findByText("No repository yet")).toBeInTheDocument();
        expect(within(row("Docs")).queryByRole("link", { name: /Link a repository/ })).not.toBeInTheDocument();
    });
});
