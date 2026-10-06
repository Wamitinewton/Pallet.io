import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it } from "vitest";
import { confirmsDeletion, DeletionNotConfirmedError, isConfirmationMismatch } from "./delete-confirmation";

describe("confirmsDeletion", () => {
    it("accepts exactly the slug", () => {
        expect(confirmsDeletion("kilima-labs", "kilima-labs")).toBe(true);
    });

    it.each([
        ["a different case", "Kilima-Labs"],
        ["a leading space", " kilima-labs"],
        ["a trailing space", "kilima-labs "],
        ["a prefix", "kilima"],
        ["nothing", ""],
    ])("refuses %s", (_, typed) => {
        expect(confirmsDeletion("kilima-labs", typed)).toBe(false);
    });
});

describe("isConfirmationMismatch", () => {
    it("recognizes the local refusal and the backend's", () => {
        expect(isConfirmationMismatch(new DeletionNotConfirmedError())).toBe(true);
        expect(isConfirmationMismatch(new ApiError(400, "CONFIRMATION_MISMATCH", "No", [], {}, undefined))).toBe(true);
        expect(isConfirmationMismatch(new ApiError(400, "VALIDATION_ERROR", "No", [], {}, undefined))).toBe(false);
    });
});
