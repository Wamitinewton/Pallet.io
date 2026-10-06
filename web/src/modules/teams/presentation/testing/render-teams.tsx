import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { asOrgId, asTeamId } from "@/shared/domain/ids";
import { error, ok, page } from "@/test/msw/envelopes";
import { memberDto, orgDto, teamDto, type MemberDto, type TeamDto } from "@/test/msw/org-team";
import { bffUrl, server } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { expect } from "vitest";
import { TeamsView } from "../TeamsView";
import { TeamView } from "../TeamView";

export const KILIMA = asOrgId("org-kilima");
const ORG_URL = bffUrl("/org-team/orgs/org-kilima");
export const TEAMS_URL = `${ORG_URL}/teams`;
export const teamUrl = (teamId: string) => `${TEAMS_URL}/${teamId}`;

export const AMANI = memberDto();
export const WANJIRU = memberDto({
    userId: "user-wanjiru",
    email: "wanjiru@kilimalabs.co",
    displayName: "Wanjiru Kamau",
    role: "ADMIN",
});
export const GRACE = memberDto({
    userId: "user-grace",
    email: "grace.njeri@kilimalabs.co",
    displayName: "Grace Njeri",
    role: "ADMIN",
});
export const FATUMA = memberDto({
    userId: "user-fatuma",
    email: "fatuma@kilimalabs.co",
    displayName: "Fatuma Hassan",
    role: "DEVELOPER",
});
export const KEVIN = memberDto({
    userId: "user-kevin",
    email: "kevin.ochieng@gmail.com",
    displayName: "Kevin Ochieng",
    role: "VIEWER",
});
export const PEOPLE: readonly MemberDto[] = [AMANI, WANJIRU, GRACE, FATUMA, KEVIN];

export const PAYMENTS = teamDto();
export const WEB = teamDto({
    id: "7c4e2a91-0b3d-4f5e-8a6c-9d1e2f3a4b5c",
    name: "Web",
    slug: "web",
    createdAt: "2026-01-10T09:00:00Z",
});
export const PAYMENTS_ID = asTeamId(PAYMENTS.id);

export type TeamOperation = "list" | "get" | "create" | "rename" | "delete" | "add" | "remove";

interface Request {
    readonly operation: TeamOperation;
    readonly body?: unknown;
    readonly params?: URLSearchParams;
}

function newTeamId(sequence: number): string {
    return `0d0c1a2b-3c4d-4e5f-8a6b-${String(sequence).padStart(12, "0")}`;
}

function byField(field: string, direction: string) {
    return (a: TeamDto, b: TeamDto) => {
        const order = field === "createdAt" ? a.createdAt.localeCompare(b.createdAt) : a.name.localeCompare(b.name);
        return direction === "desc" ? -order : order;
    };
}

/**
 * The org-team teams backend as these screens use it. Counts are read from the rosters, as the backend
 * reads them from the assignments, and `replies` answers the next request of a kind in its place.
 */
export class TeamsBackend {
    readonly requests: Request[] = [];
    readonly replies = new Map<TeamOperation, () => Response>();
    private sequence = 0;

    constructor(
        public teams: TeamDto[] = [PAYMENTS, WEB],
        public rosters = new Map<string, MemberDto[]>([[PAYMENTS.id, [GRACE, WANJIRU]]]),
        public people: MemberDto[] = [...PEOPLE],
        public me: MemberDto = AMANI,
    ) {}

    sent(operation: TeamOperation): Request[] {
        return this.requests.filter((request) => request.operation === operation);
    }

    roster(teamId: string): MemberDto[] {
        return this.rosters.get(teamId) ?? [];
    }

    install(): void {
        server.use(
            http.get(ORG_URL, () => ok(orgDto())),
            http.get(`${ORG_URL}/members/me`, () => ok(this.me)),
            http.get(`${ORG_URL}/members`, ({ request }) => {
                const params = new URL(request.url).searchParams;
                const q = (params.get("q") ?? "").toLowerCase();
                const matching = this.people.filter(
                    (person) => person.displayName.toLowerCase().startsWith(q) || person.email.startsWith(q),
                );
                return this.page(matching, params);
            }),
            http.get(TEAMS_URL, ({ request }) => {
                const params = new URL(request.url).searchParams;
                return this.answer({ operation: "list", params }, () => {
                    const [field = "name", direction = "asc"] = (params.get("sort") ?? "name,asc").split(",");
                    return this.page(
                        this.teams.map((team) => this.counted(team)).toSorted(byField(field, direction)),
                        params,
                    );
                });
            }),
            http.post(TEAMS_URL, async ({ request }) => {
                const body = (await request.json()) as { name: string; slug?: string };
                return this.answer({ operation: "create", body }, () => {
                    this.sequence += 1;
                    const team = teamDto({
                        id: newTeamId(this.sequence),
                        name: body.name,
                        slug: body.slug ?? body.name.toLowerCase(),
                    });
                    this.teams.push(team);
                    return ok(team, "Team created", { status: 201 });
                });
            }),
            http.get(`${TEAMS_URL}/:teamId`, ({ params }) =>
                this.answer({ operation: "get" }, () => this.withTeam(String(params.teamId), (team) => ok(team))),
            ),
            http.patch(`${TEAMS_URL}/:teamId`, async ({ params, request }) => {
                const body = (await request.json()) as { name: string };
                return this.answer({ operation: "rename", body }, () =>
                    this.withTeam(String(params.teamId), (team) => {
                        this.teams = this.teams.map((current) =>
                            current.id === team.id ? { ...current, name: body.name } : current,
                        );
                        return ok({ ...team, name: body.name }, "Team updated");
                    }),
                );
            }),
            http.delete(`${TEAMS_URL}/:teamId`, ({ params }) =>
                this.answer({ operation: "delete" }, () =>
                    this.withTeam(String(params.teamId), (team) => {
                        this.teams = this.teams.filter((current) => current.id !== team.id);
                        this.rosters.delete(team.id);
                        return new HttpResponse(null, { status: 204 });
                    }),
                ),
            ),
            http.get(`${TEAMS_URL}/:teamId/members`, ({ params, request }) =>
                this.withTeam(String(params.teamId), (team) =>
                    this.page(
                        this.roster(team.id).toSorted((a, b) => a.displayName.localeCompare(b.displayName)),
                        new URL(request.url).searchParams,
                    ),
                ),
            ),
            http.post(`${TEAMS_URL}/:teamId/members`, async ({ params, request }) => {
                const body = (await request.json()) as { userId: string };
                return this.answer({ operation: "add", body }, () =>
                    this.withTeam(String(params.teamId), (team) => {
                        const person = this.people.find((candidate) => candidate.userId === body.userId);
                        if (person === undefined)
                            return error(404, "MEMBER_NOT_FOUND", { message: "Member not found." });
                        if (this.roster(team.id).some((member) => member.userId === body.userId)) {
                            return error(409, "ALREADY_IN_TEAM", { message: "That member is already in this team." });
                        }
                        this.rosters.set(team.id, [...this.roster(team.id), person]);
                        return ok(person, "Team member added", { status: 201 });
                    }),
                );
            }),
            http.delete(`${TEAMS_URL}/:teamId/members/:userId`, ({ params }) =>
                this.answer({ operation: "remove", body: { userId: params.userId } }, () =>
                    this.withTeam(String(params.teamId), (team) => {
                        const roster = this.roster(team.id);
                        if (!roster.some((member) => member.userId === params.userId)) {
                            return error(404, "MEMBER_NOT_FOUND", { message: "Member not found." });
                        }
                        this.rosters.set(
                            team.id,
                            roster.filter((member) => member.userId !== params.userId),
                        );
                        return new HttpResponse(null, { status: 204 });
                    }),
                ),
            ),
        );
    }

    private answer(request: Request, work: () => Response): Response {
        this.requests.push(request);
        const reply = this.replies.get(request.operation);
        if (reply === undefined) return work();
        this.replies.delete(request.operation);
        return reply();
    }

    private counted(team: TeamDto): TeamDto {
        return { ...team, memberCount: this.roster(team.id).length };
    }

    private withTeam(teamId: string, work: (team: TeamDto) => Response): Response {
        const team = this.teams.find((candidate) => candidate.id === teamId);
        if (team === undefined) return error(404, "TEAM_NOT_FOUND", { message: "Team not found." });
        return work(this.counted(team));
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

export interface TeamsScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: TeamsBackend;
}

function seeded(backend: TeamsBackend) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    return queryClient;
}

/** The teams page as the org layout mounts it: inside the caller's access, with their membership already read. */
export async function renderTeams({ backend = new TeamsBackend(), ...options }: TeamsScenario = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <TeamsView orgId={KILIMA} orgName="Kilima Labs" />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("list", { name: "Loading teams" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}

/** One team's page, read from the fake backend, as the org layout mounts it. */
export async function renderTeam({
    backend = new TeamsBackend(),
    teamId = PAYMENTS.id,
    ...options
}: TeamsScenario & { readonly teamId?: string } = {}) {
    const queryClient = seeded(backend);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind="TEAM">
            <TeamView orgId={KILIMA} orgName="Kilima Labs" teamId={asTeamId(teamId)} />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("status", { name: "Loading team" })).not.toBeInTheDocument();
        expect(screen.queryByRole("list", { name: "Loading people" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}
