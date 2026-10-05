import { asUserId } from "@/shared/domain/ids";
import { asIsoInstant, instantToDate } from "@/shared/domain/instant";
import { z } from "zod";
import type { SessionStore } from "../application/ports";
import type { Session, SessionId } from "../domain/session";
import type { RedisConnection } from "./redis-client";
import type { SessionCipher } from "./session-cipher";
import { refreshTokenFingerprint, sessionKey } from "./session-keys";

const DATA_FIELD = "d";
const FINGERPRINT_FIELD = "r";

const REPLACE_IF_CURRENT = `
if redis.call('HGET', KEYS[1], '${FINGERPRINT_FIELD}') ~= ARGV[1] then return 0 end
redis.call('HSET', KEYS[1], '${DATA_FIELD}', ARGV[2], '${FINGERPRINT_FIELD}', ARGV[3])
redis.call('PEXPIREAT', KEYS[1], ARGV[4])
return 1
`;

const instant = z.string().transform((value, context) => {
    try {
        return asIsoInstant(value);
    } catch {
        context.addIssue({ code: "custom", message: "not an instant" });
        return z.NEVER;
    }
});

const storedSessionSchema = z.object({
    userId: z.string().min(1).transform(asUserId),
    email: z.string(),
    keycloakSessionId: z.string().optional(),
    accessToken: z.string().min(1),
    accessExpiresAt: instant,
    refreshToken: z.string().min(1),
    refreshExpiresAt: instant,
    createdAt: instant,
});

type StoredSession = Omit<Session, "id">;

const expiresAtMillis = (session: Session) => instantToDate(session.refreshExpiresAt).getTime();

export interface RedisSessionStoreOptions {
    readonly redis: RedisConnection;
    readonly cipher: SessionCipher;
}

export function redisSessionStore({ redis, cipher }: RedisSessionStoreOptions): SessionStore {
    const seal = (session: Session, key: string) => {
        const stored: StoredSession = {
            userId: session.userId,
            email: session.email,
            keycloakSessionId: session.keycloakSessionId,
            accessToken: session.accessToken,
            accessExpiresAt: session.accessExpiresAt,
            refreshToken: session.refreshToken,
            refreshExpiresAt: session.refreshExpiresAt,
            createdAt: session.createdAt,
        };
        return cipher.encrypt(JSON.stringify(stored), key);
    };

    const open = (id: SessionId, payload: string, key: string): Session | undefined => {
        const plaintext = cipher.decrypt(payload, key);
        if (plaintext === undefined) return undefined;
        try {
            const parsed = storedSessionSchema.safeParse(JSON.parse(plaintext));
            if (!parsed.success) return undefined;
            const { keycloakSessionId, ...rest } = parsed.data;
            return { id, ...rest, keycloakSessionId };
        } catch {
            return undefined;
        }
    };

    return {
        async create(session) {
            const key = sessionKey(session.id);
            const client = await redis();
            await client
                .multi()
                .hSet(key, {
                    [DATA_FIELD]: seal(session, key),
                    [FINGERPRINT_FIELD]: refreshTokenFingerprint(session.refreshToken),
                })
                .pExpireAt(key, expiresAtMillis(session))
                .exec();
        },

        async get(id) {
            const key = sessionKey(id);
            const client = await redis();
            const payload = await client.hGet(key, DATA_FIELD);
            if (payload === null) return undefined;

            const session = open(id, payload, key);
            if (session === undefined) await client.del(key);
            return session;
        },

        async replaceTokens(next, expectedRefreshToken) {
            const key = sessionKey(next.id);
            const client = await redis();
            const replaced = await client.eval(REPLACE_IF_CURRENT, {
                keys: [key],
                arguments: [
                    refreshTokenFingerprint(expectedRefreshToken),
                    seal(next, key),
                    refreshTokenFingerprint(next.refreshToken),
                    String(expiresAtMillis(next)),
                ],
            });
            return replaced === 1;
        },

        async delete(id) {
            const client = await redis();
            await client.del(sessionKey(id));
        },
    };
}
