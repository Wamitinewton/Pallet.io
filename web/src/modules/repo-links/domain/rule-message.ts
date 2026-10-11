/** The backend's `"must not contain '..'"` as a field error reads it: `"Must not contain '..'."`. */
export function ruleMessage(violation: string): string {
    return `${violation.charAt(0).toUpperCase()}${violation.slice(1)}.`;
}
