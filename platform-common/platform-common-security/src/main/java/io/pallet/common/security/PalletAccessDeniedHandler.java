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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.server.resource.web.access.BearerTokenAccessDeniedHandler;
import org.springframework.security.web.access.AccessDeniedHandler;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders the same {@link ErrorResponse} shape {@code GlobalExceptionHandler} produces everywhere
 * else. Like {@link PalletAuthenticationEntryPoint}, this runs in the security filter chain ahead
 * of the {@code DispatcherServlet}, so {@code SecurityExceptionHandler}'s
 * {@code @RestControllerAdvice} never sees an authorization failure raised there.
 */
class PalletAccessDeniedHandler implements AccessDeniedHandler {

    private static final Logger log = LoggerFactory.getLogger(PalletAccessDeniedHandler.class);
    private static final String MESSAGE = "You do not have permission to perform this action.";
    private static final String ERROR_CODE = "ACCESS_DENIED";

    private final AccessDeniedHandler headerHandler = new BearerTokenAccessDeniedHandler();
    private final ObjectMapper objectMapper;

    PalletAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(
            HttpServletRequest request, HttpServletResponse response, AccessDeniedException accessDeniedException)
            throws IOException, ServletException {
        log.warn("[ACCESS_DENIED] {} {}", request.getMethod(), request.getRequestURI());
        headerHandler.handle(request, response, accessDeniedException);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(
                        HttpStatus.valueOf(response.getStatus()), MESSAGE, ERROR_CODE, request.getRequestURI()));
    }
}
