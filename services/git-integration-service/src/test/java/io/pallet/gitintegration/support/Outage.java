package io.pallet.gitintegration.support;

import com.github.dockerjava.api.DockerClient;
import java.util.concurrent.Callable;
import org.testcontainers.containers.GenericContainer;

/**
 * Freezes a container for the length of a block and always thaws it. A paused container keeps its sockets open and
 * answers nothing, which is how a hung dependency looks, not how a clean shutdown does.
 */
public final class Outage {

    /** Past Hikari's 500 ms window in which a recently used connection is handed out without validation. */
    public static final long IDLE_PAST_ALIVE_BYPASS_MS = 1_000;

    private Outage() {}

    public static <T> T during(GenericContainer<?> container, Callable<T> block) throws Exception {
        DockerClient docker = container.getDockerClient();
        docker.pauseContainerCmd(container.getContainerId()).exec();
        try {
            return block.call();
        } finally {
            docker.unpauseContainerCmd(container.getContainerId()).exec();
        }
    }

    public static void during(GenericContainer<?> container, ThrowingRunnable block) throws Exception {
        during(container, () -> {
            block.run();
            return null;
        });
    }

    @FunctionalInterface
    public interface ThrowingRunnable {

        void run() throws Exception;
    }
}
