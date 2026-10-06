import { createLoader, createParser, parseAsInteger, parseAsString, parseAsStringLiteral } from "nuqs/server";
import { DEFAULT_APP_LIST_PARAMS, formatAppSort, parseAppSort, sameAppSort } from "../domain/app-list-query";
import { CLOUD_PROVIDERS } from "../domain/region-catalog";

const appSort = createParser({
    parse: (value) => parseAppSort(value) ?? null,
    serialize: formatAppSort,
    eq: sameAppSort,
});

/** Shared by the apps page's server prefetch and the list on the client, so both ask for the same thing. */
export const appSearchParams = {
    q: parseAsString.withDefault(DEFAULT_APP_LIST_PARAMS.q),
    team: parseAsString,
    cloud: parseAsStringLiteral(CLOUD_PROVIDERS),
    sort: appSort.withDefault(DEFAULT_APP_LIST_PARAMS.sort),
    page: parseAsInteger.withDefault(DEFAULT_APP_LIST_PARAMS.page),
};

export const loadAppSearchParams = createLoader(appSearchParams);

export const APP_PAGE_TABS = ["repository", "settings"] as const;

export type AppPageTab = (typeof APP_PAGE_TABS)[number];

export const appTabParam = parseAsStringLiteral(APP_PAGE_TABS).withDefault("repository");
