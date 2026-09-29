package io.pallet.gitintegration.delivery.payload;

/** A {@code repository} event. */
public record RepositoryPayload(String action, Repository repository, long installationId, long senderId)
        implements DeliveryPayload {

    public record Repository(long id, String fullName, String defaultBranch, boolean isPrivate, boolean archived) {}
}
