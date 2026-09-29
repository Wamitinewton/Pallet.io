package io.pallet.gitintegration.push;

import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.delivery.payload.PushPayload.Commit;
import io.pallet.gitintegration.delivery.payload.PushPayload.HeadCommit;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/** Push payloads built field by field, for the rules that read them. */
final class PushPayloads {

    static final long INSTALLATION_ID = 41000001L;
    static final long REPO_ID = 700000001L;
    static final String BEFORE = "1".repeat(40);
    static final String AFTER = "2".repeat(40);

    private String ref = "refs/heads/main";
    private String before = BEFORE;
    private boolean deleted;
    private boolean forced;
    private String message = "Tune startup probe";
    private boolean skipMarked;
    private List<Commit> commits = List.of(modifying("src/App.java"));

    static PushPayloads push() {
        return new PushPayloads();
    }

    static Commit modifying(String... paths) {
        return new Commit(List.of(), List.of(), List.of(paths));
    }

    static Commit changing(List<String> added, List<String> removed, List<String> modified) {
        return new Commit(added, removed, modified);
    }

    static List<Commit> copies(Commit commit, int count) {
        return Collections.nCopies(count, commit);
    }

    PushPayloads ref(String ref) {
        this.ref = ref;
        return this;
    }

    PushPayloads before(String before) {
        this.before = before;
        return this;
    }

    PushPayloads deleted() {
        this.deleted = true;
        return this;
    }

    PushPayloads forced() {
        this.forced = true;
        return this;
    }

    PushPayloads skipMarked() {
        this.skipMarked = true;
        return this;
    }

    PushPayloads commits(List<Commit> commits) {
        this.commits = commits;
        return this;
    }

    PushPayloads commits(Commit... commits) {
        return commits(List.of(commits));
    }

    PushPayload build() {
        return new PushPayload(
                ref,
                before,
                deleted ? PushPayload.NULL_SHA : AFTER,
                false,
                deleted,
                forced,
                INSTALLATION_ID,
                REPO_ID,
                "pallet-fixtures/hello-web",
                9100001L,
                deleted ? Optional.empty() : Optional.of(new HeadCommit(AFTER, message, "fixture-dev", skipMarked)),
                commits);
    }
}
