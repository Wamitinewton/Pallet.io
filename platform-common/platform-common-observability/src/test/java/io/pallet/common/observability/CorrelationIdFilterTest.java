package io.pallet.common.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

class CorrelationIdFilterTest {

    private final MockHttpServletRequest request = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void generatesCorrelationIdWhenHeaderAbsent() throws Exception {
        AtomicReference<String> seenInChain = new AtomicReference<>();
        MockFilterChain chain = capturing(seenInChain);

        filter(List.of()).doFilter(request, response, chain);

        assertThat(seenInChain.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo(seenInChain.get());
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void reusesInboundCorrelationId() throws Exception {
        request.addHeader(CorrelationId.HEADER, "abc");
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter(List.of()).doFilter(request, response, capturing(seenInChain));

        assertThat(seenInChain.get()).isEqualTo("abc");
        assertThat(response.getHeader(CorrelationId.HEADER)).isEqualTo("abc");
    }

    @Test
    void rejectsInboundIdWithUnsafeCharacters() throws Exception {
        request.addHeader(CorrelationId.HEADER, "abc\r\nInjected: value");
        AtomicReference<String> seenInChain = new AtomicReference<>();

        filter(List.of()).doFilter(request, response, capturing(seenInChain));

        assertThat(seenInChain.get()).doesNotContain("Injected").hasSize(36);
    }

    @Test
    void clearsMdcEvenWhenChainThrows() {
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                throw new RuntimeException("kaboom");
            }
        };

        assertThatThrownBy(() -> filter(List.of()).doFilter(request, response, chain))
                .isInstanceOf(RuntimeException.class);
        assertThat(MDC.get(CorrelationId.MDC_KEY)).isNull();
    }

    @Test
    void appliesContributorsForTheRequestScopeOnly() throws Exception {
        AtomicReference<String> orgIdInChain = new AtomicReference<>();
        MockFilterChain chain = new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
                orgIdInChain.set(MDC.get("orgId"));
            }
        };

        filter(List.of(() -> Map.of("orgId", "org-7"))).doFilter(request, response, chain);

        assertThat(orgIdInChain.get()).isEqualTo("org-7");
        assertThat(MDC.get("orgId")).isNull();
    }

    private OncePerRequestFilter filter(List<MdcContributor> contributors) {
        return new CorrelationIdFilter(CorrelationId.HEADER, contributors);
    }

    private MockFilterChain capturing(AtomicReference<String> target) {
        return new MockFilterChain() {
            @Override
            public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res)
                    throws IOException, ServletException {
                target.set(MDC.get(CorrelationId.MDC_KEY));
                super.doFilter(req, res);
            }
        };
    }
}
