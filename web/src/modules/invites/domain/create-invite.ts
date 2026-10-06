import { MAX_EMAIL_LENGTH } from "@/shared/domain/email";
import { grantableInviteRoles } from "@/shared/domain/permissions";
import type { Role } from "@/shared/domain/role";
import { z } from "zod";
import { INVITABLE_ROLES, isInvitableRole, type InvitableRole } from "./invite";

export const ENTER_EMAIL_MESSAGE = "Enter their email address";
export const INVALID_EMAIL_MESSAGE = "Enter a valid email address";
export const ROLE_NOT_GRANTABLE_MESSAGE = "You can't invite someone with that role";

export interface CreateInviteForm {
    email: string;
    role: string;
}

export interface NewInvite {
    readonly email: string;
    readonly role: InvitableRole;
}

/** The roles `caller` may offer, as the backend's `MembershipPolicy.checkCanInvite` allows them. */
export function invitableRolesFor(caller: Role): readonly InvitableRole[] {
    return grantableInviteRoles(caller).filter(isInvitableRole);
}

/** Developer when the caller may grant it, which every inviter can. */
export function defaultInviteRole(caller: Role): InvitableRole | undefined {
    const roles = invitableRolesFor(caller);
    return roles.includes("DEVELOPER") ? "DEVELOPER" : roles[0];
}

/** Addresses compare case-insensitively on the backend, so they are sent the way it stores them. */
export function createInviteSchema(caller: Role) {
    const grantable = invitableRolesFor(caller);
    return z.object({
        email: z
            .string()
            .trim()
            .min(1, ENTER_EMAIL_MESSAGE)
            .max(MAX_EMAIL_LENGTH, INVALID_EMAIL_MESSAGE)
            .pipe(z.email(INVALID_EMAIL_MESSAGE))
            .transform((email) => email.toLowerCase()),
        role: z
            .string()
            .pipe(z.enum(INVITABLE_ROLES, ROLE_NOT_GRANTABLE_MESSAGE))
            .refine((role) => grantable.includes(role), ROLE_NOT_GRANTABLE_MESSAGE),
    }) satisfies z.ZodType<NewInvite, CreateInviteForm>;
}
