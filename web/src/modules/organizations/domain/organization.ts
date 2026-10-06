import { ApiError } from "@/shared/domain/errors";
import type { OrgId, UserId } from "@/shared/domain/ids";
import type { IsoInstant } from "@/shared/domain/instant";
import type { OrgKind } from "@/shared/domain/org-kind";
import type { Role } from "@/shared/domain/role";
import { optionalSlugSchema } from "@/shared/domain/slug";
import { z } from "zod";

export type { OrgKind } from "@/shared/domain/org-kind";

export const ORG_STATUSES = ["ACTIVE", "DELETED"] as const;

export type OrgStatus = (typeof ORG_STATUSES)[number];

export interface OrgSummary {
    readonly orgId: OrgId;
    readonly name: string;
    readonly slug: string;
    readonly kind: OrgKind;
    readonly myRole: Role;
}

export interface OrgCounts {
    readonly members: number;
    readonly teams: number;
    readonly apps: number;
}

export interface Organization {
    readonly orgId: OrgId;
    readonly name: string;
    readonly slug: string;
    readonly kind: OrgKind;
    readonly status: OrgStatus;
    readonly ownerUserId: UserId;
    readonly createdAt: IsoInstant;
    readonly counts: OrgCounts;
}

/** The backend's page ceiling; nobody belongs to more organizations than this in practice. */
export const MY_ORGANIZATIONS_PAGE_SIZE = 100;

export const MAX_ORGANIZATION_NAME_LENGTH = 255;

export const SLUG_TAKEN = "SLUG_TAKEN";

const UNAVAILABLE_CODES: readonly string[] = ["ORG_NOT_FOUND", "NOT_A_MEMBER"];

/** Another organization's id answers exactly like a missing one, so the two are treated alike. */
export function isOrganizationUnavailable(error: unknown): boolean {
    return error instanceof ApiError && UNAVAILABLE_CODES.includes(error.code);
}

/** The backend's `\p{Cntrl}`: the C0 controls and DEL, each a single UTF-16 code unit. */
function hasControlCharacter(value: string): boolean {
    for (let index = 0; index < value.length; index++) {
        const code = value.charCodeAt(index);
        if (code < 0x20 || code === 0x7f) return true;
    }
    return false;
}

export const organizationNameSchema = z
    .string()
    .trim()
    .min(1, "Enter a name for the organization")
    .max(MAX_ORGANIZATION_NAME_LENGTH, `Keep the name to ${String(MAX_ORGANIZATION_NAME_LENGTH)} characters or fewer`)
    .refine((name) => !hasControlCharacter(name), "Remove the control characters from the name");

export const renameOrganizationSchema = z.object({ name: organizationNameSchema });

export type RenameOrganizationForm = z.input<typeof renameOrganizationSchema>;

export type OrganizationRename = z.output<typeof renameOrganizationSchema>;

export const newOrganizationSchema = z.object({ name: organizationNameSchema, slug: optionalSlugSchema });

export type NewOrganizationForm = z.input<typeof newOrganizationSchema>;

export type NewOrganization = z.output<typeof newOrganizationSchema>;

/** Surrounding blanks never count as a change, since the name is saved trimmed. */
export function isSameName(organization: Pick<Organization, "name">, typed: string): boolean {
    return typed.trim() === organization.name;
}
