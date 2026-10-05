import type { IdentityClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { z } from "zod";
import type { PasswordRepository } from "../application/ports";

const noData = z.null();

export function httpPasswordRepository(identity: IdentityClient): PasswordRepository {
    return {
        async requestReset(email) {
            unwrap(await identity.POST("/auth/password/forgot", { body: { email } }), noData);
        },

        async reset({ token, newPassword }) {
            unwrap(await identity.POST("/auth/password/reset", { body: { token, newPassword } }), noData);
        },
    };
}
