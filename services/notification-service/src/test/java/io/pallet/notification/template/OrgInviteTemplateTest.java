package io.pallet.notification.template;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import io.pallet.notification.domain.Channel;
import io.pallet.notification.template.TemplateRenderer.RenderedNotification;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

@UnitTest
class OrgInviteTemplateTest {

    private static final String ACCEPT_URL = "https://app.pallet.test/invites/abc.def.ghi";

    private NotificationTemplate template;
    private TemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        template = new NotificationTemplateRegistry(
                        new PathMatchingResourcePatternResolver(),
                        NotificationTemplateRegistry.DEFAULT_LOCATION_PATTERN)
                .resolve("ORG_INVITE");

        ClassLoaderTemplateResolver templateResolver = new ClassLoaderTemplateResolver();
        templateResolver.setPrefix("templates/");
        templateResolver.setSuffix(".html");
        templateResolver.setTemplateMode(TemplateMode.HTML);
        SpringTemplateEngine templateEngine = new SpringTemplateEngine();
        templateEngine.setTemplateResolver(templateResolver);
        renderer = new ThymeleafTemplateRenderer(templateEngine);
    }

    @Test
    void isRegisteredForEmailOnly() {
        assertThat(template.defaultChannels()).containsExactly(Channel.EMAIL);
        assertThat(template.subjectTemplate()).isEqualTo("{{inviterName}} invited you to join {{orgName}} on Pallet");
    }

    @Test
    void rendersAllFourVariablesAndTheAcceptLink() {
        RenderedNotification rendered = renderer.render(
                template,
                Map.of("orgName", "Acme", "inviterName", "Ada", "role", "developer", "acceptUrl", ACCEPT_URL));

        assertThat(rendered.title()).isEqualTo("Ada invited you to join Acme on Pallet");
        assertThat(rendered.body())
                .contains("Ada", "Acme", "developer")
                .contains("href=\"" + ACCEPT_URL + "\"")
                .contains(">" + ACCEPT_URL + "<");
    }

    @Test
    void escapesHtmlInUserSuppliedNames() {
        RenderedNotification rendered = renderer.render(
                template,
                Map.of(
                        "orgName", "<script>alert(1)</script>",
                        "inviterName", "<b>Ada</b>",
                        "role", "viewer",
                        "acceptUrl", ACCEPT_URL));

        assertThat(rendered.body()).doesNotContain("<script>").doesNotContain("<b>Ada</b>");
        assertThat(rendered.body()).contains("&lt;script&gt;").contains("&lt;b&gt;Ada&lt;/b&gt;");
    }
}
