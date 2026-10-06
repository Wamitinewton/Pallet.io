import { asOrgId } from "@/shared/domain/ids";
import { describe, expect, it } from "vitest";
import { installIntent } from "../domain/return-intent";
import { RETURN_INTENT_KEY, sessionStorageReturnIntentStore } from "./session-storage-return-intent-store";

class MapStorage implements Storage {
    private readonly entries = new Map<string, string>();
    get length() {
        return this.entries.size;
    }
    clear() {
        this.entries.clear();
    }
    getItem(key: string) {
        return this.entries.get(key) ?? null;
    }
    key(index: number) {
        return [...this.entries.keys()][index] ?? null;
    }
    removeItem(key: string) {
        this.entries.delete(key);
    }
    setItem(key: string, value: string) {
        this.entries.set(key, value);
    }
}

const intent = installIntent(new Date("2026-01-15T12:00:00Z"), asOrgId("org-kilima"), "/orgs/org-kilima/github");

describe("sessionStorageReturnIntentStore", () => {
    it("keeps the intent as JSON under one key and forgets it when cleared", () => {
        const storage = new MapStorage();
        const store = sessionStorageReturnIntentStore(() => storage);

        store.save(intent);
        expect(JSON.parse(storage.getItem(RETURN_INTENT_KEY) ?? "")).toEqual(intent);
        expect(store.read()).toEqual(intent);

        store.clear();
        expect(store.read()).toBeUndefined();
    });

    it("reads text that isn't JSON as no intent", () => {
        const storage = new MapStorage();
        storage.setItem(RETURN_INTENT_KEY, "{not json");

        expect(sessionStorageReturnIntentStore(() => storage).read()).toBeUndefined();
    });

    it("reads as empty where there is no storage at all", () => {
        const store = sessionStorageReturnIntentStore(() => undefined);

        store.save(intent);
        expect(store.read()).toBeUndefined();
    });

    it("reads as empty where the browser refuses storage", () => {
        const store = sessionStorageReturnIntentStore(() => {
            throw new DOMException("Denied", "SecurityError");
        });

        expect(() => {
            store.save(intent);
        }).not.toThrow();
        expect(store.read()).toBeUndefined();
    });
});
