import type { SessionSummary } from "../domain/session";
import type { SessionGateway } from "./ports";

export type ReadSessionSummary = () => Promise<SessionSummary>;

export function makeReadSessionSummary(gateway: SessionGateway): ReadSessionSummary {
    return () => gateway.summary();
}
