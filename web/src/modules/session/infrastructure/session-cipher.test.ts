import { describe, expect, it } from "vitest";
import { createSessionCipher } from "./session-cipher";

const key = (fill: number) => Buffer.alloc(32, fill).toString("base64");
const context = "web:session:abc";

describe("createSessionCipher", () => {
    const cipher = createSessionCipher({ currentKeyId: "2", currentKey: key(2) });

    it("round-trips and never writes the plaintext", () => {
        const payload = cipher.encrypt('{"accessToken":"secret"}', context);

        expect(payload).toMatch(/^2\.[A-Za-z0-9_-]+$/);
        expect(payload).not.toContain("secret");
        expect(cipher.decrypt(payload, context)).toBe('{"accessToken":"secret"}');
    });

    it("uses a fresh IV per write", () => {
        expect(cipher.encrypt("same", context)).not.toBe(cipher.encrypt("same", context));
    });

    it("rejects tampered ciphertext", () => {
        const payload = cipher.encrypt("plaintext", context);
        const sealed = Buffer.from(payload.slice(2), "base64url");
        sealed[sealed.length - 1] = (sealed.at(-1) ?? 0) ^ 1;

        expect(cipher.decrypt(`2.${sealed.toString("base64url")}`, context)).toBeUndefined();
    });

    it("rejects a payload moved to another key", () => {
        expect(cipher.decrypt(cipher.encrypt("plaintext", context), "web:session:other")).toBeUndefined();
    });

    it.each(["", "2", "2.", "2.AAAA", "9.AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", "garbage"])(
        "rejects the malformed payload %j",
        (payload) => {
            expect(cipher.decrypt(payload, context)).toBeUndefined();
        },
    );

    it("still decrypts what an old key version wrote, and writes with the new one", () => {
        const before = createSessionCipher({ currentKeyId: "1", currentKey: key(1) });
        const after = createSessionCipher({ currentKeyId: "2", currentKey: key(2), retiredKeys: { "1": key(1) } });
        const written = before.encrypt("plaintext", context);

        expect(after.decrypt(written, context)).toBe("plaintext");
        expect(after.encrypt("plaintext", context).startsWith("2.")).toBe(true);
        expect(cipher.decrypt(written, context)).toBeUndefined();
    });

    it("refuses a key of the wrong length", () => {
        expect(() =>
            createSessionCipher({ currentKeyId: "1", currentKey: Buffer.alloc(16).toString("base64") }),
        ).toThrow(RangeError);
    });
});
