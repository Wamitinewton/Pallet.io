"use client";

import { useState, type ReactNode } from "react";
import { Button } from "../button/Button";
import { Field } from "../field/Field";
import { Input } from "../input/Input";
import { Dialog, DialogBody, DialogClose, DialogFooter } from "./Dialog";

export interface ConfirmDialogProps {
    title: ReactNode;
    description: ReactNode;
    confirmValue: string;
    confirmLabel: ReactNode;
    onConfirm: () => void;
    open?: boolean;
    onOpenChange?: (open: boolean) => void;
    trigger?: ReactNode;
    loading?: boolean;
    confirmDisabled?: boolean;
    cancelLabel?: ReactNode;
    children?: ReactNode;
}

export function ConfirmDialog({ title, description, trigger, open, onOpenChange, ...form }: ConfirmDialogProps) {
    return (
        <Dialog
            title={title}
            description={description}
            {...(trigger !== undefined && { trigger })}
            {...(open !== undefined && { open })}
            {...(onOpenChange && { onOpenChange })}
        >
            <ConfirmForm {...form} />
        </Dialog>
    );
}

type ConfirmFormProps = Omit<ConfirmDialogProps, "title" | "description" | "trigger" | "open" | "onOpenChange">;

function ConfirmForm({
    confirmValue,
    confirmLabel,
    onConfirm,
    loading = false,
    confirmDisabled = false,
    cancelLabel = "Cancel",
    children,
}: ConfirmFormProps) {
    const [typed, setTyped] = useState("");
    const ready = typed === confirmValue && !confirmDisabled;

    return (
        <form
            noValidate
            onSubmit={(event) => {
                event.preventDefault();
                if (ready && !loading) onConfirm();
            }}
        >
            <DialogBody>
                <Field
                    label={
                        <>
                            Type <code>{confirmValue}</code> to confirm
                        </>
                    }
                >
                    <Input
                        mono
                        autoComplete="off"
                        autoCapitalize="off"
                        spellCheck={false}
                        value={typed}
                        onChange={(event) => {
                            setTyped(event.currentTarget.value);
                        }}
                    />
                </Field>
                {children}
            </DialogBody>
            <DialogFooter>
                <DialogClose>
                    <Button variant="secondary">{cancelLabel}</Button>
                </DialogClose>
                <Button type="submit" variant="danger" disabled={!ready} loading={loading}>
                    {confirmLabel}
                </Button>
            </DialogFooter>
        </form>
    );
}
