import { fixedClock } from "@/shared/domain/clock";
import { ApiError, NetworkError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import {
    INVALID_TOKEN,
    INVITE_ALREADY_CONSUMED,
    INVITE_EMAIL_MISMATCH,
    INVITE_NO_LONGER_VALID,
    INVITE_SIGN_IN_REQUIRED,
} from "../domain/invite-errors";
import { anInvitePreview, anInviteToken } from "../domain/testing/fixtures";
import { InMemoryInviteAcceptanceRepository, InMemoryInviteRepository } from "./testing/in-memory";
import { makeInviteUseCases } from "./use-cases";

const clock = fixedClock("2026-01-15T12:00:00Z");
const token = anInviteToken();
const lapsedToken = anInviteToken({ exp: Date.parse("2026-01-15T11:00:00Z") / 1000 });
const PASSWORD = "mango-season-in-kisumu";

const refusal = (status: number, code: string) => new ApiError(status, code, code, [], {}, undefined);

function useCasesFor(acceptance: InMemoryInviteAcceptanceRepository) {
    return makeInviteUseCases({ invites: new InMemoryInviteRepository(), acceptance, clock });
}

describe("previewInvite", () => {
    it("answers what the invite is for", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();

        expect(await useCasesFor(acceptance).previewInvite(token)).toEqual({
            kind: "open",
            preview: anInvitePreview(),
        });
        expect(acceptance.previewed).toEqual([token]);
    });

    it("never sends something that can't be a token", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();

        expect(await useCasesFor(acceptance).previewInvite("not-a-token")).toEqual({
            kind: "unavailable",
            reason: "withdrawn",
        });
        expect(acceptance.previewed).toEqual([]);
    });

    it.each([
        ["a lapsed link refused as INVALID_TOKEN", lapsedToken, refusal(400, INVALID_TOKEN), "expired"],
        ["a live link refused as INVALID_TOKEN", token, refusal(400, INVALID_TOKEN), "withdrawn"],
        ["a revoked or accepted invite", token, refusal(410, INVITE_NO_LONGER_VALID), "withdrawn"],
    ] as const)("reads %s as %s", async (_, link, failure, reason) => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.failure = failure;

        expect(await useCasesFor(acceptance).previewInvite(link)).toEqual({ kind: "unavailable", reason });
    });

    it("passes an outage on", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.failure = new NetworkError();

        await expect(useCasesFor(acceptance).previewInvite(token)).rejects.toBeInstanceOf(NetworkError);
    });
});

describe("acceptWithNewAccount", () => {
    it("creates the account and names the organization it joins", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();

        expect(await useCasesFor(acceptance).acceptWithNewAccount(token, PASSWORD)).toEqual({
            kind: "accepted",
            orgId: "org-kilima",
        });
        expect(acceptance.newAccounts).toEqual([{ token, password: PASSWORD }]);
    });

    it("asks an address that already has an account to sign in", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository(anInvitePreview(), true);

        expect(await useCasesFor(acceptance).acceptWithNewAccount(token, PASSWORD)).toEqual({
            kind: "sign-in-required",
        });
    });

    it("reads an invite used meanwhile as no longer valid", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.consumed = true;

        expect(await useCasesFor(acceptance).acceptWithNewAccount(token, PASSWORD)).toEqual({
            kind: "unavailable",
            reason: "withdrawn",
        });
    });

    it("reads a link that lapsed while the page was open as expired", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.failure = refusal(400, INVALID_TOKEN);

        expect(await useCasesFor(acceptance).acceptWithNewAccount(lapsedToken, PASSWORD)).toEqual({
            kind: "unavailable",
            reason: "expired",
        });
    });

    it("passes a field error on for the form to place", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.failure = refusal(400, "VALIDATION_FAILED");

        await expect(useCasesFor(acceptance).acceptWithNewAccount(token, PASSWORD)).rejects.toMatchObject({
            code: "VALIDATION_FAILED",
        });
    });
});

describe("acceptAsSignedIn", () => {
    it("joins as the signed-in account", async () => {
        const acceptance = new InMemoryInviteAcceptanceRepository();

        expect(await useCasesFor(acceptance).acceptAsSignedIn(token)).toEqual({
            kind: "accepted",
            orgId: "org-kilima",
        });
        expect(acceptance.signedInAcceptances).toEqual([token]);
    });

    it.each([
        [INVITE_EMAIL_MISMATCH, 403, { kind: "email-mismatch" }],
        [INVITE_SIGN_IN_REQUIRED, 401, { kind: "sign-in-required" }],
        [INVITE_ALREADY_CONSUMED, 409, { kind: "unavailable", reason: "withdrawn" }],
    ] as const)("reads %s as an outcome", async (code, status, outcome) => {
        const acceptance = new InMemoryInviteAcceptanceRepository();
        acceptance.failure = refusal(status, code);

        expect(await useCasesFor(acceptance).acceptAsSignedIn(token)).toEqual(outcome);
    });
});
