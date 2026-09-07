package io.pallet.common.error;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AppExceptionCatalogTest {

    static Stream<Arguments> catalog() {
        return Stream.of(
                Arguments.of(new NotFoundException("gone"), HttpStatus.NOT_FOUND, "NOT_FOUND"),
                Arguments.of(new BadRequestException("bad"), HttpStatus.BAD_REQUEST, "BAD_REQUEST"),
                Arguments.of(new ValidationException("invalid", List.of("a")), HttpStatus.UNPROCESSABLE_CONTENT,
                        "VALIDATION_ERROR"),
                Arguments.of(new UnauthorizedException("no creds"), HttpStatus.UNAUTHORIZED, "UNAUTHORIZED"),
                Arguments.of(new ForbiddenException("nope"), HttpStatus.FORBIDDEN, "FORBIDDEN"),
                Arguments.of(new ConflictException("clash"), HttpStatus.CONFLICT, "CONFLICT"),
                Arguments.of(new TooManyRequestsException("slow down"), HttpStatus.TOO_MANY_REQUESTS,
                        "TOO_MANY_REQUESTS"),
                Arguments.of(new IdempotencyKeyReuseException("reused"), HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSE"),
                Arguments.of(new ExternalServiceException("upstream down", "connect timeout", new RuntimeException()),
                        HttpStatus.BAD_GATEWAY, "EXTERNAL_SERVICE_ERROR"));
    }

    @ParameterizedTest
    @MethodSource("catalog")
    void exposesDocumentedStatusAndCode(AppException ex, HttpStatus status, String code) {
        assertThat(ex.getStatus()).isEqualTo(status);
        assertThat(ex.getErrorCode()).isEqualTo(code);
    }

    @Test
    void validationExceptionCarriesReasonsAsValidationErrors() {
        ValidationException ex = new ValidationException("invalid", List.of("name is blank", "age is negative"));

        assertThat(ex.getValidationErrors()).containsExactly("name is blank", "age is negative");
        assertThat(ex.getMeta()).isNull();
    }

    @Test
    void tooManyRequestsPutsRetryAfterOnMeta() {
        TooManyRequestsException ex = new TooManyRequestsException("locked out", Duration.ofSeconds(30));

        assertThat(ex.getMeta()).containsEntry("retryAfter", 30L);
    }

    @Test
    void metaAndValidationErrorsAreSetOnceAndImmutable() {
        AppException ex = new BadRequestException("bad").withValidationErrors(List.of("first"));
        ex.withValidationErrors(List.of("second"));

        assertThat(ex.getValidationErrors()).containsExactly("first");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ex.getValidationErrors().add("x"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
