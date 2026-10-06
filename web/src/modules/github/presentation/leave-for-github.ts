/**
 * A full navigation in this tab: never a popup or a new tab, since GitHub's redirect must land where the
 * return intent was recorded.
 */
export function leaveForGitHub(url: string): void {
    window.location.assign(url);
}
