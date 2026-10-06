import type { ReturnIntentStore } from "../application/ports";

export const RETURN_INTENT_KEY = "pallet.github.return-intent";

/**
 * Per tab, which is the point: an intent recorded in another tab never steers this one. Storage that is
 * missing (the server) or refused (a locked-down browser) reads as no intent, and the callback asks the
 * person to start again rather than failing.
 */
export function sessionStorageReturnIntentStore(
    storage: () => Storage | undefined = () => globalThis.sessionStorage,
): ReturnIntentStore {
    const attempt = <T>(work: (store: Storage) => T): T | undefined => {
        try {
            const store = storage();
            return store === undefined ? undefined : work(store);
        } catch {
            return undefined;
        }
    };

    return {
        save(intent) {
            attempt((store) => {
                store.setItem(RETURN_INTENT_KEY, JSON.stringify(intent));
            });
        },
        read() {
            return attempt((store) => {
                const stored = store.getItem(RETURN_INTENT_KEY);
                return stored === null ? undefined : (JSON.parse(stored) as unknown);
            });
        },
        clear() {
            attempt((store) => {
                store.removeItem(RETURN_INTENT_KEY);
            });
        },
    };
}
