import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { INVALID_AUTHORIZATION_STATE } from "../domain/github-errors";
import { makeCompleteAuthorization } from "./complete-authorization";
import { InMemoryGitHubSessionRepository } from "./testing/in-memory";

const grant = { code: "a1b2c3", state: "signed-state" };

describe("completeAuthorization", () => {
    it("hands code and state to the service and answers with the session it stored", async () => {
        const sessions = new InMemoryGitHubSessionRepository();

        const session = await makeCompleteAuthorization(sessions)(grant);

        expect(sessions.completed).toEqual([grant]);
        expect(session.githubLogin).toBe("amani-otieno");
    });

    it("lets the service's refusal of the state through untouched", async () => {
        const sessions = new InMemoryGitHubSessionRepository();
        sessions.failNext = new ApiError(400, INVALID_AUTHORIZATION_STATE, "Refused", [], {}, undefined);

        await expect(makeCompleteAuthorization(sessions)(grant)).rejects.toMatchObject({
            code: INVALID_AUTHORIZATION_STATE,
        });
    });
});
