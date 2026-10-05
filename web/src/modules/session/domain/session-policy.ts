import type { Clock } from "@/shared/domain/clock";
import { instantToDate } from "@/shared/domain/instant";
import type { Session } from "./session";

export const REFRESH_WINDOW_MS = 30_000;

const millisUntil = (instant: Session["accessExpiresAt"], clock: Clock) =>
    instantToDate(instant).getTime() - clock.now().getTime();

export function needsRefresh(session: Session, clock: Clock): boolean {
    return millisUntil(session.accessExpiresAt, clock) <= REFRESH_WINDOW_MS;
}

export function hasValidAccessToken(session: Session, clock: Clock): boolean {
    return millisUntil(session.accessExpiresAt, clock) > 0;
}

export function isRefreshable(session: Session, clock: Clock): boolean {
    return millisUntil(session.refreshExpiresAt, clock) > 0;
}

export function remainingLifetimeSeconds(session: Session, clock: Clock): number {
    return Math.max(0, Math.floor(millisUntil(session.refreshExpiresAt, clock) / 1000));
}
