/**
 * Shared observability wiring for every Pallet service: {@link io.pallet.common.observability.Monitored}
 * / {@link io.pallet.common.observability.MonitoringAspect} for per-method timers,
 * {@link io.pallet.common.observability.CorrelationIdFilter} and
 * {@link io.pallet.common.observability.CorrelationConsumerInterceptor} for an end-to-end
 * {@code X-Correlation-Id}, and the {@link io.pallet.common.observability.MdcContributor} SPI for
 * other modules to enrich the MDC.
 *
 * <p>All auto-configured by {@link io.pallet.common.observability.PalletObservabilityAutoConfiguration};
 * a service never component-scans this package.
 */
package io.pallet.common.observability;
