package io.pallet.gitintegration.push;

import java.util.UUID;

/** What a {@code GitPushReceived} copies from the app's repo link, read at the moment the event is built. */
public interface PushTarget {

    UUID appId();

    String orgId();

    long installationId();

    long repoId();

    String repoFullName();

    String productionBranch();

    String rootDirectory();
}
