import type { Role } from "@/shared/domain/role";

export const ROLE_LABEL: Readonly<Record<Role, string>> = {
    VIEWER: "Viewer",
    DEVELOPER: "Developer",
    ADMIN: "Admin",
    OWNER: "Owner",
};

export const ROLE_DESCRIPTION: Readonly<Record<Role, string>> = {
    OWNER: "Everything an admin can do, plus changing roles and deleting the organization.",
    ADMIN: "Invite people, manage teams, connect GitHub, link repositories.",
    DEVELOPER: "Create and edit apps, change build settings, start builds.",
    VIEWER: "See everything, change nothing.",
};
