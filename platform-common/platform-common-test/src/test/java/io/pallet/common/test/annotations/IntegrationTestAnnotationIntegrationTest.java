package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Proves {@code @IntegrationTest} bootstraps a full context with both a real Postgres and a real
 * Kafka reachable, with zero manual property wiring in the test itself.
 */
@IntegrationTest
class IntegrationTestAnnotationIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Test
    void dataSourceResolvesAndReachesTheRealPostgresContainer() throws Exception {
        try (Connection connection = dataSource.getConnection();
                Statement statement = connection.createStatement()) {
            boolean hasResultSet = statement.execute("SELECT 1");

            assertThat(hasResultSet).isTrue();
        }
    }

    @Test
    void kafkaAdminResolvesAndReachesTheRealKafkaContainer() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> topicNames = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);

            assertThat(topicNames).isNotNull();
        }
    }
}
