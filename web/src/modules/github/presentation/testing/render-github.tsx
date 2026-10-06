import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { asOrgId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import {
    githubSessionDto,
    installationLinkDto,
    visibleInstallationDto,
    type GitHubSessionDto,
    type InstallationLinkDto,
    type VisibleInstallationDto,
} from "@/test/msw/git-integration";
import { memberDto, orgDto, type MemberDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect } from "vitest";
import { GitHubView } from "../GitHubView";

export const KILIMA = asOrgId("org-kilima");
export const GITHUB_PAGE = "/orgs/org-kilima/github";
const ORG_TEAM_URL = bffUrl("/org-team/orgs/org-kilima");
const GITHUB_URL = bffUrl("/git-integration/github");
const ORG_GITHUB_URL = bffUrl("/git-integration/orgs/org-kilima/github");

export const AMANI = memberDto();
export const WANJIRU = memberDto({ userId: "user-wanjiru", displayName: "Wanjiru Kamau", role: "ADMIN" });
export const KEVIN = memberDto({ userId: "user-kevin", displayName: "Kevin Ochieng", role: "VIEWER" });

export const KILIMA_LABS = installationLinkDto();
export const WANJIRU_ACCOUNT = installationLinkDto({
    installationId: 41000003,
    accountLogin: "wanjiru-kamau",
    accountType: "User",
    status: "SUSPENDED",
    linkedByUserId: "user-gone",
    linkedAt: "2026-01-12T09:00:00Z",
});

export const AUTHORIZE_URL = "https://github.com/login/oauth/authorize?client_id=pallet&state=signed-authorize";
export const INSTALL_URL = "https://github.com/apps/pallet/installations/new?state=signed-install";

export type GitHubOperation =
    | "session"
    | "endSession"
    | "authorize"
    | "complete"
    | "visible"
    | "installations"
    | "installSession"
    | "link"
    | "unlink";

interface SentRequest {
    readonly operation: GitHubOperation;
    readonly body?: unknown;
    /** What the address bar said when the request left. */
    readonly address: string;
    readonly installationId?: string;
}

/**
 * git-integration-service as these screens use it, with the caller's org-team membership beside it.
 * `replies` answers the next request of a kind in its place.
 */
export class GitHubBackend {
    readonly requests: SentRequest[] = [];
    readonly replies = new Map<GitHubOperation, () => Response>();

    constructor(
        public installations: InstallationLinkDto[] = [KILIMA_LABS, WANJIRU_ACCOUNT],
        public session: GitHubSessionDto | null = githubSessionDto(),
        public visible: VisibleInstallationDto[] = [visibleInstallationDto()],
        public me: MemberDto = AMANI,
        public members: MemberDto[] = [AMANI, WANJIRU, KEVIN],
    ) {}

    sent(operation: GitHubOperation): SentRequest[] {
        return this.requests.filter((request) => request.operation === operation);
    }

    install(): void {
        server.use(
            http.get(ORG_TEAM_URL, () => ok(orgDto())),
            http.get(`${ORG_TEAM_URL}/members/me`, () => ok(this.me)),
            http.get(`${ORG_TEAM_URL}/members`, () => page(this.members)),
            http.get(`${GITHUB_URL}/session`, () =>
                this.answer({ operation: "session" }, () =>
                    this.session === null
                        ? error(404, "GITHUB_SESSION_NOT_FOUND", { message: "No GitHub session." })
                        : ok(this.session),
                ),
            ),
            http.delete(`${GITHUB_URL}/session`, () =>
                this.answer({ operation: "endSession" }, () => {
                    this.session = null;
                    return new HttpResponse(null, { status: 204 });
                }),
            ),
            http.post(`${GITHUB_URL}/authorizations`, () =>
                this.answer({ operation: "authorize" }, () =>
                    ok({ authorizeUrl: AUTHORIZE_URL, expiresAt: "2026-01-15T12:10:00Z" }, "Started", { status: 201 }),
                ),
            ),
            http.post(`${GITHUB_URL}/authorizations/complete`, async ({ request }) => {
                const body = (await request.json()) as { code: string; state: string };
                return this.answer({ operation: "complete", body }, () => {
                    this.session = githubSessionDto();
                    return ok(this.session);
                });
            }),
            http.get(`${GITHUB_URL}/installations`, () =>
                this.answer({ operation: "visible" }, () =>
                    this.session === null
                        ? error(403, "GITHUB_AUTHORIZATION_REQUIRED", { message: "Authorize first." })
                        : page(this.visible, { size: 100 }),
                ),
            ),
            http.get(`${ORG_GITHUB_URL}/installations`, () =>
                this.answer({ operation: "installations" }, () => page(this.installations, { size: 100 })),
            ),
            http.post(`${ORG_GITHUB_URL}/install-sessions`, () =>
                this.answer({ operation: "installSession" }, () =>
                    ok({ installUrl: INSTALL_URL, expiresAt: "2026-01-15T12:10:00Z" }, "Started", { status: 201 }),
                ),
            ),
            http.post(`${ORG_GITHUB_URL}/installations`, async ({ request }) => {
                const body = (await request.json()) as { installationId: number };
                return this.answer({ operation: "link", body }, () => this.link(body.installationId));
            }),
            http.delete(`${ORG_GITHUB_URL}/installations/:installationId`, ({ params }) => {
                const installationId = String(params.installationId);
                return this.answer({ operation: "unlink", installationId }, () => {
                    const before = this.installations.length;
                    this.installations = this.installations.filter(
                        (link) => String(link.installationId) !== installationId,
                    );
                    return this.installations.length === before
                        ? error(404, "INSTALLATION_NOT_FOUND", { message: "Not linked." })
                        : new HttpResponse(null, { status: 204 });
                });
            }),
        );
    }

    private link(installationId: number): Response {
        const existing = this.installations.find((link) => link.installationId === installationId);
        if (existing !== undefined) return ok(existing, "Installation already linked");
        const visible = this.visible.find((candidate) => candidate.installationId === installationId);
        const link = installationLinkDto({
            installationId,
            accountLogin: visible?.accountLogin ?? "new-account",
            accountType: visible?.accountType ?? "Organization",
            linkedAt: "2026-01-15T12:00:00Z",
        });
        this.installations = [link, ...this.installations];
        return ok(link, "Installation linked", { status: 201 });
    }

    private answer(request: Omit<SentRequest, "address">, work: () => Response): Response {
        const { pathname, search } = window.location;
        this.requests.push({ ...request, address: `${pathname}${search}` });
        const reply = this.replies.get(request.operation);
        if (reply === undefined) return work();
        this.replies.delete(request.operation);
        return reply();
    }
}

export interface GitHubScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: GitHubBackend;
}

export function seeded(backend: GitHubBackend) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    return queryClient;
}

/** The GitHub page as the org layout mounts it, once the installations and the session have arrived. */
export async function renderGitHub({ backend = new GitHubBackend(), ...options }: GitHubScenario = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <GitHubView orgId={KILIMA} orgName="Kilima Labs" />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("table", { name: "Loading installations" })).not.toBeInTheDocument();
        expect(screen.queryByRole("status", { name: "Loading your GitHub sign-in" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}
