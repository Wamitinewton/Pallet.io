package io.pallet.notification;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

@IntegrationTest
class NotificationServiceApplicationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    void contextLoads() {}

    @Test
    void flywayAppliesTheNotificationSchema() {
        Integer appliedV1 = jdbcTemplate.queryForObject(
                "select count(*) from notification.flyway_schema_history where version = '1' and success = true",
                Integer.class);
        assertThat(appliedV1).isEqualTo(1);

        // Not asserted empty: the notifications table lives in a singleton Postgres container
        // shared with every other @IntegrationTest class in this fork, so other classes' rows
        // may already be present. This only proves the table exists and is queryable.
        Integer notificationsCount =
                jdbcTemplate.queryForObject("select count(*) from notification.notifications", Integer.class);
        assertThat(notificationsCount).isNotNull();
    }

    @Test
    void aKafkaTemplateBeanIsResolvable() {
        assertThat(kafkaTemplate).isNotNull();
    }
}
