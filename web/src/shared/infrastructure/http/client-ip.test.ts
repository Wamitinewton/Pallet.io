import { describe, expect, it } from "vitest";
import { clientIp } from "./client-ip";

describe("clientIp", () => {
    it("trusts nothing with zero hops", () => {
        expect(clientIp("203.0.113.7", 0)).toBeUndefined();
    });

    it("takes the entry the outermost trusted proxy appended", () => {
        expect(clientIp("203.0.113.7", 1)).toBe("203.0.113.7");
        expect(clientIp("203.0.113.7, 10.0.0.2", 2)).toBe("203.0.113.7");
    });

    it("ignores spoofed entries a client put in front", () => {
        expect(clientIp("1.1.1.1, 6.6.6.6, 203.0.113.7", 1)).toBe("203.0.113.7");
        expect(clientIp("1.1.1.1, 203.0.113.7, 10.0.0.2", 2)).toBe("203.0.113.7");
    });

    it("uses the left-most entry when the chain is shorter than the configured hops", () => {
        expect(clientIp("203.0.113.7", 3)).toBe("203.0.113.7");
    });

    it("normalizes ports and brackets, and keeps IPv6", () => {
        expect(clientIp("203.0.113.7:51234", 1)).toBe("203.0.113.7");
        expect(clientIp("[2001:db8::1]:443", 1)).toBe("2001:db8::1");
        expect(clientIp("2001:db8::1", 1)).toBe("2001:db8::1");
    });

    it.each([null, "", "unknown", "203.0.113.7, not-an-ip", "<script>"])("refuses %j", (header) => {
        expect(clientIp(header, 1)).toBeUndefined();
    });
});
