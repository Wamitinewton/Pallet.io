package io.pallet.build.support;

import io.pallet.common.events.BuildDispatched;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Well-formed {@link BuildDispatched} events built with the released factory, a fresh event id, build id and commit
 * each, adjusted one field at a time.
 */
public final class Dispatches {

    private Dispatches() {}

    public static Builder dispatch(String orgId, String appId) {
        return new Builder(orgId, appId);
    }

    public static String randomSha() {
        byte[] bytes = new byte[20];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    public static final class Builder {

        private UUID eventId = UUID.randomUUID();
        private String orgId;
        private String appId;
        private String buildId = UUID.randomUUID().toString();
        private int attempt = 1;
        private String branch = "main";
        private String commitSha = randomSha();
        private UUID pushEventId = UUID.randomUUID();
        private String trigger = "WEBHOOK";
        private String provider = "GITHUB";
        private long installationId = 41;
        private long repoId = 9001;
        private String repo = "octo-org/web";
        private String rootDirectory = "services/web";
        private Instant startBy = Instant.now().plus(Duration.ofMinutes(5));
        private long maxDurationSeconds = 1800;

        private Builder(String orgId, String appId) {
            this.orgId = orgId;
            this.appId = appId;
        }

        /** The same build again, as a redelivery under a new event id or a redispatch would carry it. */
        public Builder sameBuildAs(BuildDispatched dispatch) {
            orgId = dispatch.orgId();
            appId = dispatch.appId();
            buildId = dispatch.buildId();
            attempt = dispatch.attempt();
            branch = dispatch.branch();
            commitSha = dispatch.commitSha();
            pushEventId = dispatch.pushEventId();
            trigger = dispatch.trigger();
            repo = dispatch.repo();
            rootDirectory = dispatch.rootDirectory();
            maxDurationSeconds = dispatch.maxDurationSeconds();
            return this;
        }

        public Builder eventId(UUID eventId) {
            this.eventId = eventId;
            return this;
        }

        public Builder orgId(String orgId) {
            this.orgId = orgId;
            return this;
        }

        public Builder appId(String appId) {
            this.appId = appId;
            return this;
        }

        public Builder buildId(String buildId) {
            this.buildId = buildId;
            return this;
        }

        public Builder attempt(int attempt) {
            this.attempt = attempt;
            return this;
        }

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder commitSha(String commitSha) {
            this.commitSha = commitSha;
            return this;
        }

        public Builder pushEventId(UUID pushEventId) {
            this.pushEventId = pushEventId;
            return this;
        }

        public Builder trigger(String trigger) {
            this.trigger = trigger;
            return this;
        }

        public Builder provider(String provider) {
            this.provider = provider;
            return this;
        }

        public Builder installationId(long installationId) {
            this.installationId = installationId;
            return this;
        }

        public Builder repoId(long repoId) {
            this.repoId = repoId;
            return this;
        }

        public Builder repo(String repo) {
            this.repo = repo;
            return this;
        }

        public Builder rootDirectory(String rootDirectory) {
            this.rootDirectory = rootDirectory;
            return this;
        }

        public Builder startBy(Instant startBy) {
            this.startBy = startBy;
            return this;
        }

        public Builder startByIn(Duration fromNow) {
            this.startBy = Instant.now().plus(fromNow);
            return this;
        }

        public Builder maxDurationSeconds(long maxDurationSeconds) {
            this.maxDurationSeconds = maxDurationSeconds;
            return this;
        }

        public BuildDispatched build() {
            return BuildDispatched.of(
                    eventId,
                    orgId,
                    Instant.now(),
                    new BuildDispatched.Dispatch(
                            buildId,
                            attempt,
                            appId,
                            branch,
                            commitSha,
                            pushEventId,
                            trigger,
                            provider,
                            installationId,
                            repoId,
                            repo,
                            rootDirectory,
                            startBy,
                            maxDurationSeconds));
        }
    }
}
