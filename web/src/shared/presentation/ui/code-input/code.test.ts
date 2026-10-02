import { describe, expect, it } from "vitest";
import { fillCells, sanitizeCode } from "./code";

describe("sanitizeCode", () => {
    it("keeps letters and digits, upper-cased", () => {
        expect(sanitizeCode(" ab-12 cd_34 ")).toBe("AB12CD34");
    });
});

describe("fillCells", () => {
    it("writes from the given index and never past the last cell", () => {
        expect(fillCells(["A", "", "", ""], 2, "XYZ")).toEqual(["A", "", "X", "Y"]);
    });
});
