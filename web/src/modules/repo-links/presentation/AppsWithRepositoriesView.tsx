"use client";

import { AppsView, type AppsViewProps } from "@/modules/apps";
import { AppRepositoryCell } from "./AppRepositoryCell";

export type AppsWithRepositoriesViewProps = Omit<AppsViewProps, "renderRepository">;

/** The apps list with each row's repository filled in, which the apps module can't draw without this one. */
export function AppsWithRepositoriesView({ orgId, orgName }: AppsWithRepositoriesViewProps) {
    return (
        <AppsView
            orgId={orgId}
            orgName={orgName}
            renderRepository={(app) => <AppRepositoryCell orgId={orgId} appId={app.id} appName={app.name} />}
        />
    );
}
