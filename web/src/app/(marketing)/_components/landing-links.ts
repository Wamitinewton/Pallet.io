export const SECTION_ID = {
    how: "how",
    teams: "teams",
} as const;

export const sectionHref = (id: (typeof SECTION_ID)[keyof typeof SECTION_ID]) => `#${id}` as const;

export const SITE_NAV = [
    { href: sectionHref(SECTION_ID.how), label: "How it works" },
    { href: sectionHref(SECTION_ID.teams), label: "Teams and roles" },
] as const;

export const REPOSITORY_URL = "https://github.com/Wamitinewton/Pallet.io";

export const repositoryFileUrl = (path: string) => `${REPOSITORY_URL}/blob/main/${path}`;
