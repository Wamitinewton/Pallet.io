"use client";

import type { Team } from "@/modules/teams";
import { SearchField, Select } from "@/shared/presentation/ui";
import {
    formatAppSort,
    MAX_APP_SEARCH_LENGTH,
    NO_TEAM_PARAM,
    parseAppSort,
    type AppFilterChange,
    type AppListParams,
} from "../domain/app-list-query";
import { CLOUD_PROVIDERS, isCloudProvider } from "../domain/region-catalog";
import {
    ALL_CLOUDS_COPY,
    ALL_TEAMS_COPY,
    NO_TEAM_COPY,
    PROVIDER_NAME,
    SEARCH_LABEL_COPY,
    SEARCH_PLACEHOLDER_COPY,
    SORT_LABEL,
} from "./app-copy";
import styles from "./Apps.module.css";

const SORT_OPTIONS = Object.entries(SORT_LABEL);

export interface AppFiltersProps {
    readonly params: AppListParams;
    /** Undefined while the organization's teams are read. */
    readonly teams: readonly Team[] | undefined;
    readonly onChange: (change: AppFilterChange) => void;
}

export function AppFilters({ params, teams, onChange }: AppFiltersProps) {
    return (
        <div className={styles.filters} role="search" aria-label="Filter apps">
            <SearchField
                className={styles.search}
                label={SEARCH_LABEL_COPY}
                placeholder={SEARCH_PLACEHOLDER_COPY}
                maxLength={MAX_APP_SEARCH_LENGTH}
                value={params.q}
                onSearch={(q) => {
                    onChange({ q });
                }}
            />
            <Select
                aria-label="Team"
                className={styles.filterSelect}
                value={params.team ?? ""}
                onChange={(event) => {
                    const team = event.currentTarget.value;
                    onChange({ team: team === "" ? null : team });
                }}
            >
                <option value="">{ALL_TEAMS_COPY}</option>
                {teams?.map((team) => (
                    <option key={team.id} value={team.id}>
                        {team.name}
                    </option>
                ))}
                <option value={NO_TEAM_PARAM}>{NO_TEAM_COPY}</option>
            </Select>
            <Select
                aria-label="Cloud"
                className={styles.filterSelect}
                value={params.cloud ?? ""}
                onChange={(event) => {
                    const cloud = event.currentTarget.value;
                    onChange({ cloud: isCloudProvider(cloud) ? cloud : null });
                }}
            >
                <option value="">{ALL_CLOUDS_COPY}</option>
                {CLOUD_PROVIDERS.map((provider) => (
                    <option key={provider} value={provider}>
                        {PROVIDER_NAME[provider]}
                    </option>
                ))}
            </Select>
            <Select
                aria-label="Sort"
                className={styles.sortSelect}
                value={formatAppSort(params.sort)}
                onChange={(event) => {
                    const sort = parseAppSort(event.currentTarget.value);
                    if (sort !== undefined) onChange({ sort });
                }}
            >
                {SORT_OPTIONS.map(([value, label]) => (
                    <option key={value} value={value}>
                        {label}
                    </option>
                ))}
            </Select>
        </div>
    );
}
