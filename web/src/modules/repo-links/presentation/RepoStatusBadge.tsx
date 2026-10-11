import { Badge } from "@/shared/presentation/ui";
import type { LinkHealth } from "../domain/link-health";
import { STATUS_LABEL } from "./repo-link-copy";

export interface RepoStatusBadgeProps {
    readonly health: LinkHealth;
}

/** Green only for a link that builds, as everywhere else: an archived repository is still connected. */
export function RepoStatusBadge({ health }: RepoStatusBadgeProps) {
    switch (health.kind) {
        case "unlinked":
            return null;
        case "connected":
        case "connected-with-warnings":
            return (
                <Badge tone="green" dot>
                    {STATUS_LABEL.connected}
                </Badge>
            );
        case "disconnected":
            return <Badge tone="red">{STATUS_LABEL.disconnected}</Badge>;
    }
}
