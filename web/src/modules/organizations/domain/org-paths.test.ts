import { describe, expect, it } from "vitest";
import { organizationPath, sectionOf, switchOrganizationPath } from "./org-paths";

describe("organizationPath", () => {
    it("builds the overview and section paths, encoding the id", () => {
        expect(organizationPath("org-1")).toBe("/orgs/org-1");
        expect(organizationPath("org-1", "members")).toBe("/orgs/org-1/members");
        expect(organizationPath("a/b")).toBe("/orgs/a%2Fb");
    });
});

describe("sectionOf", () => {
    it.each([
        ["/orgs/a", undefined],
        ["/orgs/a/apps", "apps"],
        ["/orgs/a/apps/app-1/connect", "apps"],
        ["/orgs/a/settings", "settings"],
        ["/orgs/a/unknown", undefined],
        ["/account", undefined],
    ])("%s is in %s", (pathname, section) => {
        expect(sectionOf(pathname)).toBe(section);
    });
});

describe("switchOrganizationPath", () => {
    it.each([
        ["/orgs/a/apps", "/orgs/b/apps"],
        ["/orgs/a/apps/app-1", "/orgs/b/apps"],
        ["/orgs/a/teams/team-1", "/orgs/b/teams"],
        ["/orgs/a/github", "/orgs/b/github"],
        ["/orgs/a", "/orgs/b"],
        ["/orgs/a/unknown", "/orgs/b"],
        ["/account", "/orgs/b"],
        ["/notifications", "/orgs/b"],
    ])("from %s goes to %s", (pathname, target) => {
        expect(switchOrganizationPath(pathname, "b")).toBe(target);
    });
});
