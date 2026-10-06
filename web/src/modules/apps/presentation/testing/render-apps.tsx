import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { asAppId, asOrgId, asTeamId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import { appDto, memberDto, orgDto, teamDto, type AppDto, type MemberDto, type TeamDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect } from "vitest";
import { isRegionOf, type CloudProvider } from "../../domain/region-catalog";
import { AppsView } from "../AppsView";
import { AppView, type AppViewSlots } from "../AppView";
import { TeamAppsPanel } from "../TeamAppsPanel";

export const KILIMA = asOrgId("org-kilima");
const ORG_URL = bffUrl("/org-team/orgs/org-kilima");
export const APPS_URL = `${ORG_URL}/apps`;

export const AMANI = memberDto();
export const WANJIRU = memberDto({ userId: "user-wanjiru", displayName: "Wanjiru Kamau", role: "ADMIN" });
export const FATUMA = memberDto({ userId: "user-fatuma", displayName: "Fatuma Hassan", role: "DEVELOPER" });
export const KEVIN = memberDto({ userId: "user-kevin", displayName: "Kevin Ochieng", role: "VIEWER" });

export const PAYMENTS = teamDto();
export const WEB = teamDto({ id: "7c4e2a91-0b3d-4f5e-8a6c-9d1e2f3a4b5c", name: "Web", slug: "web" });

export const CHECKOUT = appDto();
export const MERCHANT = appDto({
    id: "8a2b3c4d-5e6f-4a1b-9c2d-3e4f5a6b7c8d",
    name: "Merchant dashboard",
    slug: "merchant-dashboard",
    cloudProvider: "GCP",
    region: "europe-west1",
    teamId: WEB.id,
    createdAt: "2026-01-10T09:00:00Z",
});
export const DOCS = appDto({
    id: "9b3c4d5e-6f7a-4b2c-8d3e-4f5a6b7c8d9e",
    name: "Docs",
    slug: "docs",
    cloudProvider: "GCP",
    region: "africa-south1",
    teamId: null,
    createdAt: "2026-01-11T09:00:00Z",
});
export const CHECKOUT_ID = asAppId(CHECKOUT.id);

export type AppOperation = "list" | "get" | "create" | "update" | "delete";

interface Request {
    readonly operation: AppOperation;
    /** The body exactly as sent, so a test can tell an absent field from a null one. */
    readonly raw?: string;
    readonly params?: URLSearchParams;
}

function newAppId(sequence: number): string {
    return `0d0c1a2b-3c4d-4e5f-8a6b-${String(sequence).padStart(12, "0")}`;
}

function byField(field: string, direction: string) {
    return (a: AppDto, b: AppDto) => {
        const order = field === "name" ? a.name.localeCompare(b.name) : a.createdAt.localeCompare(b.createdAt);
        return direction === "desc" ? -order : order;
    };
}

/**
 * The org-team apps backend as these screens use it, with the region allow-list it validates against.
 * `withdrawn` regions are the ones dropped from the config since the client's catalog was written, and
 * `replies` answers the next request of a kind in its place.
 */
export class AppsBackend {
    readonly requests: Request[] = [];
    readonly replies = new Map<AppOperation, () => Response>();
    readonly withdrawn = new Set<string>();
    private sequence = 0;

    constructor(
        public apps: AppDto[] = [CHECKOUT, MERCHANT, DOCS],
        public teams: TeamDto[] = [PAYMENTS, WEB],
        public me: MemberDto = AMANI,
    ) {}

    sent(operation: AppOperation): Request[] {
        return this.requests.filter((request) => request.operation === operation);
    }

    install(): void {
        server.use(
            http.get(ORG_URL, () => ok(orgDto())),
            http.get(`${ORG_URL}/members/me`, () => ok(this.me)),
            http.get(`${ORG_URL}/teams`, ({ request }) => this.page(this.teams, new URL(request.url).searchParams)),
            http.get(APPS_URL, ({ request }) => {
                const params = new URL(request.url).searchParams;
                return this.answer({ operation: "list", params }, () => {
                    const [field = "createdAt", direction = "desc"] = (params.get("sort") ?? "createdAt,desc").split(
                        ",",
                    );
                    const teamId = params.get("teamId");
                    const unassigned = params.get("unassigned") === "true";
                    const cloud = params.get("cloudProvider");
                    const q = params.get("q")?.trim().toLowerCase() ?? "";
                    if (unassigned && teamId !== null) {
                        return error(400, "BAD_REQUEST", {
                            message: "Filter by a team or by apps without a team, not both.",
                        });
                    }
                    return this.page(
                        this.apps
                            .filter(
                                (app) =>
                                    (teamId === null || app.teamId === teamId) &&
                                    (!unassigned || app.teamId == null) &&
                                    (cloud === null || app.cloudProvider === cloud) &&
                                    (app.name.toLowerCase().includes(q) || app.slug.toLowerCase().includes(q)),
                            )
                            .toSorted(byField(field, direction)),
                        params,
                    );
                });
            }),
            http.post(APPS_URL, async ({ request }) => {
                const raw = await request.text();
                return this.answer({ operation: "create", raw }, () => this.create(JSON.parse(raw) as NewAppBody));
            }),
            http.get(`${APPS_URL}/:appId`, ({ params }) =>
                this.answer({ operation: "get" }, () => this.withApp(String(params.appId), (app) => ok(app))),
            ),
            http.patch(`${APPS_URL}/:appId`, async ({ params, request }) => {
                const raw = await request.text();
                return this.answer({ operation: "update", raw }, () =>
                    this.withApp(String(params.appId), (app) => {
                        const body = JSON.parse(raw) as { name?: string; teamId?: string | null };
                        if (body.teamId != null && !this.hasTeam(body.teamId)) {
                            return error(404, "TEAM_NOT_FOUND", { message: "Team not found." });
                        }
                        const updated: AppDto = {
                            ...app,
                            ...(body.name !== undefined && { name: body.name.trim() }),
                            ...(body.teamId !== undefined && { teamId: body.teamId }),
                            updatedAt: "2026-01-15T12:00:00Z",
                        };
                        this.replace(updated);
                        return ok(updated, "App updated");
                    }),
                );
            }),
            http.delete(`${APPS_URL}/:appId`, ({ params }) =>
                this.answer({ operation: "delete" }, () =>
                    this.withApp(String(params.appId), (app) => {
                        this.apps = this.apps.filter((current) => current.id !== app.id);
                        return new HttpResponse(null, { status: 204 });
                    }),
                ),
            ),
        );
    }

    /** Someone else's save landing first, as the backend would hold it. */
    replace(app: AppDto): void {
        this.apps = this.apps.map((current) => (current.id === app.id ? app : current));
    }

    private create(body: NewAppBody): Response {
        if (!isRegionOf(body.cloudProvider, body.region) || this.withdrawn.has(body.region)) {
            return error(400, "INVALID_REGION", { message: `That region is not available for ${body.cloudProvider}.` });
        }
        if (body.teamId !== undefined && !this.hasTeam(body.teamId)) {
            return error(404, "TEAM_NOT_FOUND", { message: "Team not found." });
        }
        const slug = body.slug ?? body.name.toLowerCase().replace(/\s+/g, "-");
        if (this.apps.some((app) => app.slug === slug)) {
            return error(409, "SLUG_TAKEN", { message: "That slug is already in use." });
        }
        this.sequence += 1;
        const app = appDto({
            id: newAppId(this.sequence),
            name: body.name,
            slug,
            cloudProvider: body.cloudProvider,
            region: body.region,
            teamId: body.teamId ?? null,
        });
        this.apps.push(app);
        return ok(app, "App created", { status: 201 });
    }

    private hasTeam(teamId: string): boolean {
        return this.teams.some((team) => team.id === teamId);
    }

    private answer(request: Request, work: () => Response): Response {
        this.requests.push(request);
        const reply = this.replies.get(request.operation);
        if (reply === undefined) return work();
        this.replies.delete(request.operation);
        return reply();
    }

    private withApp(appId: string, work: (app: AppDto) => Response): Response {
        const app = this.apps.find((candidate) => candidate.id === appId);
        if (app === undefined) return error(404, "APP_NOT_FOUND", { message: "App not found." });
        return work(app);
    }

    private page(items: readonly unknown[], params: URLSearchParams): Response {
        const number = Number(params.get("page") ?? "0");
        const size = Number(params.get("size") ?? "20");
        return page(items.slice(number * size, (number + 1) * size), {
            page: number,
            size,
            totalElements: items.length,
        });
    }
}

interface NewAppBody {
    readonly name: string;
    readonly slug?: string;
    readonly cloudProvider: CloudProvider;
    readonly region: string;
    readonly teamId?: string;
}

export interface AppsScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: AppsBackend;
}

function seeded(backend: AppsBackend) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    return queryClient;
}

/** The apps page as the org layout mounts it: inside the caller's access, with their membership already read. */
export async function renderApps({ backend = new AppsBackend(), ...options }: AppsScenario = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <AppsView orgId={KILIMA} orgName="Kilima Labs" />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("table", { name: "Loading apps" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}

/** One app's page, read from the fake backend, once it and the team names it shows have arrived. */
export async function renderApp({
    backend = new AppsBackend(),
    appId = CHECKOUT.id,
    slots = {},
    ...options
}: AppsScenario & { readonly appId?: string; readonly slots?: AppViewSlots } = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <AppView orgId={KILIMA} orgName="Kilima Labs" appId={asAppId(appId)} {...slots} />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("status", { name: "Loading app" })).not.toBeInTheDocument();
    });
    await waitFor(() => {
        expect(queryClient.isFetching()).toBe(0);
    });
    return { ...rendered, backend };
}

/** The team page's apps panel on its own. */
export async function renderTeamApps({ backend = new AppsBackend(), teamId = PAYMENTS.id } = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(<TeamAppsPanel orgId={KILIMA} teamId={asTeamId(teamId)} />, {
        queryClient,
    });
    await waitFor(() => {
        expect(screen.queryByRole("list", { name: "Loading the team's apps" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}
