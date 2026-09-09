package io.pallet.notification.audience;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.RepositoryTest;
import io.pallet.notification.domain.Audience;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@RepositoryTest
class LocalProjectionAudienceResolverIntegrationTest {

    @Autowired
    private OrgMembershipRepository orgMembershipRepository;

    private LocalProjectionAudienceResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new LocalProjectionAudienceResolver(orgMembershipRepository);

        orgMembershipRepository.saveAll(List.of(
                new OrgMember("org-a", "user-1", "user1@org-a.example.com", OrgMemberStatus.ACTIVE),
                new OrgMember("org-a", "user-2", "user2@org-a.example.com", OrgMemberStatus.ACTIVE),
                new OrgMember("org-a", "user-3", "user3@org-a.example.com", OrgMemberStatus.REMOVED),
                new OrgMember("org-b", "user-4", "user4@org-b.example.com", OrgMemberStatus.ACTIVE)));
    }

    @Test
    void orgResolvesToOnlyThatOrgsActiveMembers() {
        List<Recipient> recipients = resolver.resolve("org-a", Audience.ORG, null);

        assertThat(recipients)
                .containsExactlyInAnyOrder(
                        new Recipient("user-1", "user1@org-a.example.com"),
                        new Recipient("user-2", "user2@org-a.example.com"));
    }

    @Test
    void orgDoesNotLeakAnotherOrgsMembers() {
        List<Recipient> recipients = resolver.resolve("org-b", Audience.ORG, null);

        assertThat(recipients).containsExactly(new Recipient("user-4", "user4@org-b.example.com"));
    }

    @Test
    void orgWithNoActiveMembersResolvesToEmptyNotAnException() {
        List<Recipient> recipients = resolver.resolve("org-c", Audience.ORG, null);

        assertThat(recipients).isEmpty();
    }

    @Test
    void singleReturnsExactlyTheGivenRecipientRegardlessOfMembershipTable() {
        List<Recipient> recipients = resolver.resolve("org-a", Audience.SINGLE, "user@example.com");

        assertThat(recipients).containsExactly(new Recipient("user@example.com", "user@example.com"));
    }
}
