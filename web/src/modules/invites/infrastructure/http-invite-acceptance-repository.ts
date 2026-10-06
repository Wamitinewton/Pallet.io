import { asIsoInstant } from "@/shared/domain/instant";
import { ROLES } from "@/shared/domain/role";
import type { IdentityClient, OrgTeamClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import type { components } from "@/shared/infrastructure/api/generated/identity";
import { z } from "zod";
import type { InviteAcceptanceRepository } from "../application/ports";
import type { InvitePreview } from "../domain/invite-preview";

const previewSchema = z.object({
    orgName: z.string().min(1),
    role: z.enum(ROLES),
    inviterName: z.string().min(1),
    maskedEmail: z.string().min(1),
    expiresAt: z.iso.datetime({ offset: true }),
});

const noData = z.null();

function toPreview(dto: z.output<typeof previewSchema>): InvitePreview {
    return { ...dto, expiresAt: asIsoInstant(dto.expiresAt) };
}

// identity-service/15 makes `password` optional for a signed-in account; the spec snapshot predates it.
const signedInAcceptance = {} as components["schemas"]["InviteAcceptRequest"];

export interface InviteAcceptanceClients {
    readonly identity: IdentityClient;
    readonly orgTeam: OrgTeamClient;
}

export function httpInviteAcceptanceRepository({
    identity,
    orgTeam,
}: InviteAcceptanceClients): InviteAcceptanceRepository {
    return {
        async preview(token) {
            return toPreview(
                unwrap(await orgTeam.GET("/invites/{token}", { params: { path: { token } } }), previewSchema),
            );
        },

        async acceptWithNewAccount(token, password) {
            unwrap(
                await identity.POST("/invites/{token}/accept", { params: { path: { token } }, body: { password } }),
                noData,
            );
        },

        async acceptAsSignedIn(token) {
            unwrap(
                await identity.POST("/invites/{token}/accept", {
                    params: { path: { token } },
                    body: signedInAcceptance,
                }),
                noData,
            );
        },
    };
}
