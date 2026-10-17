package io.pallet.buildqueue.observability;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.core.read.ListAppender;
import io.pallet.buildqueue.build.BuildStatus;
import io.pallet.buildqueue.support.StubBuilder;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

/**
 * Pushes whose commit message, author and repository carry a marker go through every status with this service's
 * loggers at DEBUG, and every line logged on any thread meanwhile is searched for the marker: in its message, its
 * key-value pairs, its MDC and its exception chain. build-service's failure reason is untrusted text too.
 */
@ObservabilityTestContext
class LogRedactionIntegrationTest {

    private static final String SERVICE_LOGGER = "io.pallet.buildqueue";
    private static final String TRANSITION = "Build transition";

    @Autowired
    private ApplicationContext context;

    private final String marker = "untrusted-" + UUID.randomUUID();
    private final ListAppender<ILoggingEvent> captured = new ListAppender<>();
    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final Logger service = (Logger) LoggerFactory.getLogger(SERVICE_LOGGER);

    private EveryStatusWalk walk;
    private Level serviceLevel;

    @BeforeEach
    void setUp() {
        walk = new EveryStatusWalk(context);
        serviceLevel = service.getLevel();
        service.setLevel(Level.DEBUG);
        captured.start();
        root.addAppender(captured);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(captured);
        captured.stop();
        service.setLevel(serviceLevel);
        walk.cleanUp();
    }

    @Test
    void noLogLineCarriesCommitTextRepositoryOrFailureReasonAtAnyLevel() {
        walk.walk(push -> push.headCommitMessage("Fix the build " + marker)
                .headCommitAuthor("Octo " + marker)
                .repo("octo-org/" + marker));

        List<ILoggingEvent> events = List.copyOf(captured.list);
        List<String> transitions = events.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith(TRANSITION))
                .toList();
        for (BuildStatus status : BuildStatus.values()) {
            assertThat(transitions).as("a transition to " + status).anyMatch(line -> line.contains("to=" + status));
        }
        assertThat(events)
                .allSatisfy(event ->
                        assertThat(rendered(event)).doesNotContain(marker).doesNotContain(StubBuilder.FAILURE_REASON));
    }

    private static String rendered(ILoggingEvent event) {
        StringBuilder text = new StringBuilder(event.getFormattedMessage());
        if (event.getKeyValuePairs() != null) {
            event.getKeyValuePairs()
                    .forEach(pair ->
                            text.append(' ').append(pair.key).append('=').append(pair.value));
        }
        text.append(' ').append(event.getMDCPropertyMap());
        for (IThrowableProxy cause = event.getThrowableProxy(); cause != null; cause = cause.getCause()) {
            text.append(' ').append(cause.getMessage());
        }
        if (event.getArgumentArray() != null) {
            text.append(' ')
                    .append(Arrays.stream(event.getArgumentArray())
                            .map(String::valueOf)
                            .collect(Collectors.joining(" ")));
        }
        return text.toString();
    }
}
