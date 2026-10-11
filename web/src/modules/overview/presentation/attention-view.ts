import { appPath } from "@/modules/apps";
import { githubPath } from "@/modules/github";
import { pendingInvitesPath } from "@/modules/invites";
import { notificationPaths } from "@/modules/notifications";
import type { LiveDisconnectReason } from "@/modules/repo-links";
import type { OrgId } from "@/shared/domain/ids";
import type { IconName } from "@/shared/presentation/ui";
import type { Route } from "next";
import { toneOf, type AttentionItem, type AttentionTone } from "../domain/attention";
import {
    archivedDescriptionCopy,
    archivedTitleCopy,
    CONNECT_GITHUB_ACTION_COPY,
    CONNECT_GITHUB_COPY,
    CONNECT_GITHUB_TITLE_COPY,
    disconnectedDescriptionCopy,
    disconnectedTitleCopy,
    FIX_LINK_COPY,
    LINK_REPOSITORY_TITLE_COPY,
    linkRepositoryDescriptionCopy,
    openAppCopy,
    PENDING_INVITES_COPY,
    pendingInvitesTitleCopy,
    READ_NOTIFICATIONS_COPY,
    REVIEW_INSTALLATIONS_COPY,
    REVIEW_LINK_COPY,
    SEE_INVITES_COPY,
    SUSPENDED_COPY,
    suspendedTitleCopy,
    UNREAD_COPY,
    unreadTitleCopy,
} from "./overview-copy";

export type OverviewAttentionItem = AttentionItem<LiveDisconnectReason>;

export interface AttentionView {
    readonly key: string;
    readonly tone: AttentionTone;
    readonly icon: IconName;
    readonly title: string;
    readonly description: string;
    readonly action: { readonly label: string; readonly href: Route };
}

export function attentionView(item: OverviewAttentionItem, orgId: OrgId): AttentionView {
    const tone = toneOf(item);
    switch (item.kind) {
        case "repository-disconnected":
            return {
                key: `${item.kind}:${item.app.appId}`,
                tone,
                icon: "unlink",
                title: disconnectedTitleCopy(item.app.appName, item.reason),
                description: disconnectedDescriptionCopy(item.repoFullName),
                action: { label: FIX_LINK_COPY, href: appPath(orgId, item.app.appId) },
            };
        case "repository-archived":
            return {
                key: `${item.kind}:${item.app.appId}`,
                tone,
                icon: "archive",
                title: archivedTitleCopy(item.app.appName),
                description: archivedDescriptionCopy(item.repoFullName),
                action: { label: REVIEW_LINK_COPY, href: appPath(orgId, item.app.appId) },
            };
        case "installation-suspended":
            return {
                key: `${item.kind}:${item.accountLogin}`,
                tone,
                icon: "github",
                title: suspendedTitleCopy(item.accountLogin),
                description: SUSPENDED_COPY,
                action: { label: REVIEW_INSTALLATIONS_COPY, href: githubPath(orgId) },
            };
        case "connect-github":
            return {
                key: item.kind,
                tone,
                icon: "github",
                title: CONNECT_GITHUB_TITLE_COPY,
                description: CONNECT_GITHUB_COPY,
                action: { label: CONNECT_GITHUB_ACTION_COPY, href: githubPath(orgId) },
            };
        case "link-repository":
            return {
                key: item.kind,
                tone,
                icon: "link",
                title: LINK_REPOSITORY_TITLE_COPY,
                description: linkRepositoryDescriptionCopy(item.app.appName),
                action: { label: openAppCopy(item.app.appName), href: appPath(orgId, item.app.appId) },
            };
        case "pending-invites":
            return {
                key: item.kind,
                tone,
                icon: "mail",
                title: pendingInvitesTitleCopy(item.count),
                description: PENDING_INVITES_COPY,
                action: { label: SEE_INVITES_COPY, href: pendingInvitesPath(orgId) },
            };
        case "unread-notifications":
            return {
                key: item.kind,
                tone,
                icon: "bell",
                title: unreadTitleCopy(item.count),
                description: UNREAD_COPY,
                action: { label: READ_NOTIFICATIONS_COPY, href: notificationPaths.list },
            };
    }
}
