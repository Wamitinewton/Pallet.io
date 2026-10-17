package io.pallet.buildqueue.lease;

import io.pallet.buildqueue.config.BuildQueueProperties;
import io.pallet.buildqueue.lifecycle.BuildEventListener;
import java.time.Duration;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.common.Node;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.stereotype.Component;

/**
 * Whether this instance can hear build-service right now: the broker answers a metadata request in time, and every
 * {@code build.*} listener container is running, unpaused, and not stopped by its error handler. Silence is evidence
 * only when both hold. Asked fresh on every sweep, never cached.
 *
 * <p>Partition assignment is not part of the check: with more instances than partitions, an instance can rightly hold
 * none.
 */
@Component
class BackboneReachability implements DisposableBean {

    static final String CLIENT_ID = "build-queue-backbone-probe";

    private static final Duration CLOSE_TIMEOUT = Duration.ofSeconds(2);

    private static final Logger log = LoggerFactory.getLogger(BackboneReachability.class);

    private final KafkaListenerEndpointRegistry listeners;
    private final Duration timeout;
    private final Admin admin;

    BackboneReachability(
            KafkaAdmin kafkaAdmin, KafkaListenerEndpointRegistry listeners, BuildQueueProperties properties) {
        this.listeners = listeners;
        this.timeout = properties.lease().reachabilityTimeout();
        this.admin = Admin.create(adminConfig(kafkaAdmin.getConfigurationProperties(), timeout));
    }

    /** Blocks for at most {@code lease.reachability-timeout}. Call outside any transaction. */
    boolean canHear() {
        return listenersRunning() && brokerAnswers();
    }

    @Override
    public void destroy() {
        admin.close(CLOSE_TIMEOUT);
    }

    private boolean listenersRunning() {
        for (String id : BuildEventListener.LISTENER_IDS) {
            MessageListenerContainer container = listeners.getListenerContainer(id);
            if (container == null
                    || !container.isRunning()
                    || !container.isInExpectedState()
                    || container.isPauseRequested()) {
                log.debug("Listener container {} cannot hear build-service", id);
                return false;
            }
        }
        return true;
    }

    private boolean brokerAnswers() {
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        try {
            Collection<Node> nodes = admin.describeCluster(new DescribeClusterOptions().timeoutMs(timeoutMillis))
                    .nodes()
                    .get(timeoutMillis, TimeUnit.MILLISECONDS);
            return !nodes.isEmpty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } catch (ExecutionException | TimeoutException e) {
            log.debug("Broker did not answer a metadata request within {}", timeout, e);
            return false;
        }
    }

    /** The platform's Kafka connection, with every request bounded by the reachability timeout. */
    private static Map<String, Object> adminConfig(Map<String, Object> platform, Duration timeout) {
        Map<String, Object> config = new HashMap<>(platform);
        int timeoutMillis = Math.toIntExact(timeout.toMillis());
        config.put(AdminClientConfig.CLIENT_ID_CONFIG, CLIENT_ID);
        config.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeoutMillis);
        config.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeoutMillis);
        return config;
    }
}
