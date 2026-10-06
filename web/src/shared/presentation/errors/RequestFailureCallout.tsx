"use client";

import type { RequestFailure } from "@/shared/domain/request-failure";
import { Button, Callout, Icon, Row, Stack } from "../ui";
import { messageFor, tooManyAttemptsCopy } from "./error-copy";

export interface RequestFailureCalloutProps {
    readonly failure: RequestFailure;
    readonly retryInSeconds: number;
    readonly onRetry: () => void;
    readonly retrying: boolean;
}

export function RequestFailureCallout({ failure, retryInSeconds, onRetry, retrying }: RequestFailureCalloutProps) {
    switch (failure.kind) {
        case "rate-limited":
            if (retryInSeconds === 0) return null;
            return (
                <Callout tone="amber" icon="clock">
                    <strong>{tooManyAttemptsCopy(retryInSeconds)}</strong>
                </Callout>
            );
        case "rejected":
            return <Callout tone="red">{messageFor(failure.error)}</Callout>;
        case "unavailable":
            return (
                <Callout tone="red">
                    <Stack gap="sm">
                        <span>{messageFor(failure.error)}</span>
                        <Row>
                            <Button variant="secondary" size="sm" onClick={onRetry} loading={retrying}>
                                <Icon name="refresh" />
                                Try again
                            </Button>
                        </Row>
                    </Stack>
                </Callout>
            );
    }
}
