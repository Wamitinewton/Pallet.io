import { ApiError } from "@/shared/domain/errors";
import { describe, expect, it, vi } from "vitest";
import { withStepUp } from "./step-up";

const reauthenticationRequired = () =>
    new ApiError(403, "REAUTHENTICATION_REQUIRED", "Sign in again", [], {}, undefined);

function scriptedAction(...outcomes: unknown[]) {
    const queue = [...outcomes];
    return vi.fn(() => {
        const outcome = queue.shift();
        return outcome instanceof Error ? Promise.reject(outcome) : Promise.resolve(outcome);
    });
}

describe("withStepUp", () => {
    it("runs the action once and never asks when it succeeds", async () => {
        const action = scriptedAction("done");
        const confirm = vi.fn(() => Promise.resolve(true));

        await expect(withStepUp(action, confirm)).resolves.toBe("done");
        expect(action).toHaveBeenCalledTimes(1);
        expect(confirm).not.toHaveBeenCalled();
    });

    it("asks only when the backend requires it, then runs the action again", async () => {
        const action = scriptedAction(reauthenticationRequired(), "done");
        const confirm = vi.fn(() => Promise.resolve(true));

        await expect(withStepUp(action, confirm)).resolves.toBe("done");
        expect(action).toHaveBeenCalledTimes(2);
        expect(confirm).toHaveBeenCalledTimes(1);
    });

    it("retries exactly once, even if the backend asks again", async () => {
        const second = reauthenticationRequired();
        const action = scriptedAction(reauthenticationRequired(), second);
        const confirm = vi.fn(() => Promise.resolve(true));

        await expect(withStepUp(action, confirm)).rejects.toBe(second);
        expect(action).toHaveBeenCalledTimes(2);
        expect(confirm).toHaveBeenCalledTimes(1);
    });

    it("rethrows the original error when the person declines", async () => {
        const original = reauthenticationRequired();
        const action = scriptedAction(original);

        await expect(withStepUp(action, () => Promise.resolve(false))).rejects.toBe(original);
        expect(action).toHaveBeenCalledTimes(1);
    });

    it("passes any other error straight through", async () => {
        const other = new ApiError(403, "INSUFFICIENT_ROLE", "No", [], {}, undefined);
        const confirm = vi.fn(() => Promise.resolve(true));

        await expect(withStepUp(scriptedAction(other), confirm)).rejects.toBe(other);
        expect(confirm).not.toHaveBeenCalled();
    });
});
