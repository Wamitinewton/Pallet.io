import { describe, expect, it } from "vitest";
import { makeCheckSlugAvailability } from "./check-slug-availability";
import { ScriptedSignupRepository } from "./testing/in-memory";

describe("checkSlugAvailability", () => {
    it("asks the port, passing the cancellation signal along", async () => {
        const signups = new ScriptedSignupRepository();
        signups.nextCheck = (slug) => Promise.resolve({ slug, available: false });
        const { signal } = new AbortController();

        expect(await makeCheckSlugAvailability(signups)("kilima", { signal })).toEqual({
            slug: "kilima",
            available: false,
        });
        expect(signups.signals).toEqual([signal]);
    });

    it("never sends a slug the backend would refuse", async () => {
        const signups = new ScriptedSignupRepository();

        await expect(makeCheckSlugAvailability(signups)("-kilima")).rejects.toThrow();
        expect(signups.checkedSlugs).toEqual([]);
    });
});
