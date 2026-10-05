import { describe, expect, it } from "vitest";
import { correlationIdFrom, traceparentFrom } from "./correlation";

describe("correlationIdFrom", () => {
    it("keeps a well-formed incoming id", () => {
        expect(correlationIdFrom(new Headers({ "X-Correlation-Id": "abc-123" }), () => "new")).toBe("abc-123");
    });

    it.each(["", "has space", "x".repeat(129), 'a"b', "a,b"])("replaces the unsafe id %j", (value) => {
        expect(correlationIdFrom(new Headers({ "X-Correlation-Id": value }), () => "new")).toBe("new");
    });

    it("generates one when none was sent", () => {
        expect(correlationIdFrom(new Headers(), () => "new")).toBe("new");
    });
});

describe("traceparentFrom", () => {
    it("passes a W3C traceparent and drops anything else", () => {
        const valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";
        expect(traceparentFrom(new Headers({ traceparent: valid }))).toBe(valid);
        expect(traceparentFrom(new Headers({ traceparent: "garbage" }))).toBeUndefined();
        expect(traceparentFrom(new Headers())).toBeUndefined();
    });
});
