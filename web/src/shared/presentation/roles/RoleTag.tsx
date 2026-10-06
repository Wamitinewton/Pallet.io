import type { Role } from "@/shared/domain/role";
import { RoleBadge, type RoleBadgeProps } from "../ui";
import { ROLE_LABEL } from "./role-copy";

export type RoleTagProps = Omit<RoleBadgeProps, "tone" | "children"> & {
    readonly role: Role;
};

/** The owner stands out; every other role reads the same, so color never ranks people. */
export function RoleTag({ role, ...props }: RoleTagProps) {
    return (
        <RoleBadge tone={role === "OWNER" ? "owner" : "default"} {...props}>
            {ROLE_LABEL[role]}
        </RoleBadge>
    );
}
