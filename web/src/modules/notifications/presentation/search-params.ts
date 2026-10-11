import { createLoader, parseAsStringLiteral } from "nuqs/server";
import { DEFAULT_READ_FILTER, READ_FILTERS } from "../domain/notification";

/** Shared by the page's server prefetch and the view on the client, so both ask for the same list. */
export const notificationSearchParams = {
    status: parseAsStringLiteral(READ_FILTERS).withDefault(DEFAULT_READ_FILTER),
};

export const loadNotificationSearchParams = createLoader(notificationSearchParams);
