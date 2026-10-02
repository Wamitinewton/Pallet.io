"use client";

import { useCallback, useState } from "react";

export interface IdempotencyKey {
    readonly key: string;
    readonly renew: () => void;
}

export function useIdempotencyKey(): IdempotencyKey {
    const [key, setKey] = useState(() => crypto.randomUUID());
    const renew = useCallback(() => {
        setKey(crypto.randomUUID());
    }, []);
    return { key, renew };
}
