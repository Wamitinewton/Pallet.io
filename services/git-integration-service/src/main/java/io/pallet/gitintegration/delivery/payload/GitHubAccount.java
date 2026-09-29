package io.pallet.gitintegration.delivery.payload;

/** The user or organization an installation belongs to. {@code type} is {@code User} or {@code Organization}. */
public record GitHubAccount(long id, String login, String type) {}
