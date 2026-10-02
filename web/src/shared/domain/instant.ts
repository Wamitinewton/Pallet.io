import type { Brand } from "./ids";

export type IsoInstant = Brand<string, "IsoInstant">;

export function asIsoInstant(value: string): IsoInstant {
    if (Number.isNaN(Date.parse(value))) {
        throw new RangeError(`Not an ISO-8601 instant: ${value}`);
    }
    return value as IsoInstant;
}

export function instantFromDate(date: Date): IsoInstant {
    return date.toISOString() as IsoInstant;
}

export function instantToDate(instant: IsoInstant): Date {
    return new Date(instant);
}
