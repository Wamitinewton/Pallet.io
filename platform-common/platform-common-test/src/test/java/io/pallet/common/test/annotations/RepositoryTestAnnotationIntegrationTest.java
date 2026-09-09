package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.fixtures.TestEntity;
import io.pallet.common.test.annotations.fixtures.TestEntityRepository;
import java.util.List;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Proves {@code @RepositoryTest} does what it claims with zero {@code @DynamicPropertySource} or
 * {@code @Container} in the test itself: it hits a real Postgres and rolls back between methods.
 * Method order is pinned so the second method can rely on the first's row being rolled back.
 */
@RepositoryTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepositoryTestAnnotationIntegrationTest {

    @Autowired
    private TestEntityRepository repository;

    @Test
    @Order(1)
    void savesAndReReadsARowAgainstARealPostgresContainer() {
        TestEntity saved = repository.saveAndFlush(new TestEntity("widget"));

        TestEntity found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getName()).isEqualTo("widget");
    }

    @Test
    @Order(2)
    void eachTestMethodRunsInARolledBackTransaction() {
        List<TestEntity> existing = repository.findAll();

        assertThat(existing).isEmpty();
    }
}
