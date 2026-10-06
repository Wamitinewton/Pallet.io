import type { OrgId } from "@/shared/domain/ids";
import { Icon, Skeleton } from "@/shared/presentation/ui";
import Link from "next/link";
import type { Team } from "../domain/team";
import { peopleCountCopy } from "./team-copy";
import { teamPath } from "./team-paths";
import styles from "./Teams.module.css";

export interface TeamCardProps {
    readonly orgId: OrgId;
    readonly team: Team;
}

/** There is no per-team app count, so a card shows its people only; the team's page lists its apps. */
export function TeamCard({ orgId, team }: TeamCardProps) {
    return (
        <Link href={teamPath(orgId, team.id)} className={styles.card}>
            <div className={styles.cardHead}>
                <h2 className={styles.cardName}>{team.name}</h2>
                <span className={styles.slug}>{team.slug}</span>
            </div>
            <div className={styles.cardFoot}>
                <Icon name="users" />
                <span>{peopleCountCopy(team.memberCount)}</span>
            </div>
        </Link>
    );
}

export function TeamCardSkeleton() {
    return (
        <div className={styles.card}>
            <div className={styles.cardHead}>
                <Skeleton width="45%" height={18} />
                <Skeleton width={60} height={12} />
            </div>
            <div className={styles.cardFoot}>
                <Skeleton width={80} height={12} />
            </div>
        </div>
    );
}
