import { describe, expect, it } from "vitest";
import { revocationAwareFetch } from "./revocation-aware-fetch";

const unauthorized = (error: string) =>
    Response.json({ success: false, message: error, error, statusCode: 401 }, { status: 401 });

const withToken = new Request("http://gateway.test/api/v1/org-team/orgs", {
    headers: { Authorization: "Bearer access-1" },
});

describe("revocationAwareFetch", () => {
    it("turns a rejected bearer token into SESSION_EXPIRED and discards the session", async () => {
        let discarded = 0;
        const send = revocationAwareFetch(
            () => {
                discarded++;
                return Promise.resolve();
            },
            () => Promise.resolve(unauthorized("AUTHENTICATION_REQUIRED")),
        );

        const response = await send(withToken, {});

        expect(response.status).toBe(401);
        expect(((await response.json()) as { error: string }).error).toBe("SESSION_EXPIRED");
        expect(discarded).toBe(1);
    });

    it("passes any other 401 through untouched", async () => {
        const send = revocationAwareFetch(
            () => Promise.reject(new Error("must not discard")),
            () => Promise.resolve(unauthorized("INVALID_CREDENTIALS")),
        );

        const response = await send(withToken, {});

        expect(((await response.json()) as { error: string }).error).toBe("INVALID_CREDENTIALS");
    });

    it("ignores AUTHENTICATION_REQUIRED on an anonymous request", async () => {
        const send = revocationAwareFetch(
            () => Promise.reject(new Error("must not discard")),
            () => Promise.resolve(unauthorized("AUTHENTICATION_REQUIRED")),
        );

        const response = await send(new Request("http://gateway.test/api/v1/org-team/orgs"), {});

        expect(((await response.json()) as { error: string }).error).toBe("AUTHENTICATION_REQUIRED");
    });
});
