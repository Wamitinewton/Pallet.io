export const ORG_KINDS = ["PERSONAL", "TEAM"] as const;

export type OrgKind = (typeof ORG_KINDS)[number];
