"use client";

import { ApiError } from "@/shared/domain/errors";
import type { OrgId } from "@/shared/domain/ids";
import type { Page } from "@/shared/domain/page";
import { queryScopes } from "@/shared/presentation/query";
import { useMutation, useQueryClient, type QueryClient, type UseMutationResult } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import type { UpdateAppOutcome } from "../application/update-app";
import type { App, NewAppForm } from "../domain/app";
import { TEAM_NOT_FOUND } from "../domain/app-errors";
import type { AppSettingsForm } from "../domain/update-app";
import { useAppUseCases } from "./app-use-cases";
import { appKeys } from "./queries";

function mapApps(page: Page<App> | undefined, change: (app: App) => App | undefined): Page<App> | undefined {
    if (page === undefined) return undefined;
    const items = page.items.flatMap((app) => {
        const changed = change(app);
        return changed === undefined ? [] : [changed];
    });
    return { ...page, items, totalItems: page.totalItems - (page.items.length - items.length) };
}

/** A team deleted meanwhile is gone from every team choice too. */
function forgetDeletedTeam(queryClient: QueryClient, orgId: OrgId, error: unknown): void {
    if (error instanceof ApiError && error.is(TEAM_NOT_FOUND)) {
        void queryClient.invalidateQueries({ queryKey: queryScopes.teams(orgId) });
    }
}

/** The new app is seeded so its page opens without a read; the lists and the organization's count follow. */
export function useCreateApp(orgId: OrgId): UseMutationResult<App, unknown, NewAppForm> {
    const { createApp } = useAppUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (app) => createApp(orgId, app),
        onSuccess: (app) => {
            queryClient.setQueryData(appKeys.detail(orgId, app.id), app);
            void queryClient.invalidateQueries({ queryKey: appKeys.lists(orgId) });
            void queryClient.invalidateQueries({ queryKey: queryScopes.organization(orgId) });
        },
        onError: (error) => {
            forgetDeletedTeam(queryClient, orgId, error);
        },
    });
}

/**
 * Whatever the backend answers with is the app as it is now, whether this save landed or someone else's
 * did first. The lists are read again, since a new name or team can move the app between pages and filters.
 */
export function useUpdateApp(orgId: OrgId, app: App): UseMutationResult<UpdateAppOutcome, unknown, AppSettingsForm> {
    const { updateApp } = useAppUseCases();
    const queryClient = useQueryClient();

    return useMutation({
        mutationFn: (settings) => updateApp({ orgId, app, settings }),
        onSuccess: (outcome) => {
            const current = outcome.status === "updated" ? outcome.app : outcome.latest;
            if (current === app) return;
            queryClient.setQueryData(appKeys.detail(orgId, current.id), current);
            queryClient.setQueriesData<Page<App>>({ queryKey: appKeys.lists(orgId) }, (page) =>
                mapApps(page, (listed) => (listed.id === current.id ? current : listed)),
            );
            void queryClient.invalidateQueries({ queryKey: appKeys.lists(orgId) });
        },
        onError: (error) => {
            forgetDeletedTeam(queryClient, orgId, error);
        },
    });
}

/**
 * The app's own queries, its repository link's among them, are dropped once its page has gone, not while
 * it is still on screen, where a dropped query would be read again and answer `404`.
 */
export function useDeleteApp(orgId: OrgId, app: App): UseMutationResult<void, unknown, string> {
    const { deleteApp } = useAppUseCases();
    const queryClient = useQueryClient();
    const [deleted, setDeleted] = useState(false);
    const appId = app.id;

    useEffect(() => {
        if (!deleted) return;
        return () => {
            queryClient.removeQueries({ queryKey: appKeys.app(orgId, appId) });
        };
    }, [deleted, queryClient, orgId, appId]);

    return useMutation({
        mutationFn: (confirmation) => deleteApp({ orgId, app, confirmation }),
        onSuccess: async () => {
            setDeleted(true);
            await queryClient.cancelQueries({ queryKey: appKeys.app(orgId, appId) });
            queryClient.setQueriesData<Page<App>>({ queryKey: appKeys.lists(orgId) }, (page) =>
                mapApps(page, (current) => (current.id === appId ? undefined : current)),
            );
            void queryClient.invalidateQueries({ queryKey: appKeys.lists(orgId) });
            void queryClient.invalidateQueries({ queryKey: queryScopes.organization(orgId) });
        },
    });
}
