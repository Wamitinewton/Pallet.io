import { createCipheriv, createDecipheriv, randomBytes } from "node:crypto";

const ALGORITHM = "aes-256-gcm";
const IV_BYTES = 12;
const TAG_BYTES = 16;
const KEY_BYTES = 32;

export interface SessionCipher {
    encrypt(plaintext: string, context: string): string;
    /** Answers `undefined` for anything it cannot authenticate: unknown key, tampered or malformed payload. */
    decrypt(payload: string, context: string): string | undefined;
}

export interface SessionKeyring {
    readonly currentKeyId: string;
    readonly currentKey: string;
    readonly retiredKeys?: Readonly<Record<string, string>> | undefined;
}

function decodeKey(id: string, base64: string): Buffer {
    const key = Buffer.from(base64, "base64");
    if (key.length !== KEY_BYTES) throw new RangeError(`Session key ${id} must be ${String(KEY_BYTES)} bytes`);
    return key;
}

const additionalData = (keyId: string, context: string) => Buffer.from(`${keyId}:${context}`, "utf8");

export function createSessionCipher({ currentKeyId, currentKey, retiredKeys = {} }: SessionKeyring): SessionCipher {
    const encryptionKey = decodeKey(currentKeyId, currentKey);
    const keys = new Map<string, Buffer>(Object.entries(retiredKeys).map(([id, key]) => [id, decodeKey(id, key)]));
    keys.set(currentKeyId, encryptionKey);

    return {
        encrypt(plaintext, context) {
            const iv = randomBytes(IV_BYTES);
            const cipher = createCipheriv(ALGORITHM, encryptionKey, iv, { authTagLength: TAG_BYTES });
            cipher.setAAD(additionalData(currentKeyId, context));
            const ciphertext = Buffer.concat([cipher.update(plaintext, "utf8"), cipher.final()]);
            const sealed = Buffer.concat([iv, cipher.getAuthTag(), ciphertext]);
            return `${currentKeyId}.${sealed.toString("base64url")}`;
        },

        decrypt(payload, context) {
            const separator = payload.indexOf(".");
            const keyId = payload.slice(0, separator);
            const key = separator > 0 ? keys.get(keyId) : undefined;
            if (key === undefined) return undefined;

            const sealed = Buffer.from(payload.slice(separator + 1), "base64url");
            if (sealed.length <= IV_BYTES + TAG_BYTES) return undefined;
            try {
                const decipher = createDecipheriv(ALGORITHM, key, sealed.subarray(0, IV_BYTES), {
                    authTagLength: TAG_BYTES,
                });
                decipher.setAAD(additionalData(keyId, context));
                decipher.setAuthTag(sealed.subarray(IV_BYTES, IV_BYTES + TAG_BYTES));
                return Buffer.concat([
                    decipher.update(sealed.subarray(IV_BYTES + TAG_BYTES)),
                    decipher.final(),
                ]).toString("utf8");
            } catch {
                return undefined;
            }
        },
    };
}
