"use client";

import { useTheme } from "next-themes";
import { useSyncExternalStore } from "react";
import { Button, type ButtonProps } from "../button/Button";
import { Icon } from "../icons/Icon";

const subscribe = () => () => undefined;

function useHydrated(): boolean {
    return useSyncExternalStore(
        subscribe,
        () => true,
        () => false,
    );
}

export type ThemeToggleProps = Omit<ButtonProps, "onClick" | "children" | "asChild">;

export function ThemeToggle({ variant = "ghost", ...props }: ThemeToggleProps) {
    const { resolvedTheme, setTheme } = useTheme();
    const hydrated = useHydrated();
    const next = resolvedTheme === "dark" ? "light" : "dark";
    const label = hydrated ? `Switch to ${next} theme` : "Switch theme";

    return (
        <Button
            variant={variant}
            iconOnly
            aria-label={label}
            title={label}
            onClick={() => {
                setTheme(next);
            }}
            {...props}
        >
            <Icon name={hydrated && next === "light" ? "sun" : "moon"} />
        </Button>
    );
}
