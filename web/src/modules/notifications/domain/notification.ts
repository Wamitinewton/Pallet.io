/** Shown on the badge as is up to this, then as "99+". */
export const MAX_DISPLAYED_UNREAD = 99;

export function unreadBadge(count: number): string {
    return count > MAX_DISPLAYED_UNREAD ? `${String(MAX_DISPLAYED_UNREAD)}+` : String(count);
}
