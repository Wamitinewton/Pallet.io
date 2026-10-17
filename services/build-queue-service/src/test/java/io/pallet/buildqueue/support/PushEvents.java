package io.pallet.buildqueue.support;

import io.pallet.common.events.GitPushReceived;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** Well-formed {@link GitPushReceived} events with a fresh event id and commit, adjusted one field at a time. */
public final class PushEvents {

    private PushEvents() {}

    public static Builder push(String orgId, String appId) {
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
        private String branch = "main";
        private String commitSha = randomSha();
        private String trigger = GitPushReceived.TRIGGER_WEBHOOK;
        private String provider = GitPushReceived.PROVIDER_GITHUB;
        private long installationId = 41;
        private long repoId = 9001;
        private String repo = "octo-org/web";
        private String rootDirectory = "services/web";
        private String headCommitMessage = "Fix the build";
        private String headCommitAuthor = "Octo Cat";

        private Builder(String orgId, String appId) {
            this.orgId = orgId;
            this.appId = appId;
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

        public Builder branch(String branch) {
            this.branch = branch;
            return this;
        }

        public Builder commitSha(String commitSha) {
            this.commitSha = commitSha;
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

        public Builder headCommitMessage(String headCommitMessage) {
            this.headCommitMessage = headCommitMessage;
            return this;
        }

        public Builder headCommitAuthor(String headCommitAuthor) {
            this.headCommitAuthor = headCommitAuthor;
            return this;
        }

        public GitPushReceived build() {
            return new GitPushReceived(
                    eventId,
                    GitPushReceived.TYPE,
                    orgId,
                    Instant.now(),
                    repo,
                    branch,
                    commitSha,
                    appId,
                    provider,
                    installationId,
                    repoId,
                    rootDirectory,
                    null,
                    false,
                    trigger,
                    headCommitMessage,
                    headCommitAuthor,
                    UUID.randomUUID().toString());
        }
    }
}
