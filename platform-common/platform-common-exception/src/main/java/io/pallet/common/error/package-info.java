/**
 * Shared error layer for every Pallet HTTP service: {@link io.pallet.common.error.AppException}
 * and its catalog, the {@link io.pallet.common.error.ErrorResponse} body shape, and the
 * auto-configured {@link io.pallet.common.error.GlobalExceptionHandler}.
 *
 * <p>{@code errorCode} values are a stable contract — clients branch on them, so a rename is a
 * breaking API change. Services add domain errors by subclassing {@code AppException}, never by
 * editing a handler.
 */
package io.pallet.common.error;
