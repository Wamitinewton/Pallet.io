import { UnexpectedResponseError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { z } from "zod";
import { expectNoContent, unwrap, unwrapPage } from "./envelope";

const response = (status = 200) => new Response(null, { status: status === 204 ? 204 : status });
const result = (data: unknown, status = 200) => ({ data, response: response(status) });

const orgSchema = z.object({ id: z.string(), name: z.string() });

describe("unwrap", () => {
    it("returns the validated data", () => {
        const org = unwrap(result({ success: true, message: "OK", data: { id: "o-1", name: "Acme" } }), orgSchema);

        expect(org).toEqual({ id: "o-1", name: "Acme" });
    });

    it("accepts a null data value when the schema does", () => {
        expect(unwrap(result({ success: true, message: "Deleted", data: null }), z.null())).toBeNull();
    });

    it("rejects success: false", () => {
        const body = { success: false, message: "OK", data: { id: "o-1", name: "Acme" } };

        expect(() => unwrap(result(body), orgSchema)).toThrow(UnexpectedResponseError);
    });

    it("rejects an envelope without a data key", () => {
        expect(() => unwrap(result({ success: true, message: "OK" }), z.unknown())).toThrow(/envelope data/);
    });

    it("rejects data that breaks the schema, naming the path", () => {
        const body = { success: true, message: "OK", data: { id: "o-1", name: 42 } };

        expect(() => unwrap(result(body), orgSchema)).toThrow(/data name/);
    });
});

describe("unwrapPage", () => {
    const pageBody = {
        content: [
            { id: "o-1", name: "Acme" },
            { id: "o-2", name: "Globex" },
        ],
        page: 1,
        size: 2,
        totalElements: 5,
        totalPages: 3,
        first: false,
        last: false,
    };

    it("maps field names and applies the item mapper", () => {
        const page = unwrapPage(result({ success: true, message: "OK", data: pageBody }), orgSchema, (org) => org.name);

        expect(page).toEqual({
            items: ["Acme", "Globex"],
            page: 1,
            size: 2,
            totalItems: 5,
            totalPages: 3,
            isFirst: false,
            isLast: false,
        });
    });

    it("rejects an item that breaks the schema, naming its index", () => {
        const broken = { ...pageBody, content: [{ id: "o-1", name: "Acme" }, { id: 7 }] };

        expect(() => unwrapPage(result({ success: true, message: "OK", data: broken }), orgSchema, (o) => o)).toThrow(
            /content\.1/,
        );
    });

    it("rejects a page missing its metadata", () => {
        const body = { success: true, message: "OK", data: { content: [] } };

        expect(() => unwrapPage(result(body), orgSchema, (o) => o)).toThrow(UnexpectedResponseError);
    });
});

describe("expectNoContent", () => {
    it("accepts 204", () => {
        expect(() => {
            expectNoContent(result(undefined, 204));
        }).not.toThrow();
    });

    it("rejects any other status", () => {
        expect(() => {
            expectNoContent(result({ success: true, message: "OK", data: null }, 200));
        }).toThrow(UnexpectedResponseError);
    });
});
