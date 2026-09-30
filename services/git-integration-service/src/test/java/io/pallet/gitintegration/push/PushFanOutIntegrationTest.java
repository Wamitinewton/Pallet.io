package io.pallet.gitintegration.push;

import static io.pallet.gitintegration.push.PushScenario.sha;
import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.events.GitPushReceived;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.delivery.DeliveryProcessor;
import io.pallet.gitintegration.push.PushScenario.Published;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.TopicProbe;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
class PushFanOutIntegrationTest {

    private static final String BEFORE = sha('a');
    private static final String AFTER = sha('b');

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeliveryProcessor processor;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private JsonMapper json;

    private PushScenario scenario;
    private List<String> orgs;
    private Map<String, UUID> apps;

    @BeforeEach
    void setUp() {
        scenario = new PushScenario(mvc, jdbc, processor);
        orgs = List.of(scenario.newOrg(), scenario.newOrg(), scenario.newOrg());
        apps = Map.of(
                orgs.get(0), scenario.linkApp(orgs.get(0)),
                orgs.get(1), scenario.linkApp(orgs.get(1)),
                orgs.get(2), scenario.linkApp(orgs.get(2)));
    }

    @AfterEach
    void tearDown() {
        scenario.cleanUp();
    }

    @Test
    void onePushToASharedRepositoryReachesEveryOrgOnItsOwnKey() {
        try (TopicProbe probe = new TopicProbe(kafka.getBootstrapServers(), GitPushReceived.TYPE)) {
            UUID delivery = scenario.post(scenario.push(BEFORE, AFTER));

            Map<String, Object> row = scenario.process(delivery);

            assertThat(row).containsEntry("status", "PROCESSED");
            for (String orgId : orgs) {
                UUID appId = apps.get(orgId);
                assertThat(scenario.published(orgId)).singleElement().satisfies(event -> {
                    assertThat(event.orgId()).isEqualTo(orgId);
                    assertThat(event.recordKey()).isNull();
                    assertThat(event.appId()).isEqualTo(appId.toString());
                    assertThat(event.eventId()).isEqualTo(PushEventFactory.eventId(delivery, appId));
                });
                assertThat(scenario.head(appId)).contains(AFTER);
                assertThat(probe.awaitKey(orgId, 1)).singleElement().satisfies(record -> {
                    GitPushReceived event = json.readValue(record.value(), GitPushReceived.class);
                    assertThat(event.orgId()).isEqualTo(orgId);
                    assertThat(event.appId()).isEqualTo(appId.toString());
                    assertThat(event.commitSha()).isEqualTo(AFTER);
                });
            }
        }
        assertThat(github.totalRequests()).isZero();
    }

    @Test
    void reprocessingAfterACrashBeforeCommitPublishesTheSameIds() {
        UUID delivery = scenario.post(scenario.push(BEFORE, AFTER));
        scenario.failWrites("webhook_deliveries", "NEW.delivery_id = '" + delivery + "' AND NEW.status = 'PROCESSED'");

        Map<String, Object> crashed = scenario.process(delivery);

        assertThat(crashed).containsEntry("status", "RECEIVED").containsEntry("attempts", 1);
        orgs.forEach(orgId -> {
            assertThat(scenario.published(orgId)).isEmpty();
            assertThat(scenario.head(apps.get(orgId))).isEmpty();
        });

        scenario.clearFaults();
        scenario.makeDue(delivery);
        Map<String, Object> retried = scenario.process(delivery);

        assertThat(retried).containsEntry("status", "PROCESSED");
        orgs.forEach(orgId -> assertThat(scenario.published(orgId))
                .extracting(Published::eventId)
                .containsExactly(PushEventFactory.eventId(delivery, apps.get(orgId))));
    }

    @Test
    void anOrgThatStoppedAutoDeployingIsLeftOutOfTheFanOut() {
        scenario.autoDeploy(apps.get(orgs.get(1)), false);

        Map<String, Object> row = scenario.postAndProcess(scenario.push(BEFORE, AFTER));

        assertThat(row).containsEntry("status", "PROCESSED");
        assertThat(scenario.published(orgs.get(0))).hasSize(1);
        assertThat(scenario.published(orgs.get(1))).isEmpty();
        assertThat(scenario.published(orgs.get(2))).hasSize(1);
    }
}
