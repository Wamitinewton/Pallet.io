import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import type { SignupDetails } from "../domain/signup";
import { SignupConflictError } from "../domain/signup-failure";
import { makeSignUp } from "./sign-up";
import { ScriptedSignupRepository } from "./testing/in-memory";

const details: SignupDetails = {
    organizationName: "Kilima Labs",
    slug: "kilima-labs",
    displayName: "Amani Otieno",
    email: "amani@kilimalabs.co",
    password: "correct horse battery",
};

const conflict = () => new ApiError(409, "CONFLICT", "Conflict", [], {}, "corr-1");

async function conflictFrom(signUp: Promise<unknown>): Promise<SignupConflictError> {
    const error: unknown = await signUp.catch((thrown: unknown) => thrown);
    expect(error).toBeInstanceOf(SignupConflictError);
    return error as SignupConflictError;
}

describe("signUp", () => {
    it("passes the details and the idempotency key through to the port", async () => {
        const signups = new ScriptedSignupRepository();

        const receipt = await makeSignUp(signups)({ ...details, email: " amani@kilimalabs.co " }, "key-1");

        expect(signups.signups).toEqual([{ details, idempotencyKey: "key-1" }]);
        expect(receipt).toMatchObject({ slug: "kilima-labs" });
    });

    it("reuses whatever key it is given, so a retry replays", async () => {
        const signups = new ScriptedSignupRepository();
        const succeed = signups.nextSignUp;
        signups.nextSignUp = () => Promise.reject(new NetworkError());
        const signUp = makeSignUp(signups);

        await expect(signUp(details, "key-1")).rejects.toBeInstanceOf(NetworkError);
        signups.nextSignUp = succeed;
        await signUp(details, "key-1");

        expect(signups.signups.map((signup) => signup.idempotencyKey)).toEqual(["key-1", "key-1"]);
    });

    it("refuses invalid details without calling the port", async () => {
        const signups = new ScriptedSignupRepository();

        await expect(makeSignUp(signups)({ ...details, slug: "Not A Slug" }, "key-1")).rejects.toThrow();
        expect(signups.signups).toEqual([]);
    });

    it("blames the slug for a conflict when the re-check says it is taken", async () => {
        const signups = new ScriptedSignupRepository();
        signups.nextSignUp = () => Promise.reject(conflict());
        signups.nextCheck = (slug) => Promise.resolve({ slug, available: false });

        const error = await conflictFrom(makeSignUp(signups)(details, "key-1"));

        expect(error.field).toBe("slug");
        expect(error.cause).toBeInstanceOf(ApiError);
        expect(signups.checkedSlugs).toEqual(["kilima-labs"]);
    });

    it("blames the email when the slug is still free", async () => {
        const signups = new ScriptedSignupRepository();
        signups.nextSignUp = () => Promise.reject(conflict());

        expect((await conflictFrom(makeSignUp(signups)(details, "key-1"))).field).toBe("email");
    });

    it("blames neither when the re-check itself fails", async () => {
        const signups = new ScriptedSignupRepository();
        signups.nextSignUp = () => Promise.reject(conflict());
        signups.nextCheck = () => Promise.reject(new NetworkError());

        expect((await conflictFrom(makeSignUp(signups)(details, "key-1"))).field).toBeUndefined();
    });

    it("surfaces any other failure untouched", async () => {
        const signups = new ScriptedSignupRepository();
        const rejection = new ApiError(409, "IDEMPOTENCY_KEY_REUSE", "Reused", [], {}, undefined);
        signups.nextSignUp = () => Promise.reject(rejection);

        await expect(makeSignUp(signups)(details, "key-1")).rejects.toBe(rejection);
        expect(signups.checkedSlugs).toEqual([]);
    });
});
