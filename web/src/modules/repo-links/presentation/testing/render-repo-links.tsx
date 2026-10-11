import { AppView } from "@/modules/apps";
import { memberKeys, OrgAccessProvider } from "@/modules/members";
import type { Clock } from "@/shared/domain/clock";
import { asAppId, asOrgId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import {
    githubSessionDto,
    installationLinkDto,
    pickerRepositoryDto,
    repoLinkDto,
    type GitHubSessionDto,
    type InstallationLinkDto,
    type ManualBuildResultDto,
    type PickerRepositoryDto,
    type RepoLinkDto,
} from "@/test/msw/git-integration";
import { appDto, memberDto, orgDto, type AppDto, type MemberDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect } from "vitest";
import { ConnectRepoView } from "../ConnectRepoView";
import { ManualBuildButton } from "../ManualBuildButton";
import { RepositoryPanel } from "../RepositoryPanel";

export const KILIMA = asOrgId("org-kilima");
const ORG_TEAM_URL = bffUrl("/org-team/orgs/org-kilima");
const GITHUB_URL = bffUrl("/git-integration/github");
const ORG_GITHUB_URL = bffUrl("/git-integration/orgs/org-kilima/github");

export const AMANI = memberDto();
export const WANJIRU = memberDto({ userId: "user-wanjiru", displayName: "Wanjiru Kamau", role: "ADMIN" });
export const FATUMA = memberDto({ userId: "user-fatuma", displayName: "Fatuma Hassan", role: "DEVELOPER" });
export const KEVIN = memberDto({ userId: "user-kevin", displayName: "Kevin Ochieng", role: "VIEWER" });

export const CHECKOUT = appDto();
export const CHECKOUT_ID = asAppId(CHECKOUT.id);
export const LINK_URL = bffUrl(`/git-integration/orgs/org-kilima/apps/${CHECKOUT.id}/repo-link`);
export const APP_PAGE = `/orgs/org-kilima/apps/${CHECKOUT.id}`;
export const CONNECT_PAGE = `${APP_PAGE}/connect`;
export const AUTHORIZE_URL = "https://github.com/login/oauth/authorize?client_id=pallet&state=signed-authorize";

export const KILIMA_LABS = installationLinkDto();
export const AMANI_ACCOUNT = installationLinkDto({
    installationId: 41000002,
    accountLogin: "amani-otieno",
    accountType: "User",
});

export const CHECKOUT_REPO = pickerRepositoryDto();
export const WEB_REPO = pickerRepositoryDto({
    repoId: 72000002,
    fullName: "kilima-labs/web",
    defaultBranch: "develop",
});
export const INFRA_REPO = pickerRepositoryDto({
    repoId: 72000003,
    fullName: "kilima-labs/infrastructure",
    permission: "triage",
    linkable: false,
});
export const OLD_REPO = pickerRepositoryDto({
    repoId: 72000004,
    fullName: "kilima-labs/checkout-v1",
    defaultBranch: "master",
    archived: true,
    linkable: false,
});

export type RepoLinkOperation =
    "session" | "authorize" | "repositories" | "get" | "link" | "tune" | "takeOver" | "disconnect" | "build";

interface SentRequest {
    readonly operation: RepoLinkOperation;
    /** The body exactly as sent, so a test can tell an absent field from a null one. */
    readonly raw?: string;
    readonly idempotencyKey?: string | null;
    readonly installationId?: string;
    readonly params?: URLSearchParams;
}

/**
 * git-integration-service's repository links as these screens use them, with the org-team reads beside them.
 * Builds replay by key as the service does.
 */
export class RepoLinksBackend {
    readonly requests: SentRequest[] = [];
    /** Answers the next request of a kind; it may call `work` to let the backend act first, as on a lost reply. */
    readonly replies = new Map<RepoLinkOperation, (work: () => Response) => Response>();
    /** Branches that exist on GitHub, for `BRANCH_NOT_FOUND`. */
    readonly branches = new Set(["main", "develop", "master", "release"]);
    private readonly builds = new Map<string, ManualBuildResultDto>();

    constructor(
        public link: RepoLinkDto | null = repoLinkDto(),
        public me: MemberDto = AMANI,
        public session: GitHubSessionDto | null = githubSessionDto(),
        public installations: InstallationLinkDto[] = [KILIMA_LABS, AMANI_ACCOUNT],
        public repositories: PickerRepositoryDto[] = [CHECKOUT_REPO, WEB_REPO, INFRA_REPO, OLD_REPO],
        public app: AppDto = CHECKOUT,
    ) {}

    sent(operation: RepoLinkOperation): SentRequest[] {
        return this.requests.filter((request) => request.operation === operation);
    }

    /** Distinct builds started, however many requests asked for them. */
    get buildsStarted(): number {
        return this.builds.size;
    }

    install(): void {
        server.use(
            http.get(ORG_TEAM_URL, () => ok(orgDto())),
            http.get(`${ORG_TEAM_URL}/members/me`, () => ok(this.me)),
            http.get(`${ORG_TEAM_URL}/members`, () => page([AMANI, WANJIRU, FATUMA, KEVIN])),
            http.get(`${ORG_TEAM_URL}/teams`, () => page([])),
            http.get(`${ORG_TEAM_URL}/apps/:appId`, () => ok(this.app)),
            http.get(`${GITHUB_URL}/session`, () =>
                this.answer({ operation: "session" }, () =>
                    this.session === null
                        ? error(404, "GITHUB_SESSION_NOT_FOUND", { message: "No GitHub session." })
                        : ok(this.session),
                ),
            ),
            http.post(`${GITHUB_URL}/authorizations`, () =>
                this.answer({ operation: "authorize" }, () =>
                    ok({ authorizeUrl: AUTHORIZE_URL, expiresAt: "2026-01-15T12:10:00Z" }, "Started", { status: 201 }),
                ),
            ),
            http.get(`${ORG_GITHUB_URL}/installations`, () => page(this.installations, { size: 100 })),
            http.get(`${ORG_GITHUB_URL}/installations/:installationId/repositories`, ({ params, request }) => {
                const query = new URL(request.url).searchParams;
                const installationId = String(params.installationId);
                return this.answer({ operation: "repositories", installationId, params: query }, () =>
                    this.listRepositories(query),
                );
            }),
            http.get(LINK_URL, () =>
                this.answer({ operation: "get" }, () =>
                    this.link === null
                        ? error(404, "REPO_LINK_NOT_FOUND", { message: "This app is not linked to a repository." })
                        : ok(this.link),
                ),
            ),
            http.put(LINK_URL, async ({ request }) => {
                const raw = await request.text();
                return this.answer({ operation: "link", raw }, () => this.linkRepository(JSON.parse(raw) as LinkBody));
            }),
            http.patch(LINK_URL, async ({ request }) => {
                const raw = await request.text();
                return this.answer({ operation: "tune", raw }, () => this.tune(JSON.parse(raw) as TuneBody));
            }),
            http.post(`${LINK_URL}/verification`, () =>
                this.answer({ operation: "takeOver" }, () => {
                    if (this.session === null) return error(403, "GITHUB_AUTHORIZATION_REQUIRED");
                    if (this.link === null) return error(404, "REPO_LINK_NOT_FOUND");
                    this.link = {
                        ...this.link,
                        verification: {
                            verifiedByUserId: this.me.userId,
                            githubLogin: this.session.githubLogin,
                            permission: "admin",
                            accessVerifiedAt: "2026-01-15T12:00:00Z",
                            accessCheckedAt: "2026-01-15T12:00:00Z",
                        },
                        version: this.link.version + 1,
                    };
                    return ok(this.link, "Repository access verified");
                }),
            ),
            http.delete(LINK_URL, () =>
                this.answer({ operation: "disconnect" }, () => {
                    if (this.link?.status !== "ACTIVE") return error(404, "REPO_LINK_NOT_FOUND");
                    this.link = {
                        ...this.link,
                        status: "DISCONNECTED",
                        disconnectReason: "UNLINKED_BY_USER",
                        disconnectedAt: "2026-01-15T12:00:00Z",
                        lastAcceptedHead: null,
                    };
                    return new HttpResponse(null, { status: 204 });
                }),
            ),
            http.post(`${LINK_URL}/builds`, async ({ request }) => {
                const raw = await request.text();
                const idempotencyKey = request.headers.get("Idempotency-Key");
                return this.answer({ operation: "build", raw, idempotencyKey }, () =>
                    this.build(idempotencyKey, JSON.parse(raw) as { branch?: string }),
                );
            }),
        );
    }

    private listRepositories(query: URLSearchParams): Response {
        if (this.session === null) return error(403, "GITHUB_AUTHORIZATION_REQUIRED", { message: "Authorize first." });
        const q = query.get("q") ?? "";
        const number = Number(query.get("page") ?? "0");
        const size = Number(query.get("size") ?? "20");
        const matching = this.repositories.filter((repository) => repository.fullName.split("/")[1]?.startsWith(q));
        return page(matching.slice(number * size, (number + 1) * size), {
            page: number,
            size,
            totalElements: matching.length,
        });
    }

    private linkRepository(body: LinkBody): Response {
        if (this.session === null) return error(403, "GITHUB_AUTHORIZATION_REQUIRED");
        if (this.link?.status === "ACTIVE") return error(409, "REPO_LINK_EXISTS");
        const repository = this.repositories.find(({ repoId }) => repoId === body.repoId);
        if (repository === undefined) return error(403, "REPOSITORY_NOT_ACCESSIBLE");
        if (!repository.linkable) return error(403, "REPOSITORY_PERMISSION_TOO_LOW");
        const branch = body.productionBranch ?? repository.defaultBranch;
        if (!this.branches.has(branch)) return error(422, "BRANCH_NOT_FOUND");
        this.link = repoLinkDto({
            installationId: body.installationId,
            repoId: repository.repoId,
            repoFullName: repository.fullName,
            productionBranch: branch,
            rootDirectory: body.rootDirectory ?? "",
            autoDeploy: body.autoDeploy,
            version: 0,
        });
        return ok(this.link, "Repository linked", { status: 201 });
    }

    private tune(body: TuneBody): Response {
        if (this.link?.status !== "ACTIVE") return error(404, "REPO_LINK_NOT_FOUND");
        if (body.version !== this.link.version) return error(409, "CONCURRENT_MODIFICATION");
        if (body.productionBranch != null && !this.branches.has(body.productionBranch)) {
            return error(422, "BRANCH_NOT_FOUND");
        }
        this.link = {
            ...this.link,
            ...(body.productionBranch != null && { productionBranch: body.productionBranch }),
            ...(body.rootDirectory != null && { rootDirectory: body.rootDirectory }),
            ...(body.autoDeploy != null && { autoDeploy: body.autoDeploy }),
            version: this.link.version + 1,
        };
        return ok(this.link, "Repository link updated");
    }

    private build(idempotencyKey: string | null, body: { branch?: string }): Response {
        if (idempotencyKey === null) return error(400, "BAD_REQUEST");
        const earlier = this.builds.get(idempotencyKey);
        if (earlier !== undefined) return ok(earlier, "Build requested", { status: 202 });
        const branch = body.branch ?? this.link?.productionBranch ?? "main";
        if (!this.branches.has(branch)) return error(422, "BRANCH_NOT_FOUND");
        const result = {
            eventId: `0d6c1a2b-3c4d-4e5f-8a6b-${String(this.builds.size + 1).padStart(12, "0")}`,
            commitSha: "3f9c2e1b7d04a8c95e21f06d3b4a7c8e9f1d2a05",
            branch,
        };
        this.builds.set(idempotencyKey, result);
        return ok(result, "Build requested", { status: 202 });
    }

    private answer(request: SentRequest, work: () => Response): Response {
        this.requests.push(request);
        const reply = this.replies.get(request.operation);
        if (reply === undefined) return work();
        this.replies.delete(request.operation);
        return reply(work);
    }
}

interface LinkBody {
    readonly installationId: number;
    readonly repoId: number;
    readonly productionBranch?: string;
    readonly rootDirectory?: string;
    readonly autoDeploy: boolean;
    readonly deployNow: boolean;
}

interface TuneBody {
    readonly productionBranch?: string | null;
    readonly rootDirectory?: string | null;
    readonly autoDeploy?: boolean | null;
    readonly version: number;
}

export interface RepoLinksScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: RepoLinksBackend;
    readonly clock?: Clock;
}

function seeded(backend: RepoLinksBackend) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    return queryClient;
}

/** Every read the screen starts has answered, including the ones that only start once an earlier one did. */
async function settled(queryClient: ReturnType<typeof createTestQueryClient>) {
    await waitFor(() => {
        expect(screen.queryAllByRole("status", { name: /^Loading/ })).toEqual([]);
        expect(queryClient.isFetching()).toBe(0);
    });
}

/**
 * The connect page as its route mounts it, once everything it reads first has arrived; `redirects` is for a
 * caller the page sends away, where nothing ever arrives.
 */
export async function renderConnect({
    backend = new RepoLinksBackend(null),
    clock,
    redirects = false,
    ...options
}: RepoLinksScenario & { readonly redirects?: boolean } = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <ConnectRepoView orgId={KILIMA} orgName="Kilima Labs" appId={CHECKOUT_ID} />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...(clock !== undefined && { clock }), ...options },
    );
    if (!redirects) {
        await screen.findByRole("heading", { level: 1 });
        await settled(queryClient);
    }
    return { ...rendered, backend };
}

/** The app page as its route composes it: the repository tab and the build button in the app's slots. */
export async function renderAppPage({ backend = new RepoLinksBackend(), clock, ...options }: RepoLinksScenario = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <AppView
                orgId={KILIMA}
                orgName="Kilima Labs"
                appId={CHECKOUT_ID}
                repository={<RepositoryPanel orgId={KILIMA} orgName="Kilima Labs" appId={CHECKOUT_ID} />}
                actions={<ManualBuildButton orgId={KILIMA} appId={CHECKOUT_ID} />}
            />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...(clock !== undefined && { clock }), ...options },
    );
    await screen.findByRole("heading", { level: 1, name: backend.app.name });
    await settled(queryClient);
    return { ...rendered, backend };
}
