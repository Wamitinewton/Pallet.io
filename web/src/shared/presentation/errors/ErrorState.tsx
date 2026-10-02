"use client";

import { correlationIdOf } from "@/shared/domain/errors";
import type { ReactNode } from "react";
import { Button, EmptyState, Icon, Text } from "../ui";
import { messageFor, type ErrorCopy } from "./error-copy";
import styles from "./ErrorState.module.css";

export interface ErrorStateProps {
    readonly error: unknown;
    readonly onRetry?: () => void;
    readonly retrying?: boolean;
    readonly title?: ReactNode;
    readonly copy?: ErrorCopy;
    readonly headingLevel?: 2 | 3;
    readonly className?: string;
}

export function ErrorState({
    error,
    onRetry,
    retrying = false,
    title = "Couldn't load this",
    copy,
    headingLevel,
    className,
}: ErrorStateProps) {
    const correlationId = correlationIdOf(error);
    const description = messageFor(error, copy, { reference: false });

    return (
        <EmptyState
            role="alert"
            icon="alert"
            title={title}
            className={className}
            {...(headingLevel !== undefined && { headingLevel })}
            description={
                <>
                    {description}
                    {correlationId !== undefined && (
                        <>
                            <br />
                            <Text size="xs" mono className={styles.reference}>
                                Reference {correlationId}
                            </Text>
                        </>
                    )}
                </>
            }
            actions={
                onRetry && (
                    <Button variant="secondary" size="sm" onClick={onRetry} loading={retrying}>
                        <Icon name="refresh" />
                        Try again
                    </Button>
                )
            }
        />
    );
}
