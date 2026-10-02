import { describe, expect, it } from "vitest";
import { MAX_PAGE_SIZE, toPageQuery } from "./page-query";

describe("toPageQuery", () => {
    it("serializes a multi-column sort in the backend's grammar", () => {
        const query = toPageQuery({
            page: 2,
            size: 25,
            sort: [
                { field: "role", direction: "desc" },
                { field: "name", direction: "asc" },
            ],
        });

        expect(query).toEqual({ page: 2, size: 25, sort: "role,desc;name,asc" });
    });

    it("omits sort when there is none", () => {
        expect(toPageQuery({ page: 0, size: 20, sort: [] })).toEqual({ page: 0, size: 20 });
    });

    it("clamps size to the backend's maximum", () => {
        expect(toPageQuery({ page: 0, size: 500 }).size).toBe(MAX_PAGE_SIZE);
        expect(MAX_PAGE_SIZE).toBe(100);
    });

    it("never sends a page below zero or a size below one", () => {
        expect(toPageQuery({ page: -3, size: 0 })).toEqual({ page: 0, size: 1 });
    });
});
