package io.pallet.gitintegration.delivery.payload;

/** A repository as the installation events list it. */
public record RepositoryRef(long id, String fullName, boolean isPrivate) {}
