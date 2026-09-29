package io.pallet.gitintegration.push;

import io.pallet.gitintegration.delivery.payload.PushPayload;
import io.pallet.gitintegration.installation.Installation;
import io.pallet.gitintegration.installation.InstallationRepository;
import io.pallet.gitintegration.repolink.RepoLink;
import io.pallet.gitintegration.repolink.RepoLinkRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The rules that end a push for every app at once (ARCHITECTURE.md §Push to event), in order; the first that matches
 * names the reason. A push none of them stops goes on with the links it should build.
 */
@Component
public class SkipRules {

    public static final String NOT_A_BRANCH = "NOT_A_BRANCH";
    public static final String BRANCH_DELETED = "BRANCH_DELETED";
    public static final String INSTALLATION_INACTIVE = "INSTALLATION_INACTIVE";
    public static final String NO_LINKED_APP = "NO_LINKED_APP";
    public static final String SKIP_MARKER = "SKIP_MARKER";

    private final InstallationRepository installations;
    private final RepoLinkRepository links;

    SkipRules(InstallationRepository installations, RepoLinkRepository links) {
        this.installations = installations;
        this.links = links;
    }

    public Result apply(PushPayload push) {
        Optional<String> branch = push.branch();
        if (branch.isEmpty()) {
            return new Skip(NOT_A_BRANCH);
        }
        if (push.deleted()) {
            return new Skip(BRANCH_DELETED);
        }
        boolean active = installations
                .findById(push.installationId())
                .map(installation -> installation.status() == Installation.Status.ACTIVE)
                .orElse(false);
        if (!active) {
            return new Skip(INSTALLATION_INACTIVE);
        }
        List<RepoLink> targets = links.findPushTargets(push.installationId(), push.repositoryId(), branch.get());
        if (targets.isEmpty()) {
            return new Skip(NO_LINKED_APP);
        }
        if (push.skipMarked()) {
            return new Skip(SKIP_MARKER);
        }
        return new Build(branch.get(), targets);
    }

    public sealed interface Result permits Skip, Build {}

    public record Skip(String reason) implements Result {}

    /** {@code links} are in {@code app_id} order, the order their heads must be locked in. */
    public record Build(String branch, List<RepoLink> links) implements Result {

        public Build {
            links = List.copyOf(links);
        }
    }
}
