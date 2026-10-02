"use client";

import { createContext, use } from "react";

export interface FieldContextValue {
    controlId: string;
    labelId: string;
    describedBy: string | undefined;
    invalid: boolean;
}

export const FieldContext = createContext<FieldContextValue | null>(null);

interface ControlProps {
    id?: string | undefined;
    "aria-describedby"?: string | undefined;
    "aria-invalid"?: boolean | "true" | "false" | "grammar" | "spelling" | undefined;
}

export function useFieldControl<P extends ControlProps>(props: P): P {
    const field = use(FieldContext);
    if (!field) return props;
    const describedBy = [field.describedBy, props["aria-describedby"]].filter(Boolean).join(" ");
    return {
        ...props,
        id: props.id ?? field.controlId,
        "aria-describedby": describedBy || undefined,
        "aria-invalid": props["aria-invalid"] ?? (field.invalid || undefined),
    };
}

export function useFieldLabelId(): string | undefined {
    return use(FieldContext)?.labelId;
}
