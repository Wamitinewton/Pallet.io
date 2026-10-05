import type { IdentityClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { z } from "zod";
import type { VerificationRepository } from "../application/ports";

const noData = z.null();

export function httpVerificationRepository(identity: IdentityClient): VerificationRepository {
    return {
        async verify({ email, code }) {
            unwrap(await identity.POST("/auth/email/verify", { body: { email, code } }), noData);
        },

        async resend(email) {
            unwrap(await identity.POST("/auth/email/resend-verification", { body: { email } }), noData);
        },
    };
}
