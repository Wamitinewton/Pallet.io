import { memberKeys, OrgAccessProvider } from "@/modules/members";
import { asOrgId } from "@/shared/domain/ids";
import type { OrgKind } from "@/shared/domain/org-kind";
import { ok, page } from "@/test/msw/envelopes";
import { sessionSummaryBody } from "@/test/msw/identity";
import { inviteDto, memberDto, type InviteDto, type MemberDto } from "@/test/msw/org-team";
import { bffUrl, server, sessionApiUrl } from "@/test/msw/server";
import { createTestQueryClient, renderWithProviders, type RenderWithProvidersOptions } from "@/test/render";
import { screen, waitFor } from "@testing-library/react";
import { http } from "msw";
import { expect } from "vitest";
import { InvitePeopleButton } from "../InvitePeopleButton";
import { InvitesProvider } from "../InvitesProvider";
import { InvitesTab } from "../InvitesTab";

export const KILIMA = asOrgId("org-kilima");
export const INVITES_URL = bffUrl("/org-team/orgs/org-kilima/invites");
const MEMBERS_URL = bffUrl("/org-team/orgs/org-kilima/members");

export const AMANI = memberDto();
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

/** Pending, sent twice by Grace, expiring a day after the tests' clock. */
export const DAVID = inviteDto();
/** Pending, sent once by Amani. */
export const LUCY = inviteDto({
    id: "0b8e5d3a-1c2f-4e6a-8b9c-7d6e5f4a3b2c",
    email: "lucy@wambui.dev",
    role: "VIEWER",
    invitedByUserId: AMANI.userId,
    sendCount: 1,
    expiresAt: "2026-01-18T09:00:00Z",
    createdAt: "2026-01-15T09:00:00Z",
});
/** Expired after every send, from someone who has since left. */
export const OTIENO = inviteDto({
    id: "9a7b6c5d-4e3f-4a1b-a2c3-d4e5f6a7b8c9",
    email: "otieno.j@savannapay.com",
    status: "EXPIRED",
    invitedByUserId: "user-gone",
    sendCount: 4,
    expiresAt: "2026-01-10T12:00:00Z",
    createdAt: "2026-01-07T12:00:00Z",
});

/** The org-team invites backend as far as this tab uses it: lists filter by status and page. */
export class InvitesBackend {
    readonly listRequests: URLSearchParams[] = [];
    readonly created: unknown[] = [];
    readonly resent: string[] = [];
    readonly revoked: string[] = [];
    /** Answers every list request with this while set. */
    listFailure: (() => Response) | undefined;
    /** Answers creating an invite with this instead of creating one, while set. */
    createReply: (() => Response) | undefined;
    /** Answers a resend with this instead of resending, while set. */
    resendReply: ((invite: InviteDto) => Response | Promise<Response>) | undefined;
    /** Answers a revoke with this instead of revoking, while set. */
    revokeReply: ((invite: InviteDto) => Response | Promise<Response>) | undefined;

    constructor(
        public invites: InviteDto[] = [DAVID, LUCY, OTIENO],
        public me: MemberDto = AMANI,
        public members: MemberDto[] = [AMANI, GRACE, FATUMA],
    ) {}

    listed(param: string): (string | null)[] {
        return this.listRequests.map((request) => request.get(param));
    }

    replace(invite: InviteDto): void {
        this.invites = this.invites.map((current) => (current.id === invite.id ? invite : current));
    }

    install(): void {
        server.use(
            http.get(`${MEMBERS_URL}/me`, () => ok(this.me)),
            http.get(MEMBERS_URL, () => page(this.members, { size: 100 })),
            http.get(sessionApiUrl(""), () => ok(sessionSummaryBody(), "Current session")),
            http.get(INVITES_URL, ({ request }) => {
                const params = new URL(request.url).searchParams;
                this.listRequests.push(params);
                if (this.listFailure !== undefined) return this.listFailure();
                const status = params.get("status");
                const matching = this.invites.filter((invite) => status === null || invite.status === status);
                const number = Number(params.get("page") ?? "0");
                const size = Number(params.get("size") ?? "20");
                return page(matching.slice(number * size, (number + 1) * size), {
                    page: number,
                    size,
                    totalElements: matching.length,
                });
            }),
            http.post(INVITES_URL, async ({ request }) => {
                const body = (await request.json()) as { email: string; role: InviteDto["role"] };
                this.created.push(body);
                if (this.createReply !== undefined) return this.createReply();
                const invite = inviteDto({
                    id: crypto.randomUUID(),
                    ...body,
                    invitedByUserId: this.me.userId,
                    sendCount: 1,
                });
                this.invites = [invite, ...this.invites];
                return ok(invite, "Invite created", { status: 201 });
            }),
            http.post(`${INVITES_URL}/:inviteId/resend`, ({ params }) => {
                const invite = this.find(String(params.inviteId));
                this.resent.push(invite.id);
                if (this.resendReply !== undefined) return this.resendReply(invite);
                const resent = { ...invite, sendCount: invite.sendCount + 1, expiresAt: "2026-01-18T12:00:00Z" };
                this.replace(resent);
                return ok(resent, "Invite resent");
            }),
            http.delete(`${INVITES_URL}/:inviteId`, ({ params }) => {
                const invite = this.find(String(params.inviteId));
                this.revoked.push(invite.id);
                if (this.revokeReply !== undefined) return this.revokeReply(invite);
                this.replace({ ...invite, status: "REVOKED" });
                return new Response(null, { status: 204 });
            }),
        );
    }

    private find(id: string): InviteDto {
        const invite = this.invites.find((candidate) => candidate.id === id);
        if (invite === undefined) throw new Error(`No invite ${id}`);
        return invite;
    }
}

export interface InvitesScenario extends Pick<RenderWithProvidersOptions, "searchParams" | "onUrlUpdate"> {
    readonly backend?: InvitesBackend;
    readonly orgKind?: OrgKind;
}

/**
 * The invites tab and the button beside the page title, as the members page mounts them: inside the
 * caller's access, with their membership already read, against the fake backend.
 */
export async function renderInvites({
    backend = new InvitesBackend(),
    orgKind = "TEAM",
    ...options
}: InvitesScenario = {}) {
    backend.install();
    const queryClient = createTestQueryClient();
    queryClient.setQueryData(memberKeys.mine(KILIMA), backend.me);
    const rendered = renderWithProviders(
        <OrgAccessProvider orgId={KILIMA} orgKind={orgKind}>
            <InvitesProvider orgId={KILIMA} orgName="Kilima Labs">
                <InvitePeopleButton />
                <InvitesTab />
            </InvitesProvider>
        </OrgAccessProvider>,
        { urlMemory: true, queryClient, ...options },
    );
    await waitFor(() => {
        expect(screen.queryByRole("table", { name: "Loading invites" })).not.toBeInTheDocument();
    });
    return { ...rendered, backend };
}
