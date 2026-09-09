package io.pallet.common.test.annotations;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Two independent {@code @RepositoryTest} classes in the same Failsafe fork prove the Postgres
 * container is a true JVM-wide singleton, not merely two containers from the same image that
 * happen to look alike: whichever class runs first records the JDBC connection URL it sees (the
 * URL encodes the container's mapped host port); the other asserts it observes the identical
 * URL, which only holds if both classes are talking to the same running container.
 */
@RepositoryTest
class SingletonContainerReuseIntegrationTest {

    static final AtomicReference<String> FIRST_OBSERVED_URL = new AtomicReference<>();

    @Autowired
    private DataSource dataSource;

    @Test
    void connectsToTheSingletonContainer() throws SQLException {
        String url = connectionUrl(dataSource);

        assertThat(url).isNotBlank();
        String previouslyObserved = FIRST_OBSERVED_URL.compareAndExchange(null, url);
        if (previouslyObserved != null) {
            assertThat(url).isEqualTo(previouslyObserved);
        }
    }

    static String connectionUrl(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return connection.getMetaData().getURL();
        }
    }
}

@RepositoryTest
class SecondSingletonContainerReuseIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @Test
    void connectsToTheSameSingletonContainerAsTheFirstClass() throws SQLException {
        String url = SingletonContainerReuseIntegrationTest.connectionUrl(dataSource);

        assertThat(url).isNotBlank();
        String previouslyObserved =
                SingletonContainerReuseIntegrationTest.FIRST_OBSERVED_URL.compareAndExchange(null, url);
        if (previouslyObserved != null) {
            assertThat(url).isEqualTo(previouslyObserved);
        }
    }
}
