import { describe, expect, it } from "vitest";
import { initials } from "./initials";

describe("initials", () => {
    it.each([
        ["Amani Otieno", "AO"],
        ["Grace Wanjiru Njeri", "GN"],
        ["kilima", "KI"],
        ["  Savanna   Pay  ", "SP"],
        ["", "?"],
    ])("%s gives %s", (name, expected) => {
        expect(initials(name)).toBe(expected);
    });
});
