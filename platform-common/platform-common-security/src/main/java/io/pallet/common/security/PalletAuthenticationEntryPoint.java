package io.pallet.common.security;

import io.pallet.common.error.ErrorResponse;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.web.BearerTokenAuthenticationEntryPoint;
import org.springframework.security.web.AuthenticationEntryPoint;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders the same {@link ErrorResponse} shape {@code GlobalExceptionHandler} produces everywhere
 * else. An unauthenticated request never reaches the {@code DispatcherServlet} — it's rejected in
 * the security filter chain, before any {@code @RestControllerAdvice} gets a chance to run — so
 * without this, a missing or rejected token comes back as a bare 401 with no body.
 *
 * <p>Delegates header-setting to {@link BearerTokenAuthenticationEntryPoint} first, so the
 * {@code WWW-Authenticate} challenge (RFC 6750) still carries the failure's error code, then
 * writes the JSON body on top.
 */
class PalletAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private static final Logger log = LoggerFactory.getLogger(PalletAuthenticationEntryPoint.class);
    private static final String MESSAGE = "Authentication is required to access this resource.";
    private static final String ERROR_CODE = "AUTHENTICATION_REQUIRED";

    private final AuthenticationEntryPoint headerEntryPoint = new BearerTokenAuthenticationEntryPoint();
    private final ObjectMapper objectMapper;

    PalletAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request, HttpServletResponse response, AuthenticationException authException)
            throws IOException, ServletException {
        log.warn("[AUTHENTICATION_REQUIRED] {} {}", request.getMethod(), request.getRequestURI());
        headerEntryPoint.commence(request, response, authException);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(
                        HttpStatus.valueOf(response.getStatus()), MESSAGE, ERROR_CODE, request.getRequestURI()));
    }
}
