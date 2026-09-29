package io.pallet.gitintegration.github;

/**
 * The fields of a check run this service writes. {@code conclusion} is set exactly when {@code status} is
 * {@code completed}; {@code detailsUrl} may be null.
 */
public record CheckRunWrite(
        String name,
        String headSha,
        String externalId,
        String status,
        String conclusion,
        String detailsUrl,
        String title,
        String summary) {}
