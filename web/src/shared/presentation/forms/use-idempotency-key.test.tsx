import { renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { useIdempotencyKey } from "./use-idempotency-key";

describe("useIdempotencyKey", () => {
    it("keeps one key across renders until it is renewed", () => {
        const { result, rerender } = renderHook(() => useIdempotencyKey());
        const first = result.current.current();

        rerender();
        expect(result.current.current()).toBe(first);

        result.current.renew();
        const second = result.current.current();
        expect(second).not.toBe(first);
        expect(result.current.current()).toBe(second);
    });
});
