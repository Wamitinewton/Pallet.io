export interface Clock {
    now(): Date;
}

export const systemClock: Clock = { now: () => new Date() };

export function fixedClock(iso: string): Clock {
    const millis = Date.parse(iso);
    if (Number.isNaN(millis)) {
        throw new RangeError(`Not an ISO-8601 instant: ${iso}`);
    }
    return { now: () => new Date(millis) };
}
