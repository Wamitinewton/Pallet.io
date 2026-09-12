package io.pallet.identity.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.pallet.common.error.IdempotencyKeyReuseException;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.KeycloakTestContainerConfiguration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;

@IntegrationTest
@Import(KeycloakTestContainerConfiguration.class)
class IdempotencyServiceIntegrationTest {

    @Autowired
    private IdempotencyService idempotencyService;

    private record Payload(String value) {}

    @Test
    void freshKeyExecutesTheOperationAndCachesTheResult() {
        String key = UUID.randomUUID().toString();
        AtomicInteger invocations = new AtomicInteger();

        String result = idempotencyService.execute(
                key,
                new Payload("a"),
                () -> {
                    invocations.incrementAndGet();
                    return "result";
                },
                String.class);

        assertThat(result).isEqualTo("result");
        assertThat(invocations).hasValue(1);
    }

    @Test
    void replayedKeyWithAnIdenticalBodyReturnsTheCachedResponseWithoutRerunningTheOperation() {
        String key = UUID.randomUUID().toString();
        Payload body = new Payload("same");
        AtomicInteger invocations = new AtomicInteger();

        String first = idempotencyService.execute(
                key,
                body,
                () -> {
                    invocations.incrementAndGet();
                    return "first-result";
                },
                String.class);
        String second = idempotencyService.execute(
                key,
                body,
                () -> {
                    invocations.incrementAndGet();
                    return "second-result";
                },
                String.class);

        assertThat(second).isEqualTo(first);
        assertThat(invocations).hasValue(1);
    }

    @Test
    void replayedKeyWithADifferentBodyThrowsIdempotencyKeyReuseException() {
        String key = UUID.randomUUID().toString();
        idempotencyService.execute(key, new Payload("a"), () -> "result", String.class);

        assertThatThrownBy(() -> idempotencyService.execute(key, new Payload("b"), () -> "result", String.class))
                .isInstanceOf(IdempotencyKeyReuseException.class);
    }
}
