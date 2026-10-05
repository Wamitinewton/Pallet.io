import { isIP } from "node:net";

function normalize(entry: string): string | undefined {
    const trimmed = entry.trim();
    const bracketed = /^\[([^\]]+)\](?::\d+)?$/.exec(trimmed);
    const withoutPort = bracketed?.[1] ?? trimmed.replace(/^(\d{1,3}(?:\.\d{1,3}){3}):\d+$/, "$1");
    return isIP(withoutPort) === 0 ? undefined : withoutPort;
}

/**
 * The client address as recorded by the first of `trustedHops` proxies in front of this server: the entry
 * that many places from the right of `X-Forwarded-For`. Entries further left were written by the client
 * and are ignored. With no trusted hop there is no trustworthy address in the header at all.
 */
export function clientIp(forwardedFor: string | null, trustedHops: number): string | undefined {
    if (forwardedFor === null || trustedHops < 1) return undefined;
    const entries = forwardedFor.split(",");
    const index = Math.max(0, entries.length - trustedHops);
    const entry = entries[index];
    return entry === undefined ? undefined : normalize(entry);
}
