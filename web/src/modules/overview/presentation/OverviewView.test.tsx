import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { asOrgId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import {
    disconnectedRepoLinkDto,
    installationLinkDto,
    repoLinkDto,
    type InstallationLinkDto,
    type RepoLinkDto,
} from "@/test/msw/git-integration";
import { appDto, inviteDto, memberDto, orgDto, type AppDto, type MemberDto, type OrgDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders } from "@/test/render";
import { screen, waitFor, within } from "@testing-library/react";
import { http } from "msw";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { OverviewView } from "./OverviewView";

const router = vi.hoisted(() => ({ push: vi.fn(), replace: vi.fn(), refresh: vi.fn(), prefetch: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => router, usePathname: () => "/orgs/org-kilima" }));

const KILIMA = asOrgId("org-kilima");
const ORG_URL = bffUrl("/org-team/orgs/org-kilima");
const ORG_GITHUB_URL = bffUrl("/git-integration/orgs/org-kilima");

const AMANI = memberDto();
const KEVIN = memberDto({ userId: "user-kevin", displayName: "Kevin Ochieng", role: "VIEWER" });

const CHECKOUT = appDto();
const RECEIPTS = appDto({ id: "8a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d", name: "Receipts worker", slug: "receipts" });
const DOCS = appDto({ id: "9b3c4d5e-6f7a-4b2c-8d3e-4f5a6b7c8d9e", name: "Docs", slug: "docs" });

type Read = "apps" | "links" | "installations" | "invites" | "unread";

interface Scenario {
    readonly me?: MemberDto;
    readonly organization?: OrgDto;
    readonly apps?: readonly AppDto[];
    readonly links?: Readonly<Record<string, RepoLinkDto | null>>;
    readonly installations?: readonly InstallationLinkDto[];
    readonly pendingInvites?: number;
    readonly unread?: number;
    readonly gitIntegrationDown?: boolean;
}

function install({
    me = AMANI,
    organization = orgDto(),
    apps = [CHECKOUT],
    links = { [CHECKOUT.id]: repoLinkDto() },
    installations = [installationLinkDto()],
    pendingInvites = 0,
    unread = 0,
    gitIntegrationDown = false,
}: Scenario = {}) {
    const reads: { readonly read: Read; readonly params: URLSearchParams }[] = [];
    const record = (read: Read, request: Request) => {
        reads.push({ read, params: new URL(request.url).searchParams });
    };
    const unavailable = () => error(503, "SERVICE_UNAVAILABLE", { message: "git-integration-service is down" });

    server.use(
        http.get(ORG_URL, () => ok(organization)),
        http.get(`${ORG_URL}/members/me`, () => ok(me)),
        http.get(`${ORG_URL}/teams`, () => page([])),
        http.get(`${ORG_URL}/apps`, ({ request }) => {
            record("apps", request);
            return page(apps, { size: 5 });
        }),
        http.get(`${ORG_URL}/invites`, ({ request }) => {
            record("invites", request);
            return page(pendingInvites === 0 ? [] : [inviteDto()], { size: 1, totalElements: pendingInvites });
        }),
        http.get(`${ORG_GITHUB_URL}/apps/:appId/repo-link`, ({ params, request }) => {
            record("links", request);
            if (gitIntegrationDown) return unavailable();
            const link = links[String(params.appId)];
            return link == null ? error(404, "REPO_LINK_NOT_FOUND") : ok(link);
        }),
        http.get(`${ORG_GITHUB_URL}/github/installations`, ({ request }) => {
            record("installations", request);
            return gitIntegrationDown ? unavailable() : page(installations, { size: 100 });
        }),
        http.get(bffUrl("/notification/notifications/unread-count"), ({ request }) => {
            record("unread", request);
            return ok(unread);
        }),
    );
    return { reads, count: (read: Read) => reads.filter((entry) => entry.read === read).length };
}

function renderOverview(me: MemberDto = AMANI) {
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), me);
    return renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <OverviewView orgId={KILIMA} orgName="Kilima Labs" />
        </OrgAccessProvider>,
        { queryClient },
    );
}

const attentionPanel = () => screen.getByRole("region", { name: "Needs your attention" });
const appsPanel = () => screen.getByRole("region", { name: "Newest apps" });

async function settled() {
    await waitFor(() => {
        expect(screen.queryAllByRole("status", { name: /^(Loading|Checking)/ })).toEqual([]);
        expect(screen.queryByText("Checking…")).not.toBeInTheDocument();
    });
}

beforeEach(() => {
    vi.clearAllMocks();
});

describe("OverviewView", () => {
    it("links each count to its page", async () => {
        install();
        renderOverview();

        expect(await screen.findByRole("link", { name: "4 apps" })).toHaveAttribute("href", "/orgs/org-kilima/apps");
        expect(screen.getByRole("link", { name: "6 members" })).toHaveAttribute("href", "/orgs/org-kilima/members");
        expect(screen.getByRole("link", { name: "3 teams" })).toHaveAttribute("href", "/orgs/org-kilima/teams");
        expect(screen.getByText("pallet.dev/kilima-labs")).toBeInTheDocument();
    });

    it("lists the newest apps with their latest push, reading no more than five links", async () => {
        const many = Array.from({ length: 5 }, (_, index) =>
            appDto({ id: `00000000-0000-4000-8000-00000000000${String(index)}`, name: `App ${String(index)}` }),
        );
        const scenario = install({
            apps: many,
            links: Object.fromEntries(many.map(({ id }) => [id, repoLinkDto({ appId: id })])),
        });
        renderOverview();
        await settled();

        expect(within(appsPanel()).getAllByRole("link", { name: /^App \d/ })).toHaveLength(5);
        const newest = within(appsPanel()).getByRole("link", { name: /^App 0/ });
        expect(newest).toHaveAttribute("href", `/orgs/org-kilima/apps/${many[0]?.id ?? ""}`);
        expect(newest).toHaveTextContent("kilima-labs/checkout-api");
        expect(newest).toHaveTextContent("3f9c2e1 on main");
        expect(within(newest).getByText("Connected")).toBeInTheDocument();

        const [apps] = scenario.reads.filter(({ read }) => read === "apps");
        expect(apps?.params.get("sort")).toBe("createdAt,desc");
        expect(apps?.params.get("size")).toBe("5");
        expect(scenario.count("links")).toBe(5);
        expect(scenario.reads.length).toBeLessThan(10);
    });

    it("turns a disconnected repository into a red item that leads to the app", async () => {
        install({
            apps: [RECEIPTS, CHECKOUT],
            links: {
                [RECEIPTS.id]: disconnectedRepoLinkDto("REPOSITORY_ACCESS_REMOVED", {
                    appId: RECEIPTS.id,
                    repoFullName: "kilima-labs/receipts",
                }),
                [CHECKOUT.id]: repoLinkDto(),
            },
        });
        renderOverview();
        await settled();

        const item = within(attentionPanel())
            .getByText(/^Receipts worker lost its repository/)
            .closest("li");
        expect(item).toHaveTextContent("the Pallet GitHub App no longer has access to it");
        expect(within(item as HTMLElement).getByRole("link", { name: "Fix the link" })).toHaveAttribute(
            "href",
            `/orgs/org-kilima/apps/${RECEIPTS.id}`,
        );
        expect(item?.querySelector("[data-tone]")).toHaveAttribute("data-tone", "red");
        expect(within(appsPanel()).getByRole("link", { name: /^Receipts worker/ })).toHaveTextContent("Disconnected");
    });

    it("asks a fresh organization's admin to connect GitHub", async () => {
        install({ organization: orgDto({ counts: { members: 1, teams: 0, apps: 0 } }), apps: [], installations: [] });
        renderOverview();
        await settled();

        expect(screen.getByRole("link", { name: "0 apps" })).toBeInTheDocument();
        expect(within(appsPanel()).getByRole("heading", { name: "No apps yet" })).toBeInTheDocument();
        expect(within(attentionPanel()).getByRole("link", { name: "Connect GitHub" })).toHaveAttribute(
            "href",
            "/orgs/org-kilima/github",
        );
    });

    it("counts pending invites and unread notifications for an admin", async () => {
        const scenario = install({ pendingInvites: 2, unread: 4 });
        renderOverview();
        await settled();

        expect(within(attentionPanel()).getByText("2 invites are waiting to be accepted")).toBeInTheDocument();
        expect(within(attentionPanel()).getByRole("link", { name: "See invites" })).toHaveAttribute(
            "href",
            "/orgs/org-kilima/members?tab=invites",
        );
        expect(within(attentionPanel()).getByText("4 unread notifications")).toBeInTheDocument();
        const [invites] = scenario.reads.filter(({ read }) => read === "invites");
        expect(invites?.params.get("status")).toBe("PENDING");
        expect(invites?.params.get("size")).toBe("1");
    });

    it("shows the calm state when nothing needs attention", async () => {
        install();
        renderOverview();
        await settled();

        expect(within(attentionPanel()).getByRole("heading", { name: "Nothing needs your attention" })).toBeVisible();
    });

    it("keeps the counts and the apps when git-integration-service is down", async () => {
        install({ apps: [CHECKOUT, DOCS], gitIntegrationDown: true });
        renderOverview();
        await settled();

        expect(screen.getByRole("link", { name: "4 apps" })).toBeInTheDocument();
        expect(within(appsPanel()).getByRole("link", { name: /^Checkout API/ })).toHaveTextContent("Couldn't check");
        expect(within(appsPanel()).getByRole("link", { name: /^Docs/ })).toBeInTheDocument();
        const alert = within(attentionPanel()).getByRole("alert");
        expect(alert).toHaveTextContent("Couldn't check what needs your attention");
        expect(within(alert).getByRole("button", { name: "Try again" })).toBeEnabled();
    });

    it("sends no invites request for a viewer, and offers no admin actions", async () => {
        const scenario = install({ me: KEVIN, installations: [] });
        renderOverview(KEVIN);
        await settled();

        expect(scenario.count("invites")).toBe(0);
        expect(within(attentionPanel()).queryByRole("link", { name: "Connect GitHub" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "Invite people" })).not.toBeInTheDocument();
        expect(screen.queryByRole("button", { name: "New app" })).not.toBeInTheDocument();
    });
});
