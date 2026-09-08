/**
 * Resilience4j defaults for every outbound call: named circuit breaker, retry and time-limiter
 * policies built from {@link io.pallet.common.resilience.ResilienceProperties}, exposed through
 * the single {@link io.pallet.common.resilience.ExternalCall} entry point. Contributed through
 * {@link io.pallet.common.resilience.PalletResilienceAutoConfiguration}; no component scanning.
 */
package io.pallet.common.resilience;
