import { asOrgId } from "@/shared/domain/ids";
import type { OrgKind } from "@/shared/domain/org-kind";
import { ok, page } from "@/test/msw/envelopes";
import { sessionSummaryBody } from "@/test/msw/identity";
import { memberDto, type MemberDto } from "@/test/msw/org-team";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import type { Route } from "next";
import { expect } from "vitest";
import { MembersView, type MembersInvitesSlot } from "../MembersView";
import { OrgAccessProvider } from "../OrgAccessProvider";
import { memberKeys } from "../queries";

export const KILIMA = asOrgId("org-kilima");
export const MEMBERS_URL = bffUrl("/org-team/orgs/org-kilima/members");
export const memberUrl = (userId: string) => `${MEMBERS_URL}/${userId}`;

export const AMANI = memberDto();
export const WANJIRU = memberDto({
    userId: "user-wanjiru",
    email: "wanjiru@kilimalabs.co",
    displayName: "Wanjiru Kamau",
    role: "ADMIN",
    joinedAt: "2026-01-03T09:00:00Z",
});
export const GRACE = memberDto({
    userId: "user-grace",
    email: "grace.njeri@kilimalabs.co",
    displayName: "Grace Njeri",
    role: "ADMIN",
    joinedAt: "2026-01-04T09:00:00Z",
});
export const FATUMA = memberDto({
    userId: "user-fatuma",
    email: "fatuma@kilimalabs.co",
    displayName: "Fatuma Hassan",
    role: "DEVELOPER",
    joinedAt: "2026-01-05T09:00:00Z",
});
export const KEVIN = memberDto({
    userId: "user-kevin",
    email: "kevin.ochieng@gmail.com",
    displayName: "Kevin Ochieng",
    role: "VIEWER",
    joinedAt: "2026-01-06T09:00:00Z",
});
export const TEAM: readonly MemberDto[] = [AMANI, WANJIRU, GRACE, FATUMA, KEVIN];

/** The org-team members backend as far as these screens use it: the list filters, searches and pages. */
export class MembersBackend {
    readonly listRequests: URLSearchParams[] = [];
    /** Answers every list request with this while set. */
    listFailure: (() => Response) | undefined;

    constructor(
        public members: MemberDto[] = [...TEAM],
        public me: MemberDto = AMANI,
    ) {}

    listed(param: string): (string | null)[] {
        return this.listRequests.map((request) => request.get(param));
    }

    install(): void {
        server.use(
            http.get(`${MEMBERS_URL}/me`, () => ok(this.me)),
            http.get(MEMBERS_URL, ({ request }) => {
                const params = new URL(request.url).searchParams;
                this.listRequests.push(params);
                if (this.listFailure !== undefined) return this.listFailure();
                const q = (params.get("q") ?? "").toLowerCase();
                const matching = this.members.filter(
                    (member) =>
                        member.status === (params.get("status") ?? "ACTIVE") &&
                        (params.get("role") === null || member.role === params.get("role")) &&
                        (member.displayName.toLowerCase().startsWith(q) || member.email.startsWith(q)),
                );
                const number = Number(params.get("page") ?? "0");
                const size = Number(params.get("size") ?? "20");
                return page(matching.slice(number * size, (number + 1) * size), {
                    page: number,
                    size,
                    totalElements: matching.length,
                });
            }),
            http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")),
        );
    }

    replace(member: MemberDto): void {
        this.members = this.members.map((current) => (current.userId === member.userId ? member : current));
    }
}

export interface MembersScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: MembersBackend;
    readonly orgKind?: OrgKind;
    readonly invites?: MembersInvitesSlot;
}

/**
 * The members page as the org layout mounts it: inside the caller's access, with their membership already
 * read, against the fake backend.
 */
export async function renderMembers({
    backend = new MembersBackend(),
    orgKind = "TEAM",
    invites,
    ...options
}: MembersScenario = {}) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind={orgKind}>
            <MembersView
                orgId={KILIMA}
                orgName="Kilima Labs"
                orgHref={"/orgs/org-kilima" as Route}
                {...(invites !== undefined && { invites })}
            />
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("table", { name: "Loading members" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}
