import { asOrgId } from "@/shared/domain/ids";
import type { IdentityClient } from "@/shared/infrastructure/api/clients";
import { unwrap } from "@/shared/infrastructure/api/envelope";
import { IDEMPOTENCY_KEY_HEADER } from "@/shared/infrastructure/api/headers";
import { z } from "zod";
import type { SignupRepository } from "../application/ports";

const signupResponseSchema = z.object({
    orgId: z.string().min(1),
    orgName: z.string(),
    slug: z.string(),
});

const slugAvailabilitySchema = z.object({
    slug: z.string(),
    available: z.boolean(),
});

export function httpSignupRepository(identity: IdentityClient): SignupRepository {
    return {
        async signUp(details, idempotencyKey) {
            const receipt = unwrap(
                await identity.POST("/signup", {
                    params: { header: { [IDEMPOTENCY_KEY_HEADER]: idempotencyKey } },
                    body: {
                        organizationName: details.organizationName,
                        slug: details.slug,
                        email: details.email,
                        displayName: details.displayName,
                        password: details.password,
                    },
                }),
                signupResponseSchema,
            );
            return { orgId: asOrgId(receipt.orgId), organizationName: receipt.orgName, slug: receipt.slug };
        },

        async checkSlug(slug, request) {
            return unwrap(
                await identity.GET("/signup/slugs/{slug}/availability", {
                    params: { path: { slug } },
                    ...(request?.signal !== undefined && { signal: request.signal }),
                }),
                slugAvailabilitySchema,
            );
        },
    };
}
