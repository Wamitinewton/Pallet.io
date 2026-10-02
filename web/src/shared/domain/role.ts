export const ROLES = ["VIEWER", "DEVELOPER", "ADMIN", "OWNER"] as const;

export type Role = (typeof ROLES)[number];

export function isRole(value: string): value is Role {
    return (ROLES as readonly string[]).includes(value);
}

export function atLeast(role: Role, minimum: Role): boolean {
    return ROLES.indexOf(role) >= ROLES.indexOf(minimum);
}
