package io.pallet.orgteam.outbox.chaos;

import com.github.dockerjava.api.DockerClient;
import java.time.Duration;
import org.testcontainers.kafka.KafkaContainer;

/** Freezes the real Kafka container, so producers see the broker as unreachable, not as a mock failure. */
final class BrokerFaults {

    private final KafkaContainer kafka;

    BrokerFaults(KafkaContainer kafka) {
        this.kafka = kafka;
    }

    Fault pause() {
        DockerClient docker = kafka.getDockerClient();
        String containerId = kafka.getContainerId();
        if (!isPaused(docker, containerId)) {
            docker.pauseContainerCmd(containerId).exec();
        }
        return new RevertibleFault(() -> {
            if (isPaused(docker, containerId)) {
                docker.unpauseContainerCmd(containerId).exec();
            }
        });
    }

    void outage(Duration duration) {
        try (Fault ignored = pause()) {
            Pauses.sleep(duration);
        }
    }

    private static boolean isPaused(DockerClient docker, String containerId) {
        return Boolean.TRUE.equals(
                docker.inspectContainerCmd(containerId).exec().getState().getPaused());
    }
}
