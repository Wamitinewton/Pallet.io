"use client";

import type { OrgId } from "@/shared/domain/ids";
import { ErrorState } from "@/shared/presentation/errors";
import {
    Badge,
    Button,
    Callout,
    EmptyState,
    Icon,
    List,
    ListItem,
    Panel,
    PanelHead,
    Skeleton,
} from "@/shared/presentation/ui";
import Link from "next/link";
import { attentionView, type AttentionView } from "./attention-view";
import {
    ATTENTION_TITLE_COPY,
    ATTENTION_UNAVAILABLE_COPY,
    attentionCountLabel,
    CALM_COPY,
    CALM_TITLE_COPY,
    CHECKING_ATTENTION_COPY,
    INCOMPLETE_COPY,
    TRY_AGAIN_COPY,
} from "./overview-copy";
import styles from "./Overview.module.css";
import { useAttention, type Attention } from "./use-attention";

const HEADING_ID = "overview-attention";
const LOADING_ITEMS = 2;

export interface AttentionListProps {
    readonly orgId: OrgId;
}

export function AttentionList({ orgId }: AttentionListProps) {
    const attention = useAttention(orgId);
    const views = attention.checking ? [] : attention.items.map((item) => attentionView(item, orgId));
    const [first] = views;

    return (
        <Panel aria-labelledby={HEADING_ID}>
            <PanelHead>
                <h2 id={HEADING_ID}>{ATTENTION_TITLE_COPY}</h2>
                {first !== undefined && (
                    <Badge tone={first.tone} aria-label={attentionCountLabel(views.length)}>
                        {views.length}
                    </Badge>
                )}
            </PanelHead>
            <AttentionBody attention={attention} views={views} />
        </Panel>
    );
}

function AttentionBody({ attention, views }: Readonly<{ attention: Attention; views: readonly AttentionView[] }>) {
    if (attention.checking) {
        return (
            <div role="status" aria-busy="true" aria-label={CHECKING_ATTENTION_COPY}>
                {Array.from({ length: LOADING_ITEMS }, (_, index) => (
                    <div key={index} className={styles.skeletonRow}>
                        <Skeleton width="60%" height={14} />
                        <Skeleton width="85%" height={12} />
                    </div>
                ))}
            </div>
        );
    }

    if (views.length === 0) {
        return attention.failed ? (
            <ErrorState
                error={attention.failure}
                title={ATTENTION_UNAVAILABLE_COPY}
                headingLevel={3}
                retrying={attention.retrying}
                onRetry={attention.retry}
            />
        ) : (
            <EmptyState icon="check-circle" title={CALM_TITLE_COPY} description={CALM_COPY} headingLevel={3} />
        );
    }

    return (
        <>
            <List>
                {views.map((view) => (
                    <ListItem key={view.key} className={styles.item}>
                        <span className={styles.glyph} data-tone={view.tone} aria-hidden="true">
                            <Icon name={view.icon} />
                        </span>
                        <div className={styles.itemText}>
                            <span className={styles.itemTitle}>{view.title}</span>
                            <span className={styles.itemDescription}>{view.description}</span>
                            <Link href={view.action.href} className={styles.itemAction}>
                                {view.action.label}
                            </Link>
                        </div>
                    </ListItem>
                ))}
            </List>
            {attention.failed && (
                <Callout tone="amber" className={styles.incomplete}>
                    {INCOMPLETE_COPY}{" "}
                    <Button variant="ghost" size="sm" onClick={attention.retry} loading={attention.retrying}>
                        <Icon name="refresh" />
                        {TRY_AGAIN_COPY}
                    </Button>
                </Callout>
            )}
        </>
    );
}
