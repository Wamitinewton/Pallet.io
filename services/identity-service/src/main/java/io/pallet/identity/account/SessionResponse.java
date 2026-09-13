package io.pallet.identity.account;

import java.time.Instant;
import org.keycloak.representations.idm.UserSessionRepresentation;

public record SessionResponse(String id, String ipAddress, Instant startedAt, Instant lastAccessedAt) {

    static SessionResponse from(UserSessionRepresentation session) {
        return new SessionResponse(
                session.getId(),
                session.getIpAddress(),
                Instant.ofEpochMilli(session.getStart()),
                Instant.ofEpochMilli(session.getLastAccess()));
    }
}
