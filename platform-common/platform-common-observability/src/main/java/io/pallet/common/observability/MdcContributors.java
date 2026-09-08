package io.pallet.common.observability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Applies and unwinds a set of {@link MdcContributor} beans over a request or record scope.
 */
final class MdcContributors {

    private final List<MdcContributor> contributors;

    MdcContributors(List<MdcContributor> contributors) {
        this.contributors = contributors;
    }

    /**
     * @return the keys this call installed, for the caller to remove once the scope ends.
     */
    List<String> apply() {
        List<String> installed = new ArrayList<>();
        for (MdcContributor contributor : contributors) {
            for (Map.Entry<String, String> entry : contributor.contribute().entrySet()) {
                if (entry.getValue() != null && MDC.get(entry.getKey()) == null) {
                    MDC.put(entry.getKey(), entry.getValue());
                    installed.add(entry.getKey());
                }
            }
        }
        return installed;
    }
}
