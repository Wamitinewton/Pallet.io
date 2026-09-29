package io.pallet.gitintegration.delivery.payload;

/** A validated webhook payload, one record per subscribed event. Holds only what handlers use; never an email. */
public sealed interface DeliveryPayload
        permits PushPayload,
                InstallationPayload,
                InstallationRepositoriesPayload,
                RepositoryPayload,
                AppAuthorizationPayload {}
