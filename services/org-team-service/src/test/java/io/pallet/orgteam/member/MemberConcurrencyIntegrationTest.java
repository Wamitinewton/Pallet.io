package io.pallet.orgteam.member;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.orgteam.security.SignedTokenTestConfiguration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, SignedTokenTestConfiguration.class})
class MemberConcurrencyIntegrationTest extends MemberIntegrationSupport {

    private static final int REPETITIONS = 5;

    @RepeatedTest(REPETITIONS)
    void twoSimultaneousTransfersToDifferentTargetsLetExactlyOneWin() throws Exception {
        TestOrg org = newTeamOrg();
        String first = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String second = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        List<Integer> statuses = race(
                () -> call(post(BASE + "/{userId}/transfer-ownership", org.orgId(), first), org),
                () -> call(post(BASE + "/{userId}/transfer-ownership", org.orgId(), second), org));

        assertThat(statuses).containsExactlyInAnyOrder(200, 403);
        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(events(org.orgId(), "org.member.role.changed")).hasSize(2);
        assertThat(jdbc.queryForObject(
                        "SELECT m.user_id FROM org_team.memberships m JOIN org_team.organizations o"
                                + " ON o.org_id = m.org_id AND o.owner_user_id = m.user_id"
                                + " WHERE m.org_id = ? AND m.role = 'OWNER'",
                        String.class,
                        org.orgId()))
                .isIn(first, second);
    }

    @RepeatedTest(REPETITIONS)
    void aTransferRacingTheOwnersSelfRemovalNeverLeavesZeroOrTwoOwners() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");

        List<Integer> statuses = race(
                () -> call(post(BASE + "/{userId}/transfer-ownership", org.orgId(), admin), org),
                () -> call(delete(BASE + "/{userId}", org.orgId(), org.owner()), org));

        assertThat(statuses).contains(200);
        assertThat(activeOwners(org.orgId())).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT owner_user_id FROM org_team.organizations WHERE org_id = ?", String.class, org.orgId()))
                .isEqualTo(admin);
    }

    @RepeatedTest(REPETITIONS)
    void anAdminsRemovalRacingTheDevelopersSelfRemovalEmitsExactlyOneEvent() throws Exception {
        TestOrg org = newTeamOrg();
        String admin = fixtures.newMember(org.orgId(), "ADMIN", "ACTIVE");
        String dev = fixtures.newMember(org.orgId(), "DEVELOPER", "ACTIVE");

        List<Integer> statuses = race(
                () -> call(delete(BASE + "/{userId}", org.orgId(), dev), org.orgId(), admin),
                () -> call(delete(BASE + "/{userId}", org.orgId(), dev), org.orgId(), dev));

        assertThat(statuses).containsExactlyInAnyOrder(204, 404);
        assertThat(events(org.orgId(), "org.member.removed")).hasSize(1);
        assertThat(activeOwners(org.orgId())).isEqualTo(1);
    }

    @Test
    void twentyConcurrentRoleChangesOnDistinctMembersAllSucceedWithOneEventEach() throws Exception {
        TestOrg org = newTeamOrg();
        List<String> members = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            members.add(fixtures.newMember(org.orgId(), "VIEWER", "ACTIVE"));
        }

        List<Integer> statuses = raceAll(members.stream()
                .<Callable<Integer>>map(member -> () -> call(
                        patch(BASE + "/{userId}", org.orgId(), member)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"role\":\"DEVELOPER\"}"),
                        org))
                .toList());

        assertThat(statuses).hasSize(20).containsOnly(200);
        List<Map<String, Object>> changes = events(org.orgId(), "org.member.role.changed");
        assertThat(changes).hasSize(20);
        assertThat(changes).extracting(event -> event.get("user_id")).containsExactlyInAnyOrderElementsOf(members);
        assertThat(changes)
                .extracting(event -> (Long) event.get("id"))
                .isSorted()
                .doesNotHaveDuplicates();
        assertThat(activeOwners(org.orgId())).isEqualTo(1);
    }

    private int call(MockHttpServletRequestBuilder request, TestOrg org) throws Exception {
        return call(request, org.orgId(), org.owner());
    }

    private int call(MockHttpServletRequestBuilder request, String orgId, String userId) throws Exception {
        return perform(request, orgId, userId).andReturn().getResponse().getStatus();
    }

    @SafeVarargs
    private static List<Integer> race(Callable<Integer>... tasks) throws Exception {
        return raceAll(List.of(tasks));
    }

    private static List<Integer> raceAll(List<Callable<Integer>> tasks) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(tasks.size());
        try {
            CountDownLatch ready = new CountDownLatch(tasks.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Integer>> futures = new ArrayList<>();
            for (Callable<Integer> task : tasks) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<Integer> results = new ArrayList<>();
            for (Future<Integer> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            executor.shutdownNow();
        }
    }
}
