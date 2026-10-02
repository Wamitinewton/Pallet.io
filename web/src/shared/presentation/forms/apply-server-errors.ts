import { ApiError, type FieldError } from "@/shared/domain/errors";
import { get, type FieldValues, type Path, type UseFormReturn } from "react-hook-form";

type ServerErrorTarget<T extends FieldValues> = Pick<UseFormReturn<T>, "setError" | "getValues">;

export function toFormPath(field: string): string {
    return field.replace(/\[(\d+)\]/g, ".$1");
}

export function applyServerErrors<T extends FieldValues>(
    form: ServerErrorTarget<T>,
    error: unknown,
): readonly FieldError[] {
    if (!(error instanceof ApiError)) return [];

    const values = form.getValues();
    const unplaced: FieldError[] = [];
    let focused = false;

    for (const fieldError of error.fieldErrors) {
        const path = toFormPath(fieldError.field);
        if (get(values, path) === undefined) {
            unplaced.push(fieldError);
            continue;
        }
        form.setError(path as Path<T>, { type: "server", message: fieldError.message }, { shouldFocus: !focused });
        focused = true;
    }
    return unplaced;
}
