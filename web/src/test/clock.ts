import type { Clock } from "@/shared/domain/clock";

export interface ManualClock extends Clock {
    advance(millis: number): void;
}

/** A clock that only moves when a test moves it. */
export function manualClock(iso: string): ManualClock {
    let millis = Date.parse(iso);
    if (Number.isNaN(millis)) throw new RangeError(`Not an ISO-8601 instant: ${iso}`);
    return {
        now: () => new Date(millis),
        advance(by) {
            millis += by;
        },
    };
}
