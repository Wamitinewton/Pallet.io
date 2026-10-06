import { makeCreateApp, type CreateApp } from "./create-app";
import { makeDeleteApp, type DeleteApp } from "./delete-app";
import { makeGetApp, type GetApp } from "./get-app";
import { makeListApps, type ListApps } from "./list-apps";
import type { AppRepository } from "./ports";
import { makeUpdateApp, type UpdateApp } from "./update-app";

export interface AppUseCases {
    readonly listApps: ListApps;
    readonly getApp: GetApp;
    readonly createApp: CreateApp;
    readonly updateApp: UpdateApp;
    readonly deleteApp: DeleteApp;
}

export interface AppDependencies {
    readonly apps: AppRepository;
}

export function makeAppUseCases({ apps }: AppDependencies): AppUseCases {
    return {
        listApps: makeListApps(apps),
        getApp: makeGetApp(apps),
        createApp: makeCreateApp(apps),
        updateApp: makeUpdateApp(apps),
        deleteApp: makeDeleteApp(apps),
    };
}
