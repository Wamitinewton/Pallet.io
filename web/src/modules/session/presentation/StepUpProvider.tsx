"use client";

import { createContext, use, useCallback, useMemo, useRef, useState, type ReactNode } from "react";
import { withStepUp } from "../application/step-up";
import { ReauthenticateDialog } from "./ReauthenticateDialog";

export interface StepUp {
    /** Runs `action`, and once more after a password prompt if the backend asks for a recent sign-in. */
    readonly runWithStepUp: <T>(action: () => Promise<T>) => Promise<T>;
}

const StepUpContext = createContext<StepUp | null>(null);

/**
 * Declining the prompt rejects with the backend's original error. Anything both attempts must share, such as
 * an idempotency key, is captured by the caller before `runWithStepUp`. Concurrent actions share one prompt.
 */
export function StepUpProvider({ children }: Readonly<{ children: ReactNode }>) {
    const [open, setOpen] = useState(false);
    const pending = useRef<{ readonly answer: Promise<boolean>; readonly settle: (confirmed: boolean) => void }>(
        undefined,
    );

    const confirmIdentity = useCallback((): Promise<boolean> => {
        if (pending.current === undefined) {
            let settle: (confirmed: boolean) => void = () => undefined;
            const answer = new Promise<boolean>((resolve) => {
                settle = resolve;
            });
            pending.current = { answer, settle };
            setOpen(true);
        }
        return pending.current.answer;
    }, []);

    const close = (confirmed: boolean) => {
        const prompt = pending.current;
        pending.current = undefined;
        setOpen(false);
        prompt?.settle(confirmed);
    };

    const stepUp = useMemo<StepUp>(
        () => ({ runWithStepUp: (action) => withStepUp(action, confirmIdentity) }),
        [confirmIdentity],
    );

    return (
        <StepUpContext value={stepUp}>
            {children}
            <ReauthenticateDialog
                open={open}
                onConfirmed={() => {
                    close(true);
                }}
                onCancelled={() => {
                    close(false);
                }}
            />
        </StepUpContext>
    );
}

export function useStepUp(): StepUp {
    const stepUp = use(StepUpContext);
    if (stepUp === null) throw new Error("useStepUp() must be called inside <StepUpProvider>");
    return stepUp;
}
