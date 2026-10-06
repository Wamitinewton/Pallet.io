/**
 * Set by `proxy.ts` on every dashboard request, overwriting anything the client sent: layouts never see
 * the pathname, and a server-side redirect to sign-in needs it for `next`.
 */
export const REQUESTED_PATH_HEADER = "x-pallet-requested-path";
