package io.pallet.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import io.pallet.common.error.ErrorResponse.ValidationError;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;

@ExtendWith(OutputCaptureExtension.class)
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/widgets/42");
        request.setRequestURI("/widgets/42");
        return request;
    }

    @Test
    void appExceptionCarriesMetaAndValidationErrorsToBody() {
        AppException ex = new ConflictException("cannot do that")
                .withValidationErrors(List.of("reason one", "reason two"))
                .withMeta(Map.of("retryAfter", 5L));

        ResponseEntity<ErrorResponse> response = handler.handleAppException(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.success()).isFalse();
        assertThat(body.error()).isEqualTo("CONFLICT");
        assertThat(body.statusCode()).isEqualTo(409);
        assertThat(body.path()).isEqualTo("/widgets/42");
        assertThat(body.timestamp()).isNotNull();
        assertThat(body.validationErrors())
                .containsExactly(new ValidationError(null, "reason one"), new ValidationError(null, "reason two"));
        assertThat(body.meta()).containsEntry("retryAfter", 5L);
    }

    @Test
    void serverErrorAppExceptionLogsAtError(CapturedOutput output) {
        handler.handleAppException(
                new ExternalServiceException("upstream down", "connect timeout", new RuntimeException("boom")),
                request());

        assertThat(output).contains("EXTERNAL_SERVICE_ERROR");
    }

    @Test
    void unexpectedExceptionFallbackDoesNotLeakThrownMessage() {
        ResponseEntity<ErrorResponse> response =
                handler.handleUnexpected(new IllegalStateException("secret internal detail"), request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("INTERNAL_ERROR");
        assertThat(body.message()).isEqualTo("An unexpected error occurred. Please try again later.");
        assertThat(body.message()).doesNotContain("secret internal detail");
    }

    @Test
    void methodArgumentNotValidMapsFieldErrors() throws Exception {
        Method method = Target.class.getDeclaredMethod("handle", String.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        BeanPropertyBindingResult binding = new BeanPropertyBindingResult(new Object(), "form");
        binding.addError(new FieldError("form", "email", "must not be blank"));
        MethodArgumentNotValidException ex = new MethodArgumentNotValidException(parameter, binding);

        ResponseEntity<ErrorResponse> response = handler.handleMethodArgumentNotValid(ex, request());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        ErrorResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.error()).isEqualTo("VALIDATION_ERROR");
        assertThat(body.validationErrors()).containsExactly(new ValidationError("email", "must not be blank"));
    }

    @SuppressWarnings("unused")
    private static final class Target {
        void handle(String value) {}
    }
}
