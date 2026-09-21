package io.pallet.orgteam.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.test.annotations.UnitTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@UnitTest
class PathRedactorTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJwdXJwb3NlIjoiaW52aXRlIn0.c2lnbmF0dXJl";

    @Test
    void thePreviewPathKeepsItsRouteAndLosesItsToken() {
        assertThat(PathRedactor.redact("/api/v1/org-team/invites/" + TOKEN))
                .isEqualTo("/api/v1/org-team/invites/{token}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/org-team/invites/TOKEN/extra",
                "/api/v1/org-team/invites/TOKEN/",
                "/api/v1/org-team/invites/TOKEN;jsessionid=abc",
                "/API/V1/ORG-TEAM/INVITES/TOKEN",
                "http://localhost:8084/api/v1/org-team/invites/TOKEN?x=1"
            })
    void everyShapeOfPathCarryingTheTokenIsRedacted(String path) {
        assertThat(PathRedactor.redact(path)).doesNotContain("TOKEN").contains("{token}");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "/api/v1/org-team/orgs/org-1/invites/8f14e45f-ceea-467a-9575-7d9b21f2dd0a/resend",
                "/api/v1/org-team/orgs/org-1/invites",
                "/actuator/health",
                "/"
            })
    void otherPathsAreLeftAlone(String path) {
        assertThat(PathRedactor.redact(path)).isEqualTo(path);
    }

    @Test
    void nullStaysNull() {
        assertThat(PathRedactor.redact(null)).isNull();
    }
}
