export function sanitizeCode(raw: string): string {
    return raw.replace(/[^a-z0-9]/gi, "").toUpperCase();
}

export function fillCells(cells: readonly string[], from: number, chars: string): string[] {
    const next = [...cells];
    for (let i = 0; i < chars.length && from + i < next.length; i++) {
        next[from + i] = chars.charAt(i);
    }
    return next;
}
