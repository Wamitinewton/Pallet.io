package io.pallet.gitintegration.delivery.payload;

import java.util.List;

public record InstallationRepositoriesPayload(
        String action,
        InstallationInfo installation,
        List<RepositoryRef> added,
        List<RepositoryRef> removed,
        long senderId)
        implements DeliveryPayload {

    public InstallationRepositoriesPayload {
        added = List.copyOf(added);
        removed = List.copyOf(removed);
    }
}
