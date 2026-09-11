package io.pallet.identity;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * {@code KeycloakTestContainerConfiguration} is imported so a real {@code JwtDecoder} bean exists
 * for {@code PalletResourceServerAutoConfiguration}'s security filter chain to build against a
 * live realm at startup, proving the resource-server wiring doesn't throw a configuration error -
 * this checkpoint has no protected endpoint of its own yet.
 */
@IntegrationTest
@Import(KeycloakTestContainerConfiguration.class)
class IdentityServiceApplicationIntegrationTest {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private Keycloak keycloakAdminClient;

    @Test
    void contextLoads() {}

    @Test
    void flywayAppliesTheIdentitySchema() {
        Integer appliedV1 = jdbcTemplate.queryForObject(
                "select count(*) from identity.flyway_schema_history where version = '1' and success = true",
                Integer.class);
        assertThat(appliedV1).isEqualTo(1);

        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.organizations", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.users", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.idempotency_keys", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.one_time_action_tokens", Integer.class))
                .isNotNull();
        assertThat(jdbcTemplate.queryForObject("select count(*) from identity.consumed_invite_tokens", Integer.class))
                .isNotNull();
    }

    @Test
    void aKafkaTemplateBeanIsResolvable() {
        assertThat(kafkaTemplate).isNotNull();
    }

    @Test
    void aKeycloakAdminClientBeanIsResolvable() {
        assertThat(keycloakAdminClient).isNotNull();
    }
}
