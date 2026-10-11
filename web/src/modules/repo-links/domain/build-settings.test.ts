import { describe, expect, it } from "vitest";
import {
    buildSettingsChange,
    buildSettingsOf,
    buildSettingsSchema,
    changedFields,
    isNoChange,
    newLinkSettings,
    type BuildSettingsForm,
} from "./build-settings";
import { aRepoLink } from "./testing/fixtures";

const link = aRepoLink({ rootDirectory: "apps/web" });
const change = (form: Partial<BuildSettingsForm>) =>
    buildSettingsChange(link, buildSettingsSchema.parse({ ...buildSettingsOf(link), ...form }));

describe("buildSettingsChange", () => {
    it("is empty when nothing differs, after trimming and dropping a trailing slash", () => {
        const unchanged = change({ productionBranch: " main ", rootDirectory: "apps/web/" });

        expect(unchanged).toEqual({});
        expect(isNoChange(unchanged)).toBe(true);
    });

    it("carries only the fields that changed", () => {
        const changed = change({ productionBranch: "release", autoDeploy: false });

        expect(changed).toEqual({ productionBranch: "release", autoDeploy: false });
        expect("rootDirectory" in changed).toBe(false);
        expect(changedFields(changed)).toEqual(["productionBranch", "autoDeploy"]);
    });

    it("sends an empty root directory to build from the repository root", () => {
        expect(change({ rootDirectory: "" })).toEqual({ rootDirectory: "" });
    });
});

describe("newLinkSettings", () => {
    it("starts from the repository's default branch, at its root, building every push", () => {
        expect(newLinkSettings("develop")).toEqual({
            productionBranch: "develop",
            rootDirectory: "",
            autoDeploy: true,
        });
    });
});

describe("buildSettingsSchema", () => {
    it("puts each broken rule on its own field", () => {
        const issues = buildSettingsSchema.safeParse({
            productionBranch: "a..b",
            rootDirectory: "/x",
            autoDeploy: true,
        }).error?.issues;

        expect(issues?.map(({ path }) => path)).toEqual([["productionBranch"], ["rootDirectory"]]);
    });
});
