import { randomBytes } from "node:crypto";
import type { SessionIdGenerator } from "../application/ports";
import { parseSessionId, type SessionId } from "../domain/session";

const SESSION_ID_BYTES = 32;

export const randomSessionIds: SessionIdGenerator = {
    next(): SessionId {
        const id = parseSessionId(randomBytes(SESSION_ID_BYTES).toString("base64url"));
        if (id === undefined) throw new Error("Generated session id does not match the session id format");
        return id;
    },
};
