export const PASSWORD_HINT_COPY = "At least 12 characters. A short sentence is easier to remember than symbols.";

const STRENGTH_LABELS = ["Empty", "Weak", "Fair", "Good", "Strong"] as const;

export function strengthLabel(score: number): string {
    return STRENGTH_LABELS[score] ?? "Strong";
}
