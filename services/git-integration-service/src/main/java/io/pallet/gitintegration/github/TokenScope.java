package io.pallet.gitintegration.github;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/** What an installation token is narrowed to; {@link #INSTALLATION} asks for everything the installation grants. */
public record TokenScope(Set<Long> repositoryIds, Map<String, Access> permissions) {

    public static final TokenScope INSTALLATION = new TokenScope(Set.of(), Map.of());

    public enum Access {
        READ("read"),
        WRITE("write");

        private final String wireValue;

        Access(String wireValue) {
            this.wireValue = wireValue;
        }

        String wireValue() {
            return wireValue;
        }
    }

    public TokenScope {
        repositoryIds = Set.copyOf(repositoryIds);
        permissions = Map.copyOf(permissions);
    }

    public static TokenScope repository(long repositoryId, Map<String, Access> permissions) {
        return new TokenScope(Set.of(repositoryId), permissions);
    }

    boolean isNarrowed() {
        return !repositoryIds.isEmpty() || !permissions.isEmpty();
    }

    Map<String, Object> requestBody() {
        Map<String, Object> body = new LinkedHashMap<>();
        if (!repositoryIds.isEmpty()) {
            body.put("repository_ids", new TreeSet<>(repositoryIds));
        }
        if (!permissions.isEmpty()) {
            Map<String, String> wire = new LinkedHashMap<>();
            permissions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(entry -> wire.put(entry.getKey(), entry.getValue().wireValue()));
            body.put("permissions", wire);
        }
        return body;
    }
}
