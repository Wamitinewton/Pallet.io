package io.pallet.gitintegration.delivery;

import io.pallet.gitintegration.config.GitIntegrationProperties;
import io.pallet.gitintegration.delivery.payload.AppAuthorizationPayload;
import io.pallet.gitintegration.delivery.payload.DeliveryPayload;
import io.pallet.gitintegration.delivery.payload.GitHubAccount;
import io.pallet.gitintegration.delivery.payload.InstallationInfo;
import io.pallet.gitintegration.delivery.payload.InstallationPayload;
import io.pallet.gitintegration.delivery.payload.InstallationRepositoriesPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.Commit;
import io.pallet.gitintegration.delivery.payload.PushPayload.HeadCommit;
import io.pallet.gitintegration.delivery.payload.RepositoryPayload;
import io.pallet.gitintegration.delivery.payload.RepositoryRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Parses a stored payload into its event's record and validates every field a handler relies on (ARCHITECTURE.md
 * §Security and threat model: a valid signature says GitHub sent it, not that its content is safe). Unknown fields are
 * ignored; a missing or invalid required one fails with its name and the rule it broke, never its value.
 */
@Component
public class PayloadParser {

    static final int MAX_MESSAGE_LENGTH = 256;
    static final int MAX_AUTHOR_LENGTH = 256;
    static final int MAX_REF_NAME_BYTES = 255;
    static final int MAX_PATH_LENGTH = 4096;

    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern FULL_NAME = Pattern.compile("[A-Za-z0-9_-]{1,39}/[A-Za-z0-9._-]{1,100}");
    private static final Pattern LOGIN = Pattern.compile("[A-Za-z0-9_-]{1,39}");
    private static final Pattern WORD = Pattern.compile("[a-z_]{1,64}");
    private static final Set<String> ACCOUNT_TYPES = Set.of("User", "Organization");
    private static final Set<String> REPOSITORY_SELECTIONS = Set.of("all", "selected");
    private static final String REF_FORBIDDEN = " ~^:?*[\\";

    /**
     * {@code jsonb} output adds a space after every {@code :} and {@code ,}, so a document that fit the body limit on
     * the way in can be longer on the way out; twice the limit bounds it.
     */
    private static final int STORED_EXPANSION = 2;

    private final JsonMapper mapper;
    private final List<String> skipMarkers;

    @Autowired
    PayloadParser(GitIntegrationProperties properties) {
        this(
                properties.webhook().maxBody().toBytes() * STORED_EXPANSION,
                properties.push().skipMarkers());
    }

    PayloadParser(long maxDocumentLength, List<String> skipMarkers) {
        this.mapper = WebhookJson.limitedMapper(maxDocumentLength);
        this.skipMarkers = List.copyOf(skipMarkers);
    }

    /** @throws MalformedPayloadException if the payload is absent, not JSON, or breaks a rule */
    public DeliveryPayload parse(String event, String payload) {
        if (payload == null) {
            throw new MalformedPayloadException("payload", "must be present");
        }
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (JacksonException e) {
            throw new MalformedPayloadException("payload", "must be one JSON document within the read limits");
        }
        if (root == null || !root.isObject()) {
            throw new MalformedPayloadException("payload", "must be a JSON object");
        }
        Node node = new Node(root, "");
        return switch (event) {
            case SubscribedEvents.PUSH -> push(node);
            case SubscribedEvents.INSTALLATION -> installation(node);
            case SubscribedEvents.INSTALLATION_REPOSITORIES -> installationRepositories(node);
            case SubscribedEvents.REPOSITORY -> repository(node);
            case SubscribedEvents.GITHUB_APP_AUTHORIZATION -> appAuthorization(node);
            default -> throw new IllegalArgumentException("No payload type for an unsubscribed event");
        };
    }

    private PushPayload push(Node root) {
        String ref = ref(root);
        boolean deleted = root.bool("deleted");
        String after = root.sha("after");
        if (isNullSha(after) && !deleted) {
            throw new MalformedPayloadException("after", "must not be all zeros unless the ref was deleted");
        }
        Node repository = root.object("repository");
        return new PushPayload(
                ref,
                root.sha("before"),
                after,
                root.bool("created"),
                deleted,
                root.bool("forced"),
                root.object("installation").id("id"),
                repository.id("id"),
                fullName(repository),
                root.object("sender").id("id"),
                root.optionalObject("head_commit").map(this::headCommit),
                root.array("commits").stream().map(PayloadParser::commit).toList());
    }

    private static String ref(Node root) {
        String ref = root.string("ref");
        String name;
        if (ref.startsWith(PushPayload.BRANCH_PREFIX)) {
            name = ref.substring(PushPayload.BRANCH_PREFIX.length());
        } else if (ref.startsWith(PushPayload.TAG_PREFIX)) {
            name = ref.substring(PushPayload.TAG_PREFIX.length());
        } else {
            throw new MalformedPayloadException("ref", "must start with refs/heads/ or refs/tags/");
        }
        refNameViolation(name).ifPresent(violation -> {
            throw new MalformedPayloadException("ref", violation);
        });
        return ref;
    }

    /** Skip markers are matched against the whole message, before it is cut to its first line. */
    private HeadCommit headCommit(Node commit) {
        String sha = commit.sha("id");
        if (isNullSha(sha)) {
            throw new MalformedPayloadException("head_commit.id", "must not be all zeros");
        }
        String message = commit.string("message");
        return new HeadCommit(
                sha,
                truncate(firstLine(message), MAX_MESSAGE_LENGTH),
                commit.optionalObject("author").map(PayloadParser::author).orElse(null),
                skipMarkers.stream().anyMatch(message::contains));
    }

    /** Reads {@code username}, else {@code name}; the email is never read. */
    private static String author(Node author) {
        String name = author.optionalString("username")
                .filter(value -> !value.isBlank())
                .or(() -> author.optionalString("name").filter(value -> !value.isBlank()))
                .orElse(null);
        return name == null ? null : truncate(firstLine(name), MAX_AUTHOR_LENGTH);
    }

    private static Commit commit(Node commit) {
        return new Commit(paths(commit, "added"), paths(commit, "removed"), paths(commit, "modified"));
    }

    private static List<String> paths(Node commit, String name) {
        List<Node> entries = commit.array(name);
        List<String> paths = new ArrayList<>(entries.size());
        for (Node entry : entries) {
            String path = entry.asString();
            if (path.isEmpty() || path.length() > MAX_PATH_LENGTH) {
                throw new MalformedPayloadException(entry.path(), "must be 1 to " + MAX_PATH_LENGTH + " characters");
            }
            paths.add(path);
        }
        return paths;
    }

    private static InstallationPayload installation(Node root) {
        return new InstallationPayload(
                action(root),
                installationInfo(root.object("installation")),
                root.optionalArray("repositories").stream()
                        .map(PayloadParser::repositoryRef)
                        .toList(),
                root.object("sender").id("id"));
    }

    private static InstallationRepositoriesPayload installationRepositories(Node root) {
        return new InstallationRepositoriesPayload(
                action(root),
                installationInfo(root.object("installation")),
                root.array("repositories_added").stream()
                        .map(PayloadParser::repositoryRef)
                        .toList(),
                root.array("repositories_removed").stream()
                        .map(PayloadParser::repositoryRef)
                        .toList(),
                root.object("sender").id("id"));
    }

    private static RepositoryPayload repository(Node root) {
        Node repository = root.object("repository");
        String defaultBranch = repository.string("default_branch");
        refNameViolation(defaultBranch).ifPresent(violation -> {
            throw new MalformedPayloadException(repository.child("default_branch"), violation);
        });
        return new RepositoryPayload(
                action(root),
                new RepositoryPayload.Repository(
                        repository.id("id"),
                        fullName(repository),
                        defaultBranch,
                        repository.bool("private"),
                        repository.bool("archived")),
                root.object("installation").id("id"),
                root.object("sender").id("id"));
    }

    private static AppAuthorizationPayload appAuthorization(Node root) {
        return new AppAuthorizationPayload(action(root), root.object("sender").id("id"));
    }

    private static String fullName(Node repository) {
        String fullName = repository.matching("full_name", FULL_NAME, "must be owner/name in GitHub's character set");
        String name = fullName.substring(fullName.indexOf('/') + 1);
        if (name.equals(".") || name.equals("..")) {
            throw new MalformedPayloadException(repository.child("full_name"), "must not name '.' or '..'");
        }
        return fullName;
    }

    private static String action(Node root) {
        return root.matching("action", WORD, "must be lowercase letters and underscores");
    }

    private static InstallationInfo installationInfo(Node installation) {
        Node account = installation.object("account");
        String type = account.string("type");
        if (!ACCOUNT_TYPES.contains(type)) {
            throw new MalformedPayloadException(account.child("type"), "must be User or Organization");
        }
        String selection = installation.string("repository_selection");
        if (!REPOSITORY_SELECTIONS.contains(selection)) {
            throw new MalformedPayloadException(installation.child("repository_selection"), "must be all or selected");
        }
        return new InstallationInfo(
                installation.id("id"),
                new GitHubAccount(account.id("id"), account.matching("login", LOGIN, "must be a GitHub login"), type),
                selection,
                permissions(installation.object("permissions")),
                installation.optionalString("suspended_at").isPresent());
    }

    private static Map<String, String> permissions(Node permissions) {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : permissions.json.properties()) {
            Node value = new Node(entry.getValue(), permissions.child(entry.getKey()));
            if (!WORD.matcher(entry.getKey()).matches()) {
                throw new MalformedPayloadException(
                        permissions.path, "names must be lowercase letters and underscores");
            }
            result.put(entry.getKey(), value.matchingSelf(WORD, "must be lowercase letters and underscores"));
        }
        return result;
    }

    private static RepositoryRef repositoryRef(Node repository) {
        return new RepositoryRef(repository.id("id"), fullName(repository), repository.bool("private"));
    }

    /**
     * Git's {@code check-ref-format} rules for the part after {@code refs/heads/} or {@code refs/tags/}.
     *
     * @return the first rule {@code name} breaks, or empty if it is a valid ref name
     */
    public static Optional<String> refNameViolation(String name) {
        if (name.isEmpty()) {
            return Optional.of("must name a branch or tag");
        }
        if (name.getBytes(StandardCharsets.UTF_8).length > MAX_REF_NAME_BYTES) {
            return Optional.of("must be at most " + MAX_REF_NAME_BYTES + " bytes");
        }
        if (name.startsWith("-")) {
            return Optional.of("must not start with '-'");
        }
        if (name.contains("..")) {
            return Optional.of("must not contain '..'");
        }
        if (name.contains("@{") || name.equals("@")) {
            return Optional.of("must not contain '@{' or be '@'");
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c < 0x20 || c == 0x7f || REF_FORBIDDEN.indexOf(c) >= 0) {
                return Optional.of("must not contain control characters, spaces, or any of ~^:?*[\\");
            }
        }
        if (name.startsWith("/") || name.endsWith("/") || name.contains("//")) {
            return Optional.of("must not start or end with '/' or contain '//'");
        }
        if (name.endsWith(".")) {
            return Optional.of("must not end with '.'");
        }
        for (String component : name.split("/", -1)) {
            if (component.startsWith(".") || component.endsWith(".lock")) {
                return Optional.of("no component may start with '.' or end with '.lock'");
            }
        }
        return Optional.empty();
    }

    private static boolean isNullSha(String sha) {
        return sha.chars().allMatch(c -> c == '0');
    }

    static String firstLine(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                return text.substring(0, i);
            }
        }
        return text;
    }

    static String truncate(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(maxLength - 1)) ? maxLength - 1 : maxLength;
        return text.substring(0, end);
    }

    /** A JSON node and the dotted path it was reached by, so every failure can name its field. */
    private record Node(JsonNode json, String path) {

        String child(String name) {
            return path.isEmpty() ? name : path + "." + name;
        }

        private JsonNode required(String name) {
            JsonNode value = json.get(name);
            if (value == null || value.isNull() || value.isMissingNode()) {
                throw new MalformedPayloadException(child(name), "is required");
            }
            return value;
        }

        Node object(String name) {
            JsonNode value = required(name);
            if (!value.isObject()) {
                throw new MalformedPayloadException(child(name), "must be an object");
            }
            return new Node(value, child(name));
        }

        Optional<Node> optionalObject(String name) {
            JsonNode value = json.get(name);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            return Optional.of(object(name));
        }

        List<Node> array(String name) {
            JsonNode value = required(name);
            if (!value.isArray()) {
                throw new MalformedPayloadException(child(name), "must be an array");
            }
            List<Node> elements = new ArrayList<>(value.size());
            for (int i = 0; i < value.size(); i++) {
                elements.add(new Node(value.get(i), child(name) + "[" + i + "]"));
            }
            return elements;
        }

        List<Node> optionalArray(String name) {
            JsonNode value = json.get(name);
            return value == null || value.isNull() ? List.of() : array(name);
        }

        long id(String name) {
            JsonNode value = required(name);
            if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() <= 0) {
                throw new MalformedPayloadException(child(name), "must be a positive integer");
            }
            return value.longValue();
        }

        boolean bool(String name) {
            JsonNode value = required(name);
            if (!value.isBoolean()) {
                throw new MalformedPayloadException(child(name), "must be a boolean");
            }
            return value.booleanValue();
        }

        String string(String name) {
            return new Node(required(name), child(name)).asString();
        }

        Optional<String> optionalString(String name) {
            JsonNode value = json.get(name);
            if (value == null || value.isNull()) {
                return Optional.empty();
            }
            return Optional.of(string(name));
        }

        String asString() {
            if (!json.isString()) {
                throw new MalformedPayloadException(path, "must be a string");
            }
            return json.stringValue();
        }

        String sha(String name) {
            return matching(name, SHA, "must be 40 lowercase hex characters");
        }

        String matching(String name, Pattern pattern, String rule) {
            return new Node(required(name), child(name)).matchingSelf(pattern, rule);
        }

        String matchingSelf(Pattern pattern, String rule) {
            String value = asString();
            if (!pattern.matcher(value).matches()) {
                throw new MalformedPayloadException(path, rule);
            }
            return value;
        }
    }
}
