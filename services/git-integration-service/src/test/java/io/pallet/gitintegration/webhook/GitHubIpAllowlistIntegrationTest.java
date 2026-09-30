package io.pallet.gitintegration.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.pallet.common.test.annotations.IntegrationTest;
import io.pallet.common.test.containers.RedisTestContainerConfiguration;
import io.pallet.gitintegration.observability.MetricsCatalog;
import io.pallet.gitintegration.support.GitHubApiStub;
import io.pallet.gitintegration.support.WebhookFixtures;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
@AutoConfigureMockMvc
@Import({RedisTestContainerConfiguration.class, GitHubApiStub.Properties.class})
@TestPropertySource(properties = "pallet.git.webhook.github-ip-allowlist.enabled=true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GitHubIpAllowlistIntegrationTest {

    private static final List<String> HOOK_RANGES = List.of("192.30.252.0/22", "2a0a:a440::/29");
    private static final String GITHUB_V4 = "192.30.252.17";
    private static final String GITHUB_V6 = "2a0a:a440:0:0:0:0:0:17";
    private static final String OUTSIDE = "203.0.113.9";

    @RegisterExtension
    static final GitHubApiStub github = new GitHubApiStub();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private GitHubIpAllowlist allowlist;

    @Autowired
    private GitHubMetaRefresher refresher;

    @LocalServerPort
    private int port;

    private final List<String> deliveries = new ArrayList<>();

    @AfterEach
    void tearDown() {
        deliveries.forEach(id -> jdbc.update(
                "DELETE FROM git_integration.webhook_deliveries WHERE delivery_id = ?", UUID.fromString(id)));
    }

    @Test
    @Order(1)
    void theAllowlistFailsOpenUntilLoadedThenRejectsOutsideAddressesAndKeepsItsRangesThroughAFailedRefresh()
            throws Exception {
        assertThat(allowlist.loadedAt())
                .as("/meta has not answered since startup")
                .isEmpty();
        double unchecked = sources(WebhookMetrics.SOURCE_UNCHECKED);
        assertThat(post(OUTSIDE).getStatus()).as("fail open").isEqualTo(202);
        assertThat(sources(WebhookMetrics.SOURCE_UNCHECKED)).isEqualTo(unchecked + 1);

        github.stubMeta(HOOK_RANGES);
        assertThat(refresher.refresh()).isTrue();

        double rejected = sources(WebhookMetrics.SOURCE_REJECTED);
        WebhookFixtures.Delivery outside = delivery();
        MockHttpServletResponse refused = post(outside, OUTSIDE);
        assertThat(refused.getStatus()).isEqualTo(403);
        assertThat(refused.getContentAsString())
                .contains("WEBHOOK_SOURCE_NOT_ALLOWED")
                .doesNotContain(OUTSIDE);
        assertThat(stored(outside)).isZero();
        assertThat(sources(WebhookMetrics.SOURCE_REJECTED)).isEqualTo(rejected + 1);

        assertThat(post(GITHUB_V4).getStatus()).isEqualTo(202);
        assertThat(post(GITHUB_V6).getStatus()).isEqualTo(202);
        assertThat(post(delivery().secret("not-the-secret"), GITHUB_V4).getStatus())
                .as("the HMAC stays the authority inside the ranges")
                .isEqualTo(401);

        github.stub5xx(GitHubApiStub.META_PATH);
        assertThat(refresher.refresh()).isFalse();
        assertThat(post(OUTSIDE).getStatus())
                .as("a failed refresh keeps the ranges")
                .isEqualTo(403);
    }

    @Test
    @Order(2)
    void theAddressIsTheOneTheGatewayForwardsNotTheSocketPeer() throws Exception {
        github.stubMeta(HOOK_RANGES);
        assertThat(refresher.refresh()).isTrue();

        assertThat(overHttp(delivery(), GITHUB_V4)).isEqualTo(202);
        assertThat(overHttp(delivery(), OUTSIDE)).isEqualTo(403);
    }

    private WebhookFixtures.Delivery delivery() {
        WebhookFixtures.Delivery delivery = WebhookFixtures.delivery("repository-archived.json");
        deliveries.add(delivery.deliveryId());
        return delivery;
    }

    private MockHttpServletResponse post(String address) {
        return post(delivery(), address);
    }

    private MockHttpServletResponse post(WebhookFixtures.Delivery delivery, String address) {
        try {
            return mvc.perform(delivery.request().with(request -> {
                        request.setRemoteAddr(address);
                        return request;
                    }))
                    .andReturn()
                    .getResponse();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Through Tomcat, from loopback, which its remote IP valve trusts as a proxy, as it will the gateway. */
    private int overHttp(WebhookFixtures.Delivery delivery, String forwardedFor) throws Exception {
        byte[] body = delivery.body();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + WebhookFixtures.PATH))
                .header("Content-Type", "application/json")
                .header("X-GitHub-Event", "repository")
                .header("X-GitHub-Delivery", delivery.deliveryId())
                .header("X-Hub-Signature-256", delivery.signature())
                .header("X-Forwarded-For", forwardedFor)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
        }
    }

    private int stored(WebhookFixtures.Delivery delivery) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM git_integration.webhook_deliveries WHERE delivery_id = ?",
                Integer.class,
                UUID.fromString(delivery.deliveryId()));
    }

    private double sources(String result) {
        return registry.get(MetricsCatalog.WEBHOOK_IP_ALLOWLIST)
                .tag(MetricsCatalog.TAG_RESULT, result)
                .counter()
                .count();
    }
}
