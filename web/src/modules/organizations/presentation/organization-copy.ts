import { REAUTHENTICATION_REQUIRED } from "@/modules/session";
import type { OrgKind } from "@/shared/domain/org-kind";
import type { ErrorCopy } from "@/shared/presentation/errors";
import { CONFIRMATION_MISMATCH, PERSONAL_ORG_IMMUTABLE } from "../domain/delete-confirmation";
import type { OrgCounts } from "../domain/organization";

export const KIND_LABEL: Readonly<Record<OrgKind, string>> = {
    PERSONAL: "Personal",
    TEAM: "Team",
};

export const SLUG_HOST = "pallet.dev/";

export const SETTINGS_TITLE_COPY = "Organization settings";
export const SETTINGS_DESCRIPTION_COPY = "Admins can rename the organization. Only the owner can delete it.";

export const GENERAL_DESCRIPTION_COPY = "The name shows up in the sidebar, in invites and in emails.";
export const SLUG_FIXED_COPY = "The URL is set when the organization is created and can't change.";
export const RENAMED_COPY = "Organization renamed";
export const NOT_RENAMED_COPY = "The name wasn't saved.";

export const MY_ORGANIZATIONS_DESCRIPTION_COPY = "Everywhere you're a member. Switch between them from the sidebar.";

export const NEW_ORGANIZATION_DESCRIPTION_COPY = "Organizations hold apps, members and teams. You'll be its owner.";
export const NEW_SLUG_HINT_COPY = "Lowercase letters, numbers and hyphens. You can't change it later.";
export const SLUG_TAKEN_COPY = "That URL is taken. Try another.";
export const ORGANIZATION_CREATED_COPY = "Organization created";

export const STEP_BACK_COPY =
    "If you only want to step back, make someone else the owner from the Members page instead.";

export const KIND_DESCRIPTION: Readonly<Record<OrgKind, string>> = {
    PERSONAL: "Personal organization",
    TEAM: "Team organization",
};

export const CONFIRMATION_MISMATCH_COPY = "That doesn't match the organization's URL. Type it exactly as shown.";

export const deleteErrorCopy: ErrorCopy = {
    [CONFIRMATION_MISMATCH]: CONFIRMATION_MISMATCH_COPY,
    [REAUTHENTICATION_REQUIRED]: "Confirm your password to delete this organization.",
    [PERSONAL_ORG_IMMUTABLE]: "A personal organization can't be deleted.",
};

function plural(count: number, one: string, many: string): string {
    return `${String(count)} ${count === 1 ? one : many}`;
}

export function sizeCopy({ members, teams, apps }: OrgCounts): string {
    return [plural(members, "member", "members"), plural(teams, "team", "teams"), plural(apps, "app", "apps")].join(
        " · ",
    );
}

export function deleteImpactCopy(name: string): string {
    return `Everyone in ${name} loses access to it right away, including its apps, teams and repository links.`;
}

export function deleteConsequencesCopy({ members, apps }: Pick<OrgCounts, "members" | "apps">): string {
    const membersLose = `${plural(members, "member", "members")} ${members === 1 ? "loses" : "lose"} access`;
    const appsStop = `${plural(apps, "app", "apps")} ${apps === 1 ? "stops" : "stop"} building`;
    return `${membersLose}, and ${appsStop}. You can't undo this.`;
}

export function deletedCopy(name: string): string {
    return `${name} deleted`;
}
