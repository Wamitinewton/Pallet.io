package io.pallet.common.observability;

import java.util.Map;

/**
 * SPI for adding MDC keys at request and record scope without this module depending on the
 * source. {@code platform-common-security} contributes {@code orgId}; a service contributes
 * {@code deploymentId}. Keys already present in the MDC are not overwritten.
 */
public interface MdcContributor {

    /**
     * Key/value pairs to install for the current request or record scope.
     */
    Map<String, String> contribute();
}
