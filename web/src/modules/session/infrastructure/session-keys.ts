import { createHash } from "node:crypto";
import type { SessionId } from "../domain/session";

const KEY_PREFIX = "web:session:";

const sha256 = (value: string) => createHash("sha256").update(value, "utf8").digest("hex");

export function sessionKey(id: SessionId): string {
    return `${KEY_PREFIX}${sha256(id)}`;
}

export function refreshLockKey(id: SessionId): string {
    return `${sessionKey(id)}:refresh`;
}

/** Safe to log: a six-character prefix of the hashed id, never the id itself. */
export function sessionLogId(id: SessionId): string {
    return sha256(id).slice(0, 6);
}

export function refreshTokenFingerprint(refreshToken: string): string {
    return sha256(refreshToken);
}
