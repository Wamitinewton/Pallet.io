/**
 * Wire shapes shared by every Pallet HTTP service: {@link io.pallet.common.api.ApiResponse}
 * (success envelope), {@link io.pallet.common.api.PageResponse} (pagination envelope) and
 * {@link io.pallet.common.api.PageQuery} (inbound pagination/sort params).
 *
 * <p>Stable by contract — a change here is a breaking API change for every service at once;
 * treat it like a public API bump.
 */
package io.pallet.common.api;
