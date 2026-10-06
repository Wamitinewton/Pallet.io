import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant } from "@/shared/domain/instant";
import type { IdentityClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { z } from "zod";
import type { AccountRepository } from "../application/ports";
import { asAccountSessionId } from "../domain/account-session";
import { ACCOUNT_STATUSES } from "../domain/profile";

const noData = z.null();

const profileSchema = z.object({
    sub: z.string().min(1),
    email: z.string(),
    displayName: z.string(),
    status: z.enum(ACCOUNT_STATUSES),
});

const instantSchema = z.iso.datetime({ offset: true }).transform(asIsoInstant);

const sessionSchema = z.object({
    id: z.string().min(1).transform(asAccountSessionId),
    ipAddress: z
        .string()
        .nullish()
        .transform((value) => (value === null || value === "" ? undefined : value)),
    startedAt: instantSchema,
    lastAccessedAt: instantSchema,
});

export function httpAccountRepository(identity: IdentityClient): AccountRepository {
    return {
        async getMyProfile() {
            const { sub, email, displayName, status } = unwrap(await identity.GET("/users/me"), profileSchema);
            return { userId: asUserId(sub), email, displayName, status };
        },

        async updateProfile({ displayName }) {
            unwrap(await identity.PATCH("/users/me", { body: { displayName } }), noData);
        },

        async changePassword({ currentPassword, newPassword }) {
            unwrap(await identity.POST("/users/me/password", { body: { currentPassword, newPassword } }), noData);
        },

        async listSessions() {
            return unwrap(await identity.GET("/users/me/sessions"), z.array(sessionSchema));
        },

        async revokeSession(sessionId) {
            unwrap(
                await identity.DELETE("/users/me/sessions/{sessionId}", { params: { path: { sessionId } } }),
                noData,
            );
        },

        async revokeOtherSessions() {
            unwrap(await identity.DELETE("/users/me/sessions"), noData);
        },
    };
}
