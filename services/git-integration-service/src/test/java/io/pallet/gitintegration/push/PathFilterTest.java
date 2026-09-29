package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.push.PushPayloads.changing;
import static io.pallet.gitintegration.push.PushPayloads.copies;
import static io.pallet.gitintegration.push.PushPayloads.modifying;
import static io.pallet.gitintegration.push.PushPayloads.push;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.gitintegration.delivery.payload.PushPayload;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

@UnitTest
class PathFilterTest {

    @Test
    void anAppAtTheRepositoryRootAlwaysBuilds() {
        assertThat(PathFilter.touches(
                        push().commits(modifying("docs/README.md")).build(), null))
                .isTrue();
    }

    @Test
    void aChangeInsideTheDirectoryBuilds() {
        PushPayload push = push().commits(modifying("docs/README.md"), modifying("apps/api/src/App.java"))
                .build();

        assertThat(PathFilter.touches(push, "apps/api")).isTrue();
    }

    @Test
    void anAddedOrRemovedFileCountsAsAChange() {
        assertThat(PathFilter.touches(
                        push().commits(changing(List.of("apps/api/New.java"), List.of(), List.of()))
                                .build(),
                        "apps/api"))
                .isTrue();
        assertThat(PathFilter.touches(
                        push().commits(changing(List.of(), List.of("apps/api/Old.java"), List.of()))
                                .build(),
                        "apps/api"))
                .isTrue();
    }

    @Test
    void aChangeOutsideTheDirectoryIsSkipped() {
        PushPayload push = push().commits(modifying("apps/web/index.ts"), modifying("README.md"))
                .build();

        assertThat(PathFilter.touches(push, "apps/api")).isFalse();
    }

    @Test
    void aSiblingDirectorySharingThePrefixIsOutside() {
        PushPayload push = push().commits(modifying("api-docs/guide.md")).build();

        assertThat(PathFilter.touches(push, "api")).isFalse();
    }

    @Test
    void aFileNamedLikeTheDirectoryIsOutside() {
        assertThat(PathFilter.touches(push().commits(modifying("apps/api")).build(), "apps/api"))
                .isFalse();
    }

    @Test
    void aPushListingNoCommitsIsSkipped() {
        assertThat(PathFilter.touches(push().commits(List.of()).build(), "apps/api"))
                .isFalse();
    }

    @Test
    void aPayloadWithGitHubsMaximumCommitCountBuilds() {
        PushPayload push = push().commits(copies(modifying("README.md"), PathFilter.MAX_LISTED_COMMITS))
                .build();

        assertThat(PathFilter.touches(push, "apps/api")).isTrue();
    }

    @Test
    void oneCommitUnderTheMaximumIsTrusted() {
        PushPayload push = push().commits(copies(modifying("README.md"), PathFilter.MAX_LISTED_COMMITS - 1))
                .build();

        assertThat(PathFilter.touches(push, "apps/api")).isFalse();
    }

    @Test
    void aFileListThatMayHaveBeenCappedBuilds() {
        List<String> files = IntStream.range(0, PathFilter.MAX_LISTED_FILES)
                .mapToObj(i -> "docs/page-" + i + ".md")
                .toList();

        assertThat(PathFilter.touches(
                        push().commits(changing(files, List.of(), List.of())).build(), "apps/api"))
                .isTrue();
        assertThat(PathFilter.touches(
                        push().commits(changing(List.of(), files, List.of())).build(), "apps/api"))
                .isTrue();
        assertThat(PathFilter.touches(
                        push().commits(changing(List.of(), List.of(), files)).build(), "apps/api"))
                .isTrue();
        assertThat(PathFilter.touches(
                        push().commits(changing(files.subList(1, files.size()), List.of(), List.of()))
                                .build(),
                        "apps/api"))
                .isFalse();
    }

    @Test
    void aForcePushBuilds() {
        assertThat(PathFilter.touches(
                        push().forced().commits(modifying("README.md")).build(), "apps/api"))
                .isTrue();
    }

    @Test
    void aNewBranchBuilds() {
        PushPayload push = push().before(PushPayload.NULL_SHA)
                .commits(modifying("README.md"))
                .build();

        assertThat(PathFilter.touches(push, "apps/api")).isTrue();
    }
}
