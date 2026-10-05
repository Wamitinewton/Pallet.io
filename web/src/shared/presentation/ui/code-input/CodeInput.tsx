"use client";

import {
    useImperativeHandle,
    useRef,
    useState,
    type ClipboardEvent,
    type ComponentProps,
    type CSSProperties,
    type KeyboardEvent,
    type Ref,
} from "react";
import { cx } from "../cx";
import { useFieldControl, useFieldLabelId } from "../field/FieldContext";
import { fillCells, sanitizeCode } from "./code";
import styles from "./CodeInput.module.css";

export interface CodeInputHandle {
    /** Empties every cell without reporting a change; the caller already knows the value it discarded. */
    clear(): void;
    focus(): void;
}

export type CodeInputProps = Omit<ComponentProps<"div">, "onChange" | "defaultValue" | "ref"> & {
    ref?: Ref<CodeInputHandle>;
    length?: number;
    defaultValue?: string;
    disabled?: boolean;
    onValueChange?: (value: string) => void;
    onComplete?: (value: string) => void;
};

export function CodeInput({
    ref,
    length = 8,
    defaultValue = "",
    disabled = false,
    onValueChange,
    onComplete,
    className,
    style,
    ...props
}: CodeInputProps) {
    const { id, ...groupProps } = useFieldControl(props);
    const labelId = useFieldLabelId();
    const [cells, setCells] = useState(() => fillCells(Array<string>(length).fill(""), 0, sanitizeCode(defaultValue)));
    const refs = useRef<(HTMLInputElement | null)[]>([]);

    const focusCell = (index: number) => {
        const target = refs.current[Math.max(0, Math.min(length - 1, index))];
        target?.focus();
        target?.select();
    };

    useImperativeHandle(
        ref,
        () => ({
            clear: () => {
                setCells(Array<string>(length).fill(""));
            },
            focus: () => {
                refs.current[0]?.focus();
            },
        }),
        [length],
    );

    const commit = (next: string[]) => {
        setCells(next);
        const value = next.join("");
        onValueChange?.(value);
        if (value.length === length) onComplete?.(value);
    };

    const write = (index: number, raw: string) => {
        const chars = sanitizeCode(raw);
        if (!chars) return;
        commit(fillCells(cells, index, chars));
        focusCell(index + chars.length);
    };

    const handleKeyDown = (index: number, event: KeyboardEvent<HTMLInputElement>) => {
        switch (event.key) {
            case "Backspace": {
                event.preventDefault();
                const target = cells[index] ? index : index - 1;
                if (target < 0) return;
                commit(cells.map((cell, i) => (i === target ? "" : cell)));
                focusCell(target);
                return;
            }
            case "Delete":
                event.preventDefault();
                commit(cells.map((cell, i) => (i === index ? "" : cell)));
                return;
            case "ArrowLeft":
                event.preventDefault();
                focusCell(index - 1);
                return;
            case "ArrowRight":
                event.preventDefault();
                focusCell(index + 1);
                return;
            case "Home":
                event.preventDefault();
                focusCell(0);
                return;
            case "End":
                event.preventDefault();
                focusCell(length - 1);
                return;
            default:
                return;
        }
    };

    const handlePaste = (index: number, event: ClipboardEvent<HTMLInputElement>) => {
        event.preventDefault();
        write(index, event.clipboardData.getData("text"));
    };

    return (
        <div
            role="group"
            aria-labelledby={labelId}
            className={cx(styles.group, className)}
            style={{ "--code-length": length, ...style } as CSSProperties}
            {...groupProps}
        >
            {cells.map((cell, index) => (
                <input
                    key={index}
                    ref={(element) => {
                        refs.current[index] = element;
                    }}
                    id={index === 0 ? id : undefined}
                    className={styles.cell}
                    value={cell}
                    disabled={disabled}
                    inputMode="text"
                    autoCapitalize="characters"
                    autoCorrect="off"
                    autoComplete={index === 0 ? "one-time-code" : "off"}
                    spellCheck={false}
                    aria-label={`Character ${String(index + 1)} of ${String(length)}`}
                    onFocus={(event) => {
                        event.currentTarget.select();
                    }}
                    onChange={(event) => {
                        const typed = event.currentTarget.value;
                        write(index, cell && typed.length === cell.length + 1 ? typed.replace(cell, "") : typed);
                    }}
                    onKeyDown={(event) => {
                        handleKeyDown(index, event);
                    }}
                    onPaste={(event) => {
                        handlePaste(index, event);
                    }}
                />
            ))}
        </div>
    );
}
