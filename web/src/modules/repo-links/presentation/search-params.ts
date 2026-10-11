import { parseAsInteger, parseAsString, parseAsStringLiteral } from "nuqs";

/** The connect page's picker as the address bar holds it, so a reload or the way back from GitHub keeps it. */
export const connectSearchParams = {
    installation: parseAsInteger,
    q: parseAsString.withDefault(""),
    page: parseAsInteger.withDefault(1),
    /** Set by the GitHub round trip on its way back; read once and taken off the address. */
    github: parseAsStringLiteral(["connected"]),
};
