const segmenter = new Intl.Segmenter("en", { granularity: "grapheme" });

function graphemes(word: string): string[] {
    return Array.from(segmenter.segment(word), ({ segment }) => segment);
}

export function initials(name: string): string {
    const words = name.trim().split(/\s+/).filter(Boolean);
    const first = words[0];
    if (!first) return "?";
    const last = words.length > 1 ? words[words.length - 1] : undefined;
    const letters = last ? [graphemes(first)[0], graphemes(last)[0]] : graphemes(first).slice(0, 2);
    return letters.join("").toUpperCase();
}
