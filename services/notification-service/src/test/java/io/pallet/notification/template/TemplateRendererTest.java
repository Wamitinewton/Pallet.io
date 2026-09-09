package io.pallet.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

class TemplateRendererTest {

    private TemplateRenderer renderer;
    private ListAppender<ILoggingEvent> logAppender;

    private static NotificationTemplate template(String bodyTemplate) {
        return new NotificationTemplate("WELCOME", Set.of(Channel.EMAIL), "Welcome to Pallet, {{name}}", bodyTemplate);
    }

    @BeforeEach
    void setUp() {
        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        renderer = new ThymeleafTemplateRenderer(templateEngine);

        logAppender = new ListAppender<>();
        logAppender.start();
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThymeleafTemplateRenderer.class))
                .addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(ThymeleafTemplateRenderer.class))
                .detachAppender(logAppender);
    }

    @Test
    void substitutesAPresentSubjectVariable() {
        RenderedNotification rendered = renderer.render(template("notification/welcome"), Map.of("name", "Ada"));

        assertThat(rendered.title()).isEqualTo("Welcome to Pallet, Ada");
    }

    @Test
    void leavesAnAbsentSubjectVariablePlaceholderUntouchedAndLogsAWarning() {
        RenderedNotification rendered = renderer.render(template("notification/welcome"), Map.of());

        assertThat(rendered.title()).isEqualTo("Welcome to Pallet, {{name}}");
        assertThat(logAppender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("name");
        });
    }

    @Test
    void extraUnusedVariableMapKeysDoNotAffectTheSubject() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", "Ada");
        variables.put("unused", "ignored");

        RenderedNotification rendered = renderer.render(template("notification/welcome"), variables);

        assertThat(rendered.title()).isEqualTo("Welcome to Pallet, Ada");
    }

    @Test
    void aNullSubjectVariableRendersAsEmptyStringNotTheLiteralNull() {
        Map<String, Object> variables = new HashMap<>();
        variables.put("name", null);

        RenderedNotification rendered = renderer.render(template("notification/welcome"), variables);

        assertThat(rendered.title()).isEqualTo("Welcome to Pallet, ");
    }

    @Test
    void aVariableContainingMarkupIsHtmlEscapedInTheBody() {
        RenderedNotification rendered = renderer.render(
                template("notification/welcome"), Map.of("name", "<script>alert(1)</script>", "orgName", "Acme"));

        assertThat(rendered.body()).contains("&lt;script&gt;alert(1)&lt;/script&gt;");
        assertThat(rendered.body()).doesNotContain("<script>alert(1)</script>");
    }

    @Test
    void aBodyTemplateWithNoVariablesRendersUnchanged() {
        RenderedNotification rendered = renderer.render(template("notification/static-body"), Map.of());

        assertThat(rendered.body()).contains("No variables here.");
    }

    @Test
    void aBodyTemplateReferencingAnUndeclaredVariableDoesNotCrashTheRender() {
        RenderedNotification rendered = renderer.render(template("notification/welcome"), Map.of("name", "Ada"));

        assertThat(rendered.body()).isNotNull();
    }
}
