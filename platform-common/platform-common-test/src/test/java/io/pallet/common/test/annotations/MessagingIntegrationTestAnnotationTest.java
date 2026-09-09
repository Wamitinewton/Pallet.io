package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Set;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.kafka.clients.admin.AdminClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Proves {@code @MessagingIntegrationTest} resolves a real Kafka broker while genuinely excluding
 * the persistence layer, even though {@code PlatformCommonTestApplication}'s package carries a
 * JPA entity/repository ({@code fixtures.TestEntity} / {@code TestEntityRepository}) that
 * {@code @RepositoryTest}/{@code @IntegrationTest} classes in this same module rely on. Without
 * the exclusions on {@code @MessagingIntegrationTest} itself, this class would fail to start:
 * {@code @EnableAutoConfiguration} would try to satisfy {@code DataSourceAutoConfiguration}
 * against a {@code spring.datasource.*} that nothing here sets.
 */
@MessagingIntegrationTest
class MessagingIntegrationTestAnnotationTest {

    @Autowired
    private KafkaAdmin kafkaAdmin;

    @Autowired
    private ApplicationContext context;

    @Test
    void kafkaAdminResolvesAndReachesTheRealKafkaContainer() throws Exception {
        try (AdminClient adminClient = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Set<String> topicNames = adminClient.listTopics().names().get(10, TimeUnit.SECONDS);

            assertThat(topicNames).isNotNull();
        }
    }

    @Test
    void noDataSourceBeanExistsDespiteAJpaEntityBeingOnTheClasspath() {
        assertThatThrownBy(() -> context.getBean(DataSource.class)).isInstanceOf(NoSuchBeanDefinitionException.class);
    }
}
