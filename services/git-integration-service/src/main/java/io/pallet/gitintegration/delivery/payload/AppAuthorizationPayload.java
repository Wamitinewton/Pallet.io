package io.pallet.gitintegration.delivery.payload;

/** A {@code github_app_authorization} event: {@code senderId} is the GitHub user who revoked the authorization. */
public record AppAuthorizationPayload(String action, long senderId) implements DeliveryPayload {}
