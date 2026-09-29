package io.pallet.gitintegration.delivery.payload;

import java.util.List;

/** An {@code installation} event. {@code repositories} is empty when the payload lists none. */
public record InstallationPayload(
        String action, InstallationInfo installation, List<RepositoryRef> repositories, long senderId)
        implements DeliveryPayload {

    public InstallationPayload {
        repositories = List.copyOf(repositories);
    }
}
